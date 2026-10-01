package com.hlmovement.mixin;

import com.hlmovement.HLConfig;
import com.hlmovement.HLDuck;
import com.hlmovement.HLMode;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Half-Life's duck hull, for players in Half-Life mode: half the standing height. */
@Mixin(Avatar.class)
public abstract class AvatarMixin {
	@Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
	private void hlmovement$duckHull(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if (pose == Pose.CROUCHING && HLConfig.hlDuckHull && (Object) this instanceof Player player && HLMode.isOn(player)) {
			cir.setReturnValue(HLDuck.DUCK_HULL);
		}
	}
}
