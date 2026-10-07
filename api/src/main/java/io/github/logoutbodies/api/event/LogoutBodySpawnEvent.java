package io.github.logoutbodies.api.event;

import io.github.logoutbodies.api.BodyInfo;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Notification after the body has become authoritative and entered its world. */
public final class LogoutBodySpawnEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player body;
    private final BodyInfo info;

    public LogoutBodySpawnEvent(Player body, BodyInfo info) {
        this.body = body;
        this.info = info;
    }

    public Player getBody() {
        return body;
    }

    public BodyInfo getBodyInfo() {
        return info;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
