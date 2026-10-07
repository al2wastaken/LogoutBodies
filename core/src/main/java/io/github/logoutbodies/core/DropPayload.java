package io.github.logoutbodies.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.util.UUID;

public record DropPayload(UUID world, double x, double y, double z, byte[] items, int experience) {
    public DropPayload {
        items = items.clone();
    }

    @Override
    public byte[] items() {
        return items.clone();
    }

    public static DropPayload of(Location at, ItemStack[] items, int experience) {
        return new DropPayload(
                at.getWorld().getUID(),
                at.getX(),
                at.getY(),
                at.getZ(),
                ItemStack.serializeItemsAsBytes(items),
                experience);
    }

    public void emit(Plugin plugin, UUID operation) {
        var loaded = Bukkit.getWorld(world);
        if (loaded == null) throw new IllegalStateException("Drop world is unavailable: " + world);
        Location at = new Location(loaded, x, y, z);
        int index = 0;
        var marker = new NamespacedKey(plugin, "drop_operation");
        for (var item : ItemStack.deserializeItemsFromBytes(items)) {
            if (item == null || item.isEmpty()) continue;
            String id = operation + ":" + index++;
            loaded.dropItemNaturally(
                    at,
                    item,
                    entity -> {
                        entity.setPickupDelay(40);
                        entity.getPersistentDataContainer()
                                .set(marker, PersistentDataType.STRING, id);
                    });
        }
        if (experience > 0)
            loaded.spawn(
                    at,
                    ExperienceOrb.class,
                    orb -> {
                        orb.setExperience(experience);
                        orb.getPersistentDataContainer()
                                .set(marker, PersistentDataType.STRING, operation + ":xp");
                    });
    }

    public void validate() {
        ItemStack.deserializeItemsFromBytes(items);
        if (experience < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalStateException("Invalid drop intent");
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
            out.writeInt(experience);
            out.writeInt(items.length);
            out.write(items);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DropPayload decode(byte[] data) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != 1) throw new IOException("Unknown drop format");
        UUID world = new UUID(in.readLong(), in.readLong());
        double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
        int exp = in.readInt(), length = in.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("Invalid drop length");
        byte[] items = in.readNBytes(length);
        if (items.length != length || in.available() != 0)
            throw new IOException("Invalid drop payload");
        return new DropPayload(world, x, y, z, items, exp);
    }
}
