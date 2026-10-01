package com.hlmovement.client;

import com.hlmovement.HLConfig;
import com.hlmovement.HLMode;
import com.hlmovement.client.mixin.EntityAccessor;
import com.hlmovement.client.mixin.LivingEntityAccessor;
import com.hlmovement.client.mixin.LocalPlayerAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * A port of the player movement code from Half-Life 1's pm_shared.c (PM_PlayerMove and friends),
 * driving the local player in place of Minecraft's travel().
 *
 * Velocity is kept in GoldSrc units per second and converted to/from Minecraft's
 * blocks-per-tick deltaMovement at the tick boundaries, so knockback, explosions,
 * bubble columns etc. still feed into it. Each 50ms tick is split into several
 * sub-steps (100 fps by default) so that acceleration, friction and air-strafing
 * behave like they do at Half-Life framerates; view yaw is interpolated across the
 * sub-steps so mouse-driven air-strafing keeps working.
 */
public final class HLPhysics {
	private static final double TICK_SECONDS = 0.05;
	/** Tiny downward probe so Minecraft keeps reporting onGround while we walk (HL traces 2 units down). */
	private static final double GROUND_PROBE_BLOCKS = 1.0E-3;
	/** PM_CatagorizePosition: moving up faster than this means we are not on the ground. */
	private static final double MAX_GROUND_UP_SPEED = 180.0;
	private static final double WATER_JUMP_TIME = 2.0;
	private static final double WATER_JUMP_PUSH = 50.0;
	/** Vanilla base jump strength; used to scale jump height with jump boost / honey etc. */
	private static final double VANILLA_JUMP_POWER = 0.42;
	private static final double VANILLA_GRAVITY = 0.08;

	// State of the (single) local player between ticks.
	private static boolean oldJump;
	private static boolean hasLastYaw;
	private static float lastYaw;
	private static double waterJumpTime;
	private static double waterJumpDirX;
	private static double waterJumpDirZ;

	private HLPhysics() {
	}

	/** Mutable velocity in GoldSrc units/sec, Minecraft axes (y is up). */
	private static final class Vel {
		double x;
		double y;
		double z;

		Vel(double x, double y, double z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		double length() {
			return Math.sqrt(x * x + y * y + z * z);
		}

		void scale(double f) {
			x *= f;
			y *= f;
			z *= f;
		}
	}

	/** Everything that is constant for one tick. */
	private record TickInput(
		double forwardMove,
		double sideMove,
		double upMove,
		double ladderForward,
		double ladderSide,
		boolean jump,
		boolean ducked,
		double maxSpeed,
		double gravity,
		double jumpSpeed,
		double dt
	) {
	}

	/** One PM_PlayerMove frame, given the interpolated view vectors for that sub-step. */
	private interface Frame {
		void run(double fx, double fz, double rx, double rz, Vec3 look);
	}

	/** The player's usercmd: forward/side/up in units per second, with +speed applied (CL_AdjustSpeed). */
	private record Command(double forward, double side, double up, double ladderForward, double ladderSide) {
	}

	/** Spectator velocity lives here: vanilla adds its own vertical flight impulse to deltaMovement. */
	private static final Vel spectatorVel = new Vel(0.0, 0.0, 0.0);

	/**
	 * While the local player is in Half-Life mode, everything they do is driven by this class, except the
	 * states Half-Life has no equivalent for (elytra, riding, sleeping, riptide, levitation while walking).
	 */
	public static boolean isControlling(Object entity) {
		if (!(entity instanceof LocalPlayer player)) {
			return false;
		}

		boolean flying = player.getAbilities().flying || player.isSpectator();
		boolean controlling = HLMode.isOn(player)
			&& ((LocalPlayerAccessor) player).hlmovement$isControlledCamera()
			&& player.isAlive()
			&& !player.isFallFlying()
			&& !player.isPassenger()
			&& !player.isSleeping()
			&& !player.isAutoSpinAttack()
			&& !player.isSwimming()
			&& (flying || !player.hasEffect(MobEffects.LEVITATION));
		if (!controlling) {
			hasLastYaw = false;
			waterJumpTime = 0.0;
		}

		return controlling;
	}

	public static void travel(LocalPlayer player) {
		double upb = HLConfig.unitsPerBlock;
		double toUps = upb / TICK_SECONDS;
		float yaw = player.getYRot();
		Vel vel;

		if (player.isSpectator()) {
			vel = spectatorVel;
			Command cmd = command(player);
			runFrames(player, HLConfig.substeps, (fx, fz, rx, rz, look) -> spectatorMove(player, vel, cmd, rx, rz, look, TICK_SECONDS / HLConfig.substeps));
		} else if (player.getAbilities().flying) {
			vel = new Vel(0.0, 0.0, 0.0);
			spectatorVel.scale(0.0);
			noclipMove(player, command(player));
		} else {
			spectatorVel.scale(0.0);
			if (!player.level().hasChunkAt(player.getBlockPosBelowThatAffectsMyMovement())) {
				// Don't fall through the world while chunks load.
				player.setDeltaMovement(Vec3.ZERO);
				return;
			}

			Vec3 delta = player.getDeltaMovement();
			vel = new Vel(delta.x * toUps, delta.y * toUps, delta.z * toUps);

			boolean stuck = ((EntityAccessor) player).hlmovement$getStuckSpeedMultiplier().lengthSqr() > 1.0E-7;
			int steps = stuck ? 1 : HLConfig.substeps;
			TickInput in = readInput(player, TICK_SECONDS / steps);
			runFrames(player, steps, (fx, fz, rx, rz, look) -> playerMove(player, vel, in, fx, fz, rx, rz, look));

			if (stuck) {
				// Cobwebs, berry bushes, powder snow: Minecraft kills momentum every tick.
				vel.x = vel.y = vel.z = 0.0;
			}
		}

		if (player.isRemoved()) {
			return;
		}

		player.setDeltaMovement(vel.x / toUps, vel.y / toUps, vel.z / toUps);
		lastYaw = yaw;
		hasLastYaw = true;

		if (HLConfig.showSpeedometer) {
			long speed = Math.round(Math.sqrt(vel.x * vel.x + vel.z * vel.z));
			player.sendOverlayMessage(Component.literal(speed + " u/s"));
		}
	}

	/** Splits the tick into frames, interpolating the view yaw so mouse movement acts like it does at high framerates. */
	private static void runFrames(LocalPlayer player, int steps, Frame frame) {
		float yaw = player.getYRot();
		float startYaw = hasLastYaw ? lastYaw : yaw;
		double pitch = player.getXRot() * Mth.DEG_TO_RAD;

		for (int i = 0; i < steps; i++) {
			double yawRad = Mth.rotLerp((i + 1.0F) / steps, startYaw, yaw) * Mth.DEG_TO_RAD;
			// Flat forward / right, and the 3D view forward (MC pitch is positive looking down).
			double fx = -Math.sin(yawRad);
			double fz = Math.cos(yawRad);
			double rx = -fz;
			double rz = fx;
			Vec3 look = new Vec3(fx * Math.cos(pitch), -Math.sin(pitch), fz * Math.cos(pitch));
			frame.run(fx, fz, rx, rz, look);
			if (player.isRemoved()) {
				return;
			}
		}
	}

	private static Command command(LocalPlayer player) {
		Input keys = player.input.keyPresses;
		double forward;
		double side;
		double ladderForward;
		double ladderSide;
		if (keys.forward() || keys.backward() || keys.left() || keys.right()) {
			forward = (keys.forward() ? HLConfig.forwardSpeed : 0.0) - (keys.backward() ? HLConfig.backSpeed : 0.0);
			side = (keys.right() ? HLConfig.sideSpeed : 0.0) - (keys.left() ? HLConfig.sideSpeed : 0.0);
			ladderForward = (keys.forward() ? 1.0 : 0.0) - (keys.backward() ? 1.0 : 0.0);
			ladderSide = (keys.right() ? 1.0 : 0.0) - (keys.left() ? 1.0 : 0.0);
		} else {
			// Analog input (controller mods): x is left, y is forward.
			Vec2 move = player.input.getMoveVector();
			forward = move.y * (move.y >= 0 ? HLConfig.forwardSpeed : HLConfig.backSpeed);
			side = -move.x * HLConfig.sideSpeed;
			ladderForward = move.y;
			ladderSide = -move.x;
		}

		// +moveup / +movedown: jump and sneak.
		double up = (keys.jump() ? HLConfig.forwardSpeed : 0.0) - (keys.shift() ? HLConfig.forwardSpeed : 0.0);

		// +speed is applied on the client before anything else (cl_movespeedkey).
		if (HLMovementClient.WALK.isDown()) {
			forward *= HLConfig.walkMultiplier;
			side *= HLConfig.walkMultiplier;
			up *= HLConfig.walkMultiplier;
		}

		return new Command(forward, side, up, ladderForward, ladderSide);
	}

	/** Minecraft's flying speed (spectators change it with the scroll wheel) relative to the default. */
	private static double flySpeedScale(LocalPlayer player) {
		return player.getAbilities().getFlyingSpeed() / 0.05;
	}

	/** PM_NoClip: straight along the view at the commanded speed, no acceleration, friction, gravity or collision. */
	private static void noclipMove(LocalPlayer player, Command cmd) {
		double scale = flySpeedScale(player);
		double forward = cmd.forward() * scale;
		double side = cmd.side() * scale;
		double up = cmd.up() * scale;

		// PM_CheckParamters clamps the whole command to maxspeed.
		double maxSpeed = HLConfig.maxSpeed * scale;
		double commandSpeed = Math.sqrt(forward * forward + side * side + up * up);
		if (commandSpeed > maxSpeed) {
			double ratio = maxSpeed / commandSpeed;
			forward *= ratio;
			side *= ratio;
			up *= ratio;
		}

		double yawRad = player.getYRot() * Mth.DEG_TO_RAD;
		double pitch = player.getXRot() * Mth.DEG_TO_RAD;
		double fx = -Math.sin(yawRad);
		double fz = Math.cos(yawRad);
		Vec3 look = new Vec3(fx * Math.cos(pitch), -Math.sin(pitch), fz * Math.cos(pitch));
		double wx = look.x * forward - fz * side;
		double wy = look.y * forward + up;
		double wz = look.z * forward + fx * side;

		player.noPhysics = true;
		player.setOnGround(false);
		double blocks = TICK_SECONDS / HLConfig.unitsPerBlock;
		player.move(MoverType.SELF, new Vec3(wx * blocks, wy * blocks, wz * blocks));
	}

	/** PM_SpectatorMove (roaming): accelerates up to sv_spectatormaxspeed, extra friction, no collision. */
	private static void spectatorMove(LocalPlayer player, Vel vel, Command cmd, double rx, double rz, Vec3 look, double dt) {
		double speed = vel.length();
		if (speed < 1.0) {
			vel.scale(0.0);
		} else {
			double friction = HLConfig.friction * 1.5;
			double control = Math.max(speed, HLConfig.stopSpeed);
			double newSpeed = Math.max(0.0, speed - control * friction * dt);
			vel.scale(newSpeed / speed);
		}

		double scale = flySpeedScale(player);
		double wx = (look.x * cmd.forward() + rx * cmd.side()) * scale;
		double wy = (look.y * cmd.forward() + cmd.up()) * scale;
		double wz = (look.z * cmd.forward() + rz * cmd.side()) * scale;
		double wishSpeed = Math.sqrt(wx * wx + wy * wy + wz * wz);
		if (wishSpeed > 0.0) {
			wx /= wishSpeed;
			wy /= wishSpeed;
			wz /= wishSpeed;
		}

		wishSpeed = Math.min(wishSpeed, HLConfig.spectatorMaxSpeed * scale);

		double currentSpeed = vel.x * wx + vel.y * wy + vel.z * wz;
		double addSpeed = wishSpeed - currentSpeed;
		if (addSpeed <= 0.0) {
			// Like Half-Life, no position update this frame: letting go of the keys stops you dead.
			return;
		}

		double accelSpeed = Math.min(HLConfig.accelerate * dt * wishSpeed, addSpeed);
		vel.x += accelSpeed * wx;
		vel.y += accelSpeed * wy;
		vel.z += accelSpeed * wz;

		double blocks = dt / HLConfig.unitsPerBlock;
		player.move(MoverType.SELF, new Vec3(vel.x * blocks, vel.y * blocks, vel.z * blocks));
	}

	private static TickInput readInput(LocalPlayer player, double dt) {
		Input keys = player.input.keyPresses;
		Command cmd = command(player);
		double forward = cmd.forward();
		double side = cmd.side();
		double ladderForward = cmd.ladderForward();
		double ladderSide = cmd.ladderSide();

		// +movedown while swimming.
		double waterHeight = Math.max(player.getFluidHeight(FluidTags.WATER), player.getFluidHeight(FluidTags.LAVA));
		double up = HLConfig.sneakSwimsDown && keys.shift() && waterHeight >= player.getBbHeight() * 0.5 ? -HLConfig.forwardSpeed : 0.0;

		double speedAttr = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		double speedBase = player.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
		double speedRatio = speedBase > 0.0 ? speedAttr / speedBase : 1.0;
		double maxSpeed = HLConfig.maxSpeed * speedRatio * ((EntityAccessor) player).hlmovement$getBlockSpeedFactor();

		// PM_CheckParamters: clamp the whole move command to maxspeed...
		double commandSpeed = Math.sqrt(forward * forward + side * side + up * up);
		if (commandSpeed > maxSpeed && commandSpeed > 0.0) {
			double ratio = maxSpeed / commandSpeed;
			forward *= ratio;
			side *= ratio;
			up *= ratio;
		}

		// ...and only then PM_Duck scales it (so ducking is 320 * 0.333 = 106.6 u/s).
		boolean ducked = player.getPose() == Pose.CROUCHING;
		if (ducked) {
			forward *= HLConfig.duckMultiplier;
			side *= HLConfig.duckMultiplier;
			up *= HLConfig.duckMultiplier;
		}

		if (HLConfig.itemUseSlowdown && player.isUsingItem()) {
			float mul = ((LocalPlayerAccessor) player).hlmovement$itemUseSpeedMultiplier();
			forward *= mul;
			side *= mul;
		}

		double gravity = HLConfig.gravity * (player.getAttributeValue(Attributes.GRAVITY) / VANILLA_GRAVITY);
		double jumpScale = ((LivingEntityAccessor) player).hlmovement$getJumpPower() / VANILLA_JUMP_POWER;
		double jumpSpeed = Math.sqrt(2.0 * gravity * HLConfig.jumpHeight) * jumpScale;

		return new TickInput(forward, side, up, ladderForward, ladderSide, keys.jump(), ducked, maxSpeed, gravity, jumpSpeed, dt);
	}

	// ------------------------------------------------------------------------------------------
	// PM_PlayerMove
	// ------------------------------------------------------------------------------------------

	private static void playerMove(LocalPlayer player, Vel vel, TickInput in, double fx, double fz, double rx, double rz, Vec3 look) {
		double dt = in.dt();
		boolean onGround = player.onGround() && vel.y <= MAX_GROUND_UP_SPEED;
		if (onGround) {
			// PM_CatagorizePosition: landing ends a water jump.
			waterJumpTime = 0.0;
		}

		if (!in.jump()) {
			oldJump = false;
		}

		if (player.onClimbable()) {
			ladderMove(player, vel, in, onGround, fx, fz, rx, rz, look);
			return;
		}

		boolean lava = player.isInLava();
		int waterLevel = waterLevel(player, lava ? FluidTags.LAVA : FluidTags.WATER);

		// Leaping out of the water.
		if (waterJumpTime > 0.0) {
			waterJumpTime -= dt;
			if (waterJumpTime <= 0.0 || waterLevel == 0) {
				waterJumpTime = 0.0;
			}

			vel.x = waterJumpDirX;
			vel.z = waterJumpDirZ;
			checkVelocity(vel);
			move(player, vel, dt, false, false);
			return;
		}

		double gravity = gravityFor(player, vel, in);

		if (waterLevel >= 2) {
			if (waterLevel == 2) {
				checkWaterJump(player, vel, fx, fz);
			}

			if (vel.y < 0.0 && waterJumpTime > 0.0) {
				waterJumpTime = 0.0;
			}

			if (in.jump() && waterJumpTime <= 0.0) {
				vel.y = lava ? HLConfig.lavaSwimUpSpeed : HLConfig.waterSwimUpSpeed;
			}

			waterMove(vel, in, rx, rz, look);
			checkVelocity(vel);
			move(player, vel, dt, false, false);
			return;
		}

		// PM_AddCorrectGravity: first half of this frame's gravity.
		vel.y -= gravity * 0.5 * dt;

		boolean jumped = false;
		if (in.jump()) {
			if (!onGround) {
				// Holding jump in the air: must release before we can jump again.
				oldJump = true;
			} else if (!oldJump || HLConfig.autoBhop) {
				if (HLConfig.bunnyhopCap) {
					preventMegaBunnyJumping(vel, in.maxSpeed());
				}

				vel.y = in.jumpSpeed();
				vel.y -= gravity * 0.5 * dt; // PM_FixupGravityVelocity inside PM_Jump
				onGround = false;
				jumped = true;
				oldJump = true;
			}
		}

		double surfaceFriction = 1.0;
		if (onGround) {
			vel.y = 0.0;
			surfaceFriction = surfaceFriction(player);
			friction(player, vel, dt, surfaceFriction);
		}

		checkVelocity(vel);

		if (onGround) {
			walkMove(vel, in, fx, fz, rx, rz, surfaceFriction);
		} else {
			airMove(vel, in, fx, fz, rx, rz);
		}

		move(player, vel, dt, onGround, onGround && !jumped);

		// PM_FixupGravityVelocity: second half of the gravity.
		vel.y -= gravity * 0.5 * dt;
		checkVelocity(vel);

		if (player.onGround() && vel.y <= MAX_GROUND_UP_SPEED) {
			vel.y = 0.0;
		}
	}

	private static double gravityFor(LocalPlayer player, Vel vel, TickInput in) {
		if (vel.y <= 0.0 && player.hasEffect(MobEffects.SLOW_FALLING)) {
			// Vanilla caps gravity at 0.01 of 0.08 while slow falling.
			return in.gravity() * 0.125;
		}

		return in.gravity();
	}

	private static int waterLevel(LocalPlayer player, TagKey<Fluid> fluid) {
		double height = player.getFluidHeight(fluid);
		if (height <= 0.0) {
			return 0;
		} else if (player.isEyeInFluid(fluid)) {
			return 3;
		} else if (height >= player.getBbHeight() * 0.5) {
			return 2;
		}

		return 1;
	}

	// ------------------------------------------------------------------------------------------
	// Ground / air
	// ------------------------------------------------------------------------------------------

	/** PM_Friction, including sv_edgefriction when about to walk off a drop. */
	private static void friction(LocalPlayer player, Vel vel, double dt, double surfaceFriction) {
		double speed = vel.length();
		if (speed < 0.1) {
			return;
		}

		double friction = HLConfig.friction * surfaceFriction;

		// Trace the hull 16 units ahead, starting with its origin at our feet and going 34 units down.
		double upb = HLConfig.unitsPerBlock;
		double halfHeight = player.getBbHeight() * 0.5;
		AABB probe = player.getBoundingBox()
			.move(vel.x / speed * 16.0 / upb, -halfHeight, vel.z / speed * 16.0 / upb)
			.expandTowards(0.0, -34.0 / upb, 0.0);
		if (player.level().noCollision(player, probe)) {
			friction *= HLConfig.edgeFriction;
		}

		double control = Math.max(speed, HLConfig.stopSpeed);
		double newSpeed = Math.max(0.0, speed - control * friction * dt);
		vel.scale(newSpeed / speed);
	}

	/** Minecraft's block slipperiness mapped onto Half-Life's per-entity friction (1 = normal ground). */
	private static double surfaceFriction(LocalPlayer player) {
		if (!HLConfig.blockFriction) {
			return 1.0;
		}

		BlockState below = player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement());
		double blockFriction = below.getBlock().getFriction();
		return Mth.clamp((1.0 - blockFriction) / (1.0 - 0.6), 0.0, 5.0);
	}

	private static void walkMove(Vel vel, TickInput in, double fx, double fz, double rx, double rz, double surfaceFriction) {
		double wx = fx * in.forwardMove() + rx * in.sideMove();
		double wz = fz * in.forwardMove() + rz * in.sideMove();
		double wishSpeed = Math.sqrt(wx * wx + wz * wz);
		if (wishSpeed > 0.0) {
			wx /= wishSpeed;
			wz /= wishSpeed;
		}

		wishSpeed = Math.min(wishSpeed, in.maxSpeed());

		vel.y = 0.0;
		accelerate(vel, wx, wz, wishSpeed, HLConfig.accelerate, in.dt(), surfaceFriction);
		vel.y = 0.0;

		if (Math.sqrt(vel.x * vel.x + vel.z * vel.z) < 1.0) {
			vel.x = 0.0;
			vel.z = 0.0;
		}
	}

	private static void airMove(Vel vel, TickInput in, double fx, double fz, double rx, double rz) {
		double wx = fx * in.forwardMove() + rx * in.sideMove();
		double wz = fz * in.forwardMove() + rz * in.sideMove();
		double wishSpeed = Math.sqrt(wx * wx + wz * wz);
		if (wishSpeed > 0.0) {
			wx /= wishSpeed;
			wz /= wishSpeed;
		}

		wishSpeed = Math.min(wishSpeed, in.maxSpeed());
		airAccelerate(vel, wx, wz, wishSpeed, HLConfig.airAccelerate, in.dt());
	}

	/** PM_Accelerate */
	private static void accelerate(Vel vel, double wx, double wz, double wishSpeed, double accel, double dt, double surfaceFriction) {
		double currentSpeed = vel.x * wx + vel.z * wz;
		double addSpeed = wishSpeed - currentSpeed;
		if (addSpeed <= 0.0) {
			return;
		}

		double accelSpeed = Math.min(accel * dt * wishSpeed * surfaceFriction, addSpeed);
		vel.x += accelSpeed * wx;
		vel.z += accelSpeed * wz;
	}

	/** PM_AirAccelerate: the 30 u/s wishspeed cap is what makes air-strafing work. */
	private static void airAccelerate(Vel vel, double wx, double wz, double wishSpeed, double accel, double dt) {
		double cappedWish = Math.min(wishSpeed, HLConfig.airSpeedCap);
		double currentSpeed = vel.x * wx + vel.z * wz;
		double addSpeed = cappedWish - currentSpeed;
		if (addSpeed <= 0.0) {
			return;
		}

		double accelSpeed = Math.min(accel * wishSpeed * dt, addSpeed);
		vel.x += accelSpeed * wx;
		vel.z += accelSpeed * wz;
	}

	/** PM_PreventMegaBunnyJumping */
	private static void preventMegaBunnyJumping(Vel vel, double maxSpeed) {
		double maxScaledSpeed = 1.7 * maxSpeed;
		if (maxScaledSpeed <= 0.0) {
			return;
		}

		double speed = vel.length();
		if (speed <= maxScaledSpeed) {
			return;
		}

		vel.scale(maxScaledSpeed / speed * 0.65);
	}

	/** PM_CheckVelocity */
	private static void checkVelocity(Vel vel) {
		double max = HLConfig.maxVelocity;
		vel.x = Double.isNaN(vel.x) ? 0.0 : Mth.clamp(vel.x, -max, max);
		vel.y = Double.isNaN(vel.y) ? 0.0 : Mth.clamp(vel.y, -max, max);
		vel.z = Double.isNaN(vel.z) ? 0.0 : Mth.clamp(vel.z, -max, max);
	}

	// ------------------------------------------------------------------------------------------
	// Water
	// ------------------------------------------------------------------------------------------

	/** PM_WaterMove */
	private static void waterMove(Vel vel, TickInput in, double rx, double rz, Vec3 look) {
		double dt = in.dt();
		double upMove = in.upMove();

		double wx = look.x * in.forwardMove() + rx * in.sideMove();
		double wy = look.y * in.forwardMove();
		double wz = look.z * in.forwardMove() + rz * in.sideMove();
		if (in.forwardMove() == 0.0 && in.sideMove() == 0.0 && upMove == 0.0) {
			wy -= HLConfig.waterSinkSpeed;
		} else {
			wy += upMove;
		}

		double wishSpeed = Math.sqrt(wx * wx + wy * wy + wz * wz);
		if (wishSpeed > in.maxSpeed()) {
			double f = in.maxSpeed() / wishSpeed;
			wx *= f;
			wy *= f;
			wz *= f;
			wishSpeed = in.maxSpeed();
		}

		wishSpeed *= HLConfig.waterSpeedMultiplier;

		double speed = vel.length();
		double newSpeed = 0.0;
		if (speed > 0.0) {
			newSpeed = Math.max(0.0, speed - dt * speed * HLConfig.friction);
			vel.scale(newSpeed / speed);
		}

		if (wishSpeed < 0.1) {
			return;
		}

		double addSpeed = wishSpeed - newSpeed;
		if (addSpeed > 0.0) {
			double len = Math.sqrt(wx * wx + wy * wy + wz * wz);
			double accelSpeed = Math.min(HLConfig.accelerate * wishSpeed * dt, addSpeed);
			vel.x += accelSpeed * wx / len;
			vel.y += accelSpeed * wy / len;
			vel.z += accelSpeed * wz / len;
		}
	}

	/** PM_CheckWaterJump: pop out of the water when swimming at a ledge with free space above it. */
	private static void checkWaterJump(LocalPlayer player, Vel vel, double fx, double fz) {
		if (waterJumpTime > 0.0 || vel.y < -MAX_GROUND_UP_SPEED) {
			return;
		}

		// Backing into the ledge? Don't pop forward.
		if ((vel.x != 0.0 || vel.z != 0.0) && vel.x * fx + vel.z * fz < 0.0) {
			return;
		}

		double reach = 24.0 / HLConfig.unitsPerBlock;
		Vec3 start = new Vec3(player.getX(), player.getY() + player.getBbHeight() * 0.5, player.getZ());
		BlockHitResult wall = player.level().clip(new ClipContext(start, start.add(fx * reach, 0.0, fz * reach), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (wall.getType() != HitResult.Type.BLOCK || wall.getDirection().getAxis() == Direction.Axis.Y) {
			return;
		}

		Vec3 top = new Vec3(start.x, player.getY() + player.getBbHeight(), start.z);
		BlockHitResult above = player.level().clip(new ClipContext(top, top.add(fx * reach, 0.0, fz * reach), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (above.getType() == HitResult.Type.MISS) {
			Direction normal = wall.getDirection();
			waterJumpDirX = -normal.getStepX() * WATER_JUMP_PUSH;
			waterJumpDirZ = -normal.getStepZ() * WATER_JUMP_PUSH;
			waterJumpTime = WATER_JUMP_TIME;
			vel.y = HLConfig.waterJumpSpeed;
			oldJump = true;
		}
	}

	// ------------------------------------------------------------------------------------------
	// Ladders
	// ------------------------------------------------------------------------------------------

	/** PM_LadderMove: view-relative climbing, pushing into the ladder moves you up. */
	private static void ladderMove(LocalPlayer player, Vel vel, TickInput in, boolean onGround, double fx, double fz, double rx, double rz, Vec3 look) {
		player.resetFallDistance();

		// Ladder plane normal: facing of ladders/trapdoors; for vines etc. assume we face it head on.
		BlockState state = player.getInBlockState();
		double nx;
		double nz;
		if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			nx = facing.getStepX();
			nz = facing.getStepZ();
		} else {
			nx = -fx;
			nz = -fz;
		}

		if (in.jump()) {
			vel.x = nx * HLConfig.ladderJumpOff;
			vel.y = 0.0;
			vel.z = nz * HLConfig.ladderJumpOff;
		} else {
			double climb = Math.min(HLConfig.climbSpeed, in.maxSpeed());
			if (in.ducked()) {
				climb *= HLConfig.duckMultiplier;
			}

			double forward = in.ladderForward() * climb;
			double right = in.ladderSide() * climb;
			if (forward != 0.0 || right != 0.0) {
				double vx = look.x * forward + rx * right;
				double vy = look.y * forward;
				double vz = look.z * forward + rz * right;

				// Split into into/out-of-ladder and lateral parts, then remap "into the ladder" to "up".
				double normalComponent = vx * nx + vz * nz;
				vel.x = vx - nx * normalComponent;
				vel.y = vy - normalComponent;
				vel.z = vz - nz * normalComponent;

				if (onGround && normalComponent > 0.0) {
					// On the floor, moving away from the ladder.
					vel.x += nx * climb;
					vel.z += nz * climb;
				}
			} else {
				vel.x = vel.y = vel.z = 0.0;
			}
		}

		checkVelocity(vel);
		move(player, vel, in.dt(), false, false);
	}

	// ------------------------------------------------------------------------------------------
	// Collision
	// ------------------------------------------------------------------------------------------

	/**
	 * Moves through Minecraft's collision (which already slides along walls and handles steps),
	 * then clips our velocity against whatever we hit, like PM_FlyMove / PM_ClipVelocity.
	 */
	private static void move(LocalPlayer player, Vel vel, double dt, boolean groundProbe, boolean allowStepDown) {
		double upb = HLConfig.unitsPerBlock;
		double toUps = upb / TICK_SECONDS;
		double dx = vel.x * dt / upb;
		double dy = vel.y * dt / upb;
		double dz = vel.z * dt / upb;
		if (groundProbe && dy > -GROUND_PROBE_BLOCKS) {
			dy = -GROUND_PROBE_BLOCKS;
		}

		boolean wasOnGround = player.onGround();
		double x0 = player.getX();
		double z0 = player.getZ();

		// deltaMovement is what Minecraft's collision response (e.g. slime bounce) reads and writes.
		player.setDeltaMovement(vel.x / toUps, vel.y / toUps, vel.z / toUps);
		player.move(MoverType.SELF, new Vec3(dx, dy, dz));
		if (player.isRemoved()) {
			return;
		}

		if (!Mth.equal(dx, player.getX() - x0)) {
			vel.x = 0.0;
		}

		if (!Mth.equal(dz, player.getZ() - z0)) {
			vel.z = 0.0;
		}

		if (player.verticalCollision) {
			vel.y = player.getDeltaMovement().y * toUps;
		}

		// Optional Source-style step-down (off by default; Half-Life 1 just falls down steps).
		if (allowStepDown && HLConfig.stepDown && wasOnGround && !player.onGround() && vel.y <= 0.0) {
			double step = player.maxUpStep();
			AABB below = player.getBoundingBox().expandTowards(0.0, -step, 0.0);
			if (!player.level().noCollision(player, below)) {
				player.setDeltaMovement(vel.x / toUps, 0.0, vel.z / toUps);
				player.move(MoverType.SELF, new Vec3(0.0, -step, 0.0));
				vel.y = 0.0;
			}
		}
	}
}
