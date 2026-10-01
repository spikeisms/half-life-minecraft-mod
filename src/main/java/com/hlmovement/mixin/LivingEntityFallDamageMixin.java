package com.hlmovement.mixin;

import com.hlmovement.HLConfig;
import com.hlmovement.HLMode;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Half-Life fall damage: nothing below PLAYER_MAX_SAFE_FALL_SPEED, then linear up to
 * PLAYER_FATAL_FALL_SPEED (100 HP). Impact speed is derived from the fall distance using sv_gravity.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallDamageMixin {
	@Inject(method = "calculateFallDamage", at = @At("HEAD"), cancellable = true)
	private void hlmovement$halfLifeFallDamage(double fallDistance, float damageModifier, CallbackInfoReturnable<Integer> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Player player) || !HLMode.isOn(player) || !HLConfig.hlFallDamage || self.is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) {
			return;
		}

		// Keep vanilla modifiers to the safe distance (e.g. jump boost) relative to the default of 3 blocks.
		double safeBonus = self.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) - 3.0;
		double blocks = Math.max(0.0, fallDistance - safeBonus);
		double impactSpeed = Math.sqrt(2.0 * HLConfig.gravity * blocks * HLConfig.unitsPerBlock);
		if (impactSpeed <= HLConfig.safeFallSpeed) {
			cir.setReturnValue(0);
			return;
		}

		double hlDamage = (impactSpeed - HLConfig.safeFallSpeed) * (100.0 / (HLConfig.fatalFallSpeed - HLConfig.safeFallSpeed));
		// Half-Life player has 100 HP, Minecraft player has 20.
		double damage = hlDamage * 0.2 * damageModifier * self.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER);
		cir.setReturnValue(Mth.floor(damage));
	}
}
