package com.hlmovement;

import com.hlmovement.net.ConfigSyncPayload;
import com.hlmovement.net.ModeRequestPayload;
import com.hlmovement.net.ModeStatePayload;
import com.hlmovement.net.PlayerSoundPayload;
import com.hlmovement.sound.HLSoundEngine;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HLMovement implements ModInitializer {
	public static final String MOD_ID = "hlmovement";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final RandomSource SERVER_RANDOM = RandomSource.create();

	@Override
	public void onInitialize() {
		HLConfig.load();

		PayloadTypeRegistry.clientboundPlay().register(ConfigSyncPayload.TYPE, ConfigSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlayerSoundPayload.TYPE, PlayerSoundPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ModeStatePayload.TYPE, ModeStatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ModeRequestPayload.TYPE, ModeRequestPayload.CODEC);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!ServerPlayNetworking.canSend(handler.player, ConfigSyncPayload.TYPE)) {
				// Half-Life mode players have a different hitbox and sounds that everyone has to agree on, so the mod is required.
				handler.disconnect(Component.literal("This server requires the Half-Life Movement mod."));
				return;
			}

			// Everyone in Half-Life mode simulates with the server's settings.
			sender.sendPacket(new ConfigSyncPayload(HLConfig.serializeSynced()));
			HLMode.players(false).forEach((id, character) -> sender.sendPacket(new ModeStatePayload(id, true, character)));
		});

		// Players choose Half-Life mode for themselves; their client asks on join and whenever they toggle it.
		ServerPlayNetworking.registerGlobalReceiver(ModeRequestPayload.TYPE, (payload, context) -> setMode(context.server(), context.player(), payload.on(), payload.character()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> setMode(server, handler.player, false, HLMode.DEFAULT_CHARACTER));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> HLMode.players(false).clear());

		ServerTickEvents.END_SERVER_TICK.register(HLMovement::tickPlayerSounds);

		LOGGER.info("Half-Life movement loaded");
	}

	private static void setMode(MinecraftServer server, ServerPlayer player, boolean on, String character) {
		boolean wasOn = HLMode.isOn(player);
		if (!HLMode.set(false, player.getUUID(), on, character)) {
			return;
		}

		if (wasOn != on) {
			((HLDuck.Holder) player).hlmovement$duckState().reset();
			((HLSoundEngine.Holder) player).hlmovement$soundState().reset();
			// The duck hull differs between the modes.
			player.refreshDimensions();
		}

		ModeStatePayload payload = new ModeStatePayload(player.getUUID(), on, HLMode.character(player));
		for (ServerPlayer other : PlayerLookup.all(server)) {
			if (ServerPlayNetworking.canSend(other, ModeStatePayload.TYPE)) {
				ServerPlayNetworking.send(other, payload);
			}
		}
	}

	/** Runs the Half-Life sound code for every player in Half-Life mode and sends the results to the other players. */
	private static void tickPlayerSounds(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			HLSoundEngine.State state = ((HLSoundEngine.Holder) player).hlmovement$soundState();
			if (!HLMode.isOn(player)) {
				state.reset();
				continue;
			}

			HLSoundEngine.tick(player, state, player.getLastClientInput().jump(), SERVER_RANDOM,
				(sound, volume) -> broadcast(player, sound, volume));
		}
	}

	private static void broadcast(ServerPlayer source, String sound, float volume) {
		PlayerSoundPayload payload = new PlayerSoundPayload(source.getId(), sound, volume);
		for (ServerPlayer observer : PlayerLookup.tracking(source)) {
			// Each client plays its own player's sounds without waiting for the server.
			if (observer != source) {
				ServerPlayNetworking.send(observer, payload);
			}
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
