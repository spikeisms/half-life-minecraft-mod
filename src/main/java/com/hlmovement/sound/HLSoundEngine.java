package com.hlmovement.sound;

import com.hlmovement.HLConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Half-Life player sounds, ported from the SDK: PM_UpdateStepSound, PM_PlayStepSound, the swim
 * sound in PM_Jump, PM_CheckFalling and PM_PlayWaterSounds (pm_shared.c), plus the surfacing
 * gasps, bubbles and fatal-fall splat from CBasePlayer (player.cpp).
 *
 * Runs on the client for the local player and on the server for every modded player, with the
 * server forwarding the sounds to everyone else. Sound names are event paths in the hlmovement
 * namespace ("player.pl_step1").
 */
public final class HLSoundEngine {
	private static final int TICK_MS = 50;
	/** Half-Life ran the player code at ~100 fps; used for the per-frame bubble chance. */
	private static final int HL_FRAMES_PER_TICK = 5;
	private static final double FALL_PUNCH_THRESHOLD = 350.0;
	private static final double MIN_BOUNCE_SPEED = 200.0;
	/** Map from HL's irand (0,1 = right foot, 2,3 = left foot, 4 = extra tile sound) to file number. */
	private static final int[] STEP_ORDER = {1, 3, 2, 4, 5};
	/** AIRTIME in player.cpp: seconds of air. */
	private static final int HL_AIR_SECONDS = 12;

	public interface Sink {
		void play(String sound, float volume);
	}

	public enum Step {
		CONCRETE("pl_step"),
		METAL("pl_metal"),
		DIRT("pl_dirt"),
		VENT("pl_duct"),
		GRATE("pl_grate"),
		TILE("pl_tile"),
		SLOSH("pl_slosh"),
		WADE("pl_wade"),
		LADDER("pl_ladder"),
		WOOD("pl_wood"),
		SNOW("pl_snow");

		final String prefix;

		Step(String prefix) {
			this.prefix = prefix;
		}
	}

	/** Implemented on Player by mixin. */
	public interface Holder {
		State hlmovement$soundState();
	}

	/** Per-player state (Half-Life keeps these in pmove / CBasePlayer). */
	public static final class State {
		int timeStepSound;
		int swimTime;
		boolean stepLeft;
		int skipStep;
		boolean initialized;
		double lastX;
		double lastY;
		double lastZ;
		double lastVy;
		boolean wasOnGround = true;
		int oldWaterLevel;
		boolean wasUnderwater;
		int lowestAir;

		public void reset() {
			this.initialized = false;
		}
	}

	private static final Map<SoundType, Step> SOUND_TYPE_STEPS = new HashMap<>();

	static {
		for (SoundType type : new SoundType[]{
			SoundType.METAL, SoundType.ANVIL, SoundType.NETHERITE_BLOCK, SoundType.LODESTONE, SoundType.CHAIN, SoundType.LANTERN,
			SoundType.COPPER, SoundType.COPPER_BULB, SoundType.IRON, SoundType.HEAVY_CORE, SoundType.VAULT, SoundType.TRIAL_SPAWNER,
			SoundType.SPAWNER, SoundType.COPPER_GOLEM_STATUE
		}) {
			SOUND_TYPE_STEPS.put(type, Step.METAL);
		}

		SOUND_TYPE_STEPS.put(SoundType.COPPER_GRATE, Step.GRATE);

		for (SoundType type : new SoundType[]{
			SoundType.GRAVEL, SoundType.GRASS, SoundType.WET_GRASS, SoundType.SAND, SoundType.SUSPICIOUS_SAND, SoundType.SUSPICIOUS_GRAVEL,
			SoundType.ROOTED_DIRT, SoundType.MANGROVE_ROOTS, SoundType.MUDDY_MANGROVE_ROOTS, SoundType.PACKED_MUD, SoundType.MOSS,
			SoundType.MOSS_CARPET, SoundType.NYLIUM, SoundType.SOUL_SAND, SoundType.SOUL_SOIL, SoundType.CROP, SoundType.HARD_CROP,
			SoundType.SWEET_BERRY_BUSH, SoundType.VINE, SoundType.ROOTS, SoundType.NETHER_SPROUTS, SoundType.FUNGUS, SoundType.AZALEA,
			SoundType.FLOWERING_AZALEA, SoundType.AZALEA_LEAVES, SoundType.CHERRY_LEAVES, SoundType.POPLAR_LEAVES, SoundType.LEAF_LITTER,
			SoundType.PINK_PETALS, SoundType.WOOL, SoundType.SCULK, SoundType.SCULK_VEIN, SoundType.SCULK_CATALYST, SoundType.SCULK_SENSOR,
			SoundType.SCULK_SHRIEKER, SoundType.SPONGE, SoundType.WET_SPONGE, SoundType.WART_BLOCK, SoundType.NETHER_WART,
			SoundType.HANGING_ROOTS, SoundType.GLOW_LICHEN, SoundType.BIG_DRIPLEAF, SoundType.SMALL_DRIPLEAF, SoundType.STRAW_BED,
			SoundType.CAVE_VINES, SoundType.WEEPING_VINES, SoundType.TWISTING_VINES, SoundType.SPORE_BLOSSOM, SoundType.CACTUS_FLOWER,
			SoundType.BAMBOO_SAPLING, SoundType.CHERRY_SAPLING, SoundType.RED_SHRUB, SoundType.SHELF_MUSHROOM
		}) {
			SOUND_TYPE_STEPS.put(type, Step.DIRT);
		}

		for (SoundType type : new SoundType[]{
			SoundType.GLASS, SoundType.CALCITE, SoundType.DEEPSLATE_TILES, SoundType.POLISHED_DEEPSLATE, SoundType.POLISHED_TUFF,
			SoundType.AMETHYST, SoundType.AMETHYST_CLUSTER, SoundType.SMALL_AMETHYST_BUD, SoundType.MEDIUM_AMETHYST_BUD,
			SoundType.LARGE_AMETHYST_BUD, SoundType.CORAL_BLOCK, SoundType.DECORATED_POT, SoundType.DECORATED_POT_CRACKED,
			SoundType.FROGLIGHT, SoundType.SHROOMLIGHT, SoundType.BONE_BLOCK
		}) {
			SOUND_TYPE_STEPS.put(type, Step.TILE);
		}

		for (SoundType type : new SoundType[]{
			SoundType.WOOD, SoundType.NETHER_WOOD, SoundType.BAMBOO_WOOD, SoundType.CHERRY_WOOD, SoundType.BAMBOO, SoundType.LADDER,
			SoundType.SCAFFOLDING, SoundType.CHISELED_BOOKSHELF, SoundType.SHELF, SoundType.HANGING_SIGN, SoundType.NETHER_WOOD_HANGING_SIGN,
			SoundType.BAMBOO_WOOD_HANGING_SIGN, SoundType.CHERRY_WOOD_HANGING_SIGN, SoundType.STEM, SoundType.CREAKING_HEART
		}) {
			SOUND_TYPE_STEPS.put(type, Step.WOOD);
		}

		SOUND_TYPE_STEPS.put(SoundType.SNOW, Step.SNOW);
		SOUND_TYPE_STEPS.put(SoundType.POWDER_SNOW, Step.SNOW);

		for (SoundType type : new SoundType[]{SoundType.SLIME_BLOCK, SoundType.HONEY_BLOCK, SoundType.FROGSPAWN, SoundType.LILY_PAD, SoundType.MUD}) {
			SOUND_TYPE_STEPS.put(type, Step.SLOSH);
		}
	}

	private HLSoundEngine() {
	}

	/** Call once per tick, after the player has moved. */
	public static void tick(Player player, State s, boolean jumpKey, RandomSource random, Sink sink) {
		if (!player.isAlive() || player.isSpectator() || player.isPassenger() || player.getAbilities().flying || player.isFallFlying()) {
			s.reset();
			return;
		}

		if (!s.initialized) {
			s.initialized = true;
			s.lastX = player.getX();
			s.lastY = player.getY();
			s.lastZ = player.getZ();
			s.lastVy = 0.0;
			s.wasOnGround = player.onGround();
			s.oldWaterLevel = waterLevel(player);
			s.wasUnderwater = false;
			return;
		}

		double toUps = HLConfig.unitsPerBlock * 20.0;
		double vx = (player.getX() - s.lastX) * toUps;
		double vy = (player.getY() - s.lastY) * toUps;
		double vz = (player.getZ() - s.lastZ) * toUps;
		s.lastX = player.getX();
		s.lastY = player.getY();
		s.lastZ = player.getZ();

		// PM_ReduceTimers
		s.timeStepSound = Math.max(0, s.timeStepSound - TICK_MS);
		s.swimTime = Math.max(0, s.swimTime - TICK_MS);

		boolean onGround = player.onGround();
		boolean ladder = player.onClimbable();
		int waterLevel = waterLevel(player);
		Ctx ctx = new Ctx(player, s, random, sink, vx, vy, vz, onGround, ladder);

		updateStepSound(ctx);

		// PM_Jump
		if (jumpKey && waterLevel >= 2) {
			// Swimming up: a wade splash at most once a second.
			if (s.swimTime <= 0) {
				s.swimTime = 1000;
				sink.play("player.pl_wade" + (random.nextInt(4) + 1), 1.0F);
			}
		} else if (jumpKey && s.wasOnGround && !onGround && !ladder && vy > 0.0) {
			playStepSound(ctx, textureStep(player), 1.0F);
		}

		// PM_CheckFalling
		if (onGround && !s.wasOnGround) {
			checkFalling(ctx, Math.max(-s.lastVy, -vy), waterLevel);
		}

		// PM_PlayWaterSounds: entering or leaving the water.
		if ((s.oldWaterLevel == 0) != (waterLevel == 0)) {
			sink.play("player.pl_wade" + (random.nextInt(4) + 1), 1.0F);
		}

		waterBreathing(player, s, waterLevel, random, sink);

		s.wasOnGround = onGround;
		s.lastVy = vy;
		s.oldWaterLevel = waterLevel;
	}

	private record Ctx(Player player, State s, RandomSource random, Sink sink, double vx, double vy, double vz, boolean onGround, boolean ladder) {
		double speed() {
			// On the ground HL's velocity is horizontal; on a ladder it includes the climb.
			return this.ladder ? Math.sqrt(vx * vx + vy * vy + vz * vz) : Math.sqrt(vx * vx + vz * vz);
		}
	}

	/** Half-Life waterlevel: 1 = feet, 2 = waist (hull center), 3 = eyes. Lava counts, like CONTENTS_LAVA. */
	private static int waterLevel(Player player) {
		double height = Math.max(player.getFluidHeight(FluidTags.WATER), player.getFluidHeight(FluidTags.LAVA));
		if (height <= 0.0) {
			return 0;
		} else if (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)) {
			return 3;
		} else if (height >= player.getBbHeight() * 0.5) {
			return 2;
		}

		return 1;
	}

	private static boolean ducking(Player player) {
		return player.getPose() == Pose.CROUCHING;
	}

	/** PM_UpdateStepSound */
	private static void updateStepSound(Ctx c) {
		State s = c.s();
		if (s.timeStepSound > 0) {
			return;
		}

		double speed = c.speed();
		if ((!c.ladder() && !c.onGround()) || speed < 1.0) {
			return;
		}

		boolean ducking = ducking(c.player());
		double velRun;
		int duckDelay;
		if (ducking || c.ladder()) {
			velRun = 80.0;
			duckDelay = 100;
		} else {
			velRun = 210.0;
			duckDelay = 0;
		}

		boolean walking = speed < velRun;
		double height = c.player().getBbHeight();
		double water = c.player().getFluidHeight(FluidTags.WATER);
		Step step;
		float volume;
		if (c.ladder()) {
			step = Step.LADDER;
			volume = 0.35F;
			s.timeStepSound = 350;
		} else if (water >= 0.2 * height) {
			// Knee deep (origin - 0.3 * height, with the origin at the hull center).
			step = Step.WADE;
			volume = 0.65F;
			s.timeStepSound = 600;
		} else if (water > 0.0) {
			// Water at the feet.
			step = Step.SLOSH;
			volume = walking ? 0.2F : 0.5F;
			s.timeStepSound = walking ? 400 : 300;
		} else {
			step = textureStep(c.player());
			volume = switch (step) {
				case DIRT -> walking ? 0.25F : 0.55F;
				case VENT -> walking ? 0.4F : 0.7F;
				default -> walking ? 0.2F : 0.5F;
			};
			s.timeStepSound = walking ? 400 : 300;
		}

		s.timeStepSound += duckDelay;
		if (ducking) {
			volume *= 0.35F;
		}

		playStepSound(c, step, volume);
	}

	/** PM_PlayStepSound: alternates left/right foot and picks a random sound for that foot. */
	private static void playStepSound(Ctx c, Step step, float volume) {
		State s = c.s();
		s.stepLeft = !s.stepLeft;
		int irand = c.random().nextInt(2) + (s.stepLeft ? 2 : 0);

		// Half-Life multiplayer: only running and ladder steps are audible.
		if (HLConfig.multiplayerFootsteps && !c.ladder() && Math.sqrt(c.vx() * c.vx() + c.vz() * c.vz()) <= 220.0) {
			return;
		}

		String sound;
		switch (step) {
			case WADE -> {
				if (s.skipStep == 0) {
					s.skipStep++;
					return;
				}

				if (s.skipStep++ == 3) {
					s.skipStep = 0;
				}

				sound = step.prefix + (irand + 1);
			}
			case TILE -> {
				if (c.random().nextInt(5) == 0) {
					irand = 4;
				}

				sound = step.prefix + STEP_ORDER[irand];
			}
			default -> sound = step.prefix + STEP_ORDER[irand];
		}

		c.sink().play("player." + sound, volume);
	}

	/** PM_CheckFalling, plus the fatal-fall splat from CBasePlayer::PostThink. */
	private static void checkFalling(Ctx c, double fallVelocity, int waterLevel) {
		if (fallVelocity < FALL_PUNCH_THRESHOLD) {
			return;
		}

		float volume = 0.5F;
		if (waterLevel > 0) {
			// Landing in water: nothing extra.
		} else if (fallVelocity > HLConfig.safeFallSpeed) {
			c.sink().play("player.pl_fallpain3", 1.0F);
			volume = 1.0F;
			if (HLConfig.hlFallDamage && expectedFallDamage(fallVelocity) >= c.player().getHealth()) {
				c.sink().play("common.bodysplat", 1.0F);
			}
		} else if (fallVelocity > HLConfig.safeFallSpeed / 2.0) {
			volume = 0.85F;
		} else if (fallVelocity < MIN_BOUNCE_SPEED) {
			volume = 0.0F;
		}

		if (volume > 0.0F) {
			// Play the landing step right away.
			c.s().timeStepSound = 0;
			updateStepSound(c);
			playStepSound(c, textureStep(c.player()), volume);
		}
	}

	/** Same formula as the fall damage mixin, in Minecraft health points. */
	private static double expectedFallDamage(double fallVelocity) {
		double hlDamage = (fallVelocity - HLConfig.safeFallSpeed) * (100.0 / (HLConfig.fatalFallSpeed - HLConfig.safeFallSpeed));
		return hlDamage * 0.2;
	}

	/**
	 * CBasePlayer::WaterMove: a gasp when surfacing after a long time under (pl_wade1 if you were
	 * drowning, pl_wade2 after a few seconds), and bubble sounds that get more frequent as air runs out.
	 */
	private static void waterBreathing(Player player, State s, int waterLevel, RandomSource random, Sink sink) {
		int maxAir = Math.max(1, player.getMaxAirSupply());
		int air = player.getAirSupply();
		if (waterLevel == 3) {
			if (!s.wasUnderwater) {
				s.wasUnderwater = true;
				s.lowestAir = air;
			}

			s.lowestAir = Math.min(s.lowestAir, air);
		} else if (s.wasUnderwater) {
			s.wasUnderwater = false;
			if (s.lowestAir <= 0) {
				sink.play("player.pl_wade1", 1.0F);
			} else if (s.lowestAir < maxAir * (HL_AIR_SECONDS - 3) / HL_AIR_SECONDS) {
				sink.play("player.pl_wade2", 1.0F);
			}
		}

		if (waterLevel == 0) {
			return;
		}

		// Seconds of air left on Half-Life's 12 second scale (always full unless submerged).
		int airSeconds = waterLevel == 3 ? air * HL_AIR_SECONDS / maxAir : HL_AIR_SECONDS;
		for (int frame = 0; frame < HL_FRAMES_PER_TICK; frame++) {
			if (random.nextInt(32) == 0 && random.nextInt(HL_AIR_SECONDS) >= airSeconds) {
				sink.play("player.pl_swim" + (random.nextInt(4) + 1), 0.8F);
				return;
			}
		}
	}

	/** PM_CatagorizeTextureType, using the block we're standing on (or a carpet/snow layer on top of it). */
	public static Step textureStep(Player player) {
		BlockPos pos = player.getOnPos();
		BlockState state = player.level().getBlockState(pos);
		BlockState above = player.level().getBlockState(pos.above());
		if (above.is(BlockTags.INSIDE_STEP_SOUND_BLOCKS) || above.is(BlockTags.COMBINATION_STEP_SOUND_BLOCKS)) {
			state = above;
		}

		Block block = state.getBlock();
		if (block instanceof IronBarsBlock) {
			return Step.GRATE;
		}

		SoundType soundType = state.getSoundType();
		if (block instanceof HopperBlock || block instanceof AbstractCauldronBlock
			|| block instanceof TrapDoorBlock && SOUND_TYPE_STEPS.get(soundType) == Step.METAL) {
			return Step.VENT;
		}

		Step step = SOUND_TYPE_STEPS.get(soundType);
		if (step != null) {
			return step;
		}

		// Plain "stone" sounding blocks that read as tiled floors.
		String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (name.contains("quartz") || name.contains("glazed_terracotta") || name.contains("tile") || name.contains("prismarine")
			|| name.contains("purpur") || name.startsWith("smooth_") || name.startsWith("polished_")) {
			return Step.TILE;
		}

		return Step.CONCRETE;
	}
}
