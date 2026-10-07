package io.github.logoutbodies.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Reference counts change only after the platform operation succeeds. */
final class ReferenceTickets<K> {
    private final Map<K, Integer> counts = new HashMap<>();
    private final Consumer<K> acquire;
    private final Consumer<K> release;

    ReferenceTickets(Consumer<K> acquire, Consumer<K> release) {
        this.acquire = acquire;
        this.release = release;
    }

    void retain(K key) {
        int count = counts.getOrDefault(key, 0);
        if (count == 0) acquire.accept(key);
        counts.put(key, count + 1);
    }

    void release(K key) {
        int count = counts.getOrDefault(key, 0);
        if (count == 0) return;
        if (count == 1) {
            release.accept(key);
            counts.remove(key);
        } else counts.put(key, count - 1);
    }
}
