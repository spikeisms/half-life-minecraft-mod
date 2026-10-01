package com.hlmovement.client.model;

import com.hlmovement.HLConfig;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Picks and plays a player's animations the way Half-Life's player code does
 * ({@code CBasePlayer::SetAnimation}, and {@code StudioEstimateGait}/{@code StudioProcessGait}/
 * {@code StudioSetupBones} for players):
 *
 * <ul>
 * <li>the body plays {@code idle}, or {@code crouch_idle} while ducked;
 * <li>the pelvis and legs are overridden by a <em>gait</em> sequence while moving: {@code run} above
 * 220 u/s, {@code walk} below, {@code crawl} while ducked. The gait advances with the distance covered
 * rather than with time, so the feet don't slide;
 * <li>the legs face the way the player is moving (the <em>gait yaw</em>) and run backwards when that's
 * more than 120 degrees from where they're looking, while the spine twists the torso and head back
 * round to the view, a quarter of the way per bone. Looking up and down bends the spine and head too;
 * <li>a jump plays {@code jump} once on the whole body and holds its last frame until landing.
 * </ul>
 *
 * Changes are cross-faded over {@link #BLEND_TIME}, like GoldSrc's sequence transition blend. An attack
 * swings the arm, which Half-Life does with per-weapon sequences instead.
 */
public final class PlayerAnimator {
	private static final float BLEND_TIME = 0.2F;
	/** HL: {@code speed > 220} picks ACT_RUN over ACT_WALK. */
	private static final float RUN_SPEED = 220.0F;
	/** Below this (u/s) the legs stand still. */
	private static final float MOVE_SPEED = 8.0F;
	/** HL flips the legs round and plays the gait backwards past this angle between view and movement. */
	private static final float BACKWARDS_ANGLE = 120.0F;
	/** Standing still, the legs are dragged round once the torso is twisted this far. */
	private static final float STANDING_TWIST = 60.0F;
	/** How fast the legs turn to a new direction (1/s, exponential); HL snaps, which looks abrupt up close. */
	private static final float TURN_RATE = 12.0F;
	/** Share of the view pitch taken by each spine bone, the neck and the head. */
	private static final float SPINE_PITCH = 0.12F, NECK_PITCH = 0.14F, HEAD_PITCH = 0.3F;
	private static final float SWING_ANGLE = 60.0F;

	private static final Map<Avatar, PlayerAnimator> STATES = new WeakHashMap<>();

	private float lastTime = Float.NaN;
	private boolean wasOnGround = true;
	private boolean wasRising;
	private boolean jumping;
	private @Nullable String body;
	private float bodyFrame;
	private @Nullable String gait;
	/** Position in the gait cycle, 0 to 1: kept across walk/run changes so the legs stay in step. */
	private float gaitPhase;
	/** Which way the legs face (Minecraft yaw, degrees). */
	private float gaitYaw = Float.NaN;
	private boolean backwards;
	private float @Nullable [] pose;
	private float @Nullable [] blendFrom;
	private float blendLeft;

	private PlayerAnimator() {}

	/** Advances this player's animation to now and returns the bone matrices to draw them with. */
	public static float[] update(HLModel model, Avatar entity, float partialTicks) {
		return STATES.computeIfAbsent(entity, e -> new PlayerAnimator()).advance(model, entity, partialTicks);
	}

	private float[] advance(HLModel model, Avatar entity, float partialTicks) {
		float time = entity.tickCount + partialTicks;
		float ticks = Float.isNaN(lastTime) ? 0.0F : Math.clamp(time - lastTime, 0.0F, 5.0F);
		float seconds = ticks / 20.0F;
		lastTime = time;

		double dx = entity.getX() - entity.xo, dz = entity.getZ() - entity.zo;
		float blocksPerTick = (float) Math.sqrt(dx * dx + dz * dz);
		float speed = blocksPerTick * 20.0F * (float) HLConfig.unitsPerBlock;
		boolean moving = speed >= MOVE_SPEED;
		boolean ducked = entity.getPose() == Pose.CROUCHING;

		// Only a jump starts the jump animation (walking off a ledge keeps the legs going), and it lasts
		// until something is underfoot again.
		boolean onGround = entity.onGround();
		boolean supported = onGround || entity.isInWater() || entity.onClimbable() || entity.isPassenger()
			|| entity instanceof Player player && player.getAbilities().flying;
		boolean rising = entity.getY() - entity.yo > 0.05;
		boolean jumped = false;
		if (supported) {
			jumping = false;
		} else if (rising && !wasRising && (wasOnGround || jumping)) {
			// Leaving the ground upwards, or (bunnyhopping) bouncing straight back up without a frame on it.
			jumping = true;
			jumped = true;
		}

		wasOnGround = onGround;
		wasRising = rising;

		float viewYaw = Mth.rotLerp(partialTicks, entity.yHeadRotO, entity.yHeadRot);
		float twist = turnLegs(viewYaw, moving, (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG), seconds);

		String wantBody = jumping ? "jump" : ducked ? "crouch_idle" : "idle";
		String wantGait = jumping || !moving ? null : ducked ? "crawl" : speed > RUN_SPEED ? "run" : "walk";
		if (model.sequence(wantBody) == null) wantBody = "idle";
		if (wantGait != null && model.sequence(wantGait) == null) wantGait = null;

		boolean changed = jumped || !wantBody.equals(body) || !Objects.equals(wantGait, gait);
		if (changed && pose != null) {
			blendFrom = pose;
			blendLeft = BLEND_TIME;
		}

		if (jumped || !wantBody.equals(body)) {
			body = wantBody;
			bodyFrame = 0.0F;
		}

		if (!Objects.equals(wantGait, gait)) {
			if (gait == null) gaitPhase = 0.0F;
			gait = wantGait;
		}

		float[] out = new float[model.poseSize()];
		HLModel.Sequence bodySequence = model.sequence(body);
		if (bodySequence == null) return model.matrices(out); // a model with no "idle": nothing sensible to show

		bodyFrame += seconds * bodySequence.fps();
		model.sample(bodySequence, bodyFrame, out, 0, model.bones());

		if (gait != null) {
			HLModel.Sequence gaitSequence = model.sequence(gait);
			float advance = gaitSequence.stride() > 0.0F
				? blocksPerTick * ticks / gaitSequence.stride()
				: seconds * gaitSequence.fps() / gaitSequence.length();
			gaitPhase += backwards ? -advance : advance;
			gaitPhase -= (float) Math.floor(gaitPhase);
			model.sample(gaitSequence, gaitPhase * gaitSequence.length(), out, 0, model.gaitBones());
		}

		if (blendFrom != null) {
			blendLeft -= seconds;
			if (blendLeft > 0.0F) {
				model.blend(blendFrom, out, 1.0F - blendLeft / BLEND_TIME, out);
			} else {
				blendFrom = null;
			}
		}

		pose = out;
		float[] bones = model.matrices(out);
		aim(model, bones, twist, entity.getXRot(partialTicks));
		swing(model, bones, entity, partialTicks, twist);

		// The renderer has already turned the whole model to the body yaw; the legs decide instead.
		float bodyYaw = Mth.rotLerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
		model.rotate(bones, 0, 0.0F, 1.0F, 0.0F, (bodyYaw - gaitYaw) * Mth.DEG_TO_RAD, 0.0F, 0.0F, 0.0F);
		return bones;
	}

	/**
	 * StudioEstimateGait: turns the legs towards the direction of travel, or towards the view when standing
	 * still. Returns how far the torso has to twist from the legs to face the view (degrees, Minecraft yaw).
	 */
	private float turnLegs(float viewYaw, boolean moving, float moveYaw, float seconds) {
		if (Float.isNaN(gaitYaw)) gaitYaw = viewYaw;

		if (moving) {
			float fromView = Math.abs(Mth.wrapDegrees(moveYaw - viewYaw));
			// A little slack so moving diagonally backwards doesn't flicker between the two.
			backwards = fromView > (backwards ? BACKWARDS_ANGLE - 10.0F : BACKWARDS_ANGLE + 10.0F);
			float target = backwards ? moveYaw + 180.0F : moveYaw;
			gaitYaw += Mth.wrapDegrees(target - gaitYaw) * Math.min(1.0F, seconds * TURN_RATE);
		} else {
			backwards = false;
			gaitYaw += Mth.wrapDegrees(viewYaw - gaitYaw) * Math.min(1.0F, seconds * 4.0F);
		}

		float limit = moving ? BACKWARDS_ANGLE + 10.0F : STANDING_TWIST;
		float twist = Mth.clamp(Mth.wrapDegrees(viewYaw - gaitYaw), -limit, limit);
		if (!moving) gaitYaw = viewYaw - twist;
		gaitYaw = Mth.wrapDegrees(gaitYaw);
		return twist;
	}

	/**
	 * Turns the upper body to the view. Yaw is Half-Life's four spine bone controllers, a quarter of the
	 * twist each; the scientist has three spine bones, so the head takes the last quarter. Half-Life
	 * leans the whole model by a third of the pitch; here the spine, neck and head bend instead.
	 */
	private static void aim(HLModel model, float[] bones, float twist, float pitch) {
		String[] chain = {"Bip Spine", "Bip Spine1", "Bip Spine2", "Bip Neck", "Bip Head"};
		float[] yawShare = {0.25F, 0.25F, 0.25F, 0.0F, 0.25F};
		float[] pitchShare = {SPINE_PITCH, SPINE_PITCH, SPINE_PITCH, NECK_PITCH, HEAD_PITCH};
		float turned = 0.0F;
		for (int i = 0; i < chain.length; i++) {
			int bone = model.bone(chain[i]);
			// Minecraft yaw runs clockwise seen from above, and positive pitch looks down.
			model.rotate(bones, bone, 0.0F, 1.0F, 0.0F, -twist * yawShare[i] * Mth.DEG_TO_RAD);
			turned += twist * yawShare[i];
			float right = -turned * Mth.DEG_TO_RAD;
			model.rotate(bones, bone, Mth.cos(right), 0.0F, -Mth.sin(right), -pitch * pitchShare[i] * Mth.DEG_TO_RAD);
		}
	}

	/** An attack or item use swings the arm down and back, from the shoulder. */
	private static void swing(HLModel model, float[] bones, Avatar entity, float partialTicks, float twist) {
		LivingEntity.SwingDescription current = entity.getCurrentSwing();
		float progress = entity.getSwingAnimation(partialTicks);
		if (current == null || progress <= 0.0F || progress >= 1.0F) return;

		boolean rightArm = (current.hand() == InteractionHand.MAIN_HAND) == (entity.getMainArm() == HumanoidArm.RIGHT);
		float right = -twist * Mth.DEG_TO_RAD;
		float angle = SWING_ANGLE * Mth.sin(progress * Mth.PI) * Mth.DEG_TO_RAD;
		model.rotate(bones, model.bone(rightArm ? "Bip R Arm1" : "Bip L Arm1"), Mth.cos(right), 0.0F, -Mth.sin(right), -angle);
	}
}
