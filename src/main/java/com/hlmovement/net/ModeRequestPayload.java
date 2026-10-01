package com.hlmovement.net;

import com.hlmovement.HLMovement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: the sender wants Half-Life mode on or off for themselves. */
public record ModeRequestPayload(boolean on) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ModeRequestPayload> TYPE = new CustomPacketPayload.Type<>(HLMovement.id("mode_request"));
	public static final StreamCodec<FriendlyByteBuf, ModeRequestPayload> CODEC = CustomPacketPayload.codec(ModeRequestPayload::write, ModeRequestPayload::new);

	private ModeRequestPayload(FriendlyByteBuf buf) {
		this(buf.readBoolean());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBoolean(this.on);
	}

	@Override
	public CustomPacketPayload.Type<ModeRequestPayload> type() {
		return TYPE;
	}
}
