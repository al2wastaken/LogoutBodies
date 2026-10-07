package io.github.logoutbodies.core;

import io.github.logoutbodies.api.*;
import io.github.logoutbodies.core.storage.OwnershipJournal;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.TimeUnit;

public abstract class LogoutBodiesPlugin extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {
    private BodyManager manager;
    private OwnershipJournal journal;
    private volatile boolean ready;

    protected abstract BodyFactory createFactory();

    protected abstract String minecraftVersion();

    @Override
    public void onEnable() {
        if (!Bukkit.getMinecraftVersion().equals(minecraftVersion())) {
            getLogger()
                    .severe(
                            "Wrong artifact: expected Minecraft "
                                    + minecraftVersion()
                                    + ", got "
                                    + Bukkit.getMinecraftVersion());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("logoutbodies")).setExecutor(this);
        getCommand("logoutbodies").setTabCompleter(this);
        try {
            saveDefaultConfig();
            java.nio.file.Files.createDirectories(getDataFolder().toPath());
            journal = new OwnershipJournal(getDataFolder().toPath().resolve("ownership.db"));
            manager = new BodyManager(this, createFactory(), journal);
            configure();
            manager.recoverDrops();
            Bukkit.getServicesManager()
                    .register(LogoutBodiesApi.class, manager, this, ServicePriority.Normal);
            Bukkit.getScheduler().runTaskTimer(this, manager::tick, 1, 1);
            ready = true;
        } catch (Exception failure) {
            // Keep the pre-login guard registered: disabling after a journal failure would
            // allow stale vanilla inventory data to be loaded without ownership reconciliation.
            getLogger()
                    .log(
                            java.util.logging.Level.SEVERE,
                            "Cannot start safely; logins are blocked until the"
                                    + " storage/configuration problem is fixed and the server"
                                    + " restarted",
                            failure);
        }
    }

    private void configure() {
        long seconds = getConfig().getLong("body-lifetime-seconds", 30);
        if (seconds < 1 || seconds > 86400)
            throw new IllegalArgumentException("body-lifetime-seconds must be 1..86400");
        manager.lifetime(Math.multiplyExact(seconds, 20));
    }

    @Override
    public void onDisable() {
        ready = false;
        Bukkit.getScheduler().cancelTasks(this);
        Bukkit.getServicesManager().unregisterAll(this);
        if (manager != null) manager.close();
        if (journal != null)
            try {
                journal.close();
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Journal close failed", e);
            }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        if (ready) manager.prepare(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!ready) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text(
                            "LogoutBodies state storage is unavailable. Please contact an"
                                    + " administrator."));
            return;
        }
        java.util.concurrent.Future<?> barrier = null;
        try {
            barrier =
                    Bukkit.getScheduler()
                            .callSyncMethod(
                                    this,
                                    () -> {
                                        if (!ready)
                                            throw new IllegalStateException("Plugin is stopping");
                                        manager.prepareLogin(event.getUniqueId(), event.getName());
                                        return null;
                                    });
            barrier.get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (barrier != null) barrier.cancel(false);
            getLogger()
                    .log(
                            java.util.logging.Level.WARNING,
                            "Owner handoff failed for " + event.getUniqueId(),
                            failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Logout body cleanup is pending. Please reconnect shortly."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        if (ready) manager.viewerJoined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(WorldUnloadEvent event) {
        if (ready) manager.worldUnloading(event.getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void load(WorldLoadEvent event) {
        if (ready) manager.replayPreparedDrops();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (ready && manager.isLogoutBody(event.getEntity()))
            manager.debug(
                    "damage owner="
                            + event.getEntity().getUniqueId()
                            + " amount="
                            + event.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (ready && manager.isLogoutBody(event.getPlayer()))
            manager.debug(
                    "teleport owner=" + event.getPlayer().getUniqueId() + " to=" + event.getTo());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("logoutbodies.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }
        if (!ready) {
            sender.sendMessage(
                    "LogoutBodies could not initialize safely. Check the server log, fix the"
                            + " problem, and restart.");
            return true;
        }
        try {
            if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
                var entries = manager.list();
                sender.sendMessage(entries.size() + " active logout bodies");
                for (var entry : entries)
                    sender.sendMessage(
                            entry.ownerName()
                                    + " "
                                    + entry.ownerUuid()
                                    + " ticks="
                                    + entry.remainingTicks());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
                var target =
                        manager.list().stream()
                                .filter(
                                        e ->
                                                e.ownerName().equalsIgnoreCase(args[1])
                                                        || e.ownerUuid()
                                                                .toString()
                                                                .equalsIgnoreCase(args[1]))
                                .findFirst();
                sender.sendMessage(
                        target.isPresent()
                                        && manager.remove(
                                                target.get().ownerUuid(),
                                                BodyRemovalReason.ADMIN_REMOVAL)
                                ? "Body removed; owner state retained."
                                : "Body not found.");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                configure();
                sender.sendMessage("Configuration reloaded. Existing expiry times are unchanged.");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("journal")) {
                for (var e : manager.diagnostics())
                    sender.sendMessage(
                            e.owner()
                                    + " session="
                                    + e.session()
                                    + " "
                                    + e.phase()
                                    + " drops="
                                    + e.dropState()
                                    + " "
                                    + e.reason());
            } else
                sender.sendMessage("/logoutbodies list | remove <player|uuid> | reload | journal");
        } catch (Exception failure) {
            sender.sendMessage("Operation failed: " + failure.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (!ready || !sender.hasPermission("logoutbodies.admin")) return List.of();
        List<String> options =
                args.length == 1
                        ? List.of("list", "remove", "reload", "journal")
                        : args.length == 2 && args[0].equals("remove")
                                ? manager.list().stream().map(BodyInfo::ownerName).toList()
                                : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
