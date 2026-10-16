package com.loaffie.titanfall.mixin.client;

import com.loaffie.titanfall.movement.TitanfallController;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vanilla adds a WASD-driven acceleration to the player's velocity inside {@code travel}, on top of
 * the velocity the movement controller writes. It is small per tick, but nothing consumes it, so in
 * the air it accumulates across a whole jump and quietly pushes the pilot above the speed the model
 * allows - exactly the kind of drift that makes chained movement feel inconsistent.
 *
 * <p>While the controller owns the player's horizontal velocity the input vector is zeroed, leaving
 * the model as the only thing accelerating the pilot. Only the input is changed: gravity, drag,
 * collision, fall distance and everything else in {@code travel} still runs in vanilla's order.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTravelMixin {
	@ModifyVariable(method = "travel(Lnet/minecraft/util/math/Vec3d;)V", at = @At("HEAD"), argsOnly = true)
	private Vec3d titanfall$dropVanillaMovementInput(Vec3d movementInput) {
		if ((Object) this instanceof ClientPlayerEntity player && TitanfallController.ownsMovement(player)) {
			return Vec3d.ZERO;
		}

		return movementInput;
	}
}
