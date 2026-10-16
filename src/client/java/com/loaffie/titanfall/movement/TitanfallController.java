package com.loaffie.titanfall.movement;

import com.loaffie.titanfall.config.TitanfallConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Source-engine style pilot movement.
 *
 * <p>While the player is airborne, wall running or sliding this controller owns their horizontal
 * velocity and rewrites it at the end of every client tick. Vanilla applies its friction after the
 * tick's movement and before our next write, so owning the value means vanilla's 0.91 air drag and
 * its ground friction never reach the movement modelled here. That is what makes the result
 * frictionless and floaty in the same way Source is: air movement in Source has no friction at all,
 * only the wish-velocity accelerator.
 *
 * <p>Speeds are kept in blocks per second (1 block = 1 metre) so the Source formulas can be used
 * unchanged, while the entity's velocity is in blocks per tick. {@link #TICKS_PER_SECOND} converts
 * between the two.
 */
public final class TitanfallController {
	private static final double TICKS_PER_SECOND = 20.0;
	private static final double TICK_SECONDS = 1.0 / TICKS_PER_SECOND;

	/** Vanilla horizontal drag while airborne, used to undo the drag on the tick the player leaves the ground. */
	private static final double AIR_DRAG = 0.91;
	/** Vanilla player gravity, blocks per tick per tick. */
	private static final double VANILLA_GRAVITY = 0.08;
	/** One Source unit (0.75 inch) in metres, so Source speeds can be converted: 30 u/s = 0.5715 blocks/s. */
	private static final double SOURCE_UNIT = 0.01905;
	/** Vanilla jump impulse, blocks per tick. */
	private static final double JUMP_VELOCITY = 0.42;

	/**
	 * Minimum horizontal speed to attach to a wall, blocks per second. Vanilla walking is 4.3 blocks/s
	 * and sprinting 5.6, so a sprint jump and anything carrying slide or air-strafe momentum attaches
	 * while a plain walk does not.
	 */
	private static final double WALL_RUN_MIN_SPEED = 5.0;
	/** A wall only counts when the player travels more than ~45 degrees away from its normal. */
	private static final double WALL_ALIGNMENT_LIMIT = 0.7;
	/** How far the player's hitbox may be from the wall surface and still latch, in blocks. */
	private static final double WALL_REACH = 0.25;
	/** Speed a knockback or explosion has to add before the model gives up its own momentum. */
	private static final double EXTERNAL_IMPULSE_THRESHOLD = 0.75;
	/** Source sv_stopspeed (100 units/s) as blocks per second, used by slide friction. */
	private static final double SLIDE_STOP_SPEED = 100.0 * SOURCE_UNIT;
	private static final double LAND_FALL_DISTANCE = 1.0;
	/** Position deltas above this are teleports or respawns rather than movement. */
	private static final double TELEPORT_SPEED_LIMIT = 60.0;
	private static final Vec3d UP = new Vec3d(0.0, 1.0, 0.0);

	private static TitanfallConfig config;
	private static boolean started;
	private static boolean jumpHeld;
	private static boolean airborne;
	private static boolean doubleJumped;
	private static double lastFallDistance;

	/** Our authoritative horizontal velocity while we own it, in blocks per second. */
	private static Vec3d momentum = Vec3d.ZERO;
	/** Last tick's position and movement, used to measure the player's real speed past vanilla friction. */
	private static Vec3d lastPosition = Vec3d.ZERO;
	private static Vec3d lastMovement = Vec3d.ZERO;
	private static double measuredSpeed;
	/** Speed measured on the previous tick, so a slide can start from the speed before the crouch slowed it. */
	private static double previousMeasuredSpeed;

	private static boolean wallRunning;
	private static Direction wallSide;
	private static int wallRunTicks;
	private static double wallRunSpeed;
	private static int wallCooldown;

	private static boolean sliding;
	private static Vec3d slideVelocity = Vec3d.ZERO;
	private static int slideBoostCooldown;
	/** Set when a slide hop has already played the jump sound, so leaving the ground does not repeat it. */
	private static boolean jumpSoundPlayed;
	/** Remaining ticks of the post-jump lurch window, and how much push is left in it. */
	private static int lurchTicksLeft;
	private static double lurchBudget;

	private TitanfallController() {
	}

	public static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			reset();
			return;
		}

		config = TitanfallConfig.get();

		if (!started) {
			started = true;
			lastPosition = position(player);
			lastMovement = Vec3d.ZERO;
			lastFallDistance = player.fallDistance;
		}

		GameOptions options = client.options;
		boolean jumpPressed = options.jumpKey.isPressed();
		boolean jumpEdge = jumpPressed && !jumpHeld;
		boolean airborneNow = isAirborne(player);

		lastMovement = position(player).subtract(lastPosition);
		measuredSpeed = Math.hypot(lastMovement.x, lastMovement.z) * TICKS_PER_SECOND;
		if (measuredSpeed > TELEPORT_SPEED_LIMIT) {
			measuredSpeed = 0.0;
			lastMovement = Vec3d.ZERO;
		}

		if (wallCooldown > 0) {
			wallCooldown--;
		}

		if (slideBoostCooldown > 0) {
			slideBoostCooldown--;
		}

		if (movementUnsuitable(player)) {
			momentum = Vec3d.ZERO;
			if (sliding || wallRunning) {
				stopSlide();
				stopWallRun();
			}
		} else if (airborneNow) {
			handleAirMovement(player, options, jumpEdge);
		} else {
			if (airborne && lastFallDistance >= LAND_FALL_DISTANCE) {
				MovementSounds.land();
			}

			handleGroundMovement(player, options, jumpEdge);
		}

		lastPosition = position(player);
		lastFallDistance = player.fallDistance;
		previousMeasuredSpeed = measuredSpeed;
		jumpHeld = jumpPressed;
		airborne = airborneNow;
	}

	/** States the pilot movement has no business touching: vehicles, flight, swimming, ladders and spectator. */
	private static boolean movementUnsuitable(ClientPlayerEntity player) {
		return player.isSpectator()
				|| player.isDead()
				|| player.hasVehicle()
				|| player.isSleeping()
				|| player.getAbilities().flying
				|| player.isGliding()
				|| player.isTouchingWater()
				|| player.isClimbing();
	}

	private static boolean isAirborne(ClientPlayerEntity player) {
		return !player.isOnGround() && !movementUnsuitable(player);
	}

	private static void handleAirMovement(ClientPlayerEntity player, GameOptions options, boolean jumpEdge) {
		if (sliding) {
			// Sliding off a ledge keeps the slide's own velocity - our value, not the friction-clipped one.
			momentum = slideVelocity;
			stopSlide();
		} else if (!airborne) {
			// Just left the ground: undo the air drag that vanilla applied after this tick's jump.
			Vec3d velocity = player.getVelocity();
			momentum = horizontal(velocity).multiply(TICKS_PER_SECOND / AIR_DRAG);

			// Rising rather than dropping off a ledge means a real ground jump. Vanilla plays no sound of
			// its own for a player jump, so this is the only thing that announces one.
			if (velocity.y > 0.0) {
				if (!jumpSoundPlayed) {
					MovementSounds.jump();
				}

				startLurch();
			}
		} else {
			momentum = adoptExternalImpulse(player);
		}

		jumpSoundPlayed = false;

		if (wallRunning) {
			if (jumpEdge) {
				wallJump(player);
				return;
			}

			if (continueWallRun(player)) {
				return;
			}
		} else if (wallCooldown == 0 && !player.isSneaking() && momentum.length() >= WALL_RUN_MIN_SPEED) {
			Direction wall = findWall(player);
			if (wall != null) {
				startWallRun(player, wall);
				return;
			}
		}

		// A wall bump - crouch held against a wall while airborne - is how Titanfall 2 hands the air jump
		// back without landing.
		if (config.airJumpOnWallBump && doubleJumped && player.isSneaking() && touchingWall(player)) {
			doubleJumped = false;
		}

		if (jumpEdge && airborne && !doubleJumped) {
			doubleJump(player);
		}

		applyLurch(player, options);
		airAccelerate(player, options);
	}

	private static void handleGroundMovement(ClientPlayerEntity player, GameOptions options, boolean jumpEdge) {
		stopWallRun();
		doubleJumped = false;

		if (sliding) {
			if (jumpEdge) {
				slideHop(player);
				return;
			}

			if (!player.isSneaking()) {
				stopSlide();
				return;
			}

			slideVelocity = applyFriction(slideVelocity, config.slideFriction, SLIDE_STOP_SPEED);
			if (slideVelocity.length() < config.slideMinSpeed) {
				stopSlide();
				return;
			}

			player.setVelocity(slideVelocity.x / TICKS_PER_SECOND, player.getVelocity().y, slideVelocity.z / TICKS_PER_SECOND);
			return;
		}

		// Measured displacement, not the entity's velocity: end-of-tick velocity has already had vanilla
		// ground friction applied to it, and would make a slide start slower than a walk.
		double speed = Math.max(measuredSpeed, previousMeasuredSpeed);
		if (!player.isSneaking() || speed < config.slideMinSpeed || slideBoostCooldown > 0) {
			return;
		}

		Vec3d direction = slideDirection(player);
		if (direction == null) {
			return;
		}

		sliding = true;
		slideVelocity = direction.multiply(Math.min(speed * (1.0 + config.slideEntryBoost), config.maxHorizontalSpeed));
		slideBoostCooldown = config.slideBoostCooldownTicks;
		MovementSounds.startSlide();
	}

	/**
	 * The Source air accelerator. The wish speed is clamped before the "how much speed can I still add
	 * along this direction" check, but the acceleration step itself uses the unclamped wish speed. The
	 * clamp is what produces air strafing: steering perpendicular to the current velocity keeps the
	 * projection small, so the accelerator keeps adding velocity at an angle and the total speed grows.
	 */
	private static void airAccelerate(ClientPlayerEntity player, GameOptions options) {
		Vec3d wishDir = wishDirection(player, options);
		// Acceleration tops out at the configured cap. A speed already above it - a lurch, an explosion,
		// a fast wall run - is left alone rather than braked back down to the cap.
		if (wishDir != null && momentum.length() < config.maxHorizontalSpeed) {
			double wishSpeed = config.maxHorizontalSpeed;
			double wishSpd = Math.min(wishSpeed, config.airWishSpeedCap);
			double currentSpeed = momentum.x * wishDir.x + momentum.z * wishDir.z;
			double addSpeed = wishSpd - currentSpeed;
			if (addSpeed > 0.0) {
				double accelSpeed = Math.min(config.airAccelerate * wishSpeed * TICK_SECONDS, addSpeed);
				Vec3d accelerated = momentum.add(wishDir.multiply(accelSpeed));
				double ceiling = config.maxHorizontalSpeed;
				double speed = accelerated.length();
				momentum = speed > ceiling ? accelerated.multiply(ceiling / speed) : accelerated;
			}
		}

		writeAirVelocity(player);
	}

	/**
	 * The model owns the pilot's momentum, but knockback, explosions, pistons and riding entities still
	 * push them. If the velocity vanilla has been left holding carries more speed than our own value in
	 * the direction it points, it came from outside the model and is adopted instead of being erased.
	 */
	private static Vec3d adoptExternalImpulse(ClientPlayerEntity player) {
		Vec3d observed = horizontal(player.getVelocity()).multiply(TICKS_PER_SECOND);
		double observedSpeed = observed.length();
		if (observedSpeed < EXTERNAL_IMPULSE_THRESHOLD) {
			return momentum;
		}

		double alignment = momentum.dotProduct(observed.multiply(1.0 / observedSpeed));
		return observedSpeed > alignment + EXTERNAL_IMPULSE_THRESHOLD ? observed : momentum;
	}

	/** Writes our momentum back, optionally leaving some gravity undone for a floatier arc. */
	private static void writeAirVelocity(ClientPlayerEntity player) {
		double gravityCompensation = (1.0 - config.airGravityScale) * VANILLA_GRAVITY;
		player.setVelocity(momentum.x / TICKS_PER_SECOND,
				player.getVelocity().y + gravityCompensation,
				momentum.z / TICKS_PER_SECOND);
	}

	/**
	 * The air jump. Like the ground jump it <em>resets</em> the vertical speed to the jump impulse rather
	 * than adding to it, so jumping at the top of an arc gives a full second jump, and it keeps every bit
	 * of the horizontal momentum. It opens its own lurch window, which is what makes an air jump usable
	 * for a sharp redirect and not just extra height.
	 */
	private static void doubleJump(ClientPlayerEntity player) {
		doubleJumped = true;
		startLurch();
		MovementSounds.doubleJump();
		player.setVelocity(momentum.x / TICKS_PER_SECOND, config.doubleJumpVelocity, momentum.z / TICKS_PER_SECOND);
	}

	private static void startWallRun(ClientPlayerEntity player, Direction wall) {
		wallRunning = true;
		wallSide = wall;
		wallRunTicks = 0;
		// Keep only the component travelling along the wall face; the part heading into the wall is
		// dropped, and nothing else about the entry speed is touched.
		wallRunSpeed = wallTangent(wall, momentum).dotProduct(momentum);
		if (config.airJumpOnWallRun) {
			doubleJumped = false;
		}

		MovementSounds.startWallRun();
		continueWallRun(player);
	}

	private static boolean continueWallRun(ClientPlayerEntity player) {
		if (wallRunTicks >= config.wallRunMaxTicks || !hasWall(player, wallSide)) {
			stopWallRun();
			return false;
		}

		wallRunTicks++;
		// No friction and no decay along the wall: the entry speed is held for the whole run.
		momentum = wallTangent(wallSide, momentum).multiply(wallRunSpeed);
		// With a vertical speed of zero the write cancels exactly the gravity vanilla is about to add,
		// so the run is a flat, weightless glide rather than a slow sink.
		player.setVelocity(momentum.x / TICKS_PER_SECOND,
				config.wallRunVerticalVelocity + VANILLA_GRAVITY,
				momentum.z / TICKS_PER_SECOND);
		return true;
	}

	private static void wallJump(ClientPlayerEntity player) {
		Vec3d tangent = wallTangent(wallSide, momentum);
		MovementSounds.wallJump();
		momentum = tangent.multiply(wallRunSpeed * config.wallJumpMomentum)
				.add(new Vec3d(-wallSide.getOffsetX() * config.wallJumpPush, 0.0, -wallSide.getOffsetZ() * config.wallJumpPush));
		startLurch();
		stopWallRun();
		player.setVelocity(momentum.x / TICKS_PER_SECOND, config.wallJumpUp, momentum.z / TICKS_PER_SECOND);
	}

	private static void stopWallRun() {
		if (wallRunning) {
			MovementSounds.stopWallRun();
		}

		wallRunning = false;
		wallRunTicks = 0;
		wallCooldown = config.wallCooldownTicks;
	}

	/**
	 * Jumping out of a slide writes back the slide's own velocity, so the horizontal vector keeps 100%
	 * of its magnitude. The player's entity velocity on this tick has already been through vanilla
	 * ground friction, which is why the value used here is ours instead.
	 */
	private static void slideHop(ClientPlayerEntity player) {
		momentum = slideVelocity.multiply(config.slideHopMomentum);
		stopSlide();
		startLurch();
		jumpSoundPlayed = true;
		MovementSounds.jump();
		player.setVelocity(momentum.x / TICKS_PER_SECOND, JUMP_VELOCITY, momentum.z / TICKS_PER_SECOND);
	}

	private static void stopSlide() {
		if (sliding) {
			MovementSounds.stopSlide();
		}

		sliding = false;
		slideVelocity = Vec3d.ZERO;
	}

	/** Source friction: drop = max(speed, stopSpeed) * friction * frametime, applied to the vector. */
	private static Vec3d applyFriction(Vec3d velocity, double friction, double stopSpeed) {
		double speed = velocity.length();
		if (speed < 0.1) {
			return Vec3d.ZERO;
		}

		double control = Math.max(speed, stopSpeed);
		double newSpeed = Math.max(speed - control * friction * TICK_SECONDS, 0.0);
		return velocity.multiply(newSpeed / speed);
	}

	/** Unit vector of the direction the player is actually travelling, or null when they are not moving. */
	private static Vec3d slideDirection(ClientPlayerEntity player) {
		Vec3d movement = horizontal(lastMovement);
		if (movement.lengthSquared() > 1.0E-8) {
			return movement.normalize();
		}

		Vec3d velocity = horizontal(player.getVelocity());
		return velocity.lengthSquared() > 1.0E-8 ? velocity.normalize() : null;
	}

	/**
	 * The wish direction in world space, built from the player's own look vector so the input basis
	 * always matches vanilla whatever the yaw convention is. The strafe axis is the physical right of
	 * the look direction (forward x up), which is the same basis vanilla uses for WASD.
	 */
	private static Vec3d wishDirection(ClientPlayerEntity player, GameOptions options) {
		double forward = (options.forwardKey.isPressed() ? 1.0 : 0.0) - (options.backKey.isPressed() ? 1.0 : 0.0);
		double sideways = (options.rightKey.isPressed() ? 1.0 : 0.0) - (options.leftKey.isPressed() ? 1.0 : 0.0);
		if (forward == 0.0 && sideways == 0.0) {
			return null;
		}

		Vec3d flat = horizontal(player.getRotationVec(1.0F));
		if (flat.lengthSquared() < 1.0E-6) {
			return null;
		}

		Vec3d forwardDir = flat.normalize();
		Vec3d rightDir = forwardDir.crossProduct(UP);
		Vec3d wish = forwardDir.multiply(forward).add(rightDir.multiply(sideways));
		double length = wish.length();
		return length < 1.0E-6 ? null : wish.multiply(1.0 / length);
	}

	private static Direction findWall(ClientPlayerEntity player) {
		double speed = momentum.length();
		if (speed < WALL_RUN_MIN_SPEED) {
			return null;
		}

		Vec3d direction = momentum.multiply(1.0 / speed);
		Direction best = null;
		double bestAlignment = WALL_ALIGNMENT_LIMIT;

		for (Direction side : Direction.Type.HORIZONTAL) {
			double alignment = Math.abs(direction.x * side.getOffsetX() + direction.z * side.getOffsetZ());
			if (alignment < bestAlignment && hasWall(player, side)) {
				best = side;
				bestAlignment = alignment;
			}
		}

		return best;
	}

	private static boolean hasWall(ClientPlayerEntity player, Direction side) {
		if (side == null) {
			return false;
		}

		World world = player.getEntityWorld();
		BlockPos pos = player.getBlockPos().offset(side);
		return isWallBlock(world, pos) && isWallBlock(world, pos.up()) && distanceToWall(player, side) <= WALL_REACH;
	}

	private static boolean isWallBlock(World world, BlockPos pos) {
		return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
	}

	/**
	 * Gap between the player's hitbox and the wall surface, in blocks. Measuring from the hitbox rather
	 * than the entity's centre is what makes latching reliable: the centre-based version only accepted a
	 * ~0.2 block window at the very edge of the player's own block, so wall runs only started when the
	 * player was already pressed against the wall.
	 */
	private static double distanceToWall(ClientPlayerEntity player, Direction side) {
		Box box = player.getBoundingBox();
		BlockPos pos = player.getBlockPos();
		double gap = switch (side) {
			case EAST -> pos.getX() + 1.0 - box.maxX;
			case WEST -> box.minX - pos.getX();
			case SOUTH -> pos.getZ() + 1.0 - box.maxZ;
			case NORTH -> box.minZ - pos.getZ();
			default -> Double.MAX_VALUE;
		};
		return Math.max(gap, 0.0);
	}

	/** Unit tangent of the wall, flipped to point the way the player is already travelling. */
	private static Vec3d wallTangent(Direction wall, Vec3d velocity) {
		Vec3d tangent = new Vec3d(-wall.getOffsetZ(), 0.0, wall.getOffsetX());
		if (tangent.x * velocity.x + tangent.z * velocity.z < 0.0) {
			tangent = tangent.multiply(-1.0);
		}

		return tangent;
	}

	/**
	 * Opens the lurch window that every jump gets: for the next few ticks a small push is applied toward
	 * whatever direction the player is holding, and the push follows the input rather than being fixed at
	 * the moment of the jump. Titanfall 2's lurch is documented as "the small push you get toward your
	 * movement direction right after a jump (~0.35s window)", and the same sources note that holding both
	 * strafe directions cancels it, which {@link #applyLurch} reproduces.
	 */
	private static void startLurch() {
		lurchTicksLeft = config.lurchTicks;
		lurchBudget = config.lurchSpeed;
	}

	/** Adds this tick's slice of the lurch budget along the current wish direction. */
	private static void applyLurch(ClientPlayerEntity player, GameOptions options) {
		if (lurchTicksLeft <= 0 || lurchBudget <= 0.0) {
			return;
		}

		lurchTicksLeft--;

		// Opposing strafe input cancels the strafe and with it the lurch.
		if (options.leftKey.isPressed() && options.rightKey.isPressed()) {
			return;
		}

		Vec3d wishDir = wishDirection(player, options);
		if (wishDir == null) {
			return;
		}

		double step = Math.min(lurchBudget, config.lurchSpeed / Math.max(1, config.lurchTicks));
		momentum = momentum.add(wishDir.multiply(step));
		lurchBudget -= step;
	}

	/** True when any horizontal face of the player's block is a wall within reach, at any height. */
	private static boolean touchingWall(ClientPlayerEntity player) {
		World world = player.getEntityWorld();
		for (Direction side : Direction.Type.HORIZONTAL) {
			BlockPos pos = player.getBlockPos().offset(side);
			if (isWallBlock(world, pos) && distanceToWall(player, side) <= WALL_REACH) {
				return true;
			}
		}

		return false;
	}

	private static Vec3d position(ClientPlayerEntity player) {
		return new Vec3d(player.getX(), player.getY(), player.getZ());
	}

	private static Vec3d horizontal(Vec3d velocity) {
		return new Vec3d(velocity.x, 0.0, velocity.z);
	}

	/**
	 * True while this controller is the authority on the player's horizontal velocity, used by the
	 * travel mixin to stop vanilla adding its own input acceleration on top of the modelled movement.
	 */
	public static boolean ownsMovement(ClientPlayerEntity player) {
		return started && !movementUnsuitable(player) && (airborne || sliding);
	}

	/** True while the player is in a controller-driven slide, used to let a slide leave a ledge. */
	public static boolean isSliding(ClientPlayerEntity player) {
		return started && sliding && !movementUnsuitable(player);
	}

	private static void reset() {
		started = false;
		jumpHeld = false;
		airborne = false;
		doubleJumped = false;
		lastFallDistance = 0.0;
		momentum = Vec3d.ZERO;
		lastPosition = Vec3d.ZERO;
		lastMovement = Vec3d.ZERO;
		measuredSpeed = 0.0;
		previousMeasuredSpeed = 0.0;
		wallRunning = false;
		wallSide = null;
		wallRunTicks = 0;
		wallRunSpeed = 0.0;
		wallCooldown = 0;
		sliding = false;
		slideVelocity = Vec3d.ZERO;
		slideBoostCooldown = 0;
		jumpSoundPlayed = false;
		lurchTicksLeft = 0;
		lurchBudget = 0.0;
		MovementSounds.clear();
	}
}
