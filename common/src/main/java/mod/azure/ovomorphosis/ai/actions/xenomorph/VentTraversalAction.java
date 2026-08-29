package mod.azure.ovomorphosis.ai.actions.xenomorph;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Carries a mob through a hive vent shortcut once it has already arrived at the entrance (see {@code XenomorphTree}'s
 * {@code AiGoalType.VENT_TRAVERSAL} branch, which handles walking to the entrance via the ordinary
 * {@code destinationMove} action and only hands off to this action on arrival).
 * <h3>Lifecycle</h3>
 * <ol>
 * <li>{@link Phase#WAITING_TO_ENTER} — polls until no nearby player has line of sight on the entrance (see
 * {@link #isWatchedByAnyPlayer}), so entering never looks like teleporting in front of someone. Gives up after
 * {@link #MAX_WAIT_TICKS} rather than stalling forever if a player simply won't look away.</li>
 * <li>{@link Phase#TRAVERSING} — the mob is hidden ({@link Mob#setInvisible}) and made non-colliding
 * ({@link Mob#noPhysics}), teleported to the entrance, and a duration proportional to the entrance-exit distance is
 * counted down. Periodically during this phase, {@link SoundRegistry#VENT_RATTLE} is played from a few other known vent
 * positions near the hive (not necessarily on the direct line between entrance and exit — see
 * {@link HiveMemory#getVentPositionsNear}) so a listening player gets a sense of movement through the duct network
 * without being able to pin down exactly where the mob will emerge.</li>
 * <li>{@link Phase#WAITING_TO_EXIT} — same visibility polling as entry, against the exit position.</li>
 * <li>Reveal — visibility/collision restored, mob teleported to the exit, crawl animation flag cleared, action reports
 * {@link ActionOutcome#success()}. The target reference is untouched throughout (this action never touches
 * {@link CommonBlackboardKeys#TARGET}), so whatever goal was driving the hunt resumes naturally from the mob's new
 * position.</li>
 * </ol>
 * {@link #stop} unconditionally restores visibility/collision/crawl-state and clears any vent-blocked flags this action
 * set, regardless of which phase it's stopped in (success, failure, or interruption) — this is what guarantees a
 * preempted mid-traversal mob can never be left permanently invisible or non-colliding.
 */
public final class VentTraversalAction<E extends Mob, G> implements Action<E, G> {

    private enum Phase {
        WAITING_TO_ENTER,
        TRAVERSING,
        WAITING_TO_EXIT
    }

    /** How far a player's eyes can be from a vent position and still count as "watching" it. */
    private static final double WATCH_CHECK_RANGE = 40.0D;

    /** Ticks to poll for a clear shot before giving up (entry) or emerging anyway rather than stalling (exit). */
    private static final int MAX_WAIT_TICKS = 100;

    /** Approximate crawl speed used to convert entrance-exit distance into a traversal duration, in blocks/tick. */
    private static final double VENT_CRAWL_SPEED = 0.35D;

    private static final int MIN_TRAVERSAL_TICKS = 30;

    private static final int MAX_TRAVERSAL_TICKS = 140;

    /** Ticks between each ambient rattle sound during the traversal. */
    private static final int AMBIENT_SOUND_INTERVAL_TICKS = 25;

    /** Radius around the entrance/exit midpoint to pull ambient rattle positions from. */
    private static final double AMBIENT_SEARCH_RADIUS = 24.0D;

    /** Max number of ambient rattle positions sampled per traversal. */
    private static final int MAX_AMBIENT_SPOTS = 3;

    private final int priority;

    private Phase phase = Phase.WAITING_TO_ENTER;

    private int ticksInPhase;

    private BlockPos entrance;

    private BlockPos exit;

    private int traversalDuration;

    private int traversalTicksElapsed;

    private List<BlockPos> ambientSpots = List.of();

    private int nextAmbientIndex;

    private int nextAmbientAtTick;

    private boolean everHidden;

    public VentTraversalAction(int priority) {
        this.priority = priority;
    }

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        phase = Phase.WAITING_TO_ENTER;
        ticksInPhase = 0;
        traversalTicksElapsed = 0;
        everHidden = false;
        ambientSpots = List.of();
        entrance = blackboard.get(AiKeys.VENT_ENTRANCE);
        exit = blackboard.get(AiKeys.VENT_EXIT);
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        var level = mob.level();
        var memory = blackboard.get(AiKeys.HIVE_MEMORY);

        if (entrance == null || exit == null || memory == null) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
        }

        ticksInPhase++;

        return switch (phase) {
            case WAITING_TO_ENTER -> tickWaitingToEnter(mob, level, memory);
            case TRAVERSING -> tickTraversing(mob, level, memory);
            case WAITING_TO_EXIT -> tickWaitingToExit(mob, level, memory);
        };
    }

    private ActionOutcome<G> tickWaitingToEnter(E mob, Level level, HiveMemory memory) {
        if (!level.getBlockState(entrance).is(ModTags.VENT_BLOCKS)) {
            memory.evictStaleVentBlocks(level);
            return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION, entrance);
        }

        if (isWatchedByAnyPlayer(level, entrance)) {
            memory.setVentBlocked(entrance, true);
            if (ticksInPhase > MAX_WAIT_TICKS) {
                memory.setVentBlocked(entrance, false);
                return ActionOutcome.failed(PlanFailureReason.FAILED_BLOCKED, entrance);
            }
            return ActionOutcome.running();
        }

        memory.setVentBlocked(entrance, false);
        enterVent(mob, level, memory);
        return ActionOutcome.running();
    }

    private void enterVent(E mob, Level level, HiveMemory memory) {
        level.playSound(null, entrance, SoundRegistry.VENT_RATTLE.get(), SoundSource.HOSTILE, 1.0F, 0.9F);
        CrawlController.setWallCrawling(mob, true);
        mob.setInvisible(true);
        mob.noPhysics = true;
        everHidden = true;
        mob.getNavigation().stop();
        mob.setPos(entrance.getX() + 0.5D, entrance.getY(), entrance.getZ() + 0.5D);
        memory.markVentUsed(entrance, mob.level().getGameTime());

        var travelDist = Math.sqrt(entrance.distSqr(exit));
        traversalDuration = Mth.clamp(
            (int) Math.round(travelDist / VENT_CRAWL_SPEED),
            MIN_TRAVERSAL_TICKS,
            MAX_TRAVERSAL_TICKS
        );
        traversalTicksElapsed = 0;

        var midpoint = new BlockPos(
            (entrance.getX() + exit.getX()) / 2,
            (entrance.getY() + exit.getY()) / 2,
            (entrance.getZ() + exit.getZ()) / 2
        );
        var candidates = new ArrayList<>(memory.getVentPositionsNear(midpoint, AMBIENT_SEARCH_RADIUS));
        candidates.remove(entrance);
        candidates.remove(exit);
        shuffle(candidates, mob.getRandom());
        ambientSpots = candidates.size() > MAX_AMBIENT_SPOTS
            ? candidates.subList(0, MAX_AMBIENT_SPOTS)
            : candidates;
        nextAmbientIndex = 0;
        nextAmbientAtTick = AMBIENT_SOUND_INTERVAL_TICKS;

        phase = Phase.TRAVERSING;
        ticksInPhase = 0;
    }

    @SuppressWarnings("unused")
    private ActionOutcome<G> tickTraversing(E mob, Level level, HiveMemory memory) {
        traversalTicksElapsed++;

        if (
            nextAmbientIndex < ambientSpots.size()
                && traversalTicksElapsed >= nextAmbientAtTick
        ) {
            var spot = ambientSpots.get(nextAmbientIndex);
            level.playSound(null, spot, SoundRegistry.VENT_RATTLE.get(), SoundSource.AMBIENT, 0.6F, 1.1F);
            nextAmbientIndex++;
            nextAmbientAtTick += AMBIENT_SOUND_INTERVAL_TICKS;
        }

        if (traversalTicksElapsed >= traversalDuration) {
            phase = Phase.WAITING_TO_EXIT;
            ticksInPhase = 0;
        }

        return ActionOutcome.running();
    }

    private ActionOutcome<G> tickWaitingToExit(E mob, Level level, HiveMemory memory) {
        var emergeAt = level.getBlockState(exit).is(ModTags.VENT_BLOCKS) ? exit : entrance;

        var stalledTooLong = ticksInPhase > MAX_WAIT_TICKS;
        if (!stalledTooLong && isWatchedByAnyPlayer(level, emergeAt)) {
            memory.setVentBlocked(emergeAt, true);
            return ActionOutcome.running();
        }

        memory.setVentBlocked(emergeAt, false);
        revealAt(mob, level, memory, emergeAt);
        return ActionOutcome.success();
    }

    private void revealAt(E mob, Level level, HiveMemory memory, BlockPos pos) {
        mob.setPos(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        mob.setInvisible(false);
        mob.noPhysics = false;
        everHidden = false;
        CrawlController.setWallCrawling(mob, false);
        memory.markVentUsed(pos, mob.level().getGameTime());
        level.playSound(null, pos, SoundRegistry.VENT_RATTLE.get(), SoundSource.HOSTILE, 1.0F, 1.1F);
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        if (everHidden) {
            mob.setInvisible(false);
            mob.noPhysics = false;
        }
        CrawlController.setWallCrawling(mob, false);

        var memory = blackboard.get(AiKeys.HIVE_MEMORY);
        if (memory != null) {
            if (entrance != null)
                memory.setVentBlocked(entrance, false);
            if (exit != null)
                memory.setVentBlocked(exit, false);
        }

        blackboard.remove(AiKeys.VENT_ENTRANCE);
        blackboard.remove(AiKeys.VENT_EXIT);
    }

    /**
     * In-place Fisher-Yates shuffle — {@code mob.getRandom()} returns a {@link RandomSource}, not a
     * {@code java.util.Random}, so {@code Collections.shuffle} isn't usable here directly.
     */
    private static void shuffle(List<BlockPos> list, RandomSource random) {
        for (var i = list.size() - 1; i > 0; i--) {
            var j = random.nextInt(i + 1);
            var tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    /** {@code true} if any nearby non-spectator player has an unobstructed line of sight to {@code pos}. */
    private static boolean isWatchedByAnyPlayer(Level level, BlockPos pos) {
        var to = Vec3.atCenterOf(pos);
        var players = level.getEntitiesOfClass(
            Player.class,
            new AABB(pos).inflate(WATCH_CHECK_RANGE),
            player -> !player.isSpectator()
        );

        for (var player : players) {
            var from = player.getEyePosition();
            if (from.distanceToSqr(to) > WATCH_CHECK_RANGE * WATCH_CHECK_RANGE)
                continue;

            var hit = level.clip(
                new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            );
            if (hit.getType() == HitResult.Type.MISS)
                return true;
        }

        return false;
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }
}
