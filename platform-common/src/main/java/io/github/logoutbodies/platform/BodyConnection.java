package io.github.logoutbodies.platform;

import com.mojang.authlib.GameProfile;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

import java.net.InetSocketAddress;
import java.util.Set;

/** An in-memory transport with no socket and no outbound queue. */
final class BodyConnection extends Connection {
    private final EmbeddedChannel memory = new EmbeddedChannel();

    BodyConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = memory;
        this.address = new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (listener != null)
            try {
                listener.operationComplete(memory.newSucceededFuture());
            } catch (Exception failure) {
                throw new IllegalStateException("Packet completion failed", failure);
            }
    }

    @Override
    public void flushChannel() {}

    void dispose() {
        memory.finishAndReleaseAll();
    }

    static final class Listener extends ServerGamePacketListenerImpl {
        private boolean nativeDeathCompleted;

        Listener(
                MinecraftServer server,
                BodyConnection connection,
                ServerPlayer player,
                GameProfile profile) {
            super(server, connection, player, CommonListenerCookie.createInitial(profile, false));
        }

        @Override
        public boolean hasClientLoaded() {
            return true;
        }

        @Override
        public void tickClientLoadTimeout() {}

        @Override
        public void tick() {}

        @Override
        public void markClientUnloadedAfterDeath() {
            nativeDeathCompleted = true;
        }

        void resetDeath() {
            nativeDeathCompleted = false;
        }

        boolean nativeDeathCompleted() {
            return nativeDeathCompleted;
        }

        @Override
        public void internalTeleport(PositionMoveRotation movement, Set<Relative> relatives) {
            // There is no client to acknowledge a teleport. Complete the native position/dimension
            // transition synchronously instead of leaving the body permanently invulnerable.
            player.teleportSetPosition(movement, relatives);
            player.hasChangedDimension();
            resetPosition();
        }

        @Override
        public void disconnect(DisconnectionDetails details) {
            // Never invoke PlayerList.remove: it would remove the owner's registry entry.
            player.discard();
        }
    }
}
