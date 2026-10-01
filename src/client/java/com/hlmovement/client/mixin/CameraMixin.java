package com.hlmovement.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Inside a solid block, vanilla only turns off occlusion culling for spectators; noclip needs it too. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
	private boolean hlmovement$noclipLikeSpectator(boolean spectator) {
		return spectator || Minecraft.getInstance().player.noPhysics;
	}
}
