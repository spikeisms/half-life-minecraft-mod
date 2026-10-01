package com.hlmovement.client;

import com.hlmovement.HLConfig;
import com.hlmovement.HLMovement;
import com.hlmovement.client.hud.HLHud;
import com.hlmovement.client.hud.HudSettingsScreen;
import com.hlmovement.HLMode;
import com.hlmovement.net.ConfigSyncPayload;
import com.hlmovement.net.ModeStatePayload;
import com.hlmovement.net.PlayerSoundPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public class HLMovementClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HLMovement.id("main"));

	/** Half-Life's +speed: hold to walk slowly. */
	public static final KeyMapping WALK = new KeyMapping("key.hlmovement.walk", InputConstants.KEY_LALT, CATEGORY);
	public static final KeyMapping SPEEDOMETER = new KeyMapping("key.hlmovement.speedometer", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/** Switches yourself in and out of Half-Life mode. */
	public static final KeyMapping MODE = new KeyMapping("key.hlmovement.mode", InputConstants.KEY_G, CATEGORY);
	public static final KeyMapping SETTINGS = new KeyMapping("key.hlmovement.settings", InputConstants.KEY_H, CATEGORY);

	@Override
	public void onInitializeClient() {
		KeyMappingHelper.registerKeyMapping(WALK);
		KeyMappingHelper.registerKeyMapping(SPEEDOMETER);
		KeyMappingHelper.registerKeyMapping(MODE);
		KeyMappingHelper.registerKeyMapping(SETTINGS);

		ClientTickEvents.END_CLIENT_TICK.register(HLMovementClient::onEndTick);
		HLHud.register();

		// The server sends its settings on join; use them until we disconnect.
		// (In singleplayer the integrated server already shares our settings.)
		ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.TYPE, (payload, context) -> {
			if (!context.client().hasSingleplayerServer()) {
				HLConfig.remoteOverride = true;
				HLConfig.applySynced(payload.config());
			}

			if (context.player() != null) {
				context.player().refreshDimensions();
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PlayerSoundPayload.TYPE, (payload, context) -> HLSounds.receive(payload, context.client()));
		ClientPlayNetworking.registerGlobalReceiver(ModeStatePayload.TYPE, (payload, context) -> HLModeClient.receive(payload, context.client()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> HLModeClient.join(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			HLMode.players(true).clear();
			if (HLConfig.remoteOverride) {
				HLConfig.remoteOverride = false;
				HLConfig.load();
			}
		});
	}

	private static void onEndTick(Minecraft minecraft) {
		HLHud.tick(minecraft);
		while (SPEEDOMETER.consumeClick()) {
			HLConfig.showSpeedometer = !HLConfig.showSpeedometer;
			HLConfig.save();
		}

		while (MODE.consumeClick()) {
			HLModeClient.set(minecraft, !HLConfig.hlMode);
		}

		while (SETTINGS.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new HudSettingsScreen(null));
			}
		}
	}
}
