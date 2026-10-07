package io.github.logoutbodies.core;

import io.github.logoutbodies.api.BodyInfo;

import java.util.UUID;

final class BodySession {
    final UUID id;
    final UUID owner;
    final BodyFactory.Handle handle;
    final Lifecycle lifecycle = new Lifecycle();
    final long expires;
    ChunkTickets.Key ticket;
    byte[] lastCheckpoint;

    BodySession(UUID id, UUID owner, BodyFactory.Handle handle, long expires, byte[] initial) {
        this.id = id;
        this.owner = owner;
        this.handle = handle;
        this.expires = expires;
        this.lastCheckpoint = initial;
    }

    BodyInfo info(long tick) {
        var player = handle.player();
        var at = player.getLocation();
        return new BodyInfo(
                id,
                owner,
                player.getName(),
                player.getEntityId(),
                at.getWorld().getUID(),
                at.getX(),
                at.getY(),
                at.getZ(),
                Math.max(0, expires - tick));
    }
}
