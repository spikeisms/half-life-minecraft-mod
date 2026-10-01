package com.hlmovement.client.model;

import com.hlmovement.HLMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Draws players in Half-Life mode as the character they chose (HLMode.CHARACTERS). */
public final class PlayerModels {
	/** From the hand bone (the wrist) along the hand to the middle of the grip, in blocks. */
	private static final float GRIP = 0.09F;
	/** How far down Minecraft's arm (from the shoulder) a held item sits: ItemInHandLayer's 10 pixels. */
	private static final float VANILLA_ARM_TO_ITEM = 0.625F;

	private PlayerModels() {}

	/** A player's Half-Life model and its animated bone matrices this frame. */
	public record Posed(HLModel model, float[] bones) {}

	/** Carries {@link Posed} on a player's render state (see AvatarRenderStateMixin). */
	public interface PoseHolder {
		@Nullable Posed hlmovement$posed();

		void hlmovement$setPosed(@Nullable Posed posed);
	}

	/** Null for anyone drawn as their normal Minecraft self. */
	public static @Nullable Posed posed(AvatarRenderState state) {
		return ((PoseHolder) state).hlmovement$posed();
	}

	/** Called while the renderer copies a player's state for this frame. */
	public static void extract(Avatar entity, AvatarRenderState state, float partialTicks) {
		HLModel model = entity instanceof Player player && HLMode.isOn(player) ? HLModel.get(HLMode.character(player)) : null;
		((PoseHolder) state).hlmovement$setPosed(model == null ? null : new Posed(model, PlayerAnimator.update(model, entity, partialTicks)));
	}

	/** Draws what the player is holding, in the Half-Life model's hands. */
	public static void submitHeldItems(AvatarRenderState state, HLModel model, float[] bones, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		submitHeldItem(state, state.rightHandItemState, model.bone("Bip R Hand"), false, bones, poseStack, collector, light);
		submitHeldItem(state, state.leftHandItemState, model.bone("Bip L Hand"), true, bones, poseStack, collector, light);
	}

	/**
	 * Items are modelled to sit in Minecraft's arm (ItemInHandLayer), so this stands a Minecraft arm in for
	 * the Half-Life hand: pointing along the hand bone, with the item's "forward" on the thumb side.
	 * A biped hand bone's X axis runs along the hand; its Y and Z are mirrored between left and right.
	 */
	private static void submitHeldItem(AvatarRenderState state, ItemStackRenderState item, int bone, boolean left, float[] m, PoseStack poseStack,
		SubmitNodeCollector collector, int light) {
		if (item.isEmpty() || bone < 0) return;
		int o = bone * 12;
		float side = left ? -1.0F : 1.0F;
		float along = GRIP - VANILLA_ARM_TO_ITEM;
		Matrix4f arm = new Matrix4f(
			side * m[o + 1], side * m[o + 5], side * m[o + 9], 0.0F,
			m[o], m[o + 4], m[o + 8], 0.0F,
			-side * m[o + 2], -side * m[o + 6], -side * m[o + 10], 0.0F,
			m[o + 3] + m[o] * along, m[o + 7] + m[o + 4] * along, m[o + 11] + m[o + 8] * along, 1.0F);

		poseStack.pushPose();
		poseStack.mulPose(arm);
		// From here on, exactly what ItemInHandLayer does inside the arm.
		poseStack.rotateDegrees(Axis.XP, -90.0F);
		poseStack.rotateDegrees(Axis.YP, 180.0F);
		poseStack.translate(side / 16.0F, 2.0F / 16.0F, -10.0F / 16.0F);
		item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		poseStack.popPose();
	}
}