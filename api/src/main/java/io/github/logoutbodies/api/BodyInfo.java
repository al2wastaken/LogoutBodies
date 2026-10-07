package io.github.logoutbodies.api;

import java.util.UUID;

/** A detached, immutable view. Coordinates describe the instant the view was created. */
public record BodyInfo(
        UUID sessionId,
        UUID ownerUuid,
        String ownerName,
        int entityId,
        UUID worldUuid,
        double x,
        double y,
        double z,
        long remainingTicks) {}
