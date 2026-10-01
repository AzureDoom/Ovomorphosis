package mod.azure.ovomorphosis.infection;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class InfectionState {

    public final int duration;

    public int ticks;

    public int ticksSinceLastDamage;

    public boolean hasBurst;

    public BlockPos lastKnownPos;

    public @Nullable ResourceKey<Level> dimension;

    public boolean isPlayer;

    public InfectionState(int duration) {
        this.duration = duration;
        this.ticks = 0;
        this.ticksSinceLastDamage = 0;
        this.hasBurst = false;
        this.lastKnownPos = BlockPos.ZERO;
        this.dimension = null;
        this.isPlayer = false;
    }

    public boolean isInDamagePhase() {
        return ticks >= (duration - 600);
    }

    public boolean isExpired() {
        return ticks >= duration;
    }

    public enum Phase {
        DORMANT,
        SYMPTOMATIC,
        CRITICAL
    }

    public Phase getPhase() {
        var progress = (float) ticks / duration;
        if (progress < 0.3f)
            return Phase.DORMANT;
        if (progress < 0.7f)
            return Phase.SYMPTOMATIC;
        return Phase.CRITICAL;
    }

}
