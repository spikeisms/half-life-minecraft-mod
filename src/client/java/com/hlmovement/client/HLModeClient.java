package com.hlmovement.client;

import com.hlmovement.HLConfig;
import com.hlmovement.HLDuck;
import com.hlmovement.HLMode;
import com.hlmovement.net.ModeRequestPayload;
import com.hlmovement.net.ModeStatePayload;
import com.hlmovement.sound.HLSoundEngine;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Client side of {@link HLMode}: our own choice (kept in the config, so it's remembered between sessions
 * and servers) and what the server tells us about everyone.
 */
public final class HLModeClient {
	private HLModeClient() {
	}

	/** Whether the local player is in Half-Life mode right now. */
	public static boolean active() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.player != null && HLMode.isOn(minecraft.player);
	}

	/** On joining a world: start in the mode we were last in, and tell the server. */
	public static void join(Minecraft minecraft) {
		if (minecraft.player != null) {
			apply(minecraft, minecraft.player.getUUID(), HLConfig.hlMode, HLConfig.character);
		}

		request();
	}

	/** The player's own switch (key or settings screen). */
	public static void set(Minecraft minecraft, boolean on) {
		if (HLConfig.hlMode != on) {
			HLConfig.hlMode = on;
			HLConfig.save();
		}

		if (minecraft.player == null) {
			return;
		}

		// Switch straight away instead of waiting for the server to confirm.
		apply(minecraft, minecraft.player.getUUID(), on, HLConfig.character);
		request();
		minecraft.player.sendOverlayMessage(Component.translatable(on ? "hlmovement.mode.on" : "hlmovement.mode.off"));
	}

	/** The player's own choice of character (settings screen). */
	public static void setCharacter(Minecraft minecraft, String character) {
		HLConfig.character = HLMode.validCharacter(character);
		HLConfig.save();
		if (minecraft.player != null && HLConfig.hlMode) {
			apply(minecraft, minecraft.player.getUUID(), true, HLConfig.character);
			request();
		}
	}

	public static void receive(ModeStatePayload payload, Minecraft minecraft) {
		if (minecraft.player != null && payload.player().equals(minecraft.player.getUUID())) {
			// We already switched ourselves; a confirmation that differs is of an earlier request.
			return;
		}

		apply(minecraft, payload.player(), payload.on(), payload.character());
	}

	private static void request() {
		if (ClientPlayNetworking.canSend(ModeRequestPayload.TYPE)) {
			ClientPlayNetworking.send(new ModeRequestPayload(HLConfig.hlMode, HLConfig.character));
		}
	}

	private static void apply(Minecraft minecraft, UUID id, boolean on, String character) {
		boolean wasOn = HLMode.players(true).containsKey(id);
		if (!HLMode.set(true, id, on, character) || wasOn == on || minecraft.level == null) {
			return;
		}

		Player player = minecraft.level.getPlayerByUUID(id);
		if (player != null) {
			((HLDuck.Holder) player).hlmovement$duckState().reset();
			((HLSoundEngine.Holder) player).hlmovement$soundState().reset();
			// The duck hull differs between the modes.
			player.refreshDimensions();
		}
	}
}
