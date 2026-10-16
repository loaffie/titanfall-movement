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
 * <p>While the player is airborne, wall running, sliding or carrying the momentum a slide left behind,
 * this controller owns their horizontal velocity and rewrites it at the end of every client tick.
 * Vanilla applies its friction after the tick's movement and before our next write, so owning the value
 * means vanilla's 0.91 air drag and its ground friction never reach the movement modelled here. That is
 * what makes the result frictionless and floaty in the same way Source is: air movement in Source has no
 * friction at all, only the wish-velocity accelerator.
 *
 * <p>The invariant that holds the model together is that <em>the pilot's actual speed equals
 * {@link #momentum}</em>: the value written at the end of a tick is exactly what vanilla moves them by
 * on the next one. That is why a transition into a mode we own can hand over the momentum directly, and
 * why leaving the ground rebuilds momentum from the measured displacement rather than from vanilla's
 * stored velocity, which has already been through ground friction and is only about 0.55 of the truth on
 * ordinary blocks.
 *
 * <p>Speeds are kept in blocks per second (1 block = 1 metre) so the Source formulas can be used
 * unchanged, while the entity's velocity is in blocks per tick. {@link #TICKS_PER_SECOND} converts
 * between the two.
 */
public final class TitanfallController {
	private static final double TICKS_PER_SECOND = 20.0;
	private static final double TICK_SECONDS = 1.0 / TICKS_PER_SECOND;

	/** Vanilla player gravity, blocks per tick per tick. */
	private static final double VANILLA_GRAVITY = 0.08;
	/** Vanilla's vertical drag on a falling entity, applied after our write and therefore undone by it. */
	private static final double VERTICAL_DRAG = 0.98;
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
	/**
	 * Stop speed for slide and carry friction, 60 Source units/s rather than Source's sv_stopspeed of
	 * 100: at Source's value a slide that has already given up its speed crawls along at a walking pace
	 * for seconds, which is the slow tail that makes a slide feel like it will not end.
	 */
	private static final double SLIDE_STOP_SPEED = 60.0 * SOURCE_UNIT;
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
	/** Set by a slide hop, which knows the exact speed it left the ground with. */
	private static boolean hopPending;

	private static boolean wallRunning;
	private static Direction wallSide;
	private static int wallRunTicks;
	private static int wallGraceTicks;
	/** The direction the run is travelling, curving onto the wall instead of snapping to it. */
	private static Vec3d wallRunDirection = Vec3d.ZERO;
	private static double wallRunSpeed;
	/** How fast the run is sinking, blocks per second, accumulated over the run. */
	private static double wallRunSink;
	private static int wallCooldown;

	/** The pilot's ground momentum is ours during a slide and during the carry a slide leaves behind. */
	private static boolean sliding;
	private static boolean carrying;
	private static Vec3d groundMomentum = Vec3d.ZERO;
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
		// The tick that has just finished was an airborne one, so the momentum the model is holding is the
		// pilot's real speed and this landing is a chance to keep all of it.
		boolean landedNow = airborne && !airborneNow;

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
			if (sliding || carrying) {
				stopSlide();
				stopCarry();
			}

			if (wallRunning) {
				stopWallRun();
			}
		} else if (airborneNow) {
			handleAirMovement(player, options, jumpEdge);
		} else {
			if (landedNow && lastFallDistance >= LAND_FALL_DISTANCE) {
				MovementSounds.land();
			}

			handleGroundMovement(player, options, landedNow);
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
		if (sliding || carrying) {
			// Leaving the ground mid-slide keeps the slide's own velocity - our value, not the
			// friction-clipped one - so a slide off a ledge carries on at full speed.
			momentum = groundMomentum;
			stopSlide();
			stopCarry();
		} else if (!airborne) {
			if (hopPending) {
				// A slide hop already wrote the exact speed the pilot left the ground with, so there is
				// nothing to rebuild. Doing it anyway would throw away a hop momentum above 1.0.
				hopPending = false;
			} else {
				// Just left the ground. The honest measure of how fast the pilot was moving is the ground
				// displacement of the tick that just ended: vanilla's stored velocity has already been
				// through ground friction, which is about 0.55 of the truth on ordinary blocks, and
				// rebuilding momentum from that number is what used to make a plain jump slower than a
				// sprint run and quietly eat 45% of the speed of every take-off.
				momentum = horizontal(lastMovement).multiply(TICKS_PER_SECOND);
			}

			// Rising rather than dropping off a ledge means a real ground jump. Vanilla plays no sound of
			// its own for a player jump, so this is the only thing that announces one.
			if (player.getVelocity().y > 0.0) {
				if (!jumpSoundPlayed) {
					MovementSounds.jump();
				}

				startLurch();
			}
		} else {
			momentum = adoptExternalImpulse(player);
		}

		jumpSoundPlayed = false;
		hopPending = false;

		if (wallRunning) {
			if (jumpEdge) {
				wallJump(player);
				return;
			}

			if (pushingAwayFromWall(player, options)) {
				// Holding away from the wall peels the run off deliberately, with the run's momentum kept.
				stopWallRun();
			} else if (continueWallRun(player)) {
				return;
			}
		} else if (wallCooldown == 0 && momentum.length() >= WALL_RUN_MIN_SPEED) {
			// Crouch held is not disqualifying: the documented hop chain holds crouch throughout, and a
			// pilot who reaches a wall that way still latches. The speed and alignment gates below are what
			// keep a slow pilot pressed against a wall out of a run.
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

	private static void handleGroundMovement(ClientPlayerEntity player, GameOptions options, boolean landedNow) {
		stopWallRun();
		doubleJumped = false;

		if (sliding || carrying) {
			handleGroundMomentum(player, options);
			return;
		}

		// Measured displacement, not the entity's velocity: end-of-tick velocity has already had vanilla
		// ground friction applied to it, and would make a slide start slower than a walk.
		double speed = Math.max(measuredSpeed, previousMeasuredSpeed);
		if (landedNow) {
			// A landing is where a chain is kept or lost, and the pilot's real speed at that moment is the
			// momentum the model carried through the air, not the friction-clipped ground measurement.
			speed = Math.max(speed, momentum.length());
		}

		if (!player.isSneaking() || speed < slideEntrySpeed()) {
			return;
		}

		Vec3d direction = slideDirection(player);
		if (landedNow && momentum.length() > 1.0E-4) {
			direction = momentum.multiply(1.0 / momentum.length());
		}

		if (direction == null) {
			return;
		}

		startSlide(player, slideEntry(direction.multiply(speed)));
	}

	/**
	 * A slide and the carry it turns into both own the pilot's ground momentum, and are the two states
	 * where being glued to the ground was most obvious: the slide used to be refused outright while the
	 * entry boost was on its cooldown, and standing up out of one deleted the momentum instead of
	 * handing it on.
	 */
	private static void handleGroundMomentum(ClientPlayerEntity player, GameOptions options) {
		// Crouching back down on a carry starts another slide: hold crouch through the hops and every
		// landing is a slide again, which is the documented slide hop chain.
		if (carrying && player.isSneaking() && groundMomentum.length() >= slideEntrySpeed()) {
			startSlide(player, slideEntry(groundMomentum));
		}

		// A held jump hops as well as a fresh press, so a chain is one continuous movement instead of a
		// one-tick timing trick.
		if (sliding && options.jumpKey.isPressed()) {
			slideHop(player);
			return;
		}

		groundMomentum = steerGroundMomentum(player, options, groundMomentum,
				sliding ? config.slideSteer : config.carrySteer);
		groundMomentum = applyFriction(groundMomentum,
				sliding ? config.slideFriction : config.carryFriction, SLIDE_STOP_SPEED);

		if (sliding && !player.isSneaking()) {
			handOverToCarry();
		}

		if (carrying && groundMomentum.length() < config.carryEndSpeed) {
			// Slow enough that vanilla walking is the same speed, so the pilot gets their legs back with
			// no visible step in speed.
			stopCarry();
			return;
		}

		if (sliding && groundMomentum.length() < config.carryEndSpeed) {
			// The slide has spent itself. It ends at the speed vanilla is about to walk at anyway, so the
			// hand-back is invisible instead of being a step down in speed.
			handOverToCarry();
			if (!carrying) {
				return;
			}
		}

		writeGroundVelocity(player, groundMomentum);
	}

	/**
	 * Speed a slide needs to start. It is deliberately never below the speed a slide hands the pilot back
	 * at: a slide that could start slower than it ends would flap on and off every tick - restarting its
	 * sound each time - for as long as the crouch key is held.
	 */
	private static double slideEntrySpeed() {
		return Math.max(config.slideMinSpeed, config.carryEndSpeed);
	}

	/** Begins a slide and writes its velocity on the same tick, so no tick of it runs at vanilla friction. */
	private static void startSlide(ClientPlayerEntity player, Vec3d velocity) {
		sliding = true;
		carrying = false;
		groundMomentum = velocity;
		MovementSounds.startSlide();
		writeGroundVelocity(player, groundMomentum);
	}

	/**
	 * Entry speed for a slide: whatever the pilot is already moving at, plus the entry boost Respawn
	 * describe when it is off cooldown. The documented 2 s timer gates the boost, not the slide, so a
	 * hop chain no longer collapses into a crouch-walk the moment the boost is unavailable.
	 */
	private static Vec3d slideEntry(Vec3d incoming) {
		double speed = incoming.length();
		if (speed < 1.0E-4) {
			return Vec3d.ZERO;
		}

		boolean boosted = slideBoostCooldown == 0;
		if (boosted) {
			slideBoostCooldown = config.slideBoostCooldownTicks;
		}

		double entry = speed * (boosted ? 1.0 + config.slideEntryBoost : 1.0);
		return incoming.multiply(Math.min(entry, config.maxHorizontalSpeed) / speed);
	}

	/**
	 * A slide that ends for any reason other than a hop keeps its momentum as a carry, which bleeds off
	 * on its own. That is the "keep the speed you earned" half of a slide: standing up used to delete it
	 * outright, which felt like being glued to the ground.
	 */
	private static void handOverToCarry() {
		double speed = groundMomentum.length();
		stopSlide();
		if (speed >= config.carryEndSpeed) {
			carrying = true;
		} else {
			groundMomentum = Vec3d.ZERO;
		}
	}

	/**
	 * Jumping out of a slide writes back the slide's own velocity, so the horizontal vector keeps all of
	 * its magnitude and the jump impulse is added on top rather than replacing it.
	 */
	private static void slideHop(ClientPlayerEntity player) {
		momentum = groundMomentum.multiply(config.slideHopMomentum);
		stopSlide();
		startLurch();
		jumpSoundPlayed = true;
		hopPending = true;
		MovementSounds.jump();
		player.setVelocity(momentum.x / TICKS_PER_SECOND, JUMP_VELOCITY, momentum.z / TICKS_PER_SECOND);
	}

	private static void stopSlide() {
		if (sliding) {
			MovementSounds.stopSlide();
		}

		sliding = false;
	}

	private static void stopCarry() {
		carrying = false;
		groundMomentum = Vec3d.ZERO;
	}

	private static void writeGroundVelocity(ClientPlayerEntity player, Vec3d velocity) {
		player.setVelocity(velocity.x / TICKS_PER_SECOND, player.getVelocity().y, velocity.z / TICKS_PER_SECOND);
	}

	/**
	 * Steering while a slide or the carry owns the momentum. A Titanfall slide keeps the speed brought
	 * into it but takes away most of the pilot's ability to stop or turn: a held direction bends the
	 * momentum sideways at a limited rate while preserving its magnitude, and pulling against the
	 * momentum trims speed instead of being ignored (which is what made a slide feel like a scripted
	 * animation rather than movement).
	 */
	private static Vec3d steerGroundMomentum(ClientPlayerEntity player, GameOptions options, Vec3d velocity, double steer) {
		double speed = velocity.length();
		Vec3d wishDir = wishDirection(player, options);
		if (wishDir == null || steer <= 0.0 || speed < 0.1) {
			return velocity;
		}

		Vec3d direction = velocity.multiply(1.0 / speed);
		double budget = steer * TICK_SECONDS;

		if (wishDir.dotProduct(direction) <= 0.0) {
			return velocity.multiply(Math.max(speed - budget, 0.0) / speed);
		}

		Vec3d lateral = wishDir.subtract(direction.multiply(wishDir.dotProduct(direction)));
		double magnitude = lateral.length();
		if (magnitude < 1.0E-6) {
			return velocity;
		}

		return direction.add(lateral.multiply(Math.min(budget, speed) / magnitude)).normalize().multiply(speed);
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
		wallGraceTicks = config.wallDetachGraceTicks;
		wallRunSink = 0.0;

		double speed = momentum.length();
		double along = Math.abs(wallTangent(wall, momentum).dotProduct(momentum));
		// Respawn: "Chaining wallruns will now help increase and retain speed". The run therefore keeps the
		// momentum the pilot arrived with, instead of being replaced by whatever happens to travel along
		// the wall face - projecting onto the tangent threw away most of the entry speed on a shallow
		// approach, which is a large part of why a latch felt like hitting a rail instead of a wall.
		wallRunSpeed = Math.max(along, speed * config.wallRunSpeedRetain);
		wallRunDirection = speed > 1.0E-4 ? momentum.multiply(1.0 / speed) : wallTangent(wall, momentum);

		if (config.airJumpOnWallRun) {
			doubleJumped = false;
		}

		MovementSounds.startWallRun();
		continueWallRun(player);
	}

	private static boolean continueWallRun(ClientPlayerEntity player) {
		if (wallRunTicks >= config.wallRunMaxTicks) {
			stopWallRun();
			return false;
		}

		if (hasWall(player, wallSide)) {
			wallGraceTicks = config.wallDetachGraceTicks;
		} else if (wallGraceTicks > 0) {
			// A doorway, a block corner or a one block gap in the sampling is not the end of a run.
			wallGraceTicks--;
		} else {
			stopWallRun();
			return false;
		}

		wallRunTicks++;

		// Curve the run onto the wall over a few ticks. Snapping the whole velocity vector sideways on the
		// first tick is a hard right angle at the moment of latching; blending turns it into an arc, which
		// is what the pilot's own view of the wall expects.
		Vec3d along = wallTangent(wallSide, wallRunDirection);
		double blend = Math.min(1.0, 1.0 / Math.max(1, config.wallRunAlignTicks));
		Vec3d turned = wallRunDirection.multiply(1.0 - blend).add(along.multiply(blend));
		if (turned.lengthSquared() > 1.0E-8) {
			wallRunDirection = turned.normalize();
		}

		// A run accelerates up to its own cap, and a pilot who arrived faster than that cap is left alone
		// rather than braked down to it. The wall jump is where the bigger boost is.
		wallRunSpeed = Math.min(wallRunSpeed + config.wallRunAccel * TICK_SECONDS,
				Math.max(wallRunSpeed, config.wallRunMaxSpeed));
		momentum = wallRunDirection.multiply(wallRunSpeed);

		// Gravity pulls the pilot down gradually during a run instead of being cancelled outright, and the
		// vertical written here is pre-compensated: vanilla subtracts its own gravity and multiplies the
		// result by 0.98 before the next tick moves the pilot, so writing the target directly would sink
		// the run faster than configured.
		wallRunSink += config.wallRunFall * TICK_SECONDS * TICK_SECONDS;
		double vertical = config.wallRunVerticalVelocity - wallRunSink;
		player.setVelocity(momentum.x / TICKS_PER_SECOND,
				vertical / VERTICAL_DRAG + VANILLA_GRAVITY,
				momentum.z / TICKS_PER_SECOND);
		return true;
	}

	/**
	 * The wall kick. It keeps the run's momentum through the turn and adds the push away from the wall on
	 * top, so a kick is a redirect that costs nothing rather than a reset, and the lurch window it opens
	 * is what lets the pilot steer the arc back into the next wall.
	 */
	private static void wallJump(ClientPlayerEntity player) {
		Vec3d along = wallRunDirection.lengthSquared() > 1.0E-8
				? wallRunDirection
				: wallTangent(wallSide, momentum);
		MovementSounds.wallJump();
		momentum = along.multiply(wallRunSpeed * config.wallJumpMomentum)
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
		wallGraceTicks = 0;
		wallCooldown = config.wallCooldownTicks;
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

	/** True when the pilot is holding a direction that points away from the wall they are running on. */
	private static boolean pushingAwayFromWall(ClientPlayerEntity player, GameOptions options) {
		Vec3d wishDir = wishDirection(player, options);
		if (wishDir == null || wallSide == null) {
			return false;
		}

		return wishDir.x * -wallSide.getOffsetX() + wishDir.z * -wallSide.getOffsetZ() > 0.5;
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
		if (side == null || distanceToWall(player, side) > WALL_REACH) {
			return false;
		}

		World world = player.getEntityWorld();
		BlockPos column = player.getBlockPos().offset(side);
		// The column beside the pilot and the one a block further along the run, so a block corner, a
		// doorway or the edge of the hitbox cannot end a run that is clearly still on a wall.
		return wallAt(world, column) || wallAt(world, aheadColumn(side, column));
	}

	/** The next block column along the wall face, in the direction the run is travelling. */
	private static BlockPos aheadColumn(Direction side, BlockPos column) {
		Vec3d tangent = wallTangent(side, wallRunDirection);
		return column.add((int) Math.round(tangent.x), 0, (int) Math.round(tangent.z));
	}

	/**
	 * A runnable wall: two solid blocks stacked, with the pilot's feet at the bottom one or at the top
	 * one. Requiring a pair keeps single blocks and fence posts from counting while still tolerating a
	 * wall that is a block higher or lower than the feet.
	 */
	private static boolean wallAt(World world, BlockPos column) {
		return (isWallBlock(world, column) && isWallBlock(world, column.up()))
				|| (isWallBlock(world, column.down()) && isWallBlock(world, column));
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
			if ((isWallBlock(world, pos) || isWallBlock(world, pos.up())) && distanceToWall(player, side) <= WALL_REACH) {
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
		return started && !movementUnsuitable(player) && (airborne || sliding || carrying);
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
		hopPending = false;
		wallRunning = false;
		wallSide = null;
		wallRunTicks = 0;
		wallGraceTicks = 0;
		wallRunDirection = Vec3d.ZERO;
		wallRunSpeed = 0.0;
		wallRunSink = 0.0;
		wallCooldown = 0;
		sliding = false;
		carrying = false;
		groundMomentum = Vec3d.ZERO;
		slideBoostCooldown = 0;
		jumpSoundPlayed = false;
		lurchTicksLeft = 0;
		lurchBudget = 0.0;
		MovementSounds.clear();
	}
}
