package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import mod.azure.ovomorphosis.CommonMod;

/**
 * A resumable A* search that spends a fixed, small node-expansion budget per call to {@link #step} instead of running
 * to completion (or to its {@code maxSearched} cap) in one synchronous burst.
 * <h3>Why this exists</h3> {@link CrawlingCustomAStar#findPath} and {@link CustomAStar#findPath} are correct but
 * unconditionally synchronous: a single call can expand up to several thousand nodes in one server tick. That's fine
 * for one mob, but {@code MoveToTargetAction} re-invokes it every ~20-40 ticks per mob, and every mob whose repath
 * cooldown happens to expire the same tick pays its full search cost on that one tick — the classic "N pathfinders all
 * wake up on the same frame" hitch. This class is the same search, restructured so its cost can be spread across many
 * ticks: call {@link #step(int)} with a small per-tick node budget from a caller that can tolerate the path not being
 * ready immediately, and it will report {@link Status#RUNNING} until either a path is found or the search is exhausted.
 * <h3>One engine, two movement models</h3> The core loop (open set, closed set, best-cost map, best-partial tracking)
 * is identical regardless of how a mob moves — what differs between a ground-walking mob and a wall-crawling one is
 * only <em>which neighbors are reachable from a position and what they cost</em>. Rather than duplicating the whole
 * engine per movement model (which is what the first version of this class did — it only ever drove
 * {@link CrawlingCustomAStar}, leaving {@code CustomAStar}'s ground-only searches fully synchronous), the neighbor,
 * cost, and heuristic functions are supplied at construction time via {@link NeighborFunction}, {@link CostFunction},
 * and {@link HeuristicFunction}. {@link #crawling} and {@link #normal} are the two supplied wirings — one over
 * {@link CrawlingCustomAStar}'s crawl-aware model, one over {@link CustomAStar}'s plain ground-walking model — but a
 * caller with a genuinely different movement model (say, a flying mob) can supply its own three functions and get the
 * same incremental budgeting for free.
 * <h3>Node cache lifetime</h3> A session is expected to live across many ticks, so (unlike the single-shot
 * {@code findPath} calls, which typically get a cache cleared at the start of the search) callers should give a session
 * a {@link PathNodeCache} whose lifetime matches the session — created fresh when the session starts, discarded when it
 * ends — rather than the same cache used for other per-tick classification queries. See {@link PathNodeCache}'s "Scope
 * rules" and {@link PathNodeCache#invalidate} for the tradeoffs of keeping a cache alive across ticks while the world
 * can still change underneath it. {@link #normal} doesn't need a cache at all — the plain ground-walking model doesn't
 * use one — so it doesn't take one.
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // Created once when a repath is needed, stored on the action/mob, and driven a little each tick:
 * var session = IncrementalPathSession.crawling(mob, mob.blockPosition(), target.blockPosition(), 96, 1, cache);
 * // ... later, once per tick, until it stops returning RUNNING:
 * var status = session.step(300); // small per-tick node budget
 * if (status == IncrementalPathSession.Status.DONE) {
 *     var path = session.result();
 * } else if (status == IncrementalPathSession.Status.FAILED) {
 *     // no path exists within range; fall back or give up — see PhasedPathSession for chaining fallbacks under
 *     // the same shared per-tick budget instead of falling back to a synchronous search.
 * }
 * // status == RUNNING: keep driving the mob along its previous path (if any) and call step() again next tick.
 * }</pre>
 *
 * @see PhasedPathSession
 */
public final class IncrementalPathSession {

    public enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    /**
     * Which movement model this session searches under. Doesn't affect the core search algorithm at all (that's
     * entirely determined by the supplied {@link NeighborFunction}/{@link CostFunction}/{@link HeuristicFunction}) — it
     * only selects which post-processing/debug-visualization convention the finished path goes through, matching
     * whatever the corresponding synchronous {@code findPath} already did.
     */
    public enum Mode {
        NORMAL,
        CRAWLING
    }

    /** Supplies the set of positions reachable from {@code pos} for a given movement model. */
    @FunctionalInterface
    public interface NeighborFunction {

        List<BlockPos> get(Level level, Mob mob, BlockPos pos, BlockPos goal, PathNodeCache cache);
    }

    /** Supplies the cost of moving from {@code from} to {@code to}; {@code >= 9999.0} means impassable. */
    @FunctionalInterface
    public interface CostFunction {

        double get(Level level, Mob mob, BlockPos from, BlockPos to, PathNodeCache cache);
    }

    /** Supplies the estimated remaining cost between two positions. */
    @FunctionalInterface
    public interface HeuristicFunction {

        double get(BlockPos a, BlockPos b);
    }

    /**
     * Matches {@link CrawlingCustomAStar#findPath}'s cap, so a crawling session searches no harder than the synchronous
     * call would.
     */
    private static final int DEFAULT_CRAWLING_MAX_SEARCHED = 6000;

    /**
     * Matches {@link CustomAStar#findPath}'s cap, so a normal session searches no harder than the synchronous call
     * would.
     */
    private static final int DEFAULT_NORMAL_MAX_SEARCHED = 2000;

    private final Mob mob;

    private final BlockPos startFeet;

    private final BlockPos goalFeet;

    private final int goalRadius;

    private final int effectiveRange;

    private final int maxSearched;

    private final PathNodeCache cache;

    private final Mode mode;

    private final NeighborFunction neighborFn;

    private final CostFunction costFn;

    private final HeuristicFunction heuristicFn;

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

    private IncrementalPathSession(
        Mob mob,
        BlockPos start,
        BlockPos goal,
        int maxRange,
        int goalRadius,
        int maxSearched,
        PathNodeCache cache,
        Mode mode,
        NeighborFunction neighborFn,
        CostFunction costFn,
        HeuristicFunction heuristicFn
    ) {
        this.mob = mob;
        this.startFeet = CustomAStar.normalizeFeet(start);
        this.goalFeet = CustomAStar.normalizeFeet(goal);
        this.goalRadius = goalRadius;
        this.effectiveRange = Math.min(maxRange, 48);
        this.maxSearched = maxSearched;
        this.cache = cache;
        this.mode = mode;
        this.neighborFn = neighborFn;
        this.costFn = costFn;
        this.heuristicFn = heuristicFn;

        open.add(new CustomAStar.Node(startFeet, 0.0D, heuristicFn.get(startFeet, goalFeet), null));
        bestCost.put(startFeet, 0.0D);
    }

    /**
     * Builds a session using {@link CrawlingCustomAStar}'s crawl-aware neighbor/cost/heuristic model, with the same
     * {@code maxSearched} cap as {@link CrawlingCustomAStar#findPath}.
     *
     * @param cache a {@link PathNodeCache} dedicated to this session's lifetime — see this class's node-cache-lifetime
     *              notes above
     */
    public static IncrementalPathSession crawling(
        Mob mob,
        BlockPos start,
        BlockPos goal,
        int maxRange,
        int goalRadius,
        PathNodeCache cache
    ) {
        return crawling(mob, start, goal, maxRange, goalRadius, DEFAULT_CRAWLING_MAX_SEARCHED, cache);
    }

    public static IncrementalPathSession crawling(
        Mob mob,
        BlockPos start,
        BlockPos goal,
        int maxRange,
        int goalRadius,
        int maxSearched,
        PathNodeCache cache
    ) {
        return new IncrementalPathSession(
            mob,
            start,
            goal,
            maxRange,
            goalRadius,
            maxSearched,
            cache,
            Mode.CRAWLING,
            CrawlingCustomAStar::neighbors,
            CrawlingCustomAStar::movementCost,
            CrawlingCustomAStar::heuristic
        );
    }

    /**
     * Builds a session using {@link CustomAStar}'s plain ground-walking neighbor/cost/heuristic model, with the same
     * {@code maxSearched} cap as {@link CustomAStar#findPath}. No {@link PathNodeCache} is needed — the ground-only
     * model doesn't use one.
     */
    public static IncrementalPathSession normal(Mob mob, BlockPos start, BlockPos goal, int maxRange, int goalRadius) {
        return normal(mob, start, goal, maxRange, goalRadius, DEFAULT_NORMAL_MAX_SEARCHED);
    }

    public static IncrementalPathSession normal(
        Mob mob,
        BlockPos start,
        BlockPos goal,
        int maxRange,
        int goalRadius,
        int maxSearched
    ) {
        return new IncrementalPathSession(
            mob,
            start,
            goal,
            maxRange,
            goalRadius,
            maxSearched,
            null,
            Mode.NORMAL,
            (level, m, pos, g, c) -> CustomAStar.neighbors(level, m, pos),
            (level, m, from, to, c) -> CustomAStar.movementCost(level, m, from, to),
            CustomAStar::heuristic
        );
    }

    /**
     * Spends up to {@code nodeBudget} node expansions on this search and returns the resulting status. Safe to call
     * repeatedly after the search has already finished — it just returns the terminal status again without doing any
     * further work.
     *
     * @param nodeBudget the maximum number of nodes to expand in this call; a few hundred is a reasonable per-tick
     *                   budget for most mob populations
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

            var partialScore = heuristicFn.get(current.pos(), goalFeet);
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
                result = finalizePath(CustomAStar.reconstruct(current), true);
                status = Status.DONE;
                return status;
            }

            for (var next : neighborFn.get(level, mob, current.pos(), goalFeet, cache)) {
                if (closed.contains(next)) {
                    continue;
                }

                if (next.distManhattan(startFeet) > effectiveRange) {
                    continue;
                }

                var stepCost = costFn.get(level, mob, current.pos(), next, cache);

                if (stepCost >= 9999.0D) {
                    continue;
                }

                var newG = current.g() + stepCost;
                var oldG = bestCost.getOrDefault(next, Double.MAX_VALUE);

                if (newG < oldG) {
                    bestCost.put(next, newG);
                    var f = newG + heuristicFn.get(next, goalFeet);
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
            result = finalizePath(CustomAStar.reconstruct(bestPartial), false);
            status = Status.DONE;
        } else {
            result = new ArrayList<>();
            status = Status.FAILED;
        }
        return status;
    }

    /** Applies the same post-processing/debug-visualization the corresponding synchronous {@code findPath} would. */
    private List<BlockPos> finalizePath(List<BlockPos> rawPath, boolean fullPath) {
        if (mode == Mode.CRAWLING) {
            var filtered = CrawlingCustomAStar.filterTransitionNodes(rawPath, mob.level(), mob, cache);
            CrawlingCustomAStar.debugParticlePath(mob, filtered, fullPath);
            return filtered;
        }

        if (CommonMod.getConfig().enablePathfindingDebug) {
            for (var i = 0; i < rawPath.size() - 1; i++) {
                AiDebugUtils.sendParticlePath(
                    mob,
                    Vec3.atCenterOf(rawPath.get(i)),
                    Vec3.atCenterOf(rawPath.get(i + 1))
                );
            }
        }
        return rawPath;
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

    /** Which movement model this session searches under. */
    public Mode mode() {
        return mode;
    }
}
