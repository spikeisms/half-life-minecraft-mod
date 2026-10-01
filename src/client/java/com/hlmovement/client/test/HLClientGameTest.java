package com.hlmovement.client.test;

import com.hlmovement.HLMode;
import com.hlmovement.HLMovement;
import com.hlmovement.client.HLModeClient;
import com.hlmovement.client.HLMovementClient;
import com.hlmovement.client.hud.HudSettingsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Automated in-game checks, run with the "Client Game Test" run configuration
 * (gradlew runClientGameTest). Builds a small test course in a fresh singleplayer world and
 * checks movement numbers against Half-Life, ducking on both client and server, and sounds.
 */
public class HLClientGameTest implements FabricClientGameTest {
	private static final double UNITS = 40.0;
	private static final int FLOOR = 150;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("hlmovement.showcase")) {
			// gradlew runShowcase: the scripted demo for recording, instead of the checks.
			Showcase.run(context);
			return;
		}

		List<String> sounds = new CopyOnWriteArrayList<>();
		SoundEventListener listener = (sound, events, range) -> sounds.add(sound.getIdentifier().toString());

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			TestServerContext server = world.getServer();
			TestInput input = context.getInput();

			server.runCommand("difficulty peaceful");
			server.runCommand("gamemode survival @a");
			server.runCommand("gamerule fall_damage false");
			server.runCommand("fill -8 " + (FLOOR - 1) + " -8 8 " + (FLOOR - 1) + " 40 minecraft:stone");
			server.runCommand("fill -8 " + FLOOR + " -8 8 " + (FLOOR + 6) + " 40 minecraft:air");
			// A tunnel exactly one block high, from z = 20 to 26.
			server.runCommand("fill -1 " + (FLOOR + 1) + " 20 1 " + (FLOOR + 1) + " 26 minecraft:stone");
			teleport(server, context, 0.5, 0.0);

			context.runOnClient(mc -> mc.getSoundManager().addListener(listener));

			// The mode is remembered between runs; start in it.
			context.runOnClient(mc -> {
				HLModeClient.setCharacter(mc, "scientist");
				HLModeClient.set(mc, true);
			});
			context.waitTicks(10);
			check(server.computeOnServer(s -> HLMode.isOn(s.getPlayerList().getPlayers().getFirst())), "server knows we're in Half-Life mode");

			// --- Running: sv_maxspeed 320 ---
			input.holdKey(o -> o.keyUp);
			context.waitTicks(20);
			double runSpeed = measureSpeed(context, 10);
			input.releaseKey(o -> o.keyUp);
			report("run speed", runSpeed, 320.0);
			check(Math.abs(runSpeed - 320.0) < 8.0, "run speed ~320 u/s, got " + runSpeed);
			check(sounds.stream().anyMatch(s -> s.startsWith("hlmovement:player.pl_step")), "Half-Life footsteps played: " + summary(sounds));
			check(sounds.stream().noneMatch(s -> s.equals("minecraft:block.stone.step")), "vanilla footsteps suppressed: " + summary(sounds));
			context.waitTicks(20);

			// --- Ducking takes 0.4 s on the ground, then uses the half-height hull ---
			teleport(server, context, 0.5, 0.0);
			input.holdKey(o -> o.keyShift);
			context.waitTicks(4);
			check(context.computeOnClient(mc -> mc.player.getPose()) == Pose.STANDING, "still standing 0.2 s into the duck");
			double midDuckEye = context.computeOnClient(mc -> (double) mc.player.getEyeHeight());
			report("eye height 0.2 s into the duck", midDuckEye, 1.185);
			check(midDuckEye > 0.8 && midDuckEye < 1.5, "the view is already sinking 0.2 s into the duck (eye height " + midDuckEye + ")");
			context.waitTicks(8);
			check(context.computeOnClient(mc -> mc.player.getPose()) == Pose.CROUCHING, "ducked after 0.4 s");
			double clientHeight = context.computeOnClient(mc -> mc.player.getBbHeight());
			double serverHeight = server.computeOnServer(s -> (double) s.getPlayerList().getPlayers().getFirst().getBbHeight());
			report("duck hull (client/server)", clientHeight, serverHeight);
			check(Math.abs(clientHeight - 0.9) < 1.0E-3 && Math.abs(serverHeight - 0.9) < 1.0E-3, "duck hull is 0.9 blocks on both sides");

			// --- Duck speed 320 * 0.333, and fit through a 1-block tunnel ---
			input.holdKey(o -> o.keyUp);
			context.waitTicks(20);
			double duckSpeed = measureSpeed(context, 10);
			report("duck speed", duckSpeed, 106.6);
			check(Math.abs(duckSpeed - 106.6) < 5.0, "duck speed ~106.6 u/s, got " + duckSpeed);
			context.waitFor(mc -> mc.player.getZ() > 27.0, 200);
			input.releaseKey(o -> o.keyUp);
			input.releaseKey(o -> o.keyShift);
			context.waitTicks(10);
			world.getConnection().waitForServerboundPackets();
			Vec3 clientPos = context.computeOnClient(mc -> mc.player.position());
			Vec3 serverPos = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().position());
			report("tunnel exit z (client/server)", clientPos.z, serverPos.z);
			check(clientPos.distanceTo(serverPos) < 0.1, "server accepted the duck-walk through the tunnel");

			// --- Jump: 45 units ---
			teleport(server, context, 0.5, 0.0);
			double jumpHeight = jumpAndMeasure(context, input, false);
			report("jump height (units)", jumpHeight * UNITS, 45.0);
			check(Math.abs(jumpHeight * UNITS - 45.0) < 3.0, "jump height ~45 units");

			// --- Crouch-jump: ducking mid-air pulls the feet up by half the hull difference (18 units) ---
			double duckJumpHeight = jumpAndMeasure(context, input, true);
			report("duck-jump height (units)", duckJumpHeight * UNITS, 63.0);
			check(duckJumpHeight * UNITS > 60.0, "duck-jump clears ~63 units");

			// --- Ladder: 200 u/s climbing while pushing into it, with ladder sounds ---
			server.runCommand("fill -1 " + FLOOR + " 39 1 " + (FLOOR + 6) + " 39 minecraft:stone");
			server.runCommand("fill 0 " + FLOOR + " 38 0 " + (FLOOR + 5) + " 38 minecraft:ladder[facing=north]");
			teleport(server, context, 0.5, 38.5);
			sounds.clear();
			input.holdKey(o -> o.keyUp);
			context.waitTicks(4);
			double y0 = context.computeOnClient(mc -> mc.player.getY());
			context.waitTicks(10);
			double climbSpeed = (context.computeOnClient(mc -> mc.player.getY()) - y0) / 10.0 * 20.0 * UNITS;
			input.releaseKey(o -> o.keyUp);
			report("climb speed", climbSpeed, 200.0);
			check(Math.abs(climbSpeed - 200.0) < 10.0, "climb speed ~200 u/s");
			check(sounds.stream().anyMatch(s -> s.startsWith("hlmovement:player.pl_ladder")), "ladder sounds: " + summary(sounds));
			context.waitTicks(40);

			// --- Water: wading in, then swimming up plays pl_wade ---
			server.runCommand("fill -3 " + FLOOR + " 28 3 " + (FLOOR + 1) + " 34 minecraft:water");
			teleport(server, context, 0.5, 24.5);
			sounds.clear();
			input.holdKey(o -> o.keyUp);
			context.waitFor(mc -> mc.player.getZ() > 31.0, 100);
			input.releaseKey(o -> o.keyUp);
			input.holdKey(o -> o.keyJump);
			context.waitTicks(30);
			input.releaseKey(o -> o.keyJump);
			check(sounds.stream().anyMatch(s -> s.startsWith("hlmovement:player.pl_wade")), "water sounds: " + summary(sounds));
			check(sounds.stream().noneMatch(s -> s.startsWith("minecraft:entity.player.splash") || s.equals("minecraft:entity.player.swim")),
				"vanilla splash/swim suppressed: " + summary(sounds));

			// --- Creative flight is PM_NoClip: 320 u/s along the view, through walls, stops dead ---
			server.runCommand("fill -7 " + FLOOR + " 10 -5 " + (FLOOR + 4) + " 11 minecraft:stone");
			server.runCommand("gamemode creative @a");
			server.runCommand("tp @a -5.5 " + (FLOOR + 1) + " 4 0 0");
			context.waitTicks(10);
			context.runOnClient(mc -> {
				mc.player.getAbilities().flying = true;
				mc.player.onUpdateAbilities();
			});
			context.waitTicks(5);
			double flyY = context.computeOnClient(mc -> mc.player.getY());
			input.holdKey(o -> o.keyUp);
			context.waitTicks(4);
			double noclipSpeed = measureSpeed(context, 10);
			context.waitFor(mc -> mc.player.getZ() > 14.0, 100);
			input.releaseKey(o -> o.keyUp);
			context.waitTick();
			double stoppedSpeed = measureSpeed(context, 2);
			report("noclip speed", noclipSpeed, 320.0);
			check(Math.abs(noclipSpeed - 320.0) < 5.0, "noclip speed ~320 u/s");
			check(stoppedSpeed < 1.0, "noclip stops dead on release, got " + stoppedSpeed);
			check(context.computeOnClient(mc -> mc.player.getAbilities().flying), "still flying (noclip never lands)");
			check(Math.abs(context.computeOnClient(mc -> mc.player.getY()) - flyY) < 0.01, "no gravity in noclip");
			world.getConnection().waitForServerboundPackets();
			context.waitTicks(5);
			Vec3 noclipClient = context.computeOnClient(mc -> mc.player.position());
			Vec3 noclipServer = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().position());
			report("noclip through wall z (client/server)", noclipClient.z, noclipServer.z);
			check(noclipClient.z > 14.0 && noclipClient.distanceTo(noclipServer) < 0.1, "flew through the wall and the server agreed");
			context.runOnClient(mc -> {
				mc.player.getAbilities().flying = false;
				mc.player.onUpdateAbilities();
			});

			// --- Spectator is PM_SpectatorMove: accelerates to 400 u/s (a single key, under the 500 cap), stops dead ---
			server.runCommand("gamemode spectator @a");
			server.runCommand("tp @a 5.5 " + (FLOOR + 1) + " -6 0 0");
			context.waitTicks(10);
			input.holdKey(o -> o.keyUp);
			context.waitTicks(10);
			double spectatorSpeed = measureSpeed(context, 10);
			input.releaseKey(o -> o.keyUp);
			context.waitTick();
			double spectatorStopped = measureSpeed(context, 2);
			report("spectator speed", spectatorSpeed, 400.0);
			check(Math.abs(spectatorSpeed - 400.0) < 5.0, "spectator speed ~400 u/s");
			check(spectatorStopped < 1.0, "spectator stops dead on release, got " + spectatorStopped);
			server.runCommand("gamemode survival @a");

			// --- HUD screenshots ---
			input.resizeWindow(1920, 1080);
			teleport(server, context, 0.5, 0.5);
			server.runCommand("item replace entity @a hotbar.0 with minecraft:stone 32");
			server.runCommand("item replace entity @a hotbar.1 with minecraft:diamond_pickaxe[damage=900]");
			server.runCommand("item replace entity @a hotbar.2 with minecraft:bow");
			server.runCommand("item replace entity @a hotbar.3 with minecraft:cooked_beef 5");
			server.runCommand("item replace entity @a hotbar.6 with minecraft:torch 64");
			server.runCommand("item replace entity @a inventory.0 with minecraft:arrow 40");
			server.runCommand("item replace entity @a inventory.1 with minecraft:stone 64");
			server.runCommand("item replace entity @a weapon.offhand with minecraft:shield");
			server.runCommand("item replace entity @a armor.chest with minecraft:iron_chestplate");
			server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().getFoodData().setFoodLevel(13));
			context.waitTicks(10);
			server.runCommand("clear @a minecraft:crafting_table");
			context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(true));
			for (int guiScale : new int[]{2, 3, 4}) {
				context.runOnClient(mc -> {
					mc.options.guiScale().set(guiScale);
					mc.resizeGui();
				});
				input.pressKey(o -> o.keyHotbarSlots[0]);
				context.waitTicks(5);
				input.pressKey(o -> o.keyHotbarSlots[2]);
				context.waitTicks(5);
				HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_hud_gui" + guiScale));
			}

			// The settings menu (opened with its key), then closed again.
			input.pressKey(HLMovementClient.SETTINGS);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof HudSettingsScreen), "settings key opens the HUD settings");
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_settings"));
			input.pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen() == null), "settings screen closes");

			// The crosshair is the same number of screen pixels at another GUI scale and window size.
			context.runOnClient(mc -> {
				mc.options.guiScale().set(2);
				mc.resizeGui();
			});
			context.waitTicks(3);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_crosshair_gui2"));
			input.resizeWindow(1280, 720);
			context.waitTicks(5);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_crosshair_720p"));
			input.resizeWindow(1920, 1080);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(4);
				mc.resizeGui();
			});
			context.waitTicks(5);

			// Slot 5 is empty: no bare arm on screen, like being unarmed in Half-Life.
			input.pressKey(o -> o.keyHotbarSlots[4]);
			context.waitTicks(15);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_empty_hand"));

			teleport(server, context, 0.5, 0.5);

			context.waitTicks(50);

			// Drowning (a damage-type icon), then an arrow from straight ahead (a direction arc).
			hurt(server, DamageTypes.DROWN, null, 1.0F);
			context.waitTicks(12);
			hurt(server, DamageTypes.ARROW, new Vec3(0.5, FLOOR + 1, 10.0), 3.0F);
			context.waitTicks(4);
			double hudHealth = context.computeOnClient(mc -> (double) mc.player.getHealth());
			check(hudHealth < 20.0, "took the test damage (health " + hudHealth + ")");
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_hud_damage"));

			// Players in Half-Life mode are drawn as the scientist: ourselves in third person. Mannequins stay Steve.
			context.waitTicks(20);
			server.runCommand("summon minecraft:mannequin -0.5 " + FLOOR + " 3.5 {Rotation:[180f,0f]}");
			server.runCommand("summon minecraft:mannequin 1.5 " + FLOOR + " 3.5 {Rotation:[150f,0f]}");
			context.runOnClient(mc -> mc.gui.hud.toggle());
			context.waitTicks(10);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_mannequins"));
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			context.waitTicks(5);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_self"));

			// Held items in the scientist's hands, the upper body following the view, an attack swing, and the
			// legs following the movement while strafing and backing up.
			server.runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
			context.waitTicks(10);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_items"));
			input.pressKey(o -> o.keyAttack);
			context.waitTicks(2);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_swing"));
			context.waitTicks(10);

			// The character picker: the server relays our choice, and the model changes.
			for (String character : new String[]{"gordon", "barney"}) {
				context.runOnClient(mc -> HLModeClient.setCharacter(mc, character));
				context.waitTicks(10);
				String onServer = server.computeOnServer(s -> HLMode.character(s.getPlayerList().getPlayers().getFirst()));
				check(character.equals(onServer), "server knows we're playing as " + character + " (it has " + onServer + ")");
				HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_" + character));
			}

			context.runOnClient(mc -> HLModeClient.setCharacter(mc, "scientist"));
			context.waitTicks(5);
			server.runCommand("tp @a 0.5 " + FLOOR + " 0.5 0 -45");
			context.waitTicks(10);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_look_up"));
			server.runCommand("tp @a 0.5 " + FLOOR + " 0.5 0 45");
			context.waitTicks(10);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_look_down"));
			teleport(server, context, 0.5, 0.5);
			input.holdKey(o -> o.keyRight);
			context.waitTicks(13);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_strafe"));
			input.releaseKey(o -> o.keyRight);
			input.holdKey(o -> o.keyDown);
			context.waitTicks(13);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_backwards"));
			input.releaseKey(o -> o.keyDown);
			teleport(server, context, 0.5, 0.5);

			// Animations: running, walking, mid-jump, ducked and crawling.
			input.holdKey(o -> o.keyUp);
			context.waitTicks(13);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_run"));
			input.holdKey(HLMovementClient.WALK);
			context.waitTicks(17);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_walk"));
			input.releaseKey(HLMovementClient.WALK);
			input.releaseKey(o -> o.keyUp);
			teleport(server, context, 0.5, 0.5);
			input.holdKey(o -> o.keyJump);
			context.waitTicks(2);
			input.releaseKey(o -> o.keyJump);
			context.waitTicks(4);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_jump"));
			context.waitTicks(30);
			input.holdKey(o -> o.keyShift);
			context.waitTicks(15);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_crouch"));
			input.holdKey(o -> o.keyUp);
			context.waitTicks(14);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_model_crawl"));
			input.releaseKey(o -> o.keyUp);
			input.releaseKey(o -> o.keyShift);
			context.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.gui.hud.toggle();
			});

			// --- Leaving Half-Life mode: an ordinary Minecraft player again, on both sides ---
			teleport(server, context, 0.5, 0.5);
			input.pressKey(HLMovementClient.MODE);
			context.waitTicks(10);
			check(!context.computeOnClient(mc -> HLModeClient.active()), "mode key turns Half-Life mode off");
			check(!server.computeOnServer(s -> HLMode.isOn(s.getPlayerList().getPlayers().getFirst())), "server agrees the mode is off");
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_mode_off_hud"));
			sounds.clear();
			input.holdKey(o -> o.keyUp);
			context.waitTicks(20);
			double vanillaSpeed = measureSpeed(context, 10);
			input.releaseKey(o -> o.keyUp);
			report("vanilla walk speed", vanillaSpeed, 172.7);
			check(Math.abs(vanillaSpeed - 172.7) < 8.0, "mode off: Minecraft walking speed (4.317 blocks/s), got " + vanillaSpeed);
			check(sounds.stream().noneMatch(s -> s.startsWith("hlmovement:player.pl_step")), "mode off: no Half-Life footsteps: " + summary(sounds));
			check(sounds.stream().anyMatch(s -> s.equals("minecraft:block.stone.step")), "mode off: vanilla footsteps: " + summary(sounds));
			teleport(server, context, 0.5, 0.5);
			input.holdKey(o -> o.keyShift);
			context.waitTicks(4);
			double offClient = context.computeOnClient(mc -> (double) mc.player.getBbHeight());
			double offServer = server.computeOnServer(s -> (double) s.getPlayerList().getPlayers().getFirst().getBbHeight());
			report("mode off sneak height (client/server)", offClient, offServer);
			check(Math.abs(offClient - 1.5) < 1.0E-3 && Math.abs(offServer - 1.5) < 1.0E-3, "mode off: sneaking is instant and 1.5 blocks on both sides");
			input.releaseKey(o -> o.keyShift);
			context.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				mc.gui.hud.toggle();
			});
			context.waitTicks(5);
			HLMovement.LOGGER.info("[HL TEST] screenshot: {}", context.takeScreenshot("hl_mode_off_model"));
			context.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.gui.hud.toggle();
			});

			// --- And back in ---
			input.pressKey(HLMovementClient.MODE);
			context.waitTicks(10);
			check(context.computeOnClient(mc -> HLModeClient.active()), "mode key turns Half-Life mode back on");
			check(server.computeOnServer(s -> HLMode.isOn(s.getPlayerList().getPlayers().getFirst())), "server agrees the mode is on");
			input.holdKey(o -> o.keyUp);
			context.waitTicks(20);
			double backSpeed = measureSpeed(context, 10);
			input.releaseKey(o -> o.keyUp);
			report("run speed after re-enabling", backSpeed, 320.0);
			check(Math.abs(backSpeed - 320.0) < 8.0, "mode back on: run speed ~320 u/s, got " + backSpeed);

			report("sounds heard", sounds.size(), 0);
			HLMovement.LOGGER.info("[HL TEST] sounds: {}", summary(sounds));
			context.runOnClient(mc -> mc.getSoundManager().removeListener(listener));
		}
	}

	private static void hurt(TestServerContext server, ResourceKey<DamageType> type, Vec3 from, float amount) {
		server.runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
			Holder<DamageType> holder = player.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type);
			player.hurtServer(player.level(), from == null ? new DamageSource(holder) : new DamageSource(holder, from), amount);
		});
	}

	private static void teleport(TestServerContext server, ClientGameTestContext context, double x, double z) {
		server.runCommand("tp @a " + x + " " + FLOOR + " " + z + " 0 0");
		context.waitTicks(10);
	}

	private static double measureSpeed(ClientGameTestContext context, int ticks) {
		Vec3 start = context.computeOnClient(mc -> mc.player.position());
		context.waitTicks(ticks);
		Vec3 end = context.computeOnClient(mc -> mc.player.position());
		return end.subtract(start).horizontalDistance() / ticks * 20.0 * UNITS;
	}

	private static double jumpAndMeasure(ClientGameTestContext context, TestInput input, boolean duck) {
		context.waitFor(mc -> mc.player.onGround(), 100);
		context.waitTicks(5);
		input.holdKey(o -> o.keyJump);
		context.waitTicks(2);
		input.releaseKey(o -> o.keyJump);
		if (duck) {
			input.holdKey(o -> o.keyShift);
		}

		double maxY = FLOOR;
		for (int i = 0; i < 30; i++) {
			context.waitTick();
			maxY = Math.max(maxY, context.computeOnClient(mc -> mc.player.getY()));
		}

		if (duck) {
			input.releaseKey(o -> o.keyShift);
		}

		context.waitTicks(20);
		return maxY - FLOOR;
	}

	private static String summary(List<String> sounds) {
		return sounds.stream().distinct().sorted().toList().toString();
	}

	private static void report(String what, double value, double expected) {
		HLMovement.LOGGER.info("[HL TEST] {}: {} (expected {})", what, String.format("%.2f", value), expected);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("[HL TEST] FAILED: " + message);
		}

		HLMovement.LOGGER.info("[HL TEST] ok: {}", message);
	}
}
