package com.hlmovement.client;

import com.hlmovement.HLMode;
import com.hlmovement.HLMovement;
import com.hlmovement.net.PlayerSoundPayload;
import com.hlmovement.sound.HLSoundEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Client half of the Half-Life player sounds: runs HLSoundEngine for the local player and plays
 * the sounds the server sends for everyone else.
 */
public final class HLSounds {
	private static final Map<String, SoundEvent> EVENTS = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private HLSounds() {
	}

	/** Called at the end of every local player tick: our own sounds, without waiting for the server. */
	public static void tick(LocalPlayer player) {
		HLSoundEngine.State state = ((HLSoundEngine.Holder) player).hlmovement$soundState();
		if (!HLMode.isOn(player)) {
			state.reset();
			return;
		}

		HLSoundEngine.tick(player, state, player.input.keyPresses.jump(), RANDOM, (sound, volume) -> play(player, sound, volume));
	}

	/** Another player's sound, sent by the server. */
	public static void receive(PlayerSoundPayload payload, Minecraft minecraft) {
		if (minecraft.level == null) {
			return;
		}

		Entity entity = minecraft.level.getEntity(payload.entityId());
		if (entity != null) {
			play(entity, payload.sound(), payload.volume());
		}
	}

	private static void play(Entity entity, String sound, float volume) {
		SoundEvent event = EVENTS.computeIfAbsent(sound, s -> SoundEvent.createVariableRangeEvent(HLMovement.id(s)));
		entity.level().playLocalSound(entity.getX(), entity.getY(), entity.getZ(), event, SoundSource.PLAYERS, volume, 1.0F, false);
	}
}
