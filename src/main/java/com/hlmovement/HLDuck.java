package com.hlmovement;

import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityAttachments;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Half-Life ducking (PM_Duck / PM_UnDuck), replacing Minecraft's instant sneak pose:
 * - on the ground, ducking takes TIME_TO_DUCK (0.4 s) before the duck hull and speed penalty apply;
 * - in the air it's instant and the hull shrinks around its center, so the feet come up
 *   (the crouch-jump), and un-ducking in the air drops the feet back down;
 * - un-ducking is refused while there's no room.
 */
public final class HLDuck {
	/** VEC_DUCK_HULL: 36 of the 72 unit standing hull. Eyes at VEC_DUCK_VIEW (12 above the hull center). */
	public static final EntityDimensions DUCK_HULL = EntityDimensions.scalable(0.6F, 0.9F)
		.withEyeHeight(0.75F)
		.withAttachments(EntityAttachments.builder().attach(EntityAttachment.VEHICLE, Avatar.DEFAULT_VEHICLE_ATTACHMENT));

	private static final int TICK_MS = 50;

	public static final class State {
		int duckTimeMs;
		boolean inDuck;

		public void reset() {
			this.duckTimeMs = 0;
			this.inDuck = false;
		}
	}

	/** Implemented on Player by mixin. */
	public interface Holder {
		State hlmovement$duckState();
	}

	private HLDuck() {
	}

	/**
	 * Runs in place of Player.updatePlayerPose. Returns false to let vanilla pick the pose.
	 */
	public static boolean updatePose(Player player) {
		if (!HLMode.isOn(player)) {
			((Holder) player).hlmovement$duckState().reset();
			return false;
		}

		boolean clientSide = player.level().isClientSide();
		boolean authoritative = player.isLocalInstanceAuthoritative();
		if (clientSide && !authoritative) {
			// Other players: keep the pose the server sent instead of re-deriving it.
			return true;
		}

		State s = ((Holder) player).hlmovement$duckState();
		if (player.isSleeping() || player.isSwimming() || player.isFallFlying() || player.isAutoSpinAttack() || player.getAbilities().flying
			|| player.isPassenger() || player.isSpectator() || !player.isAlive() || !fits(player, Pose.SWIMMING, 0.0)) {
			s.reset();
			return false;
		}

		// PM_ReduceTimers
		s.duckTimeMs = Math.max(0, s.duckTimeMs - TICK_MS);

		boolean ducked = player.getPose() == Pose.CROUCHING;
		boolean onGround = player.onGround();
		boolean duckKey = player.isShiftKeyDown();
		double halfDiff = (player.getDimensions(Pose.STANDING).height() - player.getDimensions(Pose.CROUCHING).height()) * 0.5;
		boolean tuck = HLConfig.airDuckTuck && halfDiff > 0.0;
		Pose pose = ducked ? Pose.CROUCHING : Pose.STANDING;

		if (duckKey) {
			if (!ducked && !s.inDuck) {
				s.duckTimeMs = 1000;
				s.inDuck = true;
			}

			// Finish ducking once TIME_TO_DUCK has passed, or immediately in the air.
			if (s.inDuck && (s.duckTimeMs <= 1000 - (int) (HLConfig.duckTime * 1000.0) || !onGround)) {
				s.inDuck = false;
				pose = Pose.CROUCHING;
				if (!onGround && tuck && authoritative && fits(player, Pose.CROUCHING, halfDiff)) {
					// Hull shrinks around its center: the feet come up.
					move(player, halfDiff);
				}
			}
		} else {
			s.inDuck = false;
			if (ducked) {
				if (onGround || !tuck) {
					if (fits(player, Pose.STANDING, 0.0)) {
						pose = Pose.STANDING;
					}
				} else if (fits(player, Pose.STANDING, -halfDiff)) {
					// In the air the feet drop back down (the server just follows the client's position).
					pose = Pose.STANDING;
					if (authoritative) {
						move(player, -halfDiff);
					}
				} else if (fits(player, Pose.STANDING, 0.0)) {
					pose = Pose.STANDING;
				}
			}
		}

		// Squeezed by the world: fall back like vanilla does.
		if (pose == Pose.STANDING && !fits(player, Pose.STANDING, 0.0)) {
			pose = Pose.CROUCHING;
		}

		if (pose == Pose.CROUCHING && !fits(player, Pose.CROUCHING, 0.0)) {
			pose = Pose.SWIMMING;
		}

		player.setPose(pose);
		return true;
	}

	private static boolean fits(Player player, Pose pose, double dy) {
		AABB box = player.getDimensions(pose).makeBoundingBox(player.position().add(0.0, dy, 0.0)).deflate(1.0E-7);
		return player.level().noCollision(player, box);
	}

	private static void move(Player player, double dy) {
		Vec3 pos = player.position();
		player.setPos(pos.x, pos.y + dy, pos.z);
	}
}
