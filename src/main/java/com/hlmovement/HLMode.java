package com.hlmovement;

import net.minecraft.world.entity.player.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is playing in Half-Life mode. Everything the mod changes about a player (movement, duck hull, fall
 * damage, sounds, HUD, model) applies only while they're in it; everyone else is a normal Minecraft player.
 *
 * Each player chooses for themselves: their client tells the server (ModeRequestPayload), and the server
 * tells everyone (ModeStatePayload). The server and client keep separate lists because in singleplayer
 * both run in the same process.
 */
public final class HLMode {
	private static final Set<UUID> SERVER = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> CLIENT = ConcurrentHashMap.newKeySet();

	private HLMode() {
	}

	public static boolean isOn(Player player) {
		UUID id = player.getUUID();
		return id != null && players(player.level().isClientSide()).contains(id);
	}

	/** Returns whether this changed anything. */
	public static boolean set(boolean clientSide, UUID player, boolean on) {
		return on ? players(clientSide).add(player) : players(clientSide).remove(player);
	}

	public static Set<UUID> players(boolean clientSide) {
		return clientSide ? CLIENT : SERVER;
	}
}
