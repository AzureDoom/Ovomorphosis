package mod.azure.ovomorphosis.ai.core;

import java.util.HashMap;
import java.util.Map;

/**
 * A type-safe key-value store used to share AI state between {@link BehaviorNode}s and {@link Action}s within a single
 * mob's brain.
 * <p>
 * Each {@link MobBrainRuntime} owns exactly one blackboard. All stored values are transient and discarded when the mob
 * is removed from the world.
 */
public final class Blackboard {

    private final Map<String, Object> values = new HashMap<>();

    /**
     * Stores {@code value} under {@code key}, replacing any prior value.
     *
     * @param <T>   the type of value being stored
     * @param key   the string key used to retrieve this value later
     * @param value the value to store
     */
    public <T> void set(String key, T value) {
        values.put(key, value);
    }

    /**
     * Retrieves a value by key, returning {@code null} if the key is absent or the stored object is not an instance of
     * {@code type}.
     *
     * @param <T>  the expected type
     * @param key  the key to look up
     * @param type the expected class of the stored value
     * @return the stored value cast to {@code T}, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        var value = values.get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    /**
     * Returns {@code true} if a value is currently associated with {@code key}.
     *
     * @param key the key to check
     * @return {@code true} if the key is present
     */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /**
     * Removes the value associated with {@code key}, if any.
     *
     * @param key the key to remove
     */
    public void remove(String key) {
        values.remove(key);
    }
}
