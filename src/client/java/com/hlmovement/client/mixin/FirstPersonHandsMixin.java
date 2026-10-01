package com.hlmovement.client.mixin;

import com.hlmovement.client.HLModeClient;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Unarmed has no viewmodel in Half-Life: don't draw the bare arm when the main hand is empty. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	@WrapWithCondition(method = "submitArmWithItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;renderPlayerArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFFLnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V"))
	private boolean hlmovement$noEmptyHand(FirstPersonHandsAndItemsRenderer renderer, PoseStack poseStack, SubmitNodeCollector collector,
		int lightCoords, float inverseArmHeight, float attack, HumanoidArm arm, PlayerRenderState playerState) {
		return !HLModeClient.active();
	}
}
