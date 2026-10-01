package com.hlmovement.mixin;

import com.hlmovement.HLConfig;
import com.hlmovement.HLDuck;
import com.hlmovement.HLMode;
import com.hlmovement.HLMovement;
import com.hlmovement.sound.HLSoundEngine;
import com.hlmovement.sound.HLSoundHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin implements HLDuck.Holder, HLSoundEngine.Holder {
	@Unique
	private final HLDuck.State hlmovement$duck = new HLDuck.State();
	@Unique
	private final HLSoundEngine.State hlmovement$sound = new HLSoundEngine.State();

	@Override
	public HLDuck.State hlmovement$duckState() {
		return this.hlmovement$duck;
	}

	@Override
	public HLSoundEngine.State hlmovement$soundState() {
		return this.hlmovement$sound;
	}

	/**
	 * Creative flight is Half-Life noclip: no collision, like spectators. Done on both sides so the
	 * server lets the player through walls and the client skips the in-wall overlay and push-out.
	 */
	@Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void hlmovement$noclip(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (self.getAbilities().flying && !self.isSpectator() && HLMode.isOn(self)) {
			self.noPhysics = true;
			// Noclip never lands; without this, vanilla would end creative flight on the first tick.
			self.setOnGround(false);
		}
	}

	/** Half-Life ducking instead of Minecraft's instant sneak pose. */
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void hlmovement$duck(CallbackInfo ci) {
		if (HLDuck.updatePose((Player) (Object) this)) {
			ci.cancel();
		}
	}

	/** Half-Life lets you duck-walk straight off a ledge. */
	@Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true)
	private void hlmovement$noEdgeSafety(CallbackInfoReturnable<Boolean> cir) {
		if (HLConfig.disableSneakEdgeSafety && HLMode.isOn((Player) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	/** Vanilla footsteps (including ladders and wading) are replaced by HLSoundEngine. */
	@Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
	private void hlmovement$suppressStep(BlockPos pos, BlockState state, CallbackInfo ci) {
		if (HLSoundHooks.suppressVanilla((Player) (Object) this)) {
			ci.cancel();
		}
	}

	/** Server-side broadcast of fall/splash/swim sounds (the local player's copy is filtered in LocalPlayer). */
	@Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
	private void hlmovement$suppressReplaced(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
		if (HLSoundHooks.isReplaced(sound) && HLSoundHooks.suppressVanilla((Player) (Object) this)) {
			ci.cancel();
		}
	}

	/** CBasePlayer::Pain: one of pl_pain5-7 whatever the damage. */
	@Inject(method = "getHurtSound", at = @At("HEAD"), cancellable = true)
	private void hlmovement$painSound(DamageSource source, CallbackInfoReturnable<SoundEvent> cir) {
		Player self = (Player) (Object) this;
		if (HLMode.isOn(self)) {
			cir.setReturnValue(hlmovement$pain(self));
		}
	}

	/** CBasePlayer::DeathSound: a pain sound, or nothing 2 times in 5. */
	@Inject(method = "getDeathSound", at = @At("HEAD"), cancellable = true)
	private void hlmovement$deathSound(CallbackInfoReturnable<SoundEvent> cir) {
		Player self = (Player) (Object) this;
		if (HLMode.isOn(self)) {
			cir.setReturnValue(self.getRandom().nextInt(5) < 2 ? null : hlmovement$pain(self));
		}
	}

	@Unique
	private static SoundEvent hlmovement$pain(Player player) {
		return SoundEvent.createVariableRangeEvent(HLMovement.id("player.pl_pain" + (5 + player.getRandom().nextInt(3))));
	}
}