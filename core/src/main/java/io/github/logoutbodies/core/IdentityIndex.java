package io.github.logoutbodies.core;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Entity equality can compare UUIDs; identity is deliberately stronger here. */
public final class IdentityIndex<E> {
    private final Map<UUID, E> byOwner = new HashMap<>();
    private final Map<E, UUID> byEntity = new IdentityHashMap<>();

    public void add(UUID owner, E entity) {
        if (byOwner.containsKey(owner) || byEntity.containsKey(entity))
            throw new IllegalStateException("Duplicate body identity");
        byOwner.put(owner, entity);
        byEntity.put(entity, owner);
    }

    public Optional<E> body(UUID owner) {
        return Optional.ofNullable(byOwner.get(owner));
    }

    public Optional<UUID> owner(E entity) {
        return Optional.ofNullable(byEntity.get(entity));
    }

    public boolean remove(UUID owner, E entity) {
        if (byOwner.get(owner) != entity) return false;
        byOwner.remove(owner);
        byEntity.remove(entity);
        return true;
    }
}
