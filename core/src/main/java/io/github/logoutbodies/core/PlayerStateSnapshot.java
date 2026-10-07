package io.github.logoutbodies.core;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.util.UUID;

/** Explicit, versioned ownership payload. Item bytes never alias either inventory. */
public record PlayerStateSnapshot(
        UUID world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        double health,
        double maxHealth,
        double absorption,
        int food,
        float saturation,
        int level,
        float experience,
        int totalExperience,
        int heldSlot,
        GameMode gameMode,
        byte[] inventory) {
    public PlayerStateSnapshot {
        inventory = inventory.clone();
    }

    @Override
    public byte[] inventory() {
        return inventory.clone();
    }

    public static PlayerStateSnapshot capture(Player player) {
        Location at = player.getLocation();
        return new PlayerStateSnapshot(
                at.getWorld().getUID(),
                at.getX(),
                at.getY(),
                at.getZ(),
                at.getYaw(),
                at.getPitch(),
                player.getHealth(),
                player.getAttribute(Attribute.MAX_HEALTH).getValue(),
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getInventory().getHeldItemSlot(),
                player.getGameMode(),
                ItemStack.serializeItemsAsBytes(player.getInventory().getContents()));
    }

    public PlayerStateSnapshot afterDeath(
            Location spawn, int newLevel, float newExperience, int newTotal) {
        return new PlayerStateSnapshot(
                spawn.getWorld().getUID(),
                spawn.getX(),
                spawn.getY(),
                spawn.getZ(),
                spawn.getYaw(),
                spawn.getPitch(),
                maxHealth,
                maxHealth,
                0,
                20,
                5,
                newLevel,
                newExperience,
                newTotal,
                heldSlot,
                gameMode,
                inventory);
    }

    /** An unclean ACTIVE session cannot prove that other plugins did not transfer its items. */
    public PlayerStateSnapshot withoutUncertainPossessions() {
        return new PlayerStateSnapshot(
                world,
                x,
                y,
                z,
                yaw,
                pitch,
                Math.max(1, health),
                maxHealth,
                absorption,
                food,
                saturation,
                0,
                0,
                0,
                heldSlot,
                gameMode,
                ItemStack.serializeItemsAsBytes(
                        new ItemStack[ItemStack.deserializeItemsFromBytes(inventory).length]));
    }

    public PlayerStateSnapshot withCurrentPossessions(Player player) {
        return new PlayerStateSnapshot(
                world,
                x,
                y,
                z,
                yaw,
                pitch,
                health,
                maxHealth,
                absorption,
                food,
                saturation,
                level,
                experience,
                totalExperience,
                heldSlot,
                gameMode,
                ItemStack.serializeItemsAsBytes(player.getInventory().getContents()));
    }

    public Location location() {
        World loaded = Bukkit.getWorld(world);
        if (loaded == null)
            throw new IllegalStateException("Snapshot world is unavailable: " + world);
        return new Location(loaded, x, y, z, yaw, pitch);
    }

    public void apply(Player player) {
        if (player.getGameMode() != gameMode) player.setGameMode(gameMode);
        player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(inventory));
        player.getInventory().setHeldItemSlot(heldSlot);
        player.setHealth(Math.min(health, player.getAttribute(Attribute.MAX_HEALTH).getValue()));
        player.setAbsorptionAmount(absorption);
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setLevel(level);
        player.setExp(experience);
        player.setTotalExperience(totalExperience);
    }

    public byte[] encode() {
        try {
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            out.writeInt(1);
            out.writeLong(world.getMostSignificantBits());
            out.writeLong(world.getLeastSignificantBits());
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeFloat(yaw);
            out.writeFloat(pitch);
            out.writeDouble(health);
            out.writeDouble(maxHealth);
            out.writeDouble(absorption);
            out.writeInt(food);
            out.writeFloat(saturation);
            out.writeInt(level);
            out.writeFloat(experience);
            out.writeInt(totalExperience);
            out.writeInt(heldSlot);
            out.writeUTF(gameMode.name());
            out.writeInt(inventory.length);
            out.write(inventory);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    public static PlayerStateSnapshot decode(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 1) throw new IOException("Unknown snapshot format");
        UUID world = new UUID(in.readLong(), in.readLong());
        double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
        float yaw = in.readFloat(), pitch = in.readFloat();
        double health = in.readDouble(), max = in.readDouble(), absorption = in.readDouble();
        int food = in.readInt();
        float sat = in.readFloat();
        int level = in.readInt();
        float exp = in.readFloat();
        int total = in.readInt(), held = in.readInt();
        GameMode mode = GameMode.valueOf(in.readUTF());
        int length = in.readInt();
        if (length < 0 || length > 16 * 1024 * 1024)
            throw new IOException("Invalid inventory payload length");
        byte[] inventory = in.readNBytes(length);
        if (inventory.length != length || in.available() != 0)
            throw new IOException("Truncated or trailing snapshot data");
        return new PlayerStateSnapshot(
                world,
                x,
                y,
                z,
                yaw,
                pitch,
                health,
                max,
                absorption,
                food,
                sat,
                level,
                exp,
                total,
                held,
                mode,
                inventory);
    }
}
