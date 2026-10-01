package com.hlmovement.client.mixin;

import com.hlmovement.HLConfig;
import com.hlmovement.client.HLPhysics;
import com.hlmovement.client.HLSounds;
import com.hlmovement.sound.HLSoundHooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "aiStep", at = @At("HEAD"))
	private void hlmovement$stopSprinting(CallbackInfo ci) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (HLConfig.disableSprint && self.isSprinting() && HLPhysics.isControlling(self)) {
			self.setSprinting(false);
		}
	}

	@Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
	private void hlmovement$noSprint(CallbackInfoReturnable<Boolean> cir) {
		if (HLConfig.disableSprint && HLPhysics.isControlling(this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void hlmovement$sounds(CallbackInfo ci) {
		HLSounds.tick((LocalPlayer) (Object) this);
	}

	/** Fall pain, water splashes and swim strokes come from HLSoundEngine instead. */
	@Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
	private void hlmovement$suppressReplaced(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
		if (HLSoundHooks.isReplaced(sound) && HLSoundHooks.suppressVanilla((LocalPlayer) (Object) this)) {
			ci.cancel();
		}
	}

	/** Half-Life has no auto-jump. */
	@Inject(method = "updateAutoJump", at = @At("HEAD"), cancellable = true)
	private void hlmovement$noAutoJump(float xa, float za, CallbackInfo ci) {
		if (HLPhysics.isControlling(this)) {
			ci.cancel();
		}
	}
}
