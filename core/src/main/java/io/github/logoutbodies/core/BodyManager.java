package io.github.logoutbodies.core;

import io.github.logoutbodies.api.*;
import io.github.logoutbodies.api.event.*;
import io.github.logoutbodies.core.storage.OwnershipJournal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class BodyManager implements LogoutBodiesApi, AutoCloseable {
    private final JavaPlugin plugin;
    private final BodyFactory factory;
    private final OwnershipJournal journal;
    private final ChunkTickets tickets;
    private final Map<UUID, BodySession> sessions = new HashMap<>();
    private final IdentityIndex<Player> identities = new IdentityIndex<>();
    private final Map<UUID, Long> reconnecting = new HashMap<>();
    private final Set<UUID> failedCleanup = new HashSet<>();
    private final BodyFactory.DeathHandler deathHandler =
            new BodyFactory.DeathHandler() {
                @Override
                public boolean begin(Player body) {
                    checkThread();
                    return identities
                            .owner(body)
                            .map(sessions::get)
                            .map(session -> session.lifecycle.beginDeath())
                            .orElse(false);
                }

                @Override
                public void cancelled(Player body) {
                    checkThread();
                    identities
                            .owner(body)
                            .map(sessions::get)
                            .ifPresent(session -> session.lifecycle.revive());
                }

                @Override
                public void died(Player body, PlayerStateSnapshot state, DropPayload drops) {
                    BodyManager.this.died(body, state, drops);
                }

                @Override
                public void failed(Player body, RuntimeException failure) {
                    checkThread();
                    identities
                            .owner(body)
                            .map(sessions::get)
                            .ifPresent(
                                    session -> {
                                        try {
                                            journal.invalidate(session.id);
                                        } catch (Exception storageFailure) {
                                            failure.addSuppressed(storageFailure);
                                        }
                                        report(
                                                "Native death failed for "
                                                        + session.owner
                                                        + "; possessions retained for inspection",
                                                failure);
                                        cleanup(session, BodyRemovalReason.INVALIDATED);
                                    });
                }
            };
    private long tick;
    private long lifetime = 600;
    private boolean closed;

    public BodyManager(JavaPlugin plugin, BodyFactory factory, OwnershipJournal journal) {
        this.plugin = plugin;
        this.factory = factory;
        this.journal = journal;
        this.tickets = new ChunkTickets(plugin);
    }

    public void lifetime(long ticks) {
        checkThread();
        lifetime = ticks;
    }

    public void prepare(Player owner) {
        checkThread();
        if (closed || owner.isDead() || isLogoutBody(owner)) return;
        UUID uuid = owner.getUniqueId();
        if (reconnecting.containsKey(uuid)) return;
        if (sessions.containsKey(uuid))
            throw new IllegalStateException("Body already exists for " + uuid);
        try {
            if (journal.pending(uuid).isPresent())
                throw new IllegalStateException("Unresolved ownership for " + uuid);
            var snapshot = PlayerStateSnapshot.capture(owner);
            byte[] bytes = snapshot.encode();
            UUID id = UUID.randomUUID();
            // Create an unregistered handle first; persistence is the ownership commit point.
            var handle = factory.create(owner, snapshot, deathHandler);
            try {
                journal.begin(id, uuid, owner.getName(), bytes);
            } catch (Exception failure) {
                handle.remove();
                throw failure;
            }
            // Ownership has committed. The retiring entity and its subsequent vanilla save
            // must not retain another copy of the body's possessions.
            owner.getInventory().clear();
            owner.setLevel(0);
            owner.setExp(0);
            owner.setTotalExperience(0);
            var session = new BodySession(id, uuid, handle, tick + lifetime, bytes);
            sessions.put(uuid, session);
            identities.add(uuid, handle.player());
            debug("prepared owner=" + uuid + " entity=" + handle.player().getEntityId());
        } catch (Exception failure) {
            report("Cannot create body for " + uuid, failure);
        }
    }

    public void tick() {
        checkThread();
        if (closed) return;
        tick++;
        if (tick % 1200 == 0) {
            try {
                journal.pruneCompleted(
                        System.currentTimeMillis()
                                - java.util.concurrent.TimeUnit.DAYS.toMillis(7));
            } catch (Exception failure) {
                report("Journal maintenance failed", failure);
            }
        }
        reconnecting.values().removeIf(until -> until < tick);
        Map<UUID, byte[]> checkpoints = new HashMap<>();
        Map<UUID, BodySession> changed = new HashMap<>();
        for (var session : List.copyOf(sessions.values())) {
            try {
                if (session.lifecycle.state() == SessionState.PREPARING) {
                    if (Bukkit.getPlayer(session.owner) != null) {
                        remove(session.owner, BodyRemovalReason.OWNER_RECONNECTED);
                        continue;
                    }
                    session.ticket = tickets.acquire(session.handle.player().getLocation());
                    session.handle.spawn();
                    session.lifecycle.activate();
                    new LogoutBodySpawnEvent(session.handle.player(), session.info(tick))
                            .callEvent();
                    debug("spawn owner=" + session.owner);
                }
                if (session.lifecycle.state() != SessionState.ACTIVE) continue;
                if (tick >= session.expires) {
                    remove(session.owner, BodyRemovalReason.EXPIRED);
                    continue;
                }
                if (!session.handle.player().isValid()) {
                    remove(session.owner, BodyRemovalReason.INVALIDATED);
                    continue;
                }
                session.ticket =
                        tickets.move(session.ticket, session.handle.player().getLocation());
                byte[] current = PlayerStateSnapshot.capture(session.handle.player()).encode();
                if (!Arrays.equals(current, session.lastCheckpoint)) {
                    checkpoints.put(session.id, current);
                    changed.put(session.id, session);
                }
            } catch (Exception failure) {
                report("Body tick failed for " + session.owner, failure);
                remove(session.owner, BodyRemovalReason.INVALIDATED);
            }
        }
        if (!checkpoints.isEmpty()) {
            try {
                journal.checkpointAll(checkpoints);
                changed.forEach((id, session) -> session.lastCheckpoint = checkpoints.get(id));
            } catch (Exception failure) {
                report("Checkpoint batch failed; settling affected bodies", failure);
                for (var session : changed.values())
                    remove(session.owner, BodyRemovalReason.INVALIDATED);
            }
        }
    }

    private void died(Player body, PlayerStateSnapshot finalState, DropPayload drops) {
        checkThread();
        var owner = identities.owner(body).orElseThrow();
        var session = sessions.get(owner);
        try {
            journal.prepareDeath(session.id, finalState.encode(), drops.encode());
            if (journal.claimDrops(session.id)) {
                drops.emit(plugin, session.id);
                journal.finalizeDeathSnapshot(
                        session.id, finalState.withCurrentPossessions(body).encode());
                journal.finishDrops(session.id);
            }
        } catch (Exception failure) {
            // A failed/partial emission must never be retried in-process.
            try {
                journal.abandonDrops(session.id);
            } catch (Exception journalFailure) {
                failure.addSuppressed(journalFailure);
            }
            report("Death transaction failed for " + owner + "; journal retained", failure);
        } finally {
            cleanup(session, BodyRemovalReason.DIED);
        }
    }

    public boolean remove(UUID owner, BodyRemovalReason reason) {
        checkThread();
        var session = sessions.get(owner);
        if (session == null) return false;
        if (session.lifecycle.state() != SessionState.PREPARING
                && session.lifecycle.state() != SessionState.ACTIVE) return false;
        try {
            journal.settle(
                    session.id,
                    PlayerStateSnapshot.capture(session.handle.player()).encode(),
                    reason.name());
        } catch (Exception failure) {
            report("Cannot settle body " + owner + "; last durable checkpoint retained", failure);
        }
        cleanup(session, reason);
        return true;
    }

    private void cleanup(BodySession session, BodyRemovalReason reason) {
        if (!session.lifecycle.beginRemoval()) return;
        var info = session.info(tick);
        try {
            session.handle.remove();
        } catch (Exception failure) {
            report("Entity cleanup failed for " + session.owner, failure);
            if (session.handle.player().isValid()) failedCleanup.add(session.owner);
        } finally {
            try {
                if (session.ticket != null) tickets.release(session.ticket);
            } catch (Exception failure) {
                report("Chunk ticket cleanup failed for " + session.owner, failure);
            }
            identities.remove(session.owner, session.handle.player());
            sessions.remove(session.owner, session);
            session.lifecycle.settled();
            new LogoutBodyRemoveEvent(session.handle.player(), info, reason).callEvent();
            debug("remove owner=" + session.owner + " reason=" + reason);
        }
    }

    public void prepareLogin(UUID owner, String name) throws Exception {
        checkThread();
        remove(owner, BodyRemovalReason.OWNER_RECONNECTED);
        if (failedCleanup.contains(owner))
            throw new IllegalStateException(
                    "A conflicting body could not be removed; restart required");
        reconnecting.put(owner, tick + 1200);
        var entry = journal.pending(owner);
        if (entry.isEmpty()) return;
        if (entry.get().phase().equals("ACTIVE"))
            throw new IllegalStateException("Unsettled ownership; restart recovery required");
        if (entry.get().dropState() == OwnershipJournal.DropState.PREPARED
                || entry.get().dropState() == OwnershipJournal.DropState.APPLYING)
            throw new IllegalStateException(
                    "Unfinished death transaction; restart recovery required");
        var state = PlayerStateSnapshot.decode(entry.get().snapshot());
        if (entry.get().reason().equals("UNCLEAN_SHUTDOWN")
                || entry.get().dropState() == OwnershipJournal.DropState.AMBIGUOUS) {
            state = state.withoutUncertainPossessions();
            plugin.getLogger()
                    .warning(
                            "Unclean session "
                                    + entry.get().session()
                                    + " for "
                                    + owner
                                    + ": uncertain inventory/XP withheld; journal retained.");
        }
        factory.persistOwner(owner, name, state, entry.get().reason().equals("DIED"));
        journal.complete(entry.get().session());
    }

    public void recoverDrops() throws Exception {
        checkThread();
        journal.recover();
        replayPreparedDrops();
    }

    public void replayPreparedDrops() {
        checkThread();
        List<OwnershipJournal.Entry> entries;
        try {
            entries = journal.unfinishedDrops();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read drop recovery journal", failure);
        }
        for (var entry : entries) {
            ChunkTickets.Key ticket = null;
            try {
                var drops = DropPayload.decode(entry.drops());
                if (Bukkit.getWorld(drops.world()) == null) continue;
                drops.validate();
                var at =
                        new org.bukkit.Location(
                                Bukkit.getWorld(drops.world()), drops.x(), drops.y(), drops.z());
                ticket = tickets.acquire(at);
                if (journal.claimDrops(entry.session())) {
                    drops.emit(plugin, entry.session());
                    journal.finishDrops(entry.session());
                }
            } catch (Exception failure) {
                try {
                    journal.abandonDrops(entry.session());
                } catch (Exception journalFailure) {
                    failure.addSuppressed(journalFailure);
                }
                report(
                        "Cannot replay drop intent " + entry.session() + "; journal retained",
                        failure);
            } finally {
                if (ticket != null) tickets.release(ticket);
            }
        }
    }

    public void worldUnloading(UUID world) {
        checkThread();
        for (var session : List.copyOf(sessions.values()))
            if (session.handle.player().getWorld().getUID().equals(world))
                remove(session.owner, BodyRemovalReason.WORLD_UNLOADED);
    }

    public void viewerJoined(Player viewer) {
        checkThread();
        reconnecting.remove(viewer.getUniqueId());
        for (var session : sessions.values())
            if (session.lifecycle.state() == SessionState.ACTIVE)
                factory.showTo(viewer, session.handle.player());
    }

    public List<BodyInfo> list() {
        checkThread();
        return sessions.values().stream().map(s -> s.info(tick)).toList();
    }

    public List<OwnershipJournal.Entry> diagnostics() throws Exception {
        checkThread();
        return journal.diagnostics();
    }

    @Override
    public boolean isLogoutBody(Entity entity) {
        return getOwnerUuid(entity).isPresent();
    }

    @Override
    public Optional<UUID> getOwnerUuid(Entity entity) {
        checkThread();
        return entity instanceof Player p ? identities.owner(p) : Optional.empty();
    }

    @Override
    public Optional<Player> getBody(UUID owner) {
        checkThread();
        return identities.body(owner);
    }

    @Override
    public Optional<BodyInfo> getBodyInfo(UUID owner) {
        checkThread();
        return Optional.ofNullable(sessions.get(owner)).map(s -> s.info(tick));
    }

    @Override
    public void close() {
        checkThread();
        closed = true;
        for (var id : List.copyOf(sessions.keySet())) {
            try {
                remove(id, BodyRemovalReason.PLUGIN_DISABLED);
            } catch (Exception failure) {
                report("Shutdown cleanup failed for " + id, failure);
            }
        }
    }

    public void debug(String message) {
        if (plugin.getConfig().getBoolean("debug")) plugin.getLogger().info(message);
    }

    private void report(String message, Exception failure) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, message, failure);
    }

    private static void checkThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("LogoutBodies requires the server thread");
    }
}
