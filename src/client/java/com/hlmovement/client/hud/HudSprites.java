package com.hlmovement.client.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hlmovement.HLMovement;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Half-Life HUD sprites (converted from sprites/hud.txt and the .spr files), described by
 * assets/hlmovement/hud.json. There are three sets of the same layout at 1x, 2x and 3x detail.
 */
public final class HudSprites {
	/** A rectangle within a HUD texture, in texture pixels. */
	public record Sprite(Identifier texture, int u, int v, int width, int height, int textureWidth, int textureHeight) {
	}

	/** One resolution's sprites. */
	public record Set(int resolution, Map<String, Sprite> sprites, List<Sprite> pain) {
		public Sprite get(String name) {
			Sprite sprite = this.sprites.get(name);
			if (sprite == null) {
				throw new IllegalArgumentException("No HUD sprite " + name);
			}

			return sprite;
		}
	}

	private static Map<Integer, Set> sets;

	private HudSprites() {
	}

	/** The 640 set at 1x, 1280 at 2x or 2560 at 3x (the HD sets are the same layout at 2x and 3x detail). */
	public static Set forDetail(int multiplier) {
		return all().get(multiplier >= 3 ? 2560 : multiplier == 2 ? 1280 : 640);
	}

	private static Map<Integer, Set> all() {
		if (sets == null) {
			sets = load();
		}

		return sets;
	}

	private static Map<Integer, Set> load() {
		Map<Integer, Set> result = new HashMap<>();
		try (Reader reader = Minecraft.getInstance().getResourceManager().openAsReader(HLMovement.id("hud.json"))) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			for (Map.Entry<String, JsonElement> resEntry : root.entrySet()) {
				int resolution = Integer.parseInt(resEntry.getKey());
				Map<String, Sprite> sprites = new HashMap<>();
				List<Sprite> pain = new ArrayList<>();
				for (Map.Entry<String, JsonElement> entry : resEntry.getValue().getAsJsonObject().entrySet()) {
					if (entry.getKey().equals("pain")) {
						for (JsonElement frame : entry.getValue().getAsJsonArray()) {
							JsonObject f = frame.getAsJsonObject();
							int w = f.get("w").getAsInt();
							int h = f.get("h").getAsInt();
							pain.add(new Sprite(texture(f.get("tex").getAsString()), 0, 0, w, h, w, h));
						}
					} else {
						JsonObject s = entry.getValue().getAsJsonObject();
						sprites.put(entry.getKey(), new Sprite(texture(s.get("tex").getAsString()),
							s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("w").getAsInt(), s.get("h").getAsInt(),
							s.get("tw").getAsInt(), s.get("th").getAsInt()));
					}
				}

				result.put(resolution, new Set(resolution, sprites, pain));
			}
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load the Half-Life HUD sprites", e);
		}

		return result;
	}

	private static Identifier texture(String name) {
		return HLMovement.id("textures/hud/" + name + ".png");
	}
}
