package io.github.logoutbodies.api;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** Obtain through Bukkit ServicesManager. All methods require the server thread. */
public interface LogoutBodiesApi {
    boolean isLogoutBody(Entity entity);

    Optional<UUID> getOwnerUuid(Entity entity);

    Optional<Player> getBody(UUID ownerUuid);

    Optional<BodyInfo> getBodyInfo(UUID ownerUuid);
}
