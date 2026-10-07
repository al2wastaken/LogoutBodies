package io.github.logoutbodies.platform;

import com.mojang.authlib.GameProfile;

import io.github.logoutbodies.core.*;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.TeleportTransition;

import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native player entity; network-only responsibilities remain in BodyConnection. */
final class BodyPlayer extends ServerPlayer {
    private final BodyFactory.DeathHandler deathHandler;
    private final MinecraftServer bodyServer;
    private final List<org.bukkit.inventory.ItemStack> deathDrops = new ArrayList<>();
    private boolean collecting;
    private boolean deathFinished;
    private int deathExperience;

    BodyPlayer(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            BodyFactory.DeathHandler deathHandler) {
        super(server, level, profile, ClientInformation.createDefault());
        this.deathHandler = deathHandler;
        this.bodyServer = server;
        this.isRealPlayer = false;
        this.invulnerableTime = 0;
        this.getBukkitEntity().setCanPickupItems(false);
    }

    @Override
    public void tick() {
        if (deathFinished) return;
        super.tick();
        // A real client's packet listener normally drives doTick(); this body has no network tick.
        if (!isRemoved()) doTick();
    }

    @Override
    public void die(DamageSource damage) {
        if (collecting || deathFinished) return;
        if (!deathHandler.begin(getBukkitEntity())) return;
        collecting = true;
        deathDrops.clear();
        deathExperience = 0;
        var listener = (BodyConnection.Listener) connection;
        listener.resetDeath();
        try {
            super.die(damage);
            if (!listener.nativeDeathCompleted())
                return; // Paper permits cancellation and revival in PlayerDeathEvent.
            deathFinished = true;
            var at = getBukkitEntity().getLocation();
            var transition =
                    findRespawnPositionAndUseSpawnBlock(
                            true,
                            TeleportTransition.DO_NOTHING,
                            PlayerRespawnEvent.RespawnReason.DEATH);
            var spawn =
                    transition == null
                            ? bodyServer.overworld().getWorld().getSpawnLocation()
                            : new org.bukkit.Location(
                                    transition.newLevel().getWorld(),
                                    transition.position().x,
                                    transition.position().y,
                                    transition.position().z,
                                    transition.yRot(),
                                    transition.xRot());
            int level = keepLevel ? experienceLevel : newLevel;
            float experience = keepLevel ? experienceProgress : 0;
            int total = keepLevel ? totalExperience : newTotalExp;
            // Match vanilla respawn's newExp advancement through the XP API.
            if (!keepLevel) {
                experienceLevel = level;
                experienceProgress = 0;
                totalExperience = total;
                giveExperiencePoints(newExp);
                level = experienceLevel;
                experience = experienceProgress;
                total = totalExperience;
            }
            removeAllEffects(); // Native respawn does not inherit the dead player's effects.
            var state =
                    PlayerStateSnapshot.capture(getBukkitEntity())
                            .afterDeath(spawn, level, experience, total);
            deathHandler.died(
                    getBukkitEntity(),
                    state,
                    DropPayload.of(
                            at,
                            deathDrops.toArray(org.bukkit.inventory.ItemStack[]::new),
                            deathExperience));
        } catch (RuntimeException failure) {
            deathFinished = true;
            deathHandler.failed(getBukkitEntity(), failure);
        } finally {
            collecting = false;
            deathDrops.clear();
            if (!deathFinished) deathHandler.cancelled(getBukkitEntity());
        }
    }

    @Override
    public ItemEntity drop(
            ItemStack stack,
            boolean around,
            boolean trace,
            boolean callEvent,
            Consumer<org.bukkit.entity.Item> operation) {
        if (!collecting) return super.drop(stack, around, trace, callEvent, operation);
        if (stack.isEmpty()) return null;
        // The native death event has finalized this stack. Delay the world mutation until its
        // journal commit.
        ItemEntity entity = new ItemEntity(level(), getX(), getEyeY() - 0.3, getZ(), stack.copy());
        if (operation != null) operation.accept((org.bukkit.entity.Item) entity.getBukkitEntity());
        deathDrops.add(CraftItemStack.asBukkitCopy(entity.getItem()));
        return entity;
    }

    @Override
    protected void dropExperience(ServerLevel level, Entity source) {
        if (collecting) {
            deathExperience = expToDrop;
            expToDrop = 0;
        } else super.dropExperience(level, source);
    }
}
