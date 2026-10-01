package com.hlmovement.net;

import com.hlmovement.HLMovement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Server -> client: a player is (or no longer is) in Half-Life mode. */
public record ModeStatePayload(UUID player, boolean on) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ModeStatePayload> TYPE = new CustomPacketPayload.Type<>(HLMovement.id("mode_state"));
	public static final StreamCodec<FriendlyByteBuf, ModeStatePayload> CODEC = CustomPacketPayload.codec(ModeStatePayload::write, ModeStatePayload::new);

	private ModeStatePayload(FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readBoolean());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUUID(this.player);
		buf.writeBoolean(this.on);
	}

	@Override
	public CustomPacketPayload.Type<ModeStatePayload> type() {
		return TYPE;
	}
}
