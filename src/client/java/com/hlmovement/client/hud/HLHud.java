package com.hlmovement.client.hud;

import com.hlmovement.HLConfig;
import com.hlmovement.HLMovement;
import com.hlmovement.client.HLModeClient;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Half-Life HUD, ported from the SDK's cl_dll (health.cpp, battery.cpp, ammo.cpp, hud_redraw.cpp):
 * health + HEV suit (+ a hunger counter in the same style) bottom-left, "ammo" bottom-right, the weapon
 * selection menu as the hotbar, the damage direction arcs and the flashing damage-type icons.
 *
 * Sized by Minecraft's GUI scale (plus the hudScale setting), using whichever of Half-Life's three sprite
 * sets is sharpest at that size, additively blended and tinted like SPR_Set / SPR_DrawAdditive.
 */
public final class HLHud {
	private static final int YELLOWISH = 0xFFA000;   // RGB_YELLOWISH
	private static final int GREENISH = 0x00A000;    // RGB_GREENISH
	private static final int PAIN_RED = 0xFA0000;    // CHudHealth::GetPainColor at low health
	private static final int MIN_ALPHA = 100;
	private static final float FADE_TIME = 100.0F;
	private static final double DMG_IMAGE_LIFE = 2.0;
	/** How long the weapon menu stays up after a fast switch. */
	private static final double MENU_TIME = 2.0;
	/** Distances in the SDK are in units; Minecraft positions are in blocks. */
	private static final double UNITS = 40.0;
	/** GUI pixels per pixel of Half-Life's 640 sprite set: health digits come out ~10 GUI pixels tall. */
	private static final double SIZE = 0.4;

	/** SPR_DrawAdditive: glBlendFunc(GL_SRC_ALPHA, GL_ONE). */
	private static final RenderPipeline ADDITIVE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
		.withLocation(HLMovement.id("pipeline/hud_additive"))
		.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
		.build());
	private static final Identifier WHITE = HLMovement.id("textures/hud/white.png");

	private enum DamageTile {
		POISON("dmg_poison"),
		CHEM("dmg_chem"),
		COLD("dmg_cold"),
		DROWN("dmg_drown"),
		HEAT("dmg_heat"),
		GAS("dmg_gas"),
		SHOCK("dmg_shock"),
		BIO("dmg_bio");

		final String sprite;

		DamageTile(String sprite) {
			this.sprite = sprite;
		}
	}

	private static final class ActiveTile {
		final DamageTile type;
		double expire;

		ActiveTile(DamageTile type, double expire) {
			this.type = type;
			this.expire = expire;
		}
	}

	// CHudHealth / CHudBattery / hunger state.
	private static int health = -1;
	private static float healthFade;
	private static int battery = -1;
	private static float batteryFade;
	private static int food = -1;
	private static float foodFade;

	// CHudAmmo state.
	private static String ammoKey = "";
	private static float ammoFade;

	// Pain compass and damage tiles.
	private static float attackFront;
	private static float attackRear;
	private static float attackLeft;
	private static float attackRight;
	/** Index 0 is the bottom of the stack (newest). */
	private static final List<ActiveTile> tiles = new ArrayList<>();

	// Weapon menu.
	private static int lastSlot = -1;
	private static double menuUntil;
	private static LocalPlayer lastPlayer;

	private static double lastFrame = -1.0;

	private HLHud() {
	}

	public static void register() {
		// In Half-Life mode these are replaced by our HUD; otherwise they draw as usual.
		for (Identifier vanilla : List.of(VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR,
			VanillaHudElements.FOOD_BAR, VanillaHudElements.CROSSHAIR)) {
			HudElementRegistry.replaceElement(vanilla, original -> (graphics, deltaTracker) -> {
				if (!HLModeClient.active()) {
					original.extractRenderState(graphics, deltaTracker);
				}
			});
		}

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HLMovement.id("hud"), HLHud::extract);
	}

	private static double now() {
		return Util.getNanos() / 1.0E9;
	}

	// ----------------------------------------------------------------------------------------------
	// Events
	// ----------------------------------------------------------------------------------------------

	/** Once per client tick: the weapon menu opens (with a sound) whenever the selected slot changes. */
	public static void tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player != lastPlayer) {
			// New life or new world: CHudHealth::Reset.
			lastPlayer = player;
			lastSlot = player == null ? -1 : player.getInventory().getSelectedSlot();
			attackFront = attackRear = attackLeft = attackRight = 0.0F;
			tiles.clear();
			menuUntil = 0.0;
			return;
		}

		if (player == null || player.isSpectator()) {
			return;
		}

		int slot = player.getInventory().getSelectedSlot();
		if (!HLModeClient.active()) {
			lastSlot = slot;
			return;
		}

		if (slot != lastSlot) {
			lastSlot = slot;
			double time = now();
			playUi(minecraft, time < menuUntil ? "common.wpn_moveselect" : "common.wpn_hudon");
			menuUntil = time + MENU_TIME;
		}
	}

	/** The local player took damage (LivingEntity.handleDamageEvent). */
	public static void onDamage(LocalPlayer player, DamageSource source) {
		double time = now();

		// CHudHealth::UpdateTiles
		DamageTile type = tileFor(player, source);
		if (type != null) {
			ActiveTile existing = tiles.stream().filter(t -> t.type == type).findFirst().orElse(null);
			if (existing != null) {
				existing.expire = time + DMG_IMAGE_LIFE;
			} else {
				tiles.addFirst(new ActiveTile(type, time + DMG_IMAGE_LIFE));
			}
		}

		// CHudHealth::CalcDamageDirection
		Vec3 from = source.getSourcePosition();
		if (from == null) {
			attackFront = attackRear = attackLeft = attackRight = 0.0F;
			return;
		}

		Vec3 origin = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
		Vec3 toSource = from.subtract(origin);
		double distance = toSource.length() * UNITS;
		if (distance <= 50.0) {
			attackFront = attackRear = attackLeft = attackRight = 1.0F;
			return;
		}

		Vec3 dir = toSource.normalize();
		Vec3 forward = player.getViewVector(1.0F);
		double yaw = player.getYRot() * Mth.DEG_TO_RAD;
		Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
		float front = (float) dir.dot(right);
		float side = (float) dir.dot(forward);

		if (side > 0.3F) {
			attackFront = Math.max(attackFront, side);
		} else if (side < -0.3F) {
			attackRear = Math.max(attackRear, -side);
		}

		if (front > 0.3F) {
			attackRight = Math.max(attackRight, front);
		} else if (front < -0.3F) {
			attackLeft = Math.max(attackLeft, -front);
		}
	}

	/** Minecraft damage types mapped onto Half-Life's damage icons. */
	private static DamageTile tileFor(LocalPlayer player, DamageSource source) {
		if (source.is(DamageTypeTags.IS_DROWNING)) {
			return DamageTile.DROWN;
		} else if (source.is(DamageTypeTags.IS_FIRE)) {
			return DamageTile.HEAT;
		} else if (source.is(DamageTypeTags.IS_FREEZING)) {
			return DamageTile.COLD;
		} else if (source.is(DamageTypeTags.IS_LIGHTNING)) {
			return DamageTile.SHOCK;
		} else if (source.is(DamageTypes.WITHER)) {
			return DamageTile.BIO;
		} else if (source.is(DamageTypes.DRAGON_BREATH)) {
			return DamageTile.CHEM;
		} else if (source.is(DamageTypes.IN_WALL)) {
			return DamageTile.GAS;
		} else if (source.is(DamageTypes.MAGIC) && player.hasEffect(MobEffects.POISON)) {
			return DamageTile.POISON;
		}

		return null;
	}

	private static void playUi(Minecraft minecraft, String sound) {
		if (minecraft.player != null) {
			SoundEvent event = SoundEvent.createVariableRangeEvent(HLMovement.id(sound));
			minecraft.player.level().playLocalSound(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
				event, SoundSource.PLAYERS, 1.0F, 1.0F, false);
		}
	}

	// ----------------------------------------------------------------------------------------------
	// Drawing
	// ----------------------------------------------------------------------------------------------

	private static void extract(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || !HLModeClient.active()) {
			return;
		}

		double time = now();
		float delta = lastFrame < 0.0 ? 0.0F : (float) Math.min(0.25, time - lastFrame);
		lastFrame = time;

		// Size follows Minecraft's GUI scale: one pixel of the 640 set is SIZE GUI pixels (times hudScale).
		// Pick the sharpest sprite set for that size and draw it slightly scaled down.
		int guiScale = minecraft.getWindow().getGuiScale();
		double screenPixelsPerUnit = SIZE * guiScale * HLConfig.hudScale;
		int detail = Mth.clamp((int) Math.ceil(screenPixelsPerUnit - 0.1), 1, 3);
		float scale = (float) (screenPixelsPerUnit / detail);
		HudSprites.Set s = HudSprites.forDetail(detail);
		Draw d = new Draw(g, s, (int) (minecraft.getWindow().getWidth() / scale), (int) (minecraft.getWindow().getHeight() / scale));

		// Coordinates below are in pixels of the chosen sprite set, laid out like Half-Life's screen pixels.
		g.pose().pushMatrix();
		g.pose().scale(scale / guiScale);

		if (!player.isSpectator()) {
			if (minecraft.gameMode != null && minecraft.gameMode.canHurtPlayer()) {
				drawHealth(d, player, delta);
				int batteryEnd = drawBattery(d, player, delta);
				drawFood(d, player, delta, batteryEnd);
			}

			drawDamageTiles(d, time);
			drawPain(d, delta);
			drawAmmo(d, player, delta);
			if (time < menuUntil) {
				drawWeaponMenu(d, player);
			}
		}

		g.pose().popMatrix();

		if (!player.isSpectator() && minecraft.options.getCameraType().isFirstPerson()) {
			drawCrosshair(g, minecraft);
		}
	}

	/** Drawing helpers in screen pixels. */
	private record Draw(GuiGraphicsExtractor g, HudSprites.Set s, int width, int height) {
		HudSprites.Sprite sprite(String name) {
			return this.s.get(name);
		}

		/** SPR_Set(r, g, b) + SPR_DrawAdditive. */
		void additive(HudSprites.Sprite sp, int x, int y, int rgb) {
			this.additive(sp, x, y, 0, sp.height(), rgb);
		}

		/** Draws rows [top, bottom) of a sprite (used for the filling suit/hunger icons). */
		void additive(HudSprites.Sprite sp, int x, int y, int top, int bottom, int rgb) {
			if (bottom <= top) {
				return;
			}

			this.g.blit(ADDITIVE, sp.texture(), x, y + top, sp.u(), sp.v() + top, sp.width(), bottom - top,
				sp.textureWidth(), sp.textureHeight(), 0xFF000000 | rgb);
		}

		/** FillRGBA, which Half-Life also draws additively. */
		void fill(int x, int y, int w, int h, int rgb, int alpha) {
			this.g.blit(ADDITIVE, WHITE, x, y, 0.0F, 0.0F, w, h, 1, 1, 1, 1, 0xFF000000 | scale(rgb, alpha));
		}

		/** Minecraft's own item icon (full colour) at size x size pixels. */
		void item(ItemStack stack, int x, int y, int size) {
			this.g.pose().pushMatrix();
			this.g.pose().translate(x, y);
			this.g.pose().scale(size / 16.0F);
			this.g.item(stack, 0, 0);
			this.g.pose().popMatrix();
		}

		int numberWidth() {
			HudSprites.Sprite zero = this.sprite("number_0");
			return zero.width();
		}

		int fontHeight() {
			return this.sprite("number_0").height();
		}

		/** CHud::DrawHudNumber with DHN_3DIGITS | DHN_DRAWZERO. */
		int number(int x, int y, int value, int rgb) {
			int w = this.numberWidth();
			if (value > 0) {
				if (value >= 100) {
					this.additive(this.sprite("number_" + Math.min(9, value / 100)), x, y, rgb);
				}

				x += w;
				if (value >= 10) {
					this.additive(this.sprite("number_" + value % 100 / 10), x, y, rgb);
				}

				x += w;
				this.additive(this.sprite("number_" + value % 10), x, y, rgb);
				x += w;
			} else {
				x += w * 2;
				this.additive(this.sprite("number_0"), x, y, rgb);
				x += w;
			}

			return x;
		}
	}

	/** ScaleColors */
	private static int scale(int rgb, int alpha) {
		alpha = Mth.clamp(alpha, 0, 255);
		int r = (rgb >> 16 & 0xFF) * alpha / 255;
		int g = (rgb >> 8 & 0xFF) * alpha / 255;
		int b = (rgb & 0xFF) * alpha / 255;
		return r << 16 | g << 8 | b;
	}

	/** The "flash when it changes, then fade back to dim" alpha used by health, battery and ammo. */
	private static int fadeAlpha(float fade) {
		return fade > 0.0F ? (int) (MIN_ALPHA + fade / FADE_TIME * 128.0F) : MIN_ALPHA;
	}

	/** CHudHealth::Draw. Returns the x just past the number. */
	private static int drawHealth(Draw d, LocalPlayer player, float delta) {
		int value = Mth.ceil(player.getHealth() * 5.0F);
		if (value != health) {
			health = value;
			healthFade = FADE_TIME;
		}

		healthFade = Math.max(0.0F, healthFade - delta * 20.0F);
		int alpha = health <= 15 ? 255 : fadeAlpha(healthFade);
		int rgb = scale(health > 25 ? YELLOWISH : PAIN_RED, alpha);

		HudSprites.Sprite cross = d.sprite("cross");
		int numberWidth = d.numberWidth();
		int fontHeight = d.fontHeight();
		int y = d.height() - fontHeight - fontHeight / 2;
		d.additive(cross, cross.width() / 2, y, rgb);

		int x = cross.width() + numberWidth / 2;
		y += (int) (fontHeight * 0.2F);
		x = d.number(x, y, health, rgb);
		x += numberWidth / 2;
		d.fill(x, y, numberWidth / 10, fontHeight, YELLOWISH, alpha);
		return x + numberWidth / 10;
	}

	/** CHudBattery::Draw: armor points as the HEV suit charge. */
	private static int drawBattery(Draw d, LocalPlayer player, float delta) {
		int value = player.getArmorValue() * 5;
		if (value != battery) {
			battery = value;
			batteryFade = FADE_TIME;
		}

		batteryFade = Math.max(0.0F, batteryFade - delta * 20.0F);
		int rgb = scale(YELLOWISH, fadeAlpha(batteryFade));
		return drawGauge(d, "suit_empty", "suit_full", battery, rgb, 3 * d.sprite("suit_empty").width());
	}

	/** Hunger, laid out like health (icon, then number) after a divider like the one after health. */
	private static void drawFood(Draw d, LocalPlayer player, float delta, int after) {
		int value = player.getFoodData().getFoodLevel() * 5;
		if (value != food) {
			food = value;
			foodFade = FADE_TIME;
		}

		foodFade = Math.max(0.0F, foodFade - delta * 20.0F);
		int alpha = food <= 15 ? 255 : fadeAlpha(foodFade);
		int rgb = scale(food > 25 ? YELLOWISH : PAIN_RED, alpha);

		HudSprites.Sprite icon = d.sprite("food");
		int numberWidth = d.numberWidth();
		int fontHeight = d.fontHeight();
		int y = d.height() - fontHeight - fontHeight / 2;
		int x = after + numberWidth / 2;
		d.fill(x, y + (int) (fontHeight * 0.2F), numberWidth / 10, fontHeight, YELLOWISH, alpha);
		x += numberWidth / 10 + numberWidth / 2;

		d.additive(icon, x, y, rgb);
		x += icon.width() / 2 + numberWidth / 2;
		d.number(x, y + (int) (fontHeight * 0.2F), food, rgb);
	}

	/** An icon that fills from the bottom with the value (0-100), followed by the number. */
	private static int drawGauge(Draw d, String emptyName, String fullName, int value, int rgb, int x) {
		HudSprites.Sprite empty = d.sprite(emptyName);
		HudSprites.Sprite full = d.sprite(fullName);
		int fontHeight = d.fontHeight();
		int offset = empty.height() / 6;
		int y = d.height() - fontHeight - fontHeight / 2;
		int top = (int) (full.height() * (100 - Math.min(100, value)) * 0.01F);

		d.additive(empty, x, y - offset, rgb);
		d.additive(full, x, y - offset, top, full.height(), rgb);

		x += empty.width();
		y += (int) (fontHeight * 0.2F);
		return d.number(x, y, value, rgb);
	}

	/** CHudHealth::DrawDamage: flashing icons stacked up the left side. */
	private static void drawDamageTiles(Draw d, double time) {
		if (tiles.isEmpty()) {
			return;
		}

		int alpha = (int) (Math.abs(Math.sin(time * 2.0)) * 256.0);
		int rgb = scale(YELLOWISH, alpha);
		HudSprites.Sprite any = d.sprite("dmg_bio");
		int tileHeight = any.height();
		for (int i = 0; i < tiles.size(); i++) {
			HudSprites.Sprite sprite = d.sprite(tiles.get(i).type.sprite);
			d.additive(sprite, sprite.width() / 8, d.height() - tileHeight * 2 - i * tileHeight, rgb);
		}

		// Expire at the low point of the flash.
		if (alpha < 40) {
			tiles.removeIf(t -> t.expire <= time);
		}
	}

	/** CHudHealth::DrawPain: red arcs around the crosshair showing where damage came from. */
	private static void drawPain(Draw d, float delta) {
		if (attackFront == 0.0F && attackRear == 0.0F && attackLeft == 0.0F && attackRight == 0.0F) {
			return;
		}

		int painColor = health > 25 ? YELLOWISH : PAIN_RED;
		float fade = delta * 2.0F;
		List<HudSprites.Sprite> pain = d.s().pain();
		int cx = d.width() / 2;
		int cy = d.height() / 2;

		if (attackFront > 0.4F) {
			HudSprites.Sprite sp = pain.get(0);
			d.additive(sp, cx - sp.width() / 2, cy - sp.height() * 3, scale(painColor, (int) (255 * Math.max(attackFront, 0.5F))));
			attackFront = Math.max(0.0F, attackFront - fade);
		} else {
			attackFront = 0.0F;
		}

		if (attackRight > 0.4F) {
			HudSprites.Sprite sp = pain.get(1);
			d.additive(sp, cx + sp.width() * 2, cy - sp.height() / 2, scale(painColor, (int) (255 * Math.max(attackRight, 0.5F))));
			attackRight = Math.max(0.0F, attackRight - fade);
		} else {
			attackRight = 0.0F;
		}

		if (attackRear > 0.4F) {
			HudSprites.Sprite sp = pain.get(2);
			d.additive(sp, cx - sp.width() / 2, cy + sp.height() * 2, scale(painColor, (int) (255 * Math.max(attackRear, 0.5F))));
			attackRear = Math.max(0.0F, attackRear - fade);
		} else {
			attackRear = 0.0F;
		}

		if (attackLeft > 0.4F) {
			HudSprites.Sprite sp = pain.get(3);
			d.additive(sp, cx - sp.width() * 3, cy - sp.height() / 2, scale(painColor, (int) (255 * Math.max(attackLeft, 0.5F))));
			attackLeft = Math.max(0.0F, attackLeft - fade);
		} else {
			attackLeft = 0.0F;
		}
	}

	/**
	 * CHudAmmo::Draw. Stackable items read like a weapon with a clip ("in hand | rest of inventory"),
	 * bows and crossbows like a weapon without one (their arrows), and the offhand as secondary ammo.
	 * Tools and other single items show nothing, like the crowbar.
	 */
	private static void drawAmmo(Draw d, LocalPlayer player, float delta) {
		Inventory inventory = player.getInventory();
		ItemStack held = player.getMainHandItem();
		ItemStack offhand = player.getOffhandItem();

		ItemStack ammoIcon = ItemStack.EMPTY;
		int clip = -1;
		int reserve = 0;
		if (held.getItem() instanceof ProjectileWeaponItem) {
			ItemStack projectile = player.getProjectile(held);
			if (!projectile.isEmpty()) {
				ammoIcon = projectile;
				reserve = player.hasInfiniteMaterials() ? 999 : count(inventory, projectile);
			}
		} else if (!held.isEmpty() && held.getMaxStackSize() > 1) {
			ammoIcon = held;
			clip = held.getCount();
			reserve = count(inventory, held) - clip;
		}

		String key = ammoIcon.getItem() + ":" + clip + ":" + reserve + ":" + offhand.getItem() + ":" + offhand.getCount();
		if (!key.equals(ammoKey)) {
			ammoKey = key;
			ammoFade = 200.0F;
		}

		int alpha = Math.max(MIN_ALPHA, (int) ammoFade);
		ammoFade = Math.max(0.0F, ammoFade - delta * 20.0F);
		int rgb = scale(YELLOWISH, alpha);

		int numberWidth = d.numberWidth();
		int fontHeight = d.fontHeight();
		int iconSize = fontHeight;
		int y = d.height() - fontHeight - fontHeight / 2 + (int) (fontHeight * 0.2F);

		if (!ammoIcon.isEmpty()) {
			int x;
			if (clip >= 0) {
				x = d.width() - 8 * numberWidth - iconSize;
				x = d.number(x, y, clip, rgb);
				x += numberWidth / 2;
				int barWidth = numberWidth / 10;
				d.fill(x, y, barWidth, fontHeight, YELLOWISH, alpha);
				x += barWidth + numberWidth / 2;
				x = d.number(x, y, Math.min(999, reserve), rgb);
			} else {
				x = d.width() - 4 * numberWidth - iconSize;
				x = d.number(x, y, Math.min(999, reserve), rgb);
			}

			d.item(ammoIcon, x, y - iconSize / 8, iconSize);
		}

		// Secondary ammo line: whatever is in the offhand.
		if (!offhand.isEmpty()) {
			int y2 = y - (fontHeight + fontHeight / 4);
			int x = d.width() - 4 * numberWidth - iconSize;
			x = d.number(x, y2, offhand.getCount(), rgb);
			d.item(offhand, x, y2 - iconSize / 8, iconSize);
		}
	}

	private static int count(Inventory inventory, ItemStack like) {
		int total = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (ItemStack.isSameItemSameComponents(stack, like)) {
				total += stack.getCount();
			}
		}

		return total;
	}

	/**
	 * CHudAmmo::DrawWList with the nine hotbar slots as nine buckets of one item each: numbered labels
	 * along the top, the selected slot widened with the selection frame, the others as small boxes.
	 */
	private static void drawWeaponMenu(Draw d, LocalPlayer player) {
		Inventory inventory = player.getInventory();
		int active = inventory.getSelectedSlot();
		HudSprites.Sprite bucket = d.sprite("bucket1");
		HudSprites.Sprite selection = d.sprite("selection");
		int bucketWidth = bucket.width();
		int bucketHeight = bucket.height();
		int barScale = d.s().resolution() == 2560 ? 4 : d.s().resolution() == 1280 ? 3 : 2;
		int barWidth = 10 * barScale;
		int barHeight = 2 * barScale;

		// Top line: the bucket numbers.
		int x = 10;
		int y = 10;
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			String name = "bucket" + (i + 1);
			d.additive(d.sprite(name), x, y, YELLOWISH);
			x += (i == active ? selection.width() : bucketWidth) + 5;
		}

		// The buckets.
		x = 10;
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			y = bucketHeight + 10;
			if (i == active) {
				if (!stack.isEmpty()) {
					int size = selection.height() * 3 / 4;
					d.item(stack, x + (selection.width() - size) / 2, y + (selection.height() - size) / 2, size);
				}

				d.additive(selection, x, y, YELLOWISH);
				drawAmmoBar(d, stack, x + barWidth / 2, y, barWidth, barHeight);
				x += selection.width() + 5;
			} else {
				if (!stack.isEmpty()) {
					d.fill(x, y, bucketWidth, bucketHeight, YELLOWISH, 128);
					int size = bucketHeight * 3 / 4;
					d.item(stack, x + (bucketWidth - size) / 2, y + (bucketHeight - size) / 2, size);
					drawAmmoBar(d, stack, x + barWidth / 2, y, barWidth, barHeight);
				}

				x += bucketWidth + 5;
			}
		}
	}

	/** DrawAmmoBar / DrawBar: green for what's left (stack fullness, or durability for tools). */
	private static void drawAmmoBar(Draw d, ItemStack stack, int x, int y, int width, int height) {
		float f;
		if (stack.isDamageableItem()) {
			f = 1.0F - (float) stack.getDamageValue() / stack.getMaxDamage();
		} else if (stack.getMaxStackSize() > 1) {
			f = (float) stack.getCount() / stack.getMaxStackSize();
		} else {
			return;
		}

		f = Mth.clamp(f, 0.0F, 1.0F);
		int w = Math.max(1, (int) (f * width));
		d.fill(x, y, w, height, GREENISH, 255);
		d.fill(x + w, y, width - w, height, YELLOWISH, 128);
	}

	/**
	 * The 9mm handgun's crosshair, in its own colours, always crosshairSize screen pixels across whatever
	 * the window size or GUI scale. Uses the sharpest of the 24/48/72 pixel versions for that size.
	 */
	private static void drawCrosshair(GuiGraphicsExtractor g, Minecraft minecraft) {
		int size = Math.max(1, HLConfig.crosshairSize);
		HudSprites.Sprite crosshair = HudSprites.forDetail(Mth.clamp((int) Math.ceil(size / 24.0 - 0.1), 1, 3)).get("crosshair");
		int width = minecraft.getWindow().getWidth();
		int height = minecraft.getWindow().getHeight();

		g.pose().pushMatrix();
		g.pose().scale(1.0F / minecraft.getWindow().getGuiScale());
		g.blit(RenderPipelines.GUI_TEXTURED, crosshair.texture(), width / 2 - size / 2, height / 2 - size / 2,
			crosshair.u(), crosshair.v(), size, size, crosshair.width(), crosshair.height(), crosshair.textureWidth(), crosshair.textureHeight());
		g.pose().popMatrix();
	}
}
