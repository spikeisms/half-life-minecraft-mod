package com.hlmovement.client.test;

import com.hlmovement.HLConfig;
import com.hlmovement.HLMovement;
import com.hlmovement.client.HLModeClient;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.phys.Vec3;

/**
 * A scripted demo for recording a showcase video (gradlew runShowcase): the same moves every time, on a
 * small stage built in a fresh world. Each segment logs "[SHOWCASE] name wall-clock-ms" so a recording can
 * be cut by the log. It waits after "[SHOWCASE] ready" for the recorder to start.
 */
final class Showcase {
	private static final int FLOOR = 150;

	private Showcase() {
	}

	static void run(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			TestServerContext server = world.getServer();
			TestInput input = context.getInput();

			// The stage: a grass field in the sky with a stone runway down the middle and a few trees.
			server.runCommand("difficulty peaceful");
			server.runCommand("gamemode survival @a");
			server.runCommand("time set 1000");
			server.runCommand("weather clear");
			server.runCommand("fill -24 " + (FLOOR - 2) + " -12 24 " + (FLOOR - 2) + " 112 minecraft:dirt");
			server.runCommand("fill -24 " + (FLOOR - 1) + " -12 24 " + (FLOOR - 1) + " 112 minecraft:grass_block");
			server.runCommand("fill -2 " + (FLOOR - 1) + " -4 2 " + (FLOOR - 1) + " 104 minecraft:smooth_stone");
			for (int[] tree : new int[][]{{-9, 6}, {8, 14}, {-12, 30}, {11, 44}, {-8, 62}, {13, 78}, {-14, 92}, {7, -8}, {-6, -9}}) {
				server.runCommand("place feature minecraft:oak " + tree[0] + " " + FLOOR + " " + tree[1]);
			}

			server.runCommand("item replace entity @a hotbar.0 with minecraft:diamond_sword");
			server.runCommand("item replace entity @a hotbar.1 with minecraft:diamond_pickaxe");
			server.runCommand("item replace entity @a hotbar.2 with minecraft:bow");
			server.runCommand("item replace entity @a hotbar.3 with minecraft:cooked_beef 12");
			server.runCommand("item replace entity @a inventory.0 with minecraft:arrow 40");
			server.runCommand("item replace entity @a armor.chest with minecraft:iron_chestplate");
			context.runOnClient(mc -> {
				mc.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
				mc.options.guiScale().set(3);
				mc.resizeGui();
				HLConfig.showSpeedometer = false;
				HLModeClient.setCharacter(mc, "scientist");
				HLModeClient.set(mc, true);
			});
			place(server, context, 0.5, 2.5, 0.0F);
			input.pressKey(o -> o.keyHotbarSlots[4]);
			context.waitTicks(60);
			context.runOnClient(mc -> {
				mc.gui.toastManager().clear();
				mc.gui.hud.getChat().clearMessages(true);
			});

			mark("ready");
			context.waitTicks(160);

			// 1. Meet the scientist: front view, looking around.
			camera(context, CameraType.THIRD_PERSON_FRONT);
			mark("intro");
			look(context, 0.0F, 0.0F, 25.0F, -15.0F, 20);
			look(context, 25.0F, -15.0F, -25.0F, 10.0F, 30);
			look(context, -25.0F, 10.0F, 0.0F, 0.0F, 20);
			context.waitTicks(10);

			// 2. Running and jumping, from behind.
			camera(context, CameraType.THIRD_PERSON_BACK);
			mark("run");
			input.holdKey(o -> o.keyUp);
			context.waitTicks(30);
			jump(context, input);
			context.waitTicks(22);
			jump(context, input);
			context.waitTicks(25);
			input.releaseKey(o -> o.keyUp);
			context.waitTicks(15);

			// 3. Bunnyhopping down the runway with the speedometer on.
			place(server, context, 0.5, 0.5, 0.0F);
			context.runOnClient(mc -> HLConfig.showSpeedometer = true);
			mark("bhop");
			bunnyhop(context, input);
			context.runOnClient(mc -> HLConfig.showSpeedometer = false);
			context.waitTicks(20);

			// 4. Ducking and crawling, from the front.
			place(server, context, 0.5, 20.5, 0.0F);
			camera(context, CameraType.THIRD_PERSON_FRONT);
			mark("duck");
			context.waitTicks(10);
			input.holdKey(o -> o.keyShift);
			context.waitTicks(25);
			input.holdKey(o -> o.keyUp);
			context.waitTicks(35);
			input.releaseKey(o -> o.keyUp);
			input.releaseKey(o -> o.keyShift);
			context.waitTicks(20);

			// 5. Held items and the character picker.
			input.pressKey(o -> o.keyHotbarSlots[0]);
			mark("characters");
			context.waitTicks(25);
			input.pressKey(o -> o.keyAttack);
			context.waitTicks(25);
			for (String character : new String[]{"gordon", "barney"}) {
				context.runOnClient(mc -> HLModeClient.setCharacter(mc, character));
				context.waitTicks(20);
				look(context, 0.0F, 0.0F, 20.0F, -10.0F, 12);
				look(context, 20.0F, -10.0F, 0.0F, 0.0F, 12);
				input.pressKey(o -> o.keyAttack);
				context.waitTicks(20);
			}

			// 6. Out of the mode and back: an ordinary Minecraft player, then the scientist again.
			context.runOnClient(mc -> HLModeClient.setCharacter(mc, "scientist"));
			context.waitTicks(25);
			mark("toggle");
			context.runOnClient(mc -> HLModeClient.set(mc, false));
			context.waitTicks(45);
			context.runOnClient(mc -> HLModeClient.set(mc, true));
			context.waitTicks(45);

			// 7. The HUD in first person: weapon menu, then damage.
			camera(context, CameraType.FIRST_PERSON);
			mark("hud");
			context.waitTicks(15);
			for (int slot : new int[]{1, 2, 3, 0}) {
				input.pressKey(o -> o.keyHotbarSlots[slot]);
				context.waitTicks(14);
			}

			hurt(server, new Vec3(6.5, FLOOR + 1, 26.5), 4.0F);
			context.waitTicks(25);
			hurt(server, new Vec3(-5.5, FLOOR + 1, 14.5), 5.0F);
			context.waitTicks(40);

			mark("done");
			context.waitTicks(60);
		}
	}

	private static void mark(String segment) {
		HLMovement.LOGGER.info("[SHOWCASE] {} {}", segment, System.currentTimeMillis());
	}

	private static void camera(ClientGameTestContext context, CameraType type) {
		context.runOnClient(mc -> mc.options.setCameraType(type));
	}

	private static void place(TestServerContext server, ClientGameTestContext context, double x, double z, float yaw) {
		server.runCommand("tp @a " + x + " " + FLOOR + " " + z + " " + yaw + " 0");
		context.waitTicks(10);
	}

	/** Turns the view smoothly from one yaw/pitch to another over some ticks. */
	private static void look(ClientGameTestContext context, float yaw0, float pitch0, float yaw1, float pitch1, int ticks) {
		for (int i = 1; i <= ticks; i++) {
			float t = (float) i / ticks;
			float eased = t * t * (3.0F - 2.0F * t);
			context.runOnClient(mc -> {
				mc.player.setYRot(yaw0 + (yaw1 - yaw0) * eased);
				mc.player.setXRot(pitch0 + (pitch1 - pitch0) * eased);
			});
			context.waitTick();
		}
	}

	private static void jump(ClientGameTestContext context, TestInput input) {
		input.holdKey(o -> o.keyJump);
		context.waitTicks(2);
		input.releaseKey(o -> o.keyJump);
	}

	/**
	 * Air-strafing the way a player does it: a run-up, then in the air hold one strafe key while turning the
	 * view that way, swapping sides each hop. A player times each jump to the landing frame (usually with the
	 * scroll wheel); a script ticking 20 times a second can't, so the take uses the mod's autoBhop setting
	 * (hold jump to hop) for the jumps. The speed gained is all from the strafing.
	 */
	private static void bunnyhop(ClientGameTestContext context, TestInput input) {
		boolean autoBhop = HLConfig.autoBhop;
		context.runOnClient(mc -> HLConfig.autoBhop = true);
		input.holdKey(o -> o.keyUp);
		context.waitTicks(12);
		input.releaseKey(o -> o.keyUp);

		// One hop is about 13 ticks in the air (268 u/s up, 800 u/s^2 down). Start with half a sweep so the
		// heading swings evenly either side of the runway.
		float yaw = 0.0F;
		int side = 1;
		int untilSwap = 7;
		input.holdKey(o -> o.keyJump);
		input.holdKey(o -> o.keyRight);
		for (int tick = 0; tick < 200; tick++) {
			double z = context.computeOnClient(mc -> mc.player.getZ());
			if (z > 96.0) {
				break;
			}

			if (--untilSwap <= 0) {
				input.releaseKey(side > 0 ? o -> o.keyRight : o -> o.keyLeft);
				side = -side;
				input.holdKey(side > 0 ? o -> o.keyRight : o -> o.keyLeft);
				untilSwap = 13;
			}

			// Minecraft yaw grows clockwise: strafing right turns right.
			yaw += side * 2.6F;
			float turn = yaw;
			context.runOnClient(mc -> mc.player.setYRot(turn));
			context.waitTick();
		}

		input.releaseKey(o -> o.keyJump);
		input.releaseKey(side > 0 ? o -> o.keyRight : o -> o.keyLeft);
		context.runOnClient(mc -> HLConfig.autoBhop = autoBhop);
	}
	private static void hurt(TestServerContext server, Vec3 from, float amount) {
		server.runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
			Holder<DamageType> type = player.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.ARROW);
			player.hurtServer(player.level(), new DamageSource(type, from), amount);
		});
	}
}
