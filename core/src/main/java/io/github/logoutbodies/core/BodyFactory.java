package io.github.logoutbodies.core;

import org.bukkit.entity.Player;

/** Internal platform boundary; no implementation type escapes into lifecycle code. */
public interface BodyFactory {
    interface Handle {
        Player player();

        void spawn();

        void remove();
    }

    interface DeathHandler {
        boolean begin(Player body);

        void cancelled(Player body);

        void failed(Player body, RuntimeException failure);

        void died(Player body, PlayerStateSnapshot finalState, DropPayload drops);
    }

    Handle create(Player owner, PlayerStateSnapshot snapshot, DeathHandler deathHandler);

    /**
     * Load the existing owner data, merge only the payload, and save before normal login loads it.
     */
    void persistOwner(java.util.UUID owner, String name, PlayerStateSnapshot snapshot, boolean died)
            throws Exception;

    void showTo(Player viewer, Player body);
}
