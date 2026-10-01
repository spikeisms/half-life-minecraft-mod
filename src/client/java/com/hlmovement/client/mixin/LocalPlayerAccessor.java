package com.hlmovement.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
	@Invoker("itemUseSpeedMultiplier")
	float hlmovement$itemUseSpeedMultiplier();

	@Invoker("isControlledCamera")
	boolean hlmovement$isControlledCamera();
}
