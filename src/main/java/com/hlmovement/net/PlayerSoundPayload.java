package com.hlmovement.net;

import com.hlmovement.HLMovement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: a Half-Life player sound made by another player.
 *
 * @param sound event path in the hlmovement namespace, e.g. "player.pl_step1"
 */
public record PlayerSoundPayload(int entityId, String sound, float volume) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PlayerSoundPayload> TYPE = new CustomPacketPayload.Type<>(HLMovement.id("player_sound"));
	public static final StreamCodec<FriendlyByteBuf, PlayerSoundPayload> CODEC = CustomPacketPayload.codec(PlayerSoundPayload::write, PlayerSoundPayload::new);

	private PlayerSoundPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), buf.readUtf(), buf.readFloat());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.entityId);
		buf.writeUtf(this.sound);
		buf.writeFloat(this.volume);
	}

	@Override
	public CustomPacketPayload.Type<PlayerSoundPayload> type() {
		return TYPE;
	}
}
