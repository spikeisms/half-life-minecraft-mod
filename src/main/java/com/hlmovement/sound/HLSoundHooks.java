package com.hlmovement.sound;

import com.hlmovement.HLMode;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

import java.util.Set;

/** Which vanilla player sounds are replaced by HLSoundEngine, and for whom. */
public final class HLSoundHooks {
	/** Vanilla player sounds with Half-Life replacements (footsteps are handled separately). */
	private static final Set<SoundEvent> REPLACED = Set.of(
		SoundEvents.PLAYER_SMALL_FALL,
		SoundEvents.PLAYER_BIG_FALL,
		SoundEvents.PLAYER_SPLASH,
		SoundEvents.PLAYER_SPLASH_HIGH_SPEED,
		SoundEvents.PLAYER_SWIM
	);

	private HLSoundHooks() {
	}

	/**
	 * Only for players in Half-Life mode.
	 * Server: every player's broadcast (the server sends Half-Life sounds to the others instead).
	 * Client: the local player's own sounds (the client plays Half-Life sounds for itself).
	 */
	public static boolean suppressVanilla(Player player) {
		return HLMode.isOn(player) && (!player.level().isClientSide() || player.isLocalInstanceAuthoritative());
	}

	public static boolean isReplaced(SoundEvent sound) {
		return REPLACED.contains(sound);
	}
}
