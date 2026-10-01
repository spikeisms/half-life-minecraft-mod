package com.hlmovement.client.mixin;

import com.hlmovement.client.HLPhysics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/** Replace the whole movement step for the local player. */
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void hlmovement$travel(Vec3 input, CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer player && HLPhysics.isControlling(player)) {
			HLPhysics.travel(player);
			ci.cancel();
		}
	}
}
