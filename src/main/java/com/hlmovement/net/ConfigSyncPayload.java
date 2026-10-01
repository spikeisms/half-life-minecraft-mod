package com.hlmovement.net;

import com.hlmovement.HLMovement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client on join: the server's Half-Life settings. Also tells the client the server runs the mod. */
public record ConfigSyncPayload(String config) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ConfigSyncPayload> TYPE = new CustomPacketPayload.Type<>(HLMovement.id("config"));
	public static final StreamCodec<FriendlyByteBuf, ConfigSyncPayload> CODEC = CustomPacketPayload.codec(ConfigSyncPayload::write, ConfigSyncPayload::new);

	private ConfigSyncPayload(FriendlyByteBuf buf) {
		this(buf.readUtf());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.config);
	}

	@Override
	public CustomPacketPayload.Type<ConfigSyncPayload> type() {
		return TYPE;
	}
}
