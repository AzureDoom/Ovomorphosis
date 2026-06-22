package mod.azure.ovomorphosis.ai.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks named, tick-based cooldowns for a single mob.
 * <p>
 * Each entry counts down by one every time {@link #tick()} is called and is automatically removed when it reaches zero.
 * Use {@link AiKeys} constants as keys to avoid typos.
 */
public final class Cooldowns {

    private final Map<String, Integer> cooldowns = new HashMap<>();

    /**
     * Advances all active cooldowns by one tick, removing any that have expired.
     */
    public void tick() {
        cooldowns.entrySet().removeIf(entry -> {
            int next = entry.getValue() - 1;
            entry.setValue(next);
            return next <= 0;
        });
    }

    /**
     * Returns {@code true} if no cooldown is currently active for {@code key}.
     *
     * @param key the cooldown key to check
     * @return {@code true} if the cooldown has expired or was never set
     */
    public boolean ready(String key) {
        return !cooldowns.containsKey(key);
    }

    /**
     * Returns {@code true} if a cooldown is currently active for {@code key}.
     *
     * @param key the cooldown key to check
     * @return {@code true} if the cooldown has not yet expired
     */
    public boolean isOnCooldown(String key) {
        return !ready(key);
    }

    /**
     * Sets a new cooldown of {@code ticks} duration, overwriting any existing value.
     *
     * @param key   the cooldown key
     * @param ticks the number of ticks to wait before the cooldown expires
     */
    public void set(String key, int ticks) {
        cooldowns.put(key, ticks);
    }
}
