package io.github.logoutbodies.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

final class ChunkTickets {
    record Key(UUID world, int x, int z) {
        static Key at(Location at) {
            return new Key(at.getWorld().getUID(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
        }
    }

    private final ReferenceTickets<Key> references;

    ChunkTickets(Plugin plugin) {
        references =
                new ReferenceTickets<>(
                        key -> {
                            var world = Bukkit.getWorld(key.world);
                            if (world == null)
                                throw new IllegalStateException("Ticket world unavailable");
                            world.addPluginChunkTicket(key.x, key.z, plugin);
                        },
                        key -> {
                            var world = Bukkit.getWorld(key.world);
                            if (world != null) world.removePluginChunkTicket(key.x, key.z, plugin);
                        });
    }

    Key acquire(Location at) {
        Key key = Key.at(at);
        references.retain(key);
        return key;
    }

    void release(Key key) {
        references.release(key);
    }

    Key move(Key before, Location at) {
        if (before.equals(Key.at(at))) return before;
        Key after = acquire(at);
        release(before);
        return after;
    }
}
