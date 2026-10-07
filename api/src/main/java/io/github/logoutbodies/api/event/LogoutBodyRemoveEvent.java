package io.github.logoutbodies.api.event;

import io.github.logoutbodies.api.BodyInfo;
import io.github.logoutbodies.api.BodyRemovalReason;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Notification after cleanup. The body reference must not be reused as an active entity. */
public final class LogoutBodyRemoveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player body;
    private final BodyInfo info;
    private final BodyRemovalReason reason;

    public LogoutBodyRemoveEvent(Player body, BodyInfo info, BodyRemovalReason reason) {
        this.body = body;
        this.info = info;
        this.reason = reason;
    }

    public Player getBody() {
        return body;
    }

    public BodyInfo getBodyInfo() {
        return info;
    }

    public BodyRemovalReason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
