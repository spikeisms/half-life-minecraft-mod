package com.hlmovement.client.mixin;

import com.hlmovement.client.HLPhysics;
import com.hlmovement.client.hud.HLHud;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla jumps (and swims up/down) inside aiStep() before travel() runs. When the
 * Half-Life model is driving the local player it handles all of that itself.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
	private void hlmovement$skipVanillaJump(CallbackInfo ci) {
		if (HLPhysics.isControlling(this)) {
			ci.cancel();
		}
	}

	@Inject(method = "jumpInLiquid", at = @At("HEAD"), cancellable = true)
	private void hlmovement$skipVanillaSwimUp(TagKey<Fluid> type, CallbackInfo ci) {
		if (HLPhysics.isControlling(this)) {
			ci.cancel();
		}
	}

	/** Feeds the HUD's damage direction arcs and damage-type icons. */
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void hlmovement$hudDamage(DamageSource source, CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer player) {
			HLHud.onDamage(player, source);
		}
	}

	@Inject(method = "goDownInWater", at = @At("HEAD"), cancellable = true)
	private void hlmovement$skipVanillaSwimDown(CallbackInfo ci) {
		if (HLPhysics.isControlling(this)) {
			ci.cancel();
		}
	}
}
