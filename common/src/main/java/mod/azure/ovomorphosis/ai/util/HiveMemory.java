package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import mod.azure.ovomorphosis.ai.actions.xenomorph.PlaceResinAction;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.infection.EggmorphTracker;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Per-mob cache of known {@code RESIN_WEB_CROSS} positions.
 */
public final class HiveMemory {

    private UUID hiveId = UUID.randomUUID();

    private static final int MAX_ENTRIES = 256;

    private static final int MISS_THRESHOLD = 8;

    private static final String NBT_KEY = "pos";

    private final Deque<BlockPos> ownedWebCrosses =
        new ArrayDeque<>();

    public UUID getHiveId() {
        return hiveId;
    }

    private int missCount = 0;

    // --- Shared hive-structure state (dome + tunnels), used by PlaceResinAction ---

    /**
     * Center of the shared hive dome. Claimed once, by whichever xenomorph first attempts to expand the hive with no
     * existing structure recorded; every subsequent {@link PlaceResinAction} activation — by any xenomorph — builds
     * toward this same dome/tunnel network rather than each mob starting its own.
     */
    private BlockPos domeCenter = null;

    /**
     * Running count of dome-shell blocks successfully placed, used as a cheap completion proxy (see
     * {@link PlaceResinAction}).
     */
    private int domeFillCount = 0;

    /** Once {@code true}, {@link PlaceResinAction} switches from building the dome shell to extending tunnels. */
    private boolean domeComplete = false;

    /** Tunnels currently being carved outward from the dome. Persisted so tunnel state survives a chunk/mob reload. */
    private final List<TunnelState> activeTunnels = new ArrayList<>();

    /**
     * Mutable cursor for a single in-progress tunnel: where its tip currently is, which direction it's heading, and how
     * many more steps it has before it terminates naturally.
     */
    public static final class TunnelState {

        private BlockPos tip;

        private double dirX;

        private double dirY;

        private double dirZ;

        private int remainingSteps;

        public TunnelState(BlockPos tip, double dirX, double dirY, double dirZ, int remainingSteps) {
            this.tip = tip.immutable();
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.remainingSteps = remainingSteps;
        }

        public BlockPos tip() {
            return tip;
        }

        public double dirX() {
            return dirX;
        }

        public double dirY() {
            return dirY;
        }

        public double dirZ() {
            return dirZ;
        }

        public int remainingSteps() {
            return remainingSteps;
        }

        /** Advances the cursor to a new tip/direction and consumes one step of its remaining budget. */
        public void advance(BlockPos newTip, double newDirX, double newDirY, double newDirZ) {
            this.tip = newTip.immutable();
            this.dirX = newDirX;
            this.dirY = newDirY;
            this.dirZ = newDirZ;
            this.remainingSteps--;
        }

        public boolean isExhausted() {
            return remainingSteps <= 0;
        }
    }

    // --- Hive vent network, used by VentTraversalAction as a long-distance pathfinding shortcut ------------------

    /** Safety cap mirroring {@link #MAX_ENTRIES}; a hive's vent network is expected to stay well under this. */
    private static final int MAX_VENT_ENTRIES = 128;

    /**
     * Transitive linking radius: two vent blocks within this many blocks of each other (directly, or through a chain of
     * other vent blocks each within range of the next) are considered part of the same vent network, and are therefore
     * valid entrance/exit pairs for each other. Deliberately generous relative to block-to-block adjacency since vent
     * blocks placed during hive construction (see {@code PlaceResinAction}) won't always end up literally touching.
     */
    private static final double VENT_LINK_RADIUS = 6.0D;

    /**
     * Flat cost added to a vent route's distance estimate, representing the time/risk of the crawl-through itself. This
     * is what the "10 + ventTraversalCost + 12 vs. 80" comparison in {@link #findVentShortcut} actually is — everything
     * else is straight-line distance. Kept modest rather than representing the literal traversal-tick duration
     * ({@code VentTraversalAction}'s own timing is separate) — a cost too close to that duration would make vents
     * mathematically unable to ever win at the shorter distances they're most useful for.
     */
    private static final double VENT_TRAVERSAL_COST = 20.0D;

    /**
     * A vent route qualifies once its total is under {@code ordinaryEstimate * VENT_SHORTCUT_MARGIN}. Set above 1.0
     * deliberately: {@code ordinaryEstimate} is only a straight-line-distance proxy for the real walked/crawled path
     * (see {@code XenomorphGoalPlanner#ORDINARY_ROUTE_ESTIMATE_MULTIPLIER}), which is itself an underestimate of the
     * real route around obstacles — so a vent route that comes out slightly *more* than the straight-line estimate is
     * still very plausibly a real shortcut, not just noise.
     */
    private static final double VENT_SHORTCUT_MARGIN = 1.2D;

    private final Map<BlockPos, HiveVentNode> ventNodes = new LinkedHashMap<>();

    /**
     * A found vent shortcut: enter at {@code entrance}, emerge at {@code exit}. Both are guaranteed to be in the same
     * linked network (see {@link #relinkVentCluster}), and were valid, unblocked, live vent blocks at query time.
     */
    public record VentRoute(
        BlockPos entrance,
        BlockPos exit
    ) {}

    /**
     * Records {@code pos} as a vent block belonging to this hive. Safe to call redundantly. Triggers
     * {@link #relinkVentCluster} so the new block (and anything it bridges together) is immediately reflected in
     * {@link #findVentShortcut} results.
     *
     * @param pos the vent block's position (stored as an immutable copy)
     */
    public void registerVentBlock(BlockPos pos) {
        var immutable = pos.immutable();
        if (ventNodes.containsKey(immutable) || ventNodes.size() >= MAX_VENT_ENTRIES)
            return;

        ventNodes.put(immutable, new HiveVentNode(immutable, List.of(), 0L, false));
        relinkVentCluster(immutable);
    }

    /**
     * Removes {@code pos} from this hive's known vent blocks (called when a vent block is broken), then re-links the
     * remainder so no stale reference to it lingers in another node's {@link HiveVentNode#linkedExits()}.
     */
    public void unregisterVentBlock(BlockPos pos) {
        if (ventNodes.remove(pos.immutable()) != null) {
            relinkAllVentClusters();
        }
    }

    /**
     * Scans a bounded box around {@code origin} for {@link ModTags#VENT_BLOCKS}-tagged positions not yet known to this
     * hive, and registers any found. This is a safety net for vent blocks that predate/bypass the normal placement hook
     * (e.g. hand-placed before this scan existed, or placed by worldgen/a structure) — ordinarily, placing a tagged
     * vent block registers it immediately via its block class, and this never has anything to find.
     * <p>
     * Bounded to a modest box (not a full sphere) and meant to be called sparingly (gate behind a cooldown) since it is
     * a real block-state scan, not a cheap registry lookup like {@link #findNearestOwnedWebCross}.
     * <p>
     * Callers must mark the owning {@code OvomorphosisSavedData} dirty (e.g.
     * {@code OvomorphosisSavedData.markHiveDirty}) when this returns {@code true} — mutating this object in place
     * doesn't do that on its own, and a discovered vent that's never flushed to disk will appear to work for the rest
     * of the session but silently vanish on reload.
     *
     * @param level  the level to scan
     * @param origin center of the scan box
     * @param radius horizontal radius of the scan box, in blocks; the vertical range is a fixed, smaller band
     * @return {@code true} if at least one new vent block was found and registered
     */
    public boolean syncVentBlocksNear(Level level, BlockPos origin, int radius) {
        var mutable = new BlockPos.MutableBlockPos();
        var verticalRadius = Math.min(radius, 6);
        var foundAny = false;

        for (var x = -radius; x <= radius; x++) {
            for (var z = -radius; z <= radius; z++) {
                for (var y = -verticalRadius; y <= verticalRadius; y++) {
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);

                    if (ventNodes.containsKey(mutable))
                        continue;

                    if (level.getBlockState(mutable).is(ModTags.VENT_BLOCKS)) {
                        registerVentBlock(mutable);
                        foundAny = true;
                    }
                }
            }
        }

        return foundAny;
    }

    /**
     * Recomputes {@link HiveVentNode#linkedExits()} for every vent block transitively within {@link #VENT_LINK_RADIUS}
     * of {@code seed} (i.e. the whole connected network {@code seed} belongs to), so each node's linked-exits list
     * stays an accurate cache rather than going stale as more vent blocks register nearby over time.
     */
    private void relinkVentCluster(BlockPos seed) {
        var radiusSq = VENT_LINK_RADIUS * VENT_LINK_RADIUS;

        var component = new ArrayList<BlockPos>();
        var visited = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(seed);
        visited.add(seed);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            component.add(current);

            for (var candidate : ventNodes.keySet()) {
                if (visited.contains(candidate))
                    continue;
                if (current.distSqr(candidate) <= radiusSq) {
                    visited.add(candidate);
                    queue.add(candidate);
                }
            }
        }

        for (var pos : component) {
            var others = new ArrayList<BlockPos>(component.size() - 1);
            for (var other : component) {
                if (!other.equals(pos))
                    others.add(other);
            }

            var existing = ventNodes.get(pos);
            if (existing != null) {
                ventNodes.put(pos, existing.withLinkedExits(others));
            }
        }
    }

    /** Re-links every currently-known vent network from scratch. Used after a bulk change such as eviction/load. */
    private void relinkAllVentClusters() {
        var pending = new HashSet<>(ventNodes.keySet());

        while (!pending.isEmpty()) {
            var seed = pending.iterator().next();
            relinkVentCluster(seed);

            var node = ventNodes.get(seed);
            if (node != null) {
                pending.removeAll(node.linkedExits());
            }
            pending.remove(seed);
        }
    }

    /**
     * Removes vent entries whose live block no longer carries {@link ModTags#VENT_BLOCKS} (broken, replaced, etc.),
     * then re-links the remaining networks so stale entries can't linger in some other node's
     * {@link HiveVentNode#linkedExits()} list.
     */
    public void evictStaleVentBlocks(Level level) {
        var removedAny = ventNodes.keySet().removeIf(pos -> !level.getBlockState(pos).is(ModTags.VENT_BLOCKS));
        if (removedAny) {
            relinkAllVentClusters();
        }
    }

    /** Marks {@code pos} as used at {@code tick} — currently informational only (surfaced for future tuning/debug). */
    public void markVentUsed(BlockPos pos, long tick) {
        var node = ventNodes.get(pos);
        if (node != null) {
            ventNodes.put(pos, node.withLastUsedTick(tick));
        }
    }

    /**
     * Marks {@code pos} as temporarily unusable (or usable again) — set by {@code VentTraversalAction} while a player
     * has line of sight on this position, so no mob (this one or another) tries to use a watched vent and make the
     * traversal look like obvious teleportation.
     */
    public void setVentBlocked(BlockPos pos, boolean blocked) {
        var node = ventNodes.get(pos);
        if (node != null) {
            ventNodes.put(pos, node.withBlocked(blocked));
        }
    }

    /**
     * Finds the cheapest known vent shortcut from {@code from} to {@code to}, if any beats {@code ordinaryEstimate} (an
     * approximate cost of the normal route, supplied by the caller — see {@code XenomorphGoalPlanner}) by at least
     * {@link #VENT_SHORTCUT_MARGIN}. Only considers vent blocks this hive itself placed/registered, which is what keeps
     * vent travel a territory perk of a mature hive rather than a global shortcut network — an isolated xenomorph with
     * no built-up vent network simply never has a candidate here.
     *
     * @param level            the level to validate live vent blocks against
     * @param from             the mob's current position
     * @param to               the mob's destination (typically the target's position)
     * @param ordinaryEstimate an approximate cost of the ordinary route, in the same units as distance (blocks)
     * @return the cheapest qualifying route, or empty if none beats the ordinary route by enough to be worth it
     */
    public Optional<VentRoute> findVentShortcut(Level level, BlockPos from, BlockPos to, double ordinaryEstimate) {
        if (ventNodes.isEmpty())
            return Optional.empty();

        BlockPos bestEntrance = null;
        BlockPos bestExit = null;
        var bestTotal = Double.MAX_VALUE;

        for (var entry : ventNodes.entrySet()) {
            var entrance = entry.getKey();
            var node = entry.getValue();

            if (node.blocked() || node.linkedExits().isEmpty())
                continue;
            if (!level.getBlockState(entrance).is(ModTags.VENT_BLOCKS))
                continue;

            var distToEntrance = Math.sqrt(from.distSqr(entrance));
            if (distToEntrance >= bestTotal)
                continue; // can't possibly beat the best total found so far even before adding the other two legs

            for (var exit : node.linkedExits()) {
                var exitNode = ventNodes.get(exit);
                if (exitNode == null || exitNode.blocked())
                    continue;
                if (!level.getBlockState(exit).is(ModTags.VENT_BLOCKS))
                    continue;

                var total = distToEntrance + VENT_TRAVERSAL_COST + Math.sqrt(exit.distSqr(to));
                if (total < bestTotal) {
                    bestTotal = total;
                    bestEntrance = entrance;
                    bestExit = exit;
                }
            }
        }

        if (bestEntrance == null || bestTotal >= ordinaryEstimate * VENT_SHORTCUT_MARGIN)
            return Optional.empty();

        return Optional.of(new VentRoute(bestEntrance, bestExit));
    }

    /** Vent positions known to this hive within {@code maxRange} of {@code origin} — used to place ambient rattle. */
    public Collection<BlockPos> getVentPositionsNear(BlockPos origin, double maxRange) {
        var maxRangeSq = maxRange * maxRange;
        List<BlockPos> result = new ArrayList<>();
        for (var pos : ventNodes.keySet()) {
            if (origin.distSqr(pos) <= maxRangeSq) {
                result.add(pos);
            }
        }
        return result;
    }

    /**
     * Records a single cross-block position. Oldest entry is evicted when the deque is full.
     *
     * @param pos the position to remember (stored as an immutable copy)
     */
    public void trackOwnedWebCross(BlockPos pos) {
        if (ownedWebCrosses.contains(pos))
            return;

        if (ownedWebCrosses.size() >= MAX_ENTRIES) {
            ownedWebCrosses.pollFirst();
        }

        ownedWebCrosses.addLast(pos.immutable());
    }

    /**
     * Returns the shared dome center, if one has been claimed yet.
     *
     * @return the dome center, or empty if no xenomorph has started building yet
     */
    public Optional<BlockPos> getDomeCenter() {
        return Optional.ofNullable(domeCenter);
    }

    /**
     * Claims {@code pos} as the shared dome center. A no-op if a center is already claimed — first claim wins, so all
     * xenomorphs converge on one structure rather than each mob starting its own dome wherever it happens to be.
     *
     * @param pos the candidate center (typically the claiming mob's current position)
     */
    public void claimDomeCenter(BlockPos pos) {
        if (domeCenter == null) {
            domeCenter = pos.immutable();
        }
    }

    /** @return {@code true} once the dome shell is considered fully built and tunnel-building should begin */
    public boolean isDomeComplete() {
        return domeComplete;
    }

    /**
     * Records that {@code count} dome-shell blocks were just placed, and flips {@link #isDomeComplete()} once the
     * running total reaches {@code completionThreshold}.
     */
    public void recordDomeBlocksPlaced(int count, int completionThreshold) {
        domeFillCount += count;
        if (domeFillCount >= completionThreshold) {
            domeComplete = true;
        }
    }

    /** @return the mutable list of tunnels currently being carved; callers may add/remove entries directly */
    public List<TunnelState> getActiveTunnels() {
        return activeTunnels;
    }

    private static final double NEEDS_SCAN_RADIUS = 64.0D;

    private static final long NEEDS_RECOMPUTE_INTERVAL_TICKS = 100L;

    private static final int LIGHT_SAMPLE_LIMIT = 8;

    private static final int FEW_HOSTS_THRESHOLD = 2;

    private static final int HOST_SURPLUS_THRESHOLD = 4;

    private static final int LITTLE_RESIN_THRESHOLD = 6;

    private static final int ILLUMINATED_THRESHOLD = 8;

    private static final int CROWDED_POPULATION_THRESHOLD = 6;

    private static final int EXPANSION_SECTORS = 8;

    private static final int THRIVING_XENO_THRESHOLD = 3;

    private static final int SUSTAINED_ATTACK_THRESHOLD = 3;

    private static final int THREAT_FRESH_WINDOW_TICKS = 600;

    private static final int MAX_THREAT_RECORDS = 12;

    private int xenoCount = 0;

    private int ovomorphCount = 0;

    private int restrainedHostCount = 0;

    private int hiveLightLevel = 0;

    private long needsRecomputedAtTick = Long.MIN_VALUE;

    /** A single recorded incursion — used to detect repeated attacks on the hive rather than one-off skirmishes. */
    public record ThreatRecord(
        BlockPos pos,
        long tick
    ) {}

    private final Deque<ThreatRecord> recentThreats = new ArrayDeque<>();

    /**
     * Coarse lifecycle stage of the hive, derived from its structural completeness and population rather than stored
     * directly — always current, never stale. Used to bias baseline behavior: a {@code HATCHLING} hive should bootstrap
     * population aggressively, while a {@code THRIVING} one has more established territory worth holding.
     */
    public enum NestMaturity {
        /** No dome has been claimed/started yet. */
        HATCHLING,
        /** Dome shell is under construction. */
        GROWING,
        /** Dome complete, but population and tunnel network are still small. */
        ESTABLISHED,
        /** Dome complete, tunnels extending, and population past {@link #THRIVING_XENO_THRESHOLD}. */
        THRIVING
    }

    /**
     * Refreshes {@link #xenoCount}, {@link #ovomorphCount}, {@link #restrainedHostCount}, and {@link #hiveLightLevel}
     * by scanning around the dome center — but only if {@link #NEEDS_RECOMPUTE_INTERVAL_TICKS} have passed since the
     * last refresh. Safe to call every planning cycle from any xenomorph sharing this hive; the throttle is on the
     * shared instance, so many mobs calling this frequently still only triggers one actual scan per interval.
     *
     * @param level       the level to scan (must be the hive's dimension)
     * @param currentTick the current game tick, used both to throttle and to evict stale threat records
     */
    public void recomputeNeedsIfDue(Level level, long currentTick) {
        if (domeCenter == null)
            return;
        if (currentTick - needsRecomputedAtTick < NEEDS_RECOMPUTE_INTERVAL_TICKS)
            return;
        needsRecomputedAtTick = currentTick;

        var aabb = AABB.ofSize(
            Vec3.atCenterOf(domeCenter),
            NEEDS_SCAN_RADIUS * 2,
            NEEDS_SCAN_RADIUS * 2,
            NEEDS_SCAN_RADIUS * 2
        );

        xenoCount = level.getEntitiesOfClass(XenomorphEntity.class, aabb).size();
        ovomorphCount = level.getEntitiesOfClass(OvomorphEntity.class, aabb).size();
        restrainedHostCount = EggmorphTracker.countActiveNear(domeCenter, NEEDS_SCAN_RADIUS);
        hiveLightLevel = computeHiveLightLevel(level);

        evictStaleThreats(currentTick);
    }

    private int computeHiveLightLevel(Level level) {
        if (domeCenter == null)
            return 0;

        var brightest = level.getMaxLocalRawBrightness(domeCenter);
        var sampled = 0;
        for (var pos : ownedWebCrosses) {
            if (sampled >= LIGHT_SAMPLE_LIMIT)
                break;
            brightest = Math.max(brightest, level.getMaxLocalRawBrightness(pos));
            sampled++;
        }
        return brightest;
    }

    public int xenoCount() {
        return xenoCount;
    }

    public int ovomorphCount() {
        return ovomorphCount;
    }

    public int restrainedHostCount() {
        return restrainedHostCount;
    }

    public int hiveLightLevel() {
        return hiveLightLevel;
    }

    /** Few hosts currently being converted — the hive should prioritize hunting/capturing over other goals. */
    public boolean hasFewHosts() {
        return restrainedHostCount < FEW_HOSTS_THRESHOLD;
    }

    /**
     * Plenty of hosts already restrained, but not much resin infrastructure to route more captures toward — the hive
     * should prioritize construction (placing resin / expanding) over capturing yet more hosts it has nowhere to put.
     */
    public boolean hasHostSurplusWithLittleResin() {
        return restrainedHostCount >= HOST_SURPLUS_THRESHOLD
            && getOwnedWebCrosses().size() < LITTLE_RESIN_THRESHOLD;
    }

    /** The hive's core has been exposed to enough light that killing light sources should take priority. */
    public boolean isHeavilyIlluminated() {
        return hiveLightLevel > ILLUMINATED_THRESHOLD;
    }

    /**
     * Rough count of distinct horizontal compass sectors ({@value #EXPANSION_SECTORS} total) that currently have an
     * active tunnel heading into them. This is necessarily an undercount of the hive's <em>total</em> explored
     * territory — a finished or blocked tunnel is removed from {@link #activeTunnels} by {@link PlaceResinAction} once
     * exhausted — but it's a cheap, self-correcting proxy for "does the hive still have obviously unclaimed directions
     * to expand into right now" without needing separate persisted state.
     */
    private int exploredExpansionSectorCount() {
        var sectors = new HashSet<Integer>();
        for (var tunnel : activeTunnels) {
            var angle = Math.atan2(tunnel.dirZ(), tunnel.dirX());
            if (angle < 0)
                angle += 2 * Math.PI;
            var sector = (int) (angle / (2 * Math.PI / EXPANSION_SECTORS)) % EXPANSION_SECTORS;
            sectors.add(sector);
        }
        return sectors.size();
    }

    /** {@code true} if there's at least one horizontal direction not currently claimed by an active tunnel. */
    public boolean hasRoomToExpand() {
        return exploredExpansionSectorCount() < EXPANSION_SECTORS;
    }

    /**
     * The hive's combined xenomorph + ovomorph population has grown large relative to
     * {@link #CROWDED_POPULATION_THRESHOLD}. Combine with {@link #hasRoomToExpand()} to decide whether the response
     * should be "extend tunnels outward" specifically, versus just generally needing more room.
     */
    public boolean isCrowded() {
        return (xenoCount + ovomorphCount) >= CROWDED_POPULATION_THRESHOLD;
    }

    /**
     * Records a single incursion near the hive (e.g. a hostile target found near hive territory during goal planning).
     * Bounded to {@link #MAX_THREAT_RECORDS}, oldest evicted first — this only needs to answer "has this been happening
     * repeatedly lately", not keep a full history.
     */
    public void recordThreat(BlockPos pos, long tick) {
        if (recentThreats.size() >= MAX_THREAT_RECORDS) {
            recentThreats.pollFirst();
        }
        recentThreats.addLast(new ThreatRecord(pos.immutable(), tick));
    }

    /** @return how many recorded threats are still within {@link #THREAT_FRESH_WINDOW_TICKS} of {@code currentTick} */
    public int recentThreatCount(long currentTick) {
        var count = 0;
        for (var threat : recentThreats) {
            if (currentTick - threat.tick() <= THREAT_FRESH_WINDOW_TICKS)
                count++;
        }
        return count;
    }

    /** The hive has been attacked repeatedly and recently enough that defensive aggression should increase. */
    public boolean isUnderSustainedAttack(long currentTick) {
        return recentThreatCount(currentTick) >= SUSTAINED_ATTACK_THRESHOLD;
    }

    private void evictStaleThreats(long currentTick) {
        recentThreats.removeIf(threat -> currentTick - threat.tick() > THREAT_FRESH_WINDOW_TICKS);
    }

    /**
     * Coarse lifecycle stage derived from structural completeness and population — see {@link NestMaturity}. Recomputed
     * from existing state each call rather than stored, so it's always consistent with the current dome/
     * tunnel/population state without needing separate persistence or invalidation.
     */
    public NestMaturity nestMaturity() {
        if (domeCenter == null)
            return NestMaturity.HATCHLING;
        if (!domeComplete)
            return NestMaturity.GROWING;
        if (activeTunnels.isEmpty() && xenoCount < THRIVING_XENO_THRESHOLD)
            return NestMaturity.ESTABLISHED;
        return NestMaturity.THRIVING;
    }

    /**
     * Like {@link #findNearestOwnedWebCross}, but additionally requires the candidate to be dark enough to serve as a
     * genuine hideout rather than just "the closest bit of hive". This is what lets low-health retreat logic route a
     * wounded xenomorph toward known cover instead of merely running away — something a vanilla mob's health-based
     * fleeing has no equivalent of, since it has no memory of where its territory's dark spots are.
     * <p>
     * Returns {@code Optional.empty()} if no owned web cross in range meets the darkness threshold; callers should fall
     * back to {@link #findNearestOwnedWebCross} in that case rather than treating this as "no hive nearby at all".
     *
     * @param level    the level to read block/light state from
     * @param origin   the position to search outward from
     * @param maxRange maximum search radius in blocks
     * @param maxLight maximum local raw light level (0-15) a candidate position may have to still count as "dark"
     * @return the nearest sufficiently dark owned web cross within range, if any
     */
    public Optional<BlockPos> findNearestDarkOwnedWebCross(
        Level level,
        BlockPos origin,
        double maxRange,
        int maxLight
    ) {
        var maxRangeSqr = maxRange * maxRange;

        BlockPos best = null;
        var bestDistSqr = Double.MAX_VALUE;

        for (var pos : ownedWebCrosses) {
            var distSqr = origin.distSqr(pos);

            if (distSqr > maxRangeSqr)
                continue;

            if (
                !level.getBlockState(pos)
                    .is(BlockRegistry.RESIN_WEB_CROSS.get())
            ) {
                continue;
            }

            if (level.getMaxLocalRawBrightness(pos) > maxLight)
                continue;

            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = pos;
            }
        }

        return Optional.ofNullable(best);
    }

    public Optional<BlockPos> findNearestOwnedWebCross(
        Level level,
        BlockPos origin,
        double maxRange
    ) {
        var maxRangeSqr = maxRange * maxRange;

        BlockPos best = null;
        var bestDistSqr = Double.MAX_VALUE;
        var missesThisCall = 0;

        for (var pos : ownedWebCrosses) {
            var distSqr = origin.distSqr(pos);

            if (distSqr > maxRangeSqr)
                continue;

            if (
                !level.getBlockState(pos)
                    .is(BlockRegistry.RESIN_WEB_CROSS.get())
            ) {
                missesThisCall++;
                continue;
            }

            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = pos;
            }
        }

        missCount += missesThisCall;

        if (missCount >= MISS_THRESHOLD) {
            evictStaleOwnedWebCrosses(level);
            missCount = 0;
        }

        return Optional.ofNullable(best);
    }

    public void evictStaleOwnedWebCrosses(Level level) {
        ownedWebCrosses.removeIf(
            pos -> !level.getBlockState(pos)
                .is(BlockRegistry.RESIN_WEB_CROSS.get())
        );
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("hiveId", hiveId);
        var list = new ListTag();
        for (var pos : ownedWebCrosses) {
            var entry = new CompoundTag();
            entry.put(NBT_KEY, NbtUtils.writeBlockPos(pos));
            list.add(entry);
        }

        tag.put("blocks", list);

        if (domeCenter != null) {
            tag.put("domeCenter", NbtUtils.writeBlockPos(domeCenter));
        }
        tag.putInt("domeFillCount", domeFillCount);
        tag.putBoolean("domeComplete", domeComplete);

        var tunnelList = new ListTag();
        for (var tunnel : activeTunnels) {
            var entry = new CompoundTag();
            entry.put("tip", NbtUtils.writeBlockPos(tunnel.tip()));
            entry.putDouble("dx", tunnel.dirX());
            entry.putDouble("dy", tunnel.dirY());
            entry.putDouble("dz", tunnel.dirZ());
            entry.putInt("remaining", tunnel.remainingSteps());
            tunnelList.add(entry);
        }
        tag.put("tunnels", tunnelList);

        tag.putInt("xenoCount", xenoCount);
        tag.putInt("ovomorphCount", ovomorphCount);
        tag.putInt("restrainedHostCount", restrainedHostCount);
        tag.putInt("hiveLightLevel", hiveLightLevel);
        tag.putLong("needsRecomputedAtTick", needsRecomputedAtTick);

        var threatList = new ListTag();
        for (var threat : recentThreats) {
            var entry = new CompoundTag();
            entry.put("pos", NbtUtils.writeBlockPos(threat.pos()));
            entry.putLong("tick", threat.tick());
            threatList.add(entry);
        }
        tag.put("threats", threatList);

        var ventList = new ListTag();
        for (var node : ventNodes.values()) {
            var entry = new CompoundTag();
            entry.put("pos", NbtUtils.writeBlockPos(node.position()));
            entry.putLong("lastUsed", node.lastUsedTick());
            ventList.add(entry);
        }
        tag.put("vents", ventList);

        return tag;
    }

    public static HiveMemory load(CompoundTag tag) {
        var memory = new HiveMemory();

        if (tag.hasUUID("hiveId")) {
            memory.hiveId = tag.getUUID("hiveId");
        }

        if (tag.contains("blocks", Tag.TAG_LIST)) {
            var list = tag.getList("blocks", Tag.TAG_COMPOUND);

            for (var i = 0; i < list.size(); i++) {
                memory.trackOwnedWebCross(NbtUtils.readBlockPos(list.getCompound(i)));
            }
        }

        if (tag.contains("domeCenter")) {
            memory.domeCenter = NbtUtils.readBlockPos(tag.getCompound("domeCenter")).immutable();
        }

        memory.domeFillCount =
            tag.getInt("domeFillCount");

        memory.domeComplete =
            tag.getBoolean("domeComplete");

        if (tag.contains("tunnels", Tag.TAG_LIST)) {
            var tunnelList =
                tag.getList("tunnels", Tag.TAG_COMPOUND);

            for (var i = 0; i < tunnelList.size(); i++) {
                var entry = tunnelList.getCompound(i);

                var tip = NbtUtils.readBlockPos(entry.getCompound("tip"));

                memory.activeTunnels.add(
                    new TunnelState(
                        tip,
                        entry.getDouble("dx"),
                        entry.getDouble("dy"),
                        entry.getDouble("dz"),
                        entry.getInt("remaining")
                    )
                );
            }
        }

        memory.xenoCount = tag.getInt("xenoCount");
        memory.ovomorphCount = tag.getInt("ovomorphCount");
        memory.restrainedHostCount = tag.getInt("restrainedHostCount");
        memory.hiveLightLevel = tag.getInt("hiveLightLevel");
        memory.needsRecomputedAtTick = tag.getLong("needsRecomputedAtTick");

        if (tag.contains("threats", Tag.TAG_LIST)) {
            var threatList = tag.getList("threats", Tag.TAG_COMPOUND);
            for (var i = 0; i < threatList.size(); i++) {
                var entry = threatList.getCompound(i);
                var pos = NbtUtils.readBlockPos(entry.getCompound("pos"));
                memory.recentThreats.addLast(new ThreatRecord(pos, entry.getLong("tick")));
            }
        }

        if (tag.contains("vents", Tag.TAG_LIST)) {
            var ventList = tag.getList("vents", Tag.TAG_COMPOUND);
            for (var i = 0; i < ventList.size(); i++) {
                var entry = ventList.getCompound(i);
                var pos = NbtUtils.readBlockPos(entry.getCompound("pos"));
                memory.ventNodes.put(pos, new HiveVentNode(pos, List.of(), entry.getLong("lastUsed"), false));
            }
            memory.relinkAllVentClusters();
        }

        return memory;
    }

    public Collection<BlockPos> getOwnedWebCrosses() {
        return Collections.unmodifiableCollection(ownedWebCrosses);
    }
}
