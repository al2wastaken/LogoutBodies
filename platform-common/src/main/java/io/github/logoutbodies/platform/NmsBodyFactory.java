package io.github.logoutbodies.platform;

import com.mojang.authlib.GameProfile;

import io.github.logoutbodies.core.*;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

final class NmsBodyFactory implements BodyFactory {
    private final MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();

    @Override
    public Handle create(Player owner, PlayerStateSnapshot snapshot, DeathHandler deathHandler) {
        ServerPlayer original = ((CraftPlayer) owner).getHandle();
        var body =
                new BodyPlayer(
                        server,
                        ((CraftWorld) owner.getWorld()).getHandle(),
                        original.getGameProfile(),
                        deathHandler);
        var connection = new BodyConnection();
        try {
            new BodyConnection.Listener(server, connection, body, body.getGameProfile());
            body.getAttribute(Attributes.MAX_HEALTH)
                    .setBaseValue(original.getAttribute(Attributes.MAX_HEALTH).getBaseValue());
            body.setGameMode(
                    GameType.byName(snapshot.gameMode().name().toLowerCase(java.util.Locale.ROOT)));
            body.copyRespawnPosition(original);
            body.setPose(original.getPose());
            body.setShiftKeyDown(original.isShiftKeyDown());
            for (var effect : original.getActiveEffects())
                body.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect));
            body.getEntityData()
                    .set(
                            net.minecraft.world.entity.player.Player.DATA_PLAYER_MODE_CUSTOMISATION,
                            original.getEntityData()
                                    .get(
                                            net.minecraft.world.entity.player.Player
                                                    .DATA_PLAYER_MODE_CUSTOMISATION));
            snapshot.apply(body.getBukkitEntity());
            body.absSnapTo(
                    snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
            body.setYHeadRot(snapshot.yaw());
            return new Handle() {
                private boolean removed;

                @Override
                public Player player() {
                    return body.getBukkitEntity();
                }

                @Override
                public void spawn() {
                    for (Player viewer : Bukkit.getOnlinePlayers()) showTo(viewer, player());
                    body.level().addNewPlayer(body);
                    if (!body.valid)
                        throw new IllegalStateException(
                                "World rejected logout body " + body.getUUID());
                }

                @Override
                public void remove() {
                    if (removed) return;
                    removed = true;
                    // Removed entity references must no longer offer a second inventory/XP source.
                    body.getInventory().clearContent();
                    body.experienceLevel = 0;
                    body.experienceProgress = 0;
                    body.totalExperience = 0;
                    try {
                        body.level().removePlayerImmediately(body, Entity.RemovalReason.DISCARDED);
                        var packet =
                                new ClientboundPlayerInfoRemovePacket(
                                        java.util.List.of(body.getUUID()));
                        for (Player viewer : Bukkit.getOnlinePlayers())
                            ((CraftPlayer) viewer).getHandle().connection.send(packet);
                    } finally {
                        try {
                            body.getTextFilter().leave();
                        } finally {
                            connection.dispose();
                        }
                    }
                }
            };
        } catch (RuntimeException failure) {
            try {
                body.getTextFilter().leave();
            } finally {
                connection.dispose();
            }
            throw failure;
        }
    }

    @Override
    public void showTo(Player viewer, Player body) {
        if (viewer.getUniqueId().equals(body.getUniqueId())) return;
        ((CraftPlayer) viewer)
                .getHandle()
                .connection
                .send(
                        ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(
                                ((CraftPlayer) body).getHandle(), false));
    }

    @Override
    public void persistOwner(UUID owner, String name, PlayerStateSnapshot snapshot, boolean died)
            throws Exception {
        var profile = new GameProfile(owner, name);
        var player =
                new ServerPlayer(
                        server,
                        server.overworld(),
                        profile,
                        net.minecraft.server.level.ClientInformation.createDefault());
        var connection = new BodyConnection();
        try {
            new BodyConnection.Listener(server, connection, player, profile);
            player.getBukkitEntity().loadData();
            if (died) {
                player.removeAllEffects();
                player.setRemainingFireTicks(0);
                player.fallDistance = 0;
            }
            snapshot.apply(player.getBukkitEntity());
            player.setServerLevel(((CraftWorld) snapshot.location().getWorld()).getHandle());
            player.absSnapTo(
                    snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
            player.getBukkitEntity().saveData();
            // PlayerDataStorage.save logs I/O errors instead of throwing. Verify the on-disk
            // payload and force it before allowing the journal ownership handoff to commit.
            var storage = server.getPlayerList().playerIo;
            var data =
                    storage.load(player.nameAndId())
                            .orElseThrow(() -> new java.io.IOException("Owner data was not saved"));
            player.load(
                    net.minecraft.world.level.storage.TagValueInput.create(
                            net.minecraft.util.ProblemReporter.DISCARDING,
                            server.registryAccess(),
                            data));
            var verified = PlayerStateSnapshot.capture(player.getBukkitEntity());
            if (!sameInventory(snapshot.inventory(), verified.inventory())
                    || Math.abs(snapshot.health() - verified.health()) > 0.001
                    || snapshot.level() != verified.level()
                    || snapshot.totalExperience() != verified.totalExperience()
                    || !snapshot.world().equals(verified.world())
                    || Math.abs(snapshot.x() - verified.x()) > 0.001
                    || Math.abs(snapshot.y() - verified.y()) > 0.001
                    || Math.abs(snapshot.z() - verified.z()) > 0.001)
                throw new java.io.IOException(
                        "Owner data verification failed; ownership journal retained; health="
                                + snapshot.health()
                                + "/"
                                + verified.health()
                                + ", world="
                                + snapshot.world()
                                + "/"
                                + verified.world()
                                + ", inventory="
                                + sameInventory(snapshot.inventory(), verified.inventory()));
            try (var file =
                    java.nio.channels.FileChannel.open(
                            storage.getPlayerDir().toPath().resolve(owner + ".dat"),
                            java.nio.file.StandardOpenOption.WRITE)) {
                file.force(true);
            }
        } finally {
            player.getTextFilter().leave();
            connection.dispose();
        }
    }

    private static boolean sameInventory(byte[] expected, byte[] actual) {
        var left = org.bukkit.inventory.ItemStack.deserializeItemsFromBytes(expected);
        var right = org.bukkit.inventory.ItemStack.deserializeItemsFromBytes(actual);
        if (left.length != right.length)
            throw new IllegalStateException(
                    "Inventory size mismatch: " + left.length + "/" + right.length);
        for (int i = 0; i < left.length; i++) {
            boolean emptyLeft = left[i] == null || left[i].isEmpty();
            boolean emptyRight = right[i] == null || right[i].isEmpty();
            if (emptyLeft && emptyRight) continue;
            if (emptyLeft != emptyRight || !left[i].equals(right[i])) return false;
        }
        return true;
    }
}
