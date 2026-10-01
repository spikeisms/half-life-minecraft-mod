import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Restyles Minecraft's container screens, their widget sprites and tooltips in the look of Half-Life's
 * VGUI1 menus (the class/team/buy menus): translucent black panels, thin amber frames, amber-outlined
 * slots and buttons.
 *
 * Container backgrounds are converted by recognising the parts by their exact vanilla colours: the panel
 * (C6C6C6 with white/grey bevels), slots and boxes (373737 top-left edge, white bottom-right edge, 8B8B8B
 * inside) and the black preview boxes. Other grey markings become amber (light) or dark panel (dark);
 * coloured artwork is kept. Widget sprites get a button style according to their state.
 *
 * Usage: java GuiRestyle.java <minecraft-client.jar> <assets dir to write into>
 */
public class GuiRestyle {
	static final int AMBER = 0xFFA000;
	static final int PANEL = 0xB4000000;                 // translucent black
	static final int FRAME = 0xFF000000 | AMBER;         // window frame
	static final int SLOT_EDGE = 0xC0000000 | 0xC07800;  // dimmer amber outline
	static final int SLOT_FILL = 0xC8100A00;             // warm near-black
	static final int PREVIEW_FILL = 0xB0000000;
	static final int DARK_AREA = 0xD0080808;

	static final int V_FACE = 0xC6C6C6, V_LIGHT = 0xFFFFFF, V_SHADOW = 0x555555, V_BLACK = 0x000000,
		V_SLOT_DARK = 0x373737, V_SLOT = 0x8B8B8B;

	/** Widget sprite styles: fill and outline. */
	enum Style {
		BUTTON(0xC8100A00, 0xC0000000 | 0xC07800),
		HIGHLIGHTED(0x60000000 | AMBER, 0xFF000000 | AMBER),
		SELECTED(0xA0000000 | AMBER, 0xFF000000 | AMBER),
		DISABLED(0x90000000, 0x50000000 | 0xC07800),
		FIELD(0xD0000000, 0xC0000000 | 0xC07800),
		SCROLLER(0x80000000 | AMBER, 0xFF000000 | AMBER),
		PANEL_BOX(PANEL, 0xFF000000 | AMBER),
		PANEL_BOX_DIM(PANEL, 0xA0000000 | 0xC07800),
		TAB_TOP_SELECTED(PANEL, 0xFF000000 | AMBER),
		TAB_TOP_UNSELECTED(0x90000000, 0xA0000000 | 0xC07800),
		TAB_BOTTOM_SELECTED(PANEL, 0xFF000000 | AMBER),
		TAB_BOTTOM_UNSELECTED(0x90000000, 0xA0000000 | 0xC07800),
		SLOT(SLOT_FILL, SLOT_EDGE);

		final int fill;
		final int edge;

		Style(int fill, int edge) {
			this.fill = fill;
			this.edge = edge;
		}
	}

	/** Sprites under textures/gui/sprites/container/, by name pattern. */
	static final Map<String, Style> SPRITES = new LinkedHashMap<>();

	static {
		for (String base : new String[]{"beacon/button", "stonecutter/recipe", "loom/pattern", "enchanting_table/enchantment_slot"}) {
			SPRITES.put(base, Style.BUTTON);
			SPRITES.put(base + "_highlighted", Style.HIGHLIGHTED);
			SPRITES.put(base + "_selected", Style.SELECTED);
			SPRITES.put(base + "_disabled", Style.DISABLED);
		}

		SPRITES.put("anvil/text_field", Style.FIELD);
		SPRITES.put("anvil/text_field_disabled", Style.DISABLED);
		for (String scroller : new String[]{"creative_inventory/scroller", "loom/scroller", "stonecutter/scroller", "villager/scroller"}) {
			SPRITES.put(scroller, Style.SCROLLER);
			SPRITES.put(scroller + "_disabled", Style.DISABLED);
		}

		SPRITES.put("inventory/effect_background", Style.PANEL_BOX);
		SPRITES.put("inventory/effect_background_ambient", Style.PANEL_BOX_DIM);
		SPRITES.put("bundle/slot_background", Style.SLOT);
		SPRITES.put("slot", Style.SLOT);
		for (int i = 1; i <= 7; i++) {
			SPRITES.put("creative_inventory/tab_top_selected_" + i, Style.TAB_TOP_SELECTED);
			SPRITES.put("creative_inventory/tab_top_unselected_" + i, Style.TAB_TOP_UNSELECTED);
			SPRITES.put("creative_inventory/tab_bottom_selected_" + i, Style.TAB_BOTTOM_SELECTED);
			SPRITES.put("creative_inventory/tab_bottom_unselected_" + i, Style.TAB_BOTTOM_UNSELECTED);
		}
	}

	/** Sprites that are little container layouts of their own (converted like a container background). */
	static final String[] LAYOUT_SPRITES = {"horse/chest_slots"};

	public static void main(String[] args) throws IOException {
		Path out = Path.of(args[1]);
		try (ZipFile jar = new ZipFile(args[0])) {
			String containers = "assets/minecraft/textures/gui/container/";
			String sprites = "assets/minecraft/textures/gui/sprites/container/";
			int count = 0;
			for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements(); ) {
				String name = e.nextElement().getName();
				if (name.startsWith(containers) && name.endsWith(".png") && !name.endsWith("gamemode_switcher.png")) {
					write(restyle(read(jar, name)), out.resolve(name.substring("assets/".length())));
					count++;
				}
			}

			for (String layout : LAYOUT_SPRITES) {
				String name = sprites + layout + ".png";
				write(restyle(read(jar, name)), out.resolve(name.substring("assets/".length())));
				count++;
			}

			for (Map.Entry<String, Style> sprite : SPRITES.entrySet()) {
				String name = sprites + sprite.getKey() + ".png";
				if (jar.getEntry(name) == null) {
					continue;
				}

				write(styleBox(read(jar, name), sprite.getValue()), out.resolve(name.substring("assets/".length())));
				count++;
			}

			// Tooltips: the same shapes, as a black box with an amber frame.
			String tooltip = "assets/minecraft/textures/gui/sprites/tooltip/";
			write(recolor(read(jar, tooltip + "background.png"), 0xE0000000), out.resolve(tooltip.substring("assets/".length()) + "background.png"));
			// (The vanilla frame is only ~30% opaque; HL's borders are solid.)
			write(solid(recolor(read(jar, tooltip + "frame.png"), 0xFF000000 | AMBER)), out.resolve(tooltip.substring("assets/".length()) + "frame.png"));
			count += 2;
			System.out.println("restyled " + count + " textures");
		}

		// Hovered slot: amber fill behind the item, and an amber outline (plus a faint wash) in front.
		Path highlights = out.resolve("minecraft/textures/gui/sprites/container");
		for (String folder : new String[]{"", "bundle/"}) {
			Files.createDirectories(highlights.resolve(folder.isEmpty() ? "." : folder));
			BufferedImage back = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
			BufferedImage front = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
			for (int y = 3; y <= 20; y++) {
				for (int x = 3; x <= 20; x++) {
					boolean edge = x == 3 || y == 3 || x == 20 || y == 20;
					back.setRGB(x, y, edge ? 0 : 0x58000000 | AMBER);
					front.setRGB(x, y, edge ? 0xFF000000 | AMBER : 0x14000000 | AMBER);
				}
			}

			write(back, highlights.resolve(folder + "slot_highlight_back.png"));
			write(front, highlights.resolve(folder + "slot_highlight_front.png"));
			String nineSlice = "{\n  \"gui\": {\n    \"scaling\": {\n      \"type\": \"nine_slice\",\n      \"width\": 24,\n      \"height\": 24,\n      \"border\": 4\n    }\n  }\n}\n";
			Files.writeString(highlights.resolve(folder + "slot_highlight_back.png.mcmeta"), nineSlice);
			Files.writeString(highlights.resolve(folder + "slot_highlight_front.png.mcmeta"), nineSlice);
		}
	}

	static BufferedImage read(ZipFile jar, String name) throws IOException {
		BufferedImage image;
		try (InputStream in = jar.getInputStream(jar.getEntry(name))) {
			image = ImageIO.read(in);
		}

		// Grayscale PNGs: ImageIO's getRGB passes them through a linear-gray colour space, which changes the
		// values (0x8B comes back as 0xC3). Take the stored samples as they are instead.
		var raster = image.getRaster();
		if (raster.getNumBands() <= 2 && image.getColorModel().getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY) {
			BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int v = raster.getSample(x, y, 0);
					int a = raster.getNumBands() == 2 ? raster.getSample(x, y, 1) : 255;
					argb.setRGB(x, y, a << 24 | v << 16 | v << 8 | v);
				}
			}

			return argb;
		}

		return image;
	}

	static void write(BufferedImage image, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		ImageIO.write(image, "png", target.toFile());
	}

	static int rgb(BufferedImage img, int x, int y) {
		return img.getRGB(x, y) & 0xFFFFFF;
	}

	static boolean opaque(BufferedImage img, int x, int y) {
		return x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && img.getRGB(x, y) >>> 24 != 0;
	}

	/** Grey or a muted colour (the enchanting table's beige bars count; saturated artwork like the beacon's icons doesn't). */
	static boolean gray(int rgb) {
		int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
		return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 80;
	}

	static int luma(int rgb) {
		return ((rgb >> 16 & 0xFF) * 3 + (rgb >> 8 & 0xFF) * 6 + (rgb & 0xFF)) / 10;
	}

	/** 4-connected pixels of exactly this colour. */
	static List<Integer> flood(BufferedImage in, boolean[] claimed, boolean[] visited, int x0, int y0, int color) {
		int w = in.getWidth();
		List<Integer> result = new ArrayList<>();
		java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
		queue.add(y0 * w + x0);
		visited[y0 * w + x0] = true;
		while (!queue.isEmpty()) {
			int p = queue.poll();
			result.add(p);
			int x = p % w;
			int y = p / w;
			int[][] next = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
			for (int[] n : next) {
				if (opaque(in, n[0], n[1])) {
					int i = n[1] * w + n[0];
					if (!visited[i] && !claimed[i] && rgb(in, n[0], n[1]) == color) {
						visited[i] = true;
						queue.add(i);
					}
				}
			}
		}

		return result;
	}

	/** A container background. */
	static BufferedImage restyle(BufferedImage in) {
		int w = in.getWidth();
		int h = in.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		boolean[] claimed = new boolean[w * h];

		// Slots and boxes: a 373737 top-left corner, edges running right and down, 8B8B8B inside.
		List<Rectangle> slots = new ArrayList<>();
		List<Rectangle> previews = new ArrayList<>();
		for (int y = 0; y < h - 1; y++) {
			for (int x = 0; x < w - 1; x++) {
				if (!opaque(in, x, y)) {
					continue;
				}

				int c = rgb(in, x, y);
				if (isBoxCorner(c, rgb(in, x + 1, y + 1)) && !(opaque(in, x - 1, y) && rgb(in, x - 1, y) == c)
					&& !(opaque(in, x, y - 1) && rgb(in, x, y - 1) == c)) {
					int rw = 0;
					while (x + rw < w && rgb(in, x + rw, y) == c) rw++;
					int rh = 0;
					while (y + rh < h && rgb(in, x, y + rh) == c) rh++;
					if (rw >= 8 && rh >= 8 && hasLightEdges(in, x, y, rw, rh)) {
						slots.add(new Rectangle(x, y, rw + 1, rh + 1));
					}
				} else if (c == V_BLACK && isInterior(in, x, y) && !(rgb(in, x - 1, y) == V_BLACK && isInterior(in, x - 1, y))
					&& !(rgb(in, x, y - 1) == V_BLACK && isInterior(in, x, y - 1))) {
					int rw = 0;
					while (x + rw < w && rgb(in, x + rw, y) == V_BLACK && isInterior(in, x + rw, y)) rw++;
					int rh = 0;
					while (y + rh < h && rgb(in, x, y + rh) == V_BLACK && isInterior(in, x, y + rh)) rh++;
					if (rw > 4 && rh > 4) {
						previews.add(new Rectangle(x, y, rw, rh));
					}
				}
			}
		}

		// Second pass: slots whose edges run into other artwork (the brewing stand's bottles meet its pipes)
		// still have a clean rectangle of slot grey inside.
		boolean[] covered = new boolean[w * h];
		for (Rectangle r : slots) {
			mark(covered, w, h, r);
		}

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (covered[y * w + x] || !opaque(in, x, y) || rgb(in, x, y) != V_SLOT) {
					continue;
				}

				// Slots, and narrower inset areas like scrollbar tracks (pipes and arrows are thinner than 4px).
				// They're inset, so they have a dark edge above and to the left; arrows sit on the plain panel.
				Rectangle inside = solidRect(in, x, y, V_SLOT);
				if (inside != null && Math.min(inside.width, inside.height) >= 4 && Math.max(inside.width, inside.height) >= 8
					&& isInsetEdge(in, x - 1, y) && isInsetEdge(in, x, y - 1)) {
					Rectangle slot = new Rectangle(inside.x - 1, inside.y - 1, inside.width + 2, inside.height + 2);
					slots.add(slot);
					mark(covered, w, h, slot);
				} else {
					covered[y * w + x] = true;
				}
			}
		}

		// Panel: every opaque pixel becomes translucent black, with the outer ring as the amber frame.
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (!opaque(in, x, y)) {
					continue;
				}

				boolean ring = !opaque(in, x - 1, y) || !opaque(in, x + 1, y) || !opaque(in, x, y - 1) || !opaque(in, x, y + 1);
				out.setRGB(x, y, ring ? FRAME : PANEL);
			}
		}

		for (Rectangle r : slots) {
			box(out, claimed, r, SLOT_EDGE, SLOT_FILL);
		}

		for (Rectangle r : previews) {
			box(out, claimed, new Rectangle(r.x - 1, r.y - 1, r.width + 2, r.height + 2), SLOT_EDGE, PREVIEW_FILL);
		}

		// Large flat areas of a non-standard (greyish) colour, like the enchanting table's option backdrop,
		// become dark panel rather than amber.
		boolean[] large = new boolean[w * h];
		boolean[] visited = new boolean[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (visited[i] || !opaque(in, x, y) || claimed[i]) {
					continue;
				}

				int c = rgb(in, x, y);
				if (c == V_FACE || c == V_LIGHT || c == V_SHADOW || c == V_BLACK || !gray(c)) {
					continue;
				}

				List<Integer> component = flood(in, claimed, visited, x, y, c);
				if (component.size() >= 300) {
					component.forEach(p -> large[p] = true);
				}
			}
		}

		// Everything else drawn on the panel: light grey markings (arrows, plus signs) turn amber, dark grey
		// areas turn into dark panel, and coloured artwork is kept as it is.
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (!opaque(in, x, y) || claimed[y * w + x] || out.getRGB(x, y) == FRAME) {
					continue;
				}

				int c = rgb(in, x, y);
				if (c == V_FACE || c == V_LIGHT || c == V_SHADOW || c == V_BLACK) {
					continue;
				}

				if (large[y * w + x]) {
					out.setRGB(x, y, DARK_AREA);
				} else if (c == 0xFF0000) {
					// The anvil's red placeholder under its name field, which is see-through now.
					out.setRGB(x, y, DARK_AREA);
				} else if (!gray(c)) {
					out.setRGB(x, y, in.getRGB(x, y));
				} else if (luma(c) >= 0x70) {
					out.setRGB(x, y, Math.min(255, luma(c) + 0x30) << 24 | AMBER);
				} else {
					out.setRGB(x, y, DARK_AREA);
				}
			}
		}

		return out;
	}

	/** A button-like sprite: its shape filled, with a 1px outline, in the given style. */
	static BufferedImage styleBox(BufferedImage in, Style style) {
		int w = in.getWidth();
		int h = in.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		// Selected creative tabs join the panel: no outline along the joining edge.
		boolean openBottom = style == Style.TAB_TOP_SELECTED;
		boolean openTop = style == Style.TAB_BOTTOM_SELECTED;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (!opaque(in, x, y)) {
					continue;
				}

				boolean edge = !opaque(in, x - 1, y) || !opaque(in, x + 1, y)
					|| (!opaque(in, x, y - 1) && !(openTop && y == 0))
					|| (!opaque(in, x, y + 1) && !(openBottom && y == h - 1));
				out.setRGB(x, y, edge ? style.edge : style.fill);
			}
		}

		return out;
	}

	/** Same shape, one colour (keeping the original alpha). */
	static BufferedImage recolor(BufferedImage in, int argb) {
		BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
		int baseAlpha = argb >>> 24;
		for (int y = 0; y < in.getHeight(); y++) {
			for (int x = 0; x < in.getWidth(); x++) {
				int a = in.getRGB(x, y) >>> 24;
				if (a != 0) {
					out.setRGB(x, y, (a * baseAlpha / 255) << 24 | argb & 0xFFFFFF);
				}
			}
		}

		return out;
	}

	/**
	 * The top-left corner of a slot or inset box: the standard 373737 slot edge (the crafter uses 383838
	 * with an extra bevel inside), or the 555555 edge of text boxes like the creative search field.
	 */
	static boolean isBoxCorner(int corner, int inside) {
		if (corner == V_SLOT_DARK || corner == 0x383838) {
			return gray(inside) && inside != corner && inside != V_BLACK;
		}

		return corner == V_SHADOW && inside == V_SLOT;
	}

	static boolean isInsetEdge(BufferedImage in, int x, int y) {
		if (!opaque(in, x, y)) {
			return false;
		}

		int c = rgb(in, x, y);
		return c == V_SLOT_DARK || c == 0x383838 || c == V_SHADOW;
	}

	/** A box found by walking its dark top/left edges must also have its white bottom/right edges. */
	static boolean hasLightEdges(BufferedImage in, int x, int y, int rw, int rh) {
		for (int i = 1; i < rh; i++) {
			if (rgb(in, x + rw, y + i) != V_LIGHT) {
				return false;
			}
		}

		for (int i = 1; i < rw; i++) {
			if (rgb(in, x + i, y + rh) != V_LIGHT) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The largest solid rectangle of one colour hanging from this top-left pixel (as wide as the top row
	 * run, as tall as stays fully filled). Pipes or bars leading out of it are left for the caller.
	 */
	static Rectangle solidRect(BufferedImage in, int x, int y, int color) {
		int w = 0;
		while (x + w < in.getWidth() && opaque(in, x + w, y) && rgb(in, x + w, y) == color) w++;
		int h = 0;
		rows:
		while (y + h < in.getHeight()) {
			for (int dx = 0; dx < w; dx++) {
				if (!opaque(in, x + dx, y + h) || rgb(in, x + dx, y + h) != color) {
					break rows;
				}
			}

			h++;
		}

		return h == 0 ? null : new Rectangle(x, y, w, h);
	}

	static void mark(boolean[] covered, int w, int h, Rectangle r) {
		for (int y = Math.max(0, r.y); y < Math.min(h, r.y + r.height); y++) {
			for (int x = Math.max(0, r.x); x < Math.min(w, r.x + r.width); x++) {
				covered[y * w + x] = true;
			}
		}
	}

	/** Every visible pixel fully opaque. */
	static BufferedImage solid(BufferedImage in) {
		for (int y = 0; y < in.getHeight(); y++) {
			for (int x = 0; x < in.getWidth(); x++) {
				int argb = in.getRGB(x, y);
				if (argb >>> 24 != 0) {
					in.setRGB(x, y, 0xFF000000 | argb);
				}
			}
		}

		return in;
	}

	/** A black pixel that's part of a filled area rather than the panel's outline. */
	static boolean isInterior(BufferedImage img, int x, int y) {
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (!opaque(img, x + dx, y + dy)) {
					return false;
				}
			}
		}

		return true;
	}

	static void box(BufferedImage out, boolean[] claimed, Rectangle r, int edge, int fill) {
		for (int y = r.y; y < r.y + r.height; y++) {
			for (int x = r.x; x < r.x + r.width; x++) {
				if (x < 0 || y < 0 || x >= out.getWidth() || y >= out.getHeight()) {
					continue;
				}

				boolean border = x == r.x || y == r.y || x == r.x + r.width - 1 || y == r.y + r.height - 1;
				out.setRGB(x, y, border ? edge : fill);
				claimed[y * out.getWidth() + x] = true;
			}
		}
	}
}
