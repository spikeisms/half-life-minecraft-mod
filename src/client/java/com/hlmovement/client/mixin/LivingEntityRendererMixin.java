package com.hlmovement.client.mixin;

import com.hlmovement.client.model.HLModel;
import com.hlmovement.client.model.PlayerModels;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Draws players in Half-Life mode as their Half-Life model instead of the Steve model. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@WrapWithCondition(method = "submit", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"))
	private boolean hlmovement$hlPlayerModel(SubmitNodeCollector collector, Model<?> model, Object state, PoseStack poseStack, RenderType renderType,
		int light, int overlay, int color, UvMapping uvMapping, int outlineColor) {
		if (!((Object) this instanceof AvatarRenderer<?>) || !(state instanceof AvatarRenderState avatar)) return true;
		float[] bones = PlayerModels.bones(avatar);
		HLModel hlModel = bones == null ? null : HLModel.get(PlayerModels.MODEL);
		if (hlModel == null) return true;

		boolean translucent = color >>> 24 != 0xFF;
		if (avatar.isInvisible && !translucent) return false; // only the glowing outline would show; not supported yet

		poseStack.pushPose();
		// Undo the model-space transform (flip, the player's 15/16 scale, the 1.501 offset) to get back to
		// Y-up, feet-at-origin space, still rotated to the body yaw.
		poseStack.translate(0.0F, 1.501F, 0.0F);
		float unscale = 1.0F / 0.9375F;
		poseStack.scale(-unscale, -unscale, unscale);
		if (avatar.isCrouching) poseStack.translate(0.0F, 2.0F / 16.0F, 0.0F); // AvatarRenderer lowers crouching players
		hlModel.submit(bones, poseStack, collector, light, overlay, color, translucent);
		PlayerModels.submitHeldItems(avatar, hlModel, bones, poseStack, collector, light);
		poseStack.popPose();
		return false;
	}
}
