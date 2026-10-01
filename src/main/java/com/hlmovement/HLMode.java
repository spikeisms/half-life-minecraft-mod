package com.hlmovement;

import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is playing in Half-Life mode, and as which character. Everything the mod changes about a player
 * (movement, duck hull, fall damage, sounds, HUD, model) applies only while they're in it; everyone else
 * is a normal Minecraft player.
 *
 * Each player chooses for themselves: their client tells the server (ModeRequestPayload), and the server
 * tells everyone (ModeStatePayload). The server and client keep separate lists because in singleplayer
 * both run in the same process.
 */
public final class HLMode {
	/** The characters a player can be: converted models in assets/hlmovement/hlmodels. */
	public static final List<String> CHARACTERS = List.of("scientist", "gordon", "barney");
	public static final String DEFAULT_CHARACTER = CHARACTERS.getFirst();

	private static final Map<UUID, String> SERVER = new ConcurrentHashMap<>();
	private static final Map<UUID, String> CLIENT = new ConcurrentHashMap<>();

	private HLMode() {
	}

	public static boolean isOn(Player player) {
		UUID id = player.getUUID();
		return id != null && players(player.level().isClientSide()).containsKey(id);
	}

	/** The character a player in Half-Life mode is playing as. */
	public static String character(Player player) {
		return players(player.level().isClientSide()).getOrDefault(player.getUUID(), DEFAULT_CHARACTER);
	}

	/** Anything that isn't one of {@link #CHARACTERS} becomes the default. */
	public static String validCharacter(String character) {
		return CHARACTERS.contains(character) ? character : DEFAULT_CHARACTER;
	}

	/** Puts a player in the mode as a character, or takes them out of it. Returns whether this changed anything. */
	public static boolean set(boolean clientSide, UUID player, boolean on, String character) {
		Map<UUID, String> players = players(clientSide);
		if (!on) {
			return players.remove(player) != null;
		}

		String valid = validCharacter(character);
		return !valid.equals(players.put(player, valid));
	}

	/** Players in the mode and their characters. */
	public static Map<UUID, String> players(boolean clientSide) {
		return clientSide ? CLIENT : SERVER;
	}
}
