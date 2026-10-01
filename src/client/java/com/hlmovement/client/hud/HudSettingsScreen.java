package com.hlmovement.client.hud;

import com.hlmovement.HLConfig;
import com.hlmovement.HLMode;
import com.hlmovement.client.HLModeClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

/**
 * In-game settings: the Half-Life mode switch, your character, and the personal HUD options. The world and HUD stay visible behind it, so changes
 * preview live; everything is saved to config/hlmovement.properties when the screen closes.
 */
public class HudSettingsScreen extends Screen {
	private static final double HUD_SCALE_MIN = 0.25;
	private static final double HUD_SCALE_MAX = 3.0;
	private static final int CROSSHAIR_MIN = 8;
	private static final int CROSSHAIR_MAX = 128;
	private static final int WIDTH = 200;
	private static final int ROW = 24;

	/** Half-height of the gap left open around the crosshair so its size can be previewed. */
	private static final int CENTER_GAP = 40;

	private final Screen parent;
	private int topPanelTop;
	private int bottomPanelTop;
	private int bottomPanelBottom;

	public HudSettingsScreen(Screen parent) {
		super(Component.translatable("hlmovement.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// Title, the mode switch, character and HUD scale above the crosshair, everything else below it.
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - CENTER_GAP - 20 - 2 * ROW;
		this.topPanelTop = y - 20;

		this.addRenderableWidget(CycleButton.onOffBuilder(HLConfig.hlMode)
			.create(x, y, WIDTH, 20, Component.translatable("hlmovement.settings.mode"), (button, value) -> HLModeClient.set(this.minecraft, value)));
		y += ROW;

		this.addRenderableWidget(CycleButton.<String>builder(character -> Component.translatable("hlmovement.character." + character), HLConfig.character)
			.withValues(HLMode.CHARACTERS)
			.create(x, y, WIDTH, 20, Component.translatable("hlmovement.settings.character"), (button, value) -> HLModeClient.setCharacter(this.minecraft, value)));
		y += ROW;

		this.addRenderableWidget(new Slider(x, y, WIDTH,
			() -> (HLConfig.hudScale - HUD_SCALE_MIN) / (HUD_SCALE_MAX - HUD_SCALE_MIN),
			value -> HLConfig.hudScale = Math.round(Mth.lerp(value, HUD_SCALE_MIN, HUD_SCALE_MAX) * 20.0) / 20.0,
			value -> Component.translatable("hlmovement.settings.hud_scale", String.format("%.2f", HLConfig.hudScale))));
		y = this.height / 2 + CENTER_GAP;
		this.bottomPanelTop = y;

		this.addRenderableWidget(new Slider(x, y, WIDTH,
			() -> (double) (HLConfig.crosshairSize - CROSSHAIR_MIN) / (CROSSHAIR_MAX - CROSSHAIR_MIN),
			value -> HLConfig.crosshairSize = (int) Math.round(Mth.lerp(value, CROSSHAIR_MIN, CROSSHAIR_MAX)),
			value -> Component.translatable("hlmovement.settings.crosshair_size", HLConfig.crosshairSize)));
		y += ROW;

		this.addRenderableWidget(CycleButton.onOffBuilder(HLConfig.showSpeedometer)
			.create(x, y, WIDTH, 20, Component.translatable("hlmovement.settings.speedometer"), (button, value) -> HLConfig.showSpeedometer = value));
		y += ROW;

		this.addRenderableWidget(Button.builder(Component.translatable("hlmovement.settings.reset"), button -> {
			HLConfig.hudScale = 1.0;
			HLConfig.crosshairSize = 32;
			HLConfig.showSpeedometer = false;
			this.rebuildWidgets();
		}).bounds(x, y, WIDTH / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
			.bounds(x + WIDTH / 2 + 2, y, WIDTH / 2 - 2, 20).build());
		this.bottomPanelBottom = y + 20;
	}

	/** Small panels behind the controls, with the HUD, and the crosshair between them, left visible. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int x0 = this.width / 2 - WIDTH / 2 - 8;
		int x1 = x0 + WIDTH + 16;
		graphics.fill(x0, this.topPanelTop - 8, x1, this.height / 2 - CENTER_GAP + 8, 0xA0000000);
		graphics.fill(x0, this.bottomPanelTop - 8, x1, this.bottomPanelBottom + 8, 0xA0000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, this.topPanelTop, 0xFFFFA000);
	}

	@Override
	public void removed() {
		HLConfig.save();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	/** A slider over 0..1 that reads and writes a setting through the given functions. */
	private static final class Slider extends AbstractSliderButton {
		private final DoubleConsumer apply;
		private final DoubleFunction<Component> label;

		Slider(int x, int y, int width, DoubleSupplier initial, DoubleConsumer apply, DoubleFunction<Component> label) {
			super(x, y, width, 20, Component.empty(), Mth.clamp(initial.getAsDouble(), 0.0, 1.0));
			this.apply = apply;
			this.label = label;
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			this.setMessage(this.label.apply(this.value));
		}

		@Override
		protected void applyValue() {
			this.apply.accept(this.value);
		}
	}
}
