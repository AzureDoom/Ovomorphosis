package mod.azure.xenogenesis.infection;

import net.minecraft.core.BlockPos;

public final class InfectionState {

    public final int duration;

    public int ticks;

    public int ticksSinceLastDamage;

    public boolean hasBurst;

    public BlockPos lastKnownPos;

    public InfectionState(int duration) {
        this.duration = duration;
        this.ticks = 0;
        this.ticksSinceLastDamage = 0;
        this.hasBurst = false;
        this.lastKnownPos = BlockPos.ZERO;
    }

    public boolean isInDamagePhase() {
        return ticks >= (duration - 600);
    }

    public boolean isExpired() {
        return ticks >= duration;
    }
}
