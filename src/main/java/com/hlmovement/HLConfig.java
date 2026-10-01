package com.hlmovement;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * All tunables for the Half-Life movement model. Values use Half-Life (GoldSrc) units
 * and defaults match the stock Half-Life 1 singleplayer cvars.
 *
 * Stored in config/hlmovement.properties. Every public static non-final field is a key.
 * The server's file is the one that matters: it is sent to every client on join and replaces
 * their values (except the client-only keys) until they disconnect.
 */
public final class HLConfig {
	private static final int CURRENT_VERSION = 2;
	/** Personal settings that are never taken from the server. */
	private static final Set<String> CLIENT_ONLY = Set.of("configVersion", "hlMode", "character", "showSpeedometer", "hudScale", "crosshairSize");

	public static int configVersion = CURRENT_VERSION;

	// --- World scale / simulation ---
	/** GoldSrc units per Minecraft block. Player hull is 72 units tall, MC player is 1.8 blocks -> 40. */
	public static double unitsPerBlock = 40.0;
	/** Physics sub-steps per 50ms game tick. 5 = 100 fps, the classic Half-Life framerate. */
	public static int substeps = 5;

	// --- sv_* cvars ---
	public static double gravity = 800.0;          // sv_gravity
	public static double friction = 4.0;           // sv_friction
	public static double edgeFriction = 2.0;       // sv_edgefriction
	public static double stopSpeed = 100.0;        // sv_stopspeed
	public static double accelerate = 10.0;        // sv_accelerate
	public static double airAccelerate = 10.0;     // sv_airaccelerate
	public static double maxSpeed = 320.0;         // sv_maxspeed
	public static double maxVelocity = 2000.0;     // sv_maxvelocity
	public static double airSpeedCap = 30.0;       // hard-coded wishspeed cap in PM_AirAccelerate
	public static double spectatorMaxSpeed = 500.0; // sv_spectatormaxspeed

	// --- cl_* cvars ---
	public static double forwardSpeed = 400.0;     // cl_forwardspeed
	public static double backSpeed = 400.0;        // cl_backspeed
	public static double sideSpeed = 400.0;        // cl_sidespeed
	public static double walkMultiplier = 0.3;     // cl_movespeedkey (+speed / walk key)
	public static double duckMultiplier = 0.333;   // PLAYER_DUCKING_MULTIPLIER

	// --- pm_shared constants ---
	public static double jumpHeight = 45.0;        // sqrt(2 * 800 * 45) = 268.3 u/s
	public static double climbSpeed = 200.0;       // MAX_CLIMB_SPEED
	public static double ladderJumpOff = 270.0;    // velocity away from a ladder when jumping off it
	public static double waterJumpSpeed = 225.0;   // PM_CheckWaterJump upward pop
	public static double waterSwimUpSpeed = 100.0; // PM_Jump while waist deep in water
	public static double lavaSwimUpSpeed = 50.0;
	public static double waterSinkSpeed = 60.0;    // passive sinking with no input
	public static double waterSpeedMultiplier = 0.8;
	public static double duckTime = 0.4;           // TIME_TO_DUCK: seconds to duck while on the ground

	/** Play in Half-Life mode (movement, HUD, sounds, scientist model). Personal; toggled in game. */
	public static boolean hlMode = true;
	/** Who you are in Half-Life mode: scientist, gordon or barney. Personal; chosen in the settings menu. */
	public static String character = HLMode.DEFAULT_CHARACTER;

	// --- Behaviour switches ---
	/** PM_PreventMegaBunnyJumping: jumping above 1.7x maxspeed scales you down to 1.105x. */
	public static boolean bunnyhopCap = true;
	/** Allow holding jump to hop. Half-Life requires releasing jump between hops. */
	public static boolean autoBhop = false;
	/** Mid-air ducking pulls the legs up (the Half-Life crouch-jump). */
	public static boolean airDuckTuck = true;
	/** Half-Life's duck hull: half the standing height (0.9 blocks), eyes 0.75 blocks up. */
	public static boolean hlDuckHull = true;
	/** Snap down steps while walking (Source-engine style; Half-Life 1 doesn't do this). */
	public static boolean stepDown = false;
	/** Map ice/slime/etc. slipperiness onto Half-Life's per-entity friction. */
	public static boolean blockFriction = true;
	/** Half-Life has no "sneak stops you at ledges". */
	public static boolean disableSneakEdgeSafety = true;
	/** No sprinting in Half-Life; you always run at sv_maxspeed. */
	public static boolean disableSprint = true;
	/** Keep Minecraft's slowdown while eating / drawing a bow. */
	public static boolean itemUseSlowdown = true;
	/** Sneak swims downward while submerged (Half-Life has no default bind for +movedown). */
	public static boolean sneakSwimsDown = true;
	/** Half-Life multiplayer footstep rule: only running (>220 u/s) and ladder steps are audible. */
	public static boolean multiplayerFootsteps = false;
	/** Show horizontal speed (units/sec) on the action bar. */
	public static boolean showSpeedometer = false;
	/** Size of the Half-Life HUD on top of Minecraft's GUI scale (1.0 = default). */
	public static double hudScale = 1.0;
	/** Crosshair size in screen pixels, regardless of window size or GUI scale. */
	public static int crosshairSize = 32;

	// --- Fall damage (server side) ---
	public static boolean hlFallDamage = true;
	public static double safeFallSpeed = 580.0;    // PLAYER_MAX_SAFE_FALL_SPEED
	public static double fatalFallSpeed = 1024.0;  // PLAYER_FATAL_FALL_SPEED

	/** True while the values above came from a remote server; saving then only touches client-only keys. */
	public static volatile boolean remoteOverride;

	private HLConfig() {
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(HLMovement.MOD_ID + ".properties");
	}

	public static synchronized void load() {
		Path file = path();
		Properties props = readFile(file);
		configVersion = 1;
		apply(props, false);

		if (configVersion < 2) {
			// v1 defaulted to the Source-style stair snap, which Half-Life 1 doesn't have.
			stepDown = false;
		}

		configVersion = CURRENT_VERSION;
		character = HLMode.validCharacter(character);
		substeps = Math.max(1, Math.min(substeps, 50));
		unitsPerBlock = Math.max(1.0, unitsPerBlock);
		// Rewrite so newly added keys show up in the file.
		save();
	}

	public static synchronized void save() {
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			if (remoteOverride) {
				// Don't write the server's values into the local file; only update personal settings.
				Properties props = readFile(file);
				for (Field field : fields()) {
					if (CLIENT_ONLY.contains(field.getName())) {
						props.setProperty(field.getName(), String.valueOf(field.get(null)));
					}
				}

				try (Writer writer = Files.newBufferedWriter(file)) {
					props.store(writer, "Half-Life movement settings");
				}

				return;
			}

			try (Writer writer = Files.newBufferedWriter(file)) {
				writer.write("# Half-Life movement settings. Speeds/distances are in GoldSrc units.\n");
				writer.write("# On a server, the server's values are used instead (except hlMode, character, showSpeedometer, hudScale and crosshairSize).\n");
				for (Field field : fields()) {
					writer.write(field.getName() + "=" + field.get(null) + "\n");
				}
			}
		} catch (IOException | ReflectiveOperationException e) {
			HLMovement.LOGGER.error("Failed to write {}", file, e);
		}
	}

	/** The server's settings as sent to clients. */
	public static synchronized String serializeSynced() {
		Properties props = new Properties();
		try {
			for (Field field : fields()) {
				if (!CLIENT_ONLY.contains(field.getName())) {
					props.setProperty(field.getName(), String.valueOf(field.get(null)));
				}
			}

			StringWriter writer = new StringWriter();
			props.store(writer, null);
			return writer.toString();
		} catch (IOException | ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Applies settings received from the server. */
	public static synchronized void applySynced(String serialized) {
		Properties props = new Properties();
		try {
			props.load(new StringReader(serialized));
		} catch (IOException e) {
			HLMovement.LOGGER.error("Bad config from server", e);
			return;
		}

		apply(props, true);
	}

	private static void apply(Properties props, boolean skipClientOnly) {
		for (Field field : fields()) {
			String name = field.getName();
			String value = props.getProperty(name);
			if (value == null || skipClientOnly && CLIENT_ONLY.contains(name)) {
				continue;
			}

			try {
				Class<?> type = field.getType();
				if (type == boolean.class) {
					field.setBoolean(null, Boolean.parseBoolean(value.trim()));
				} else if (type == int.class) {
					field.setInt(null, Integer.parseInt(value.trim()));
				} else if (type == double.class) {
					field.setDouble(null, Double.parseDouble(value.trim()));
				}
			} catch (ReflectiveOperationException | NumberFormatException e) {
				HLMovement.LOGGER.warn("Bad config value for {}: {}", name, value);
			}
		}
	}

	private static Properties readFile(Path file) {
		Properties props = new Properties();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				props.load(reader);
			} catch (IOException e) {
				HLMovement.LOGGER.error("Failed to read {}", file, e);
			}
		}

		return props;
	}

	private static List<Field> fields() {
		List<Field> result = new ArrayList<>();
		for (Field field : HLConfig.class.getDeclaredFields()) {
			int mods = field.getModifiers();
			if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && !Modifier.isFinal(mods) && !Modifier.isVolatile(mods)) {
				result.add(field);
			}
		}

		return result;
	}
}
