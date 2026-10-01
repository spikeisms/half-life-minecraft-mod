import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/**
 * Converts the Half-Life HUD sprites used by the mod into PNGs and writes hud.json describing
 * where each named HUD element lives, for the 640 / 1280 / 2560 sprite sets.
 * Usage: java SprConvert.java <valve dir> <assets/hlmovement dir>
 */
public class SprConvert {
	static final int[] RESOLUTIONS = {640, 1280, 2560};
	static final List<String> WANTED = new ArrayList<>(List.of(
		"number_0", "number_1", "number_2", "number_3", "number_4", "number_5", "number_6", "number_7", "number_8", "number_9",
		"divider", "cross", "suit_full", "suit_empty", "selection",
		"bucket1", "bucket2", "bucket3", "bucket4", "bucket5", "bucket0",
		"dmg_bio", "dmg_poison", "dmg_chem", "dmg_cold", "dmg_drown", "dmg_heat", "dmg_gas", "dmg_rad", "dmg_shock"));

	record Frame(int w, int h, BufferedImage image) {}

	public static void main(String[] args) throws Exception {
		Path valve = Path.of(args[0]);
		Path out = Path.of(args[1]);
		Path texDir = out.resolve("textures/hud");
		Files.createDirectories(texDir);

		// name -> res -> [sprite, x, y, w, h]
		Map<Integer, Map<String, String[]>> entries = new TreeMap<>();
		readList(valve.resolve("sprites/hud.txt"), entries, null);
		// The 9mm handgun's crosshair stands in for "the" crosshair.
		readList(valve.resolve("sprites/weapon_9mmhandgun.txt"), entries, Map.of("crosshair", "crosshair"));

		Map<String, Frame> converted = new HashMap<>();
		StringBuilder json = new StringBuilder("{\n");
		boolean firstRes = true;
		for (int res : RESOLUTIONS) {
			Map<String, String[]> byName = entries.get(res);
			if (!firstRes) json.append(",\n");
			firstRes = false;
			json.append("  \"").append(res).append("\": {\n");
			List<String> names = new ArrayList<>(WANTED);
			names.add("crosshair");
			boolean first = true;
			for (String name : names) {
				String[] e = byName.get(name);
				if (e == null) throw new IllegalStateException("missing " + name + " @" + res);
				String sprite = e[0];
				Frame f = converted.computeIfAbsent(sprite, s -> convert(valve.resolve("sprites/" + s + ".spr"), texDir, texName(s), true).get(0));
				if (!first) json.append(",\n");
				first = false;
				json.append(String.format("    \"%s\": {\"tex\": \"%s\", \"x\": %s, \"y\": %s, \"w\": %s, \"h\": %s, \"tw\": %d, \"th\": %d}",
					name, texName(sprite), e[1], e[2], e[3], e[4], f.w(), f.h()));
			}

			// Bucket labels 6-9: Half-Life only ships 1-5. Its blank "bucket0" box is much brighter than the
			// numbered ones, so start from bucket1, erase its "1", and draw the new digit in the same 5x5 font.
			String[] one = byName.get("bucket1");
			BufferedImage sheet = ImageIO.read(texDir.resolve(texName(one[0]) + ".png").toFile());
			int bw = Integer.parseInt(one[3]);
			int scale = bw / 20;
			for (int digit = 6; digit <= 9; digit++) {
				BufferedImage label = sheet.getSubimage(Integer.parseInt(one[1]), Integer.parseInt(one[2]), bw, bw);
				BufferedImage copy = new BufferedImage(bw, bw, BufferedImage.TYPE_INT_ARGB);
				copy.getGraphics().drawImage(label, 0, 0, null);
				int background = copy.getRGB(scale, scale);
				for (int py = 13 * scale; py < 18 * scale; py++) {
					for (int px = 13 * scale; px < 18 * scale; px++) {
						copy.setRGB(px, py, background);
					}
				}

				String[] glyph = GLYPHS[digit - 6];
				for (int gy = 0; gy < 5; gy++) {
					for (int gx = 0; gx < 5; gx++) {
						if (glyph[gy].charAt(gx) == '#') {
							for (int py = 0; py < scale; py++) {
								for (int px = 0; px < scale; px++) {
									copy.setRGB((13 + gx) * scale + px, (13 + gy) * scale + py, 0xFFFFFFFF);
								}
							}
						}
					}
				}

				String tex = "bucket" + digit + "_" + res;
				write(copy, texDir.resolve(tex + ".png"));
				json.append(String.format(",\n    \"bucket%d\": {\"tex\": \"%s\", \"x\": 0, \"y\": 0, \"w\": %d, \"h\": %d, \"tw\": %d, \"th\": %d}", digit, tex, bw, bw, bw, bw));
			}

			// Hunger has no Half-Life icon: a drumstick drawn like the health cross (solid core, soft glow), at its size.
			int size = Integer.parseInt(byName.get("cross")[3]);
			String foodTex = "food_" + res;
			write(drawFood(size), texDir.resolve(foodTex + ".png"));
			json.append(String.format(",\n    \"food\": {\"tex\": \"%s\", \"x\": 0, \"y\": 0, \"w\": %d, \"h\": %d, \"tw\": %d, \"th\": %d}",
				foodTex, size, size, size, size));

			// Damage direction arcs: sprites/<res>_pain.spr, 4 frames (front, right, rear, left).
			List<Frame> pain = convert(valve.resolve("sprites/" + res + "_pain.spr"), texDir, res + "_pain", false);
			json.append(",\n    \"pain\": [");
			for (int i = 0; i < pain.size(); i++) {
				if (i > 0) json.append(", ");
				json.append(String.format("{\"tex\": \"%d_pain_%d\", \"w\": %d, \"h\": %d}", res, i, pain.get(i).w(), pain.get(i).h()));
			}

			json.append("]\n  }");
		}

		json.append("\n}\n");
		Files.writeString(out.resolve("hud.json"), json.toString());

		BufferedImage white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		white.setRGB(0, 0, 0xFFFFFFFF);
		write(white, texDir.resolve("white.png"));
		System.out.println("converted " + converted.size() + " sprite sheets");
	}

	/** 5x5 digits in the style of the bucket labels. */
	static final String[][] GLYPHS = {
		{"#####", "#....", "#####", "#...#", "#####"},
		{"#####", "....#", "....#", "....#", "....#"},
		{"#####", "#...#", "#####", "#...#", "#####"},
		{"#####", "#...#", "#####", "....#", "#####"},
	};

	/**
	 * A drumstick in the style of hud_cross: a solid bright (~245) shape with a soft glow falling off over
	 * ~3/32 of the icon, no outline. The shape fills about the same area as the cross does.
	 */
	static BufferedImage drawFood(int size) {
		int ss = 8; // supersample
		int s = size * ss;
		BufferedImage mask = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = mask.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(java.awt.Color.WHITE);

		// Keep inside the same 18/32 core the cross uses, centred.
		double c0 = s * 7.0 / 32.0;
		double c = s * 18.0 / 32.0;
		java.awt.geom.Area shape = new java.awt.geom.Area();
		java.awt.geom.Ellipse2D meat = new java.awt.geom.Ellipse2D.Double(c0 + c * 0.38, c0 + c * 0.0, c * 0.62, c * 0.72);
		java.awt.geom.AffineTransform rot = java.awt.geom.AffineTransform.getRotateInstance(Math.toRadians(45), c0 + c * 0.69, c0 + c * 0.36);
		shape.add(new java.awt.geom.Area(rot.createTransformedShape(meat)));
		java.awt.BasicStroke bone = new java.awt.BasicStroke((float) (c * 0.16), java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_ROUND);
		shape.add(new java.awt.geom.Area(bone.createStrokedShape(new java.awt.geom.Line2D.Double(c0 + c * 0.55, c0 + c * 0.45, c0 + c * 0.2, c0 + c * 0.8))));
		double knob = c * 0.2;
		shape.add(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(c0 + c * 0.0, c0 + c * 0.72, knob, knob)));
		shape.add(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(c0 + c * 0.08, c0 + c * 0.80, knob, knob)));
		g.fill(shape);
		g.dispose();

		// Downsample the mask.
		float[] core = new float[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int sum = 0;
				for (int dy = 0; dy < ss; dy++) {
					for (int dx = 0; dx < ss; dx++) {
						sum += mask.getRGB(x * ss + dx, y * ss + dy) >>> 24;
					}
				}

				core[y * size + x] = sum / (255.0F * ss * ss);
			}
		}

		// Glow: the mask blurred over ~3/32 of the icon, much dimmer than the core.
		int radius = Math.max(1, size * 3 / 32);
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				float glow = 0.0F;
				float weight = 0.0F;
				for (int dy = -radius; dy <= radius; dy++) {
					for (int dx = -radius; dx <= radius; dx++) {
						int px = x + dx;
						int py = y + dy;
						float w = (float) Math.exp(-(dx * dx + dy * dy) / (0.6 * radius * radius));
						weight += w;
						if (px >= 0 && py >= 0 && px < size && py < size) {
							glow += core[py * size + px] * w;
						}
					}
				}

				float v = Math.max(core[y * size + x] * 245.0F, glow / weight * 120.0F);
				int b = Math.min(255, Math.round(v));
				out.setRGB(x, y, 0xFF000000 | b << 16 | b << 8 | b);
			}
		}

		return out;
	}

	/** Writes a PNG plus a .mcmeta asking Minecraft for smooth (linear) filtering, since the HUD is drawn scaled. */
	static void write(BufferedImage image, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		ImageIO.write(image, "png", target.toFile());
		Files.writeString(target.resolveSibling(target.getFileName() + ".mcmeta"), "{\"texture\": {\"blur\": true, \"clamp\": true}}\n");
	}

	static String texName(String sprite) {
		return sprite.toLowerCase(Locale.ROOT);
	}

	static void readList(Path file, Map<Integer, Map<String, String[]>> entries, Map<String, String> only) throws IOException {
		for (String line : Files.readAllLines(file)) {
			line = line.trim();
			if (line.isEmpty() || line.startsWith("//")) continue;
			String[] p = line.split("\\s+");
			if (p.length < 7) continue;
			String name = p[0];
			if (only != null && !only.containsKey(name)) continue;
			int res;
			try {
				res = Integer.parseInt(p[1]);
			} catch (NumberFormatException e) {
				continue;
			}

			// The sprite name may be glued to the resolution-less prefix, e.g. "2560crosshairs" or "2560/hud_x".
			entries.computeIfAbsent(res, r -> new HashMap<>()).put(name, new String[]{p[2], p[3], p[4], p[5], p[6]});
		}
	}

	/** Decodes a GoldSrc (version 2) sprite. Writes frame 0 as <name>.png, or every frame as <name>_<i>.png. */
	static List<Frame> convert(Path spr, Path texDir, String name, boolean firstFrameOnly) {
		try {
			ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(spr)).order(ByteOrder.LITTLE_ENDIAN);
			byte[] ident = new byte[4];
			b.get(ident);
			if (!new String(ident).equals("IDSP")) throw new IOException("not a sprite: " + spr);
			int version = b.getInt();
			if (version != 2) throw new IOException("unsupported sprite version " + version + ": " + spr);
			b.getInt(); // type
			int texFormat = b.getInt();
			b.getFloat(); // bounding radius
			b.getInt(); // max width
			b.getInt(); // max height
			int numFrames = b.getInt();
			b.getFloat(); // beam length
			b.getInt(); // sync type
			int paletteSize = Short.toUnsignedInt(b.getShort());
			int[] palette = new int[paletteSize];
			for (int i = 0; i < paletteSize; i++) {
				palette[i] = (b.get() & 0xFF) << 16 | (b.get() & 0xFF) << 8 | (b.get() & 0xFF);
			}

			List<Frame> frames = new ArrayList<>();
			for (int f = 0; f < numFrames; f++) {
				int type = b.getInt();
				if (type != 0) throw new IOException("frame groups not supported: " + spr);
				b.getInt(); // origin x
				b.getInt(); // origin y
				int w = b.getInt();
				int h = b.getInt();
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int index = b.get() & 0xFF;
						int argb;
						switch (texFormat) {
							case 2 -> argb = index << 24 | palette[paletteSize - 1]; // SPR_INDEXALPHA
							case 3 -> argb = index == 255 ? 0 : 0xFF000000 | palette[index]; // SPR_ALPHTEST
							default -> argb = 0xFF000000 | palette[index]; // SPR_NORMAL / SPR_ADDITIVE
						}

						img.setRGB(x, y, argb);
					}
				}

				String file = firstFrameOnly ? name + ".png" : name + "_" + f + ".png";
				Path target = texDir.resolve(file);
				write(img, target);
				frames.add(new Frame(w, h, img));
				if (firstFrameOnly) break;
			}

			System.out.println(spr.getFileName() + " fmt=" + texFormat + " frames=" + numFrames + " -> " + frames.get(0).w() + "x" + frames.get(0).h());
			return frames;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
