package com.hlmovement.client.mixin;

import com.hlmovement.client.model.PlayerModels;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Armor, capes, arrows and the rest are attached to the Steve model's limbs, which the Half-Life model
 * doesn't have, so they're left off while players are drawn as one. (Held items are drawn in its hands by
 * PlayerModels instead.)
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Z", at = @At("HEAD"), cancellable = true)
	private void hlmovement$noLayers(AvatarRenderState state, CallbackInfoReturnable<Boolean> cir) {
		if (PlayerModels.bones(state) != null) cir.setReturnValue(false);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void hlmovement$animate(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		PlayerModels.extract(entity, state, partialTicks);
	}
}
