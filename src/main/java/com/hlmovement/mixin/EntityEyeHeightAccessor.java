package com.hlmovement.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets HLDuck lower the view gradually while a duck is in progress. */
@Mixin(Entity.class)
public interface EntityEyeHeightAccessor {
	@Accessor("eyeHeight")
	void hlmovement$setEyeHeight(float eyeHeight);
}
