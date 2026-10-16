package com.loaffie.titanfall.mixin.client;

import com.loaffie.titanfall.movement.TitanfallController;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla stops a sneaking player from walking off a ledge by clamping their horizontal movement at
 * the edge. Sliding uses the sneak key, so that clamp used to halt every slide at the first drop and
 * a slide could never carry the player off a cliff, which is most of what sliding is for.
 *
 * <p>While the controller is running a slide the clamp is skipped, so the slide keeps its momentum and
 * the player simply leaves the edge. Outside a slide the vanilla behaviour is untouched.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntitySneakMixin {
	@Inject(
			method = "adjustMovementForSneaking(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/entity/MovementType;)Lnet/minecraft/util/math/Vec3d;",
			at = @At("HEAD"),
			cancellable = true)
	private void titanfall$allowSlidingOffLedges(Vec3d movementInput, MovementType type, CallbackInfoReturnable<Vec3d> cir) {
		if ((Object) this instanceof ClientPlayerEntity player && TitanfallController.isSliding(player)) {
			cir.setReturnValue(movementInput);
		}
	}
}
