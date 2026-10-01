package com.hlmovement.client.mixin;

import com.hlmovement.client.model.PlayerModels;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries a player's Half-Life bone matrices from extraction to drawing. */
@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements PlayerModels.PoseHolder {
	@Unique
	private float @Nullable [] hlmovement$bones;

	@Override
	public float @Nullable [] hlmovement$bones() {
		return hlmovement$bones;
	}

	@Override
	public void hlmovement$setBones(float @Nullable [] bones) {
		hlmovement$bones = bones;
	}
}
