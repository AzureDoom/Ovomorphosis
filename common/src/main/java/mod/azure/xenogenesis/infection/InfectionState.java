package mod.azure.xenogenesis.infection;

public final class InfectionState {

    public static final int MIN_DURATION = 2400;

    public static final int MAX_DURATION = 6000;

    public static final int DAMAGE_PHASE_LEAD = 600;

    public static final float DAMAGE_PER_INTERVAL = 1.0f;

    public static final int DAMAGE_INTERVAL = 20;

    public static final float BURST_HEALTH_THRESHOLD = 1.0f;

    public final int duration;

    public int ticks;

    public int ticksSinceLastDamage;

    public boolean hasBurst;

    public InfectionState(int duration) {
        this.duration = duration;
        this.ticks = 0;
        this.ticksSinceLastDamage = 0;
        this.hasBurst = false;
    }

    public boolean isInDamagePhase() {
        return ticks >= (duration - DAMAGE_PHASE_LEAD);
    }

    public boolean isExpired() {
        return ticks >= duration;
    }
}
