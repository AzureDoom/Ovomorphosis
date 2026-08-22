package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

import java.util.*;

/**
 * A resumable A* search that spends a fixed, small node-expansion budget per call to {@link #step} instead of running
 * to completion (or to its {@code maxSearched} cap) in one synchronous burst.
 * <h3>Why this exists</h3> {@link CrawlingCustomAStar#findPath} and {@link CustomAStar#findPath} are correct but
 * unconditionally synchronous: a single call can expand up to several thousand nodes (see {@code maxSearched} in
 * {@link CrawlingCustomAStar#findPath}) in one server tick. That's fine for one mob, but {@code MoveToTargetAction}
 * re-invokes it every ~20-40 ticks per mob, and every mob whose repath cooldown happens to expire the same tick pays
 * its full search cost on that one tick — the classic "N pathfinders all wake up on the same frame" hitch. This class
 * is the same search, restructured so its cost can be spread across many ticks: call {@link #step(int)} with a small
 * per-tick node budget (mirroring the {@code stepsPerTick}/{@code maxSteps} split in Gigeresque's own incrementally-
 * budgeted {@code Pathfinder}) from a caller that can tolerate the path not being ready immediately, and it will report
 * {@link Status#RUNNING} until either a path is found or the search is exhausted.
 * <h3>What it reuses, deliberately, instead of reimplementing</h3>
 * <ul>
 * <li>{@link CustomAStar.Node}, {@link CustomAStar#reconstruct}, {@link CustomAStar#isCloseEnoughToGoal}, and
 * {@link CustomAStar#normalizeFeet} — the existing open-set node type and bookkeeping helpers.</li>
 * <li>{@link CrawlingCustomAStar#neighbors}, {@link CrawlingCustomAStar#movementCost}, and
 * {@link CrawlingCustomAStar#heuristic} — the existing crawl-aware expansion and cost model, unmodified.</li>
 * <li>The caller's {@link PathNodeCache} — a session is expected to live across many ticks, so (unlike the single-shot
 * {@code findPath} calls, which typically get a cache cleared at the start of the search) callers should pass a cache
 * whose lifetime matches the session, or accept that a long-lived session sees a snapshot of terrain classifications
 * from whenever those cache entries were first populated. See {@link PathNodeCache}'s "Scope rules" for the
 * tradeoff.</li>
 * </ul>
 * This intentionally does not change the underlying search algorithm, cost function, or crawl geometry at all — it is
 * the same A* over the same graph, just paused and resumed instead of run to completion in one call. Combining these
 * four pieces (priority-queue A*, node cache, crawl geometry, incremental budget) is what actually makes the pathfinder
 * scale to a mob population, rather than any one of them alone.
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // Created once when a repath is needed, stored on the action/mob, and driven a little each tick:
 * var session = new IncrementalPathSession(mob, mob.blockPosition(), target.blockPosition(), 96, 1, cache);
 * // ... later, once per tick, until it stops returning RUNNING:
 * var status = session.step(200); // small per-tick node budget
 * if (status == IncrementalPathSession.Status.DONE) {
 *     var path = session.result();
 * } else if (status == IncrementalPathSession.Status.FAILED) {
 *     // no path exists within range; fall back or give up
 * }
 * // status == RUNNING: keep driving the mob along its previous path (if any) and call step() again next tick.
 * }</pre>
 */
public final class IncrementalPathSession {

    public enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    /**
     * Matches {@link CrawlingCustomAStar#findPath}'s cap, so a session searches no harder than the synchronous call
     * would.
     */
    private static final int DEFAULT_MAX_SEARCHED = 6000;

    private final Mob mob;

    private final BlockPos startFeet;

    private final BlockPos goalFeet;

    private final int goalRadius;

    private final int effectiveRange;

    private final int maxSearched;

    private final PathNodeCache cache;

    private final PriorityQueue<CustomAStar.Node> open = new PriorityQueue<>(
        Comparator.comparingDouble(CustomAStar.Node::f)
    );

    private final Map<BlockPos, Double> bestCost = new HashMap<>();

    private final Set<BlockPos> closed = new HashSet<>();

    private CustomAStar.Node bestPartial;

    private double bestPartialScore = Double.MAX_VALUE;

    private int searched = 0;

    private Status status = Status.RUNNING;

    private List<BlockPos> result = List.of();

    public IncrementalPathSession(
        Mob mob,
        BlockPos start,
        BlockPos goal,
        int maxRange,
        int goalRadius,
        PathNodeCache cache
    ) {
        this(mob, start, goal, maxRange, goalRadius, DEFAULT_MAX_SEARCHED, cache);
    }

    public IncrementalPathSession(
        Mob mob,
        BlockPos start,
        BlockPos goal,
        int maxRange,
        int goalRadius,
        int maxSearched,
        PathNodeCache cache
    ) {
        this.mob = mob;
        this.startFeet = CustomAStar.normalizeFeet(start);
        this.goalFeet = CustomAStar.normalizeFeet(goal);
        this.goalRadius = goalRadius;
        this.effectiveRange = Math.min(maxRange, 48);
        this.maxSearched = maxSearched;
        this.cache = cache;

        open.add(new CustomAStar.Node(startFeet, 0.0D, CrawlingCustomAStar.heuristic(startFeet, goalFeet), null));
        bestCost.put(startFeet, 0.0D);
    }

    /**
     * Spends up to {@code nodeBudget} node expansions on this search and returns the resulting status. Safe to call
     * repeatedly after the search has already finished — it just returns the terminal status again without doing any
     * further work.
     *
     * @param nodeBudget the maximum number of nodes to expand in this call (mirrors {@code stepsPerTick} in
     *                   Gigeresque's {@code Pathfinder}); a few hundred is a reasonable per-tick budget for most mob
     *                   populations
     * @return {@link Status#RUNNING} if the budget was exhausted before the search concluded, {@link Status#DONE} if a
     *         path (full or best-effort partial) is ready in {@link #result()}, or {@link Status#FAILED} if no path —
     *         not even a partial one — could be found
     */
    public Status step(int nodeBudget) {
        if (status != Status.RUNNING)
            return status;

        var level = mob.level();
        var spent = 0;

        while (!open.isEmpty() && spent < nodeBudget) {
            if (searched >= maxSearched) {
                return finish();
            }

            searched++;
            spent++;

            var current = open.poll();

            var partialScore = CrawlingCustomAStar.heuristic(current.pos(), goalFeet);
            if (
                partialScore < bestPartialScore
                    && !CustomAStar.solidlySeparatedVertically(level, current.pos(), goalFeet)
            ) {
                bestPartialScore = partialScore;
                bestPartial = current;
            }

            if (!closed.add(current.pos())) {
                continue;
            }

            if (
                CustomAStar.isCloseEnoughToGoal(current.pos(), goalFeet, goalRadius)
                    && !CustomAStar.solidlySeparatedVertically(level, current.pos(), goalFeet)
            ) {
                var fullPath = CrawlingCustomAStar.filterTransitionNodes(
                    CustomAStar.reconstruct(current),
                    level,
                    mob,
                    cache
                );
                CrawlingCustomAStar.debugParticlePath(mob, fullPath, true);
                result = fullPath;
                status = Status.DONE;
                return status;
            }

            for (var next : CrawlingCustomAStar.neighbors(level, mob, current.pos(), goalFeet, cache)) {
                if (closed.contains(next)) {
                    continue;
                }

                if (next.distManhattan(startFeet) > effectiveRange) {
                    continue;
                }

                var stepCost = CrawlingCustomAStar.movementCost(level, mob, current.pos(), next, cache);

                if (stepCost >= 9999.0D) {
                    continue;
                }

                var newG = current.g() + stepCost;
                var oldG = bestCost.getOrDefault(next, Double.MAX_VALUE);

                if (newG < oldG) {
                    bestCost.put(next, newG);
                    var f = newG + CrawlingCustomAStar.heuristic(next, goalFeet);
                    open.add(new CustomAStar.Node(next, newG, f, current));
                }
            }
        }

        if (open.isEmpty()) {
            return finish();
        }

        return Status.RUNNING;
    }

    private Status finish() {
        if (bestPartial != null && bestPartial.parent() != null) {
            var partialPath = CrawlingCustomAStar.filterTransitionNodes(
                CustomAStar.reconstruct(bestPartial),
                mob.level(),
                mob,
                cache
            );
            CrawlingCustomAStar.debugParticlePath(mob, partialPath, false);
            result = partialPath;
            status = Status.DONE;
        } else {
            result = new ArrayList<>();
            status = Status.FAILED;
        }
        return status;
    }

    /** Returns the current status without doing any further search work. */
    public Status status() {
        return status;
    }

    /**
     * Returns the path found so far. Only meaningful once {@link #step} has returned {@link Status#DONE}; empty before
     * then or on {@link Status#FAILED}.
     */
    public List<BlockPos> result() {
        return result;
    }

    /** Total nodes expanded across all {@link #step} calls so far, for diagnostics/tuning the per-tick budget. */
    public int nodesSearched() {
        return searched;
    }
}
