package com.loaffie.titanfall.movement;

import com.loaffie.titanfall.config.TitanfallConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class TitanfallController {
	private static final double JUMP_VELOCITY = 0.42;
	private static final double MIN_AIR_SPEED = 0.05;
	private static final double WALL_RUN_MIN_SPEED = 0.15;
	private static final double WALL_REACH = 0.5;
	private static final double WALL_ALIGNMENT_LIMIT = 0.7;
	private static final double LAND_FALL_DISTANCE = 1.0;

	private static TitanfallConfig config;
	private static boolean started;
	private static float lastYaw;
	private static double lastFallDistance;
	private static boolean jumpHeld;
	private static boolean airborne;
	private static boolean doubleJumped;
	private static double previousSpeed;

	private static boolean wallRunning;
	private static Direction wallSide;
	private static int wallRunTicks;
	private static double wallRunSpeed;
	private static int wallCooldown;

	private static boolean sliding;
	private static Vec3d slideVelocity = Vec3d.ZERO;

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
			lastYaw = player.getYaw();
			started = true;
		}

		GameOptions options = client.options;
		boolean jumpPressed = options.jumpKey.isPressed();
		boolean jumpEdge = jumpPressed && !jumpHeld;
		float yawDelta = MathHelper.wrapDegrees(player.getYaw() - lastYaw);
		boolean airborneNow = isAirborne(player);

		if (wallCooldown > 0) {
			wallCooldown--;
		}

		if (airborneNow) {
			handleAirMovement(player, options, jumpEdge, yawDelta);
		} else {
			if (airborne && lastFallDistance >= LAND_FALL_DISTANCE) {
				MovementSounds.land();
			}

			handleGroundMovement(player, jumpEdge);
		}

		lastYaw = player.getYaw();
		lastFallDistance = player.fallDistance;
		jumpHeld = jumpPressed;
		airborne = airborneNow;
		previousSpeed = player.getVelocity().horizontalLength();
	}

	private static boolean isAirborne(ClientPlayerEntity player) {
		return !player.isOnGround()
				&& !player.isTouchingWater()
				&& !player.isClimbing()
				&& !player.isGliding()
				&& !player.getAbilities().flying
				&& !player.isSpectator()
				&& !player.hasVehicle();
	}

	private static void handleAirMovement(ClientPlayerEntity player, GameOptions options, boolean jumpEdge, float yawDelta) {
		if (sliding) {
			sliding = false;
			MovementSounds.stopSlide();
		}

		if (wallRunning) {
			if (jumpEdge) {
				wallJump(player);
				return;
			}

			if (updateWallRun(player)) {
				return;
			}
		} else if (wallCooldown == 0 && player.isSprinting() && !player.isSneaking()) {
			Direction wall = findWall(player);
			if (wall != null) {
				startWallRun(player, wall);
				return;
			}
		}

		if (jumpEdge && airborne && !doubleJumped) {
			doubleJump(player);
		}

		airMove(player, options, yawDelta);
	}

	private static void handleGroundMovement(ClientPlayerEntity player, boolean jumpEdge) {
		wallRunning = false;
		wallRunTicks = 0;
		doubleJumped = false;

		if (sliding) {
			if (jumpEdge) {
				player.setVelocity(slideVelocity.x, JUMP_VELOCITY, slideVelocity.z);
				sliding = false;
				MovementSounds.stopSlide();
				MovementSounds.jump();
			} else if (player.isSneaking() && slideVelocity.length() > config.slideMinSpeed) {
				slideVelocity = slideVelocity.multiply(config.slideFriction);
				player.setVelocity(slideVelocity.x, player.getVelocity().y, slideVelocity.z);
			} else {
				sliding = false;
				MovementSounds.stopSlide();
			}

			return;
		}

		Vec3d velocity = player.getVelocity();
		double speed = velocity.horizontalLength();
		if (player.isSneaking() && speed > config.slideMinSpeed) {
			sliding = true;
			double target = Math.min(Math.max(speed, previousSpeed), config.maxHorizontalSpeed);
			slideVelocity = new Vec3d(velocity.x / speed, 0.0, velocity.z / speed).multiply(target);
			MovementSounds.startSlide();
		}
	}

	private static void airMove(ClientPlayerEntity player, GameOptions options, float yawDelta) {
		Vec3d velocity = player.getVelocity();
		Vec3d horizontal = new Vec3d(velocity.x, 0.0, velocity.z);
		double speed = horizontal.length();
		if (speed < MIN_AIR_SPEED || previousSpeed < config.airPreserveSpeed) {
			return;
		}

		boolean strafeLeft = options.leftKey.isPressed() && yawDelta < 0.0F;
		boolean strafeRight = options.rightKey.isPressed() && yawDelta > 0.0F;
		if (strafeLeft || strafeRight) {
			horizontal = rotateY(horizontal, yawDelta * config.strafeTurnFactor);
		}

		double target = Math.min(previousSpeed, config.maxHorizontalSpeed);
		horizontal = horizontal.multiply(target / speed);
		player.setVelocity(horizontal.x, velocity.y, horizontal.z);
	}

	private static void doubleJump(ClientPlayerEntity player) {
		Vec3d velocity = player.getVelocity();
		player.setVelocity(velocity.x, config.doubleJumpVelocity, velocity.z);
		doubleJumped = true;
		MovementSounds.doubleJump();
	}

	private static void startWallRun(ClientPlayerEntity player, Direction wall) {
		wallRunning = true;
		wallSide = wall;
		wallRunTicks = 0;
		wallRunSpeed = Math.min(player.getVelocity().horizontalLength(), config.maxHorizontalSpeed);
		doubleJumped = false;
		updateWallRun(player);
		MovementSounds.startWallRun();
	}

	private static boolean updateWallRun(ClientPlayerEntity player) {
		if (wallRunTicks >= config.wallRunMaxTicks || !hasWall(player, wallSide)) {
			endWallRun();
			return false;
		}

		Vec3d velocity = player.getVelocity();
		Vec3d tangent = wallTangent(wallSide, velocity);
		player.setVelocity(tangent.x * wallRunSpeed, Math.max(velocity.y, config.wallRunFallSpeed), tangent.z * wallRunSpeed);
		wallRunTicks++;
		return true;
	}

	private static void wallJump(ClientPlayerEntity player) {
		Vec3d tangent = wallTangent(wallSide, player.getVelocity());
		MovementSounds.wallJump();
		player.setVelocity(
				tangent.x * wallRunSpeed - wallSide.getOffsetX() * config.wallJumpPush,
				JUMP_VELOCITY,
				tangent.z * wallRunSpeed - wallSide.getOffsetZ() * config.wallJumpPush);
		endWallRun();
	}

	private static void endWallRun() {
		wallRunning = false;
		wallRunTicks = 0;
		wallCooldown = config.wallCooldownTicks;
		MovementSounds.stopWallRun();
	}

	private static Direction findWall(ClientPlayerEntity player) {
		Vec3d velocity = player.getVelocity();
		double speed = velocity.horizontalLength();
		if (speed < WALL_RUN_MIN_SPEED) {
			return null;
		}

		double velocityX = velocity.x / speed;
		double velocityZ = velocity.z / speed;
		Direction best = null;
		double bestAlignment = WALL_ALIGNMENT_LIMIT;

		for (Direction side : Direction.Type.HORIZONTAL) {
			double alignment = Math.abs(velocityX * side.getOffsetX() + velocityZ * side.getOffsetZ());
			if (alignment < bestAlignment && hasWall(player, side)) {
				best = side;
				bestAlignment = alignment;
			}
		}

		return best;
	}

	private static boolean hasWall(ClientPlayerEntity player, Direction side) {
		World world = player.getEntityWorld();
		BlockPos pos = player.getBlockPos().offset(side);
		return isWallBlock(world, pos) && isWallBlock(world, pos.up()) && distanceToWall(player, side) <= WALL_REACH;
	}

	private static boolean isWallBlock(World world, BlockPos pos) {
		return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
	}

	private static double distanceToWall(ClientPlayerEntity player, Direction side) {
		BlockPos pos = player.getBlockPos();
		return switch (side) {
			case EAST -> pos.getX() + 1.0 - player.getX();
			case WEST -> player.getX() - pos.getX();
			case SOUTH -> pos.getZ() + 1.0 - player.getZ();
			case NORTH -> player.getZ() - pos.getZ();
			default -> Double.MAX_VALUE;
		};
	}

	private static Vec3d wallTangent(Direction wall, Vec3d velocity) {
		Vec3d tangent = new Vec3d(-wall.getOffsetZ(), 0.0, wall.getOffsetX());
		if (tangent.x * velocity.x + tangent.z * velocity.z < 0.0) {
			tangent = tangent.multiply(-1.0);
		}

		return tangent;
	}

	private static Vec3d rotateY(Vec3d velocity, double degrees) {
		double radians = Math.toRadians(degrees);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return new Vec3d(velocity.x * cos - velocity.z * sin, velocity.y, velocity.x * sin + velocity.z * cos);
	}

	private static void reset() {
		started = false;
		jumpHeld = false;
		airborne = false;
		doubleJumped = false;
		previousSpeed = 0.0;
		lastFallDistance = 0.0;
		wallRunning = false;
		wallSide = null;
		wallRunTicks = 0;
		wallRunSpeed = 0.0;
		wallCooldown = 0;
		sliding = false;
		slideVelocity = Vec3d.ZERO;
		MovementSounds.clear();
	}
}
