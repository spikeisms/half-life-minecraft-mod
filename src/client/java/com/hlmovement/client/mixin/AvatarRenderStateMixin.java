package com.hlmovement.client.mixin;

import com.hlmovement.client.model.PlayerModels;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries a player's Half-Life model and pose from extraction to drawing. */
@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements PlayerModels.PoseHolder {
	@Unique
	private PlayerModels.@Nullable Posed hlmovement$posed;

	@Override
	public PlayerModels.@Nullable Posed hlmovement$posed() {
		return hlmovement$posed;
	}

	@Override
	public void hlmovement$setPosed(PlayerModels.@Nullable Posed posed) {
		hlmovement$posed = posed;
	}
}
