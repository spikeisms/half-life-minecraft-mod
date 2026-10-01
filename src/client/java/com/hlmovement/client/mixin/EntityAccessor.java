package com.hlmovement.client.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
	@Accessor("stuckSpeedMultiplier")
	Vec3 hlmovement$getStuckSpeedMultiplier();

	@Invoker("getBlockSpeedFactor")
	float hlmovement$getBlockSpeedFactor();
}
