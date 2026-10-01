package com.hlmovement.net;

import com.hlmovement.HLMovement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the sender wants Half-Life mode on or off for themselves.
 *
 * @param character which of HLMode.CHARACTERS to be while it's on
 */
public record ModeRequestPayload(boolean on, String character) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ModeRequestPayload> TYPE = new CustomPacketPayload.Type<>(HLMovement.id("mode_request"));
	public static final StreamCodec<FriendlyByteBuf, ModeRequestPayload> CODEC = CustomPacketPayload.codec(ModeRequestPayload::write, ModeRequestPayload::new);

	private ModeRequestPayload(FriendlyByteBuf buf) {
		this(buf.readBoolean(), buf.readUtf(32));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBoolean(this.on);
		buf.writeUtf(this.character, 32);
	}

	@Override
	public CustomPacketPayload.Type<ModeRequestPayload> type() {
		return TYPE;
	}
}
