package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.blocks.ResinBlock;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Builds the hive's physical structure: first a hollow dome shell around a shared center, then random tunnels carving
 * outward from that dome once it's complete.
 * <h3>Shared structure, not per-mob blobs</h3> Every xenomorph's activation of this action contributes to the
 * <em>same</em> dome/tunnel network, tracked in the shared {@link HiveMemory} (persisted per-world, same object every
 * xenomorph reads/writes). The first xenomorph to attempt hive expansion with no existing structure claims its own
 * position as {@link HiveMemory#claimDomeCenter}; every activation after that — by any xenomorph, anywhere near the
 * structure — builds toward that one dome/tunnel network instead of starting a new one wherever it happens to be
 * standing.
 * <h3>Two phases</h3>
 * <ol>
 * <li><b>Dome</b> — while {@link HiveMemory#isDomeComplete()} is {@code false}, each activation scans a small area
 * around the <em>mob's own position</em> (not the far-off dome center) for candidate blocks that fall on the dome's
 * shell surface (a hollow hemisphere around the center) and are valid to replace. A mob has to actually be near the
 * shell to contribute on a given tick — this deliberately avoids needing dedicated pathfinding logic in this action;
 * xenomorphs already wander toward the hive/darkness over time (see {@code WanderAction}'s {@code preferDark} bias), so
 * the dome naturally accretes as mobs pass near it. Once enough shell blocks have been placed (a cheap running counter,
 * not an exhaustive scan), the dome is marked complete.</li>
 * <li><b>Tunnels</b> — once the dome is complete, activations instead look for an existing tunnel whose tip is near the
 * mob and extend it a few steps, or spawn a brand new tunnel (up to {@link #MAX_ACTIVE_TUNNELS} concurrent) from a
 * random point on the dome's outer surface, heading outward. A tunnel terminates naturally when its step budget runs
 * out or it runs into something it can't carve through.</li>
 * </ol>
 * <h3>What can be replaced</h3> Both phases accept a block as a valid dig/build target if it's either naturally
 * {@link BlockState#canBeReplaced()} (air, grass, flowers, ...) <em>or</em> tagged {@link ModTags#WEAK_BLOCKS} (dirt,
 * stone, sand, planks, wool, glass, ...) with a sane hardness — the same "soft enough to tunnel through" category used
 * elsewhere in the mod (e.g. {@code BreakToTargetAction}), rather than only ever filling in already-open space.
 */
public final class PlaceResinAction<E extends Mob> implements Action<E> {

    private static final int MAX_LIGHT_LEVEL = 4;

    /** How far around the mob's own position to scan for dome-shell or tunnel-adjacent candidates each activation. */
    private static final int LOCAL_SCAN_RADIUS = 12;

    /** Outer radius of the dome hemisphere, measured from {@link HiveMemory#getDomeCenter()}. */
    private static final double DOME_RADIUS = 10.0D;

    /** The shell is the band between {@code DOME_RADIUS} and {@code DOME_RADIUS - DOME_SHELL_THICKNESS}. */
    private static final double DOME_SHELL_THICKNESS = 2.0D;

    /**
     * Running placed-block count at which the dome is considered complete. A cheap proxy for "the shell is filled in"
     * rather than an exhaustive scan of the whole hemisphere every activation — most of a shell this size is already
     * solid terrain requiring no placement, so this comfortably covers the blocks that actually needed replacing.
     */
    private static final int DOME_COMPLETE_BLOCK_TARGET = 260;

    private static final int MAX_ACTIVE_TUNNELS = 4;

    private static final int TUNNEL_MIN_LENGTH = 24;

    private static final int TUNNEL_MAX_LENGTH = 56;

    /** How close the mob must be to a tunnel's tip to extend that specific tunnel this activation. */
    private static final double TUNNEL_CAPTURE_RADIUS = 5.0D;

    /** How many tunnel steps a single activation may carve, mirroring the dome phase's per-tick block cap. */
    private static final int MAX_TUNNEL_STEPS_PER_TICK = 6;

    /**
     * Number of times, within a single tick, to reshuffle and retry the candidate list when the random per-candidate
     * placement roll happens to reject every entry. Without this, a location with plenty of valid candidates could
     * still report {@link PlanFailureReason#FAILED_NO_VALID_PLACEMENT} purely from bad RNG, incorrectly suppressing
     * {@link AiGoalType#EXPAND_HIVE} at a perfectly good spot.
     */
    private static final int PLACEMENT_ROLL_RETRIES = 3;

    private final int priority;

    private final int placementCooldownTicks;

    private int settleTicks = 0;

    private boolean placed = false;

    public PlaceResinAction(int priority, int placementCooldownTicks) {
        this.priority = priority;
        this.placementCooldownTicks = placementCooldownTicks;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        settleTicks = 0;
        placed = false;
    }

    @Override
    public ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionOutcome.failed();

        var mobPos = mob.blockPosition();
        if (mob.level().getMaxLocalRawBrightness(mobPos) > MAX_LIGHT_LEVEL) {
            // Deliberately attributed to EXPAND_HIVE regardless of ACTIVE_GOAL_TYPE: this action fires
            // opportunistically outside the EXPAND_HIVE goal too (see the tree's off-cooldown random-chance
            // branch), but the failure is always about hive expansion at this location, and that's what the
            // planner needs to suppress.
            return ActionOutcome.failed(PlanFailureReason.FAILED_TOO_BRIGHT, mobPos, AiGoalType.EXPAND_HIVE);
        }

        if (!placed) {
            var hiveMemory = getOrCreateHiveMemory(blackboard);
            var domeCenter = resolveDomeCenter(mob, hiveMemory);

            var count = hiveMemory.isDomeComplete()
                ? tickTunnelPhase(mob, hiveMemory, domeCenter)
                : tickDomePhase(mob, hiveMemory, domeCenter);

            if (count <= 0) {
                return ActionOutcome.failed(
                    PlanFailureReason.FAILED_NO_VALID_PLACEMENT,
                    mobPos,
                    AiGoalType.EXPAND_HIVE
                );
            }

            cooldowns.set(AiKeys.RESIN_PLACE_COOLDOWN, placementCooldownTicks);
            placed = true;
        }

        if (settleTicks++ >= 20)
            return ActionOutcome.SUCCESS;

        return ActionOutcome.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {}

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * Returns the shared dome center, claiming the mob's current position as that center if none exists yet.
     */
    private BlockPos resolveDomeCenter(E mob, HiveMemory hiveMemory) {
        var existing = hiveMemory.getDomeCenter();
        if (existing.isPresent())
            return existing.get();

        var claimed = mob.blockPosition().immutable();
        hiveMemory.claimDomeCenter(claimed);
        return claimed;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Dome phase
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Scans near the mob for dome-shell candidates and places a batch of them, returning how many blocks were placed (0
     * if the mob isn't currently near any unfilled part of the shell).
     */
    private int tickDomePhase(E mob, HiveMemory hiveMemory, BlockPos domeCenter) {
        var candidates = findDomeShellCandidates(mob, domeCenter);
        if (candidates.isEmpty())
            return 0;

        var count = placeBatch(mob, hiveMemory, candidates);
        if (count > 0) {
            hiveMemory.recordDomeBlocksPlaced(count, DOME_COMPLETE_BLOCK_TARGET);
        }
        return count;
    }

    /**
     * Finds candidates near the mob's own position that fall within the dome's shell band (the hollow hemisphere
     * surface, not its solid interior) and are valid to replace with resin.
     */
    private List<BlockPos> findDomeShellCandidates(E mob, BlockPos domeCenter) {
        var level = mob.level();
        var origin = mob.blockPosition();

        List<BlockPos> candidates = new ArrayList<>();

        for (var x = -LOCAL_SCAN_RADIUS; x <= LOCAL_SCAN_RADIUS; x++) {
            for (var y = -LOCAL_SCAN_RADIUS; y <= LOCAL_SCAN_RADIUS; y++) {
                for (var z = -LOCAL_SCAN_RADIUS; z <= LOCAL_SCAN_RADIUS; z++) {
                    var pos = origin.offset(x, y, z);

                    if (pos.getY() < domeCenter.getY())
                        continue; // upper hemisphere only — don't hollow out the ground beneath the dome

                    var distFromCenter = Math.sqrt(pos.distSqr(domeCenter));
                    if (distFromCenter > DOME_RADIUS || distFromCenter < DOME_RADIUS - DOME_SHELL_THICKNESS)
                        continue; // only the shell band, not the dome's hollow interior

                    if (isValidReplacementTarget(level, pos))
                        candidates.add(pos.immutable());
                }
            }
        }

        return candidates;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Tunnel phase
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Either extends an existing tunnel the mob is currently near, or spawns a brand new one from the dome's outer
     * surface (if under {@link #MAX_ACTIVE_TUNNELS}), returning how many blocks were carved this activation.
     */
    private int tickTunnelPhase(E mob, HiveMemory hiveMemory, BlockPos domeCenter) {
        var tunnels = hiveMemory.getActiveTunnels();

        var nearby = findNearestTunnel(mob, tunnels);
        if (nearby != null) {
            return extendTunnel(mob, hiveMemory, nearby);
        }

        if (tunnels.size() < MAX_ACTIVE_TUNNELS) {
            var spawned = spawnTunnel(mob, domeCenter);
            if (spawned != null) {
                tunnels.add(spawned);
                return extendTunnel(mob, hiveMemory, spawned);
            }
        }

        return 0;
    }

    private HiveMemory.TunnelState findNearestTunnel(E mob, List<HiveMemory.TunnelState> tunnels) {
        var origin = mob.blockPosition();
        var captureSq = TUNNEL_CAPTURE_RADIUS * TUNNEL_CAPTURE_RADIUS;

        HiveMemory.TunnelState best = null;
        var bestDistSq = Double.MAX_VALUE;

        for (var tunnel : tunnels) {
            var distSq = origin.distSqr(tunnel.tip());
            if (distSq > captureSq)
                continue;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = tunnel;
            }
        }

        return best;
    }

    /**
     * Picks a random point on the dome's outer surface and starts a new tunnel heading outward from it. Returns
     * {@code null} if the mob isn't near enough to the dome to plausibly start one there (avoids spawning a tunnel
     * whose starting tip is nowhere near any mob able to carve it).
     */
    private HiveMemory.TunnelState spawnTunnel(E mob, BlockPos domeCenter) {
        var random = mob.getRandom();
        var mobPos = mob.blockPosition();

        // Base the new tunnel's direction on the mob's own current bearing from the dome center (with random
        // jitter), rather than picking a fully independent random direction. An independently-random direction would
        // only rarely happen to land near whichever mob is actually available to carve it; biasing toward the mob's
        // own bearing means the computed start point lands near the mob far more reliably, while the jitter still
        // gives each tunnel a distinct heading.
        double baseX = mobPos.getX() - domeCenter.getX();
        double baseY = mobPos.getY() - domeCenter.getY();
        double baseZ = mobPos.getZ() - domeCenter.getZ();
        var baseLen = Math.sqrt(baseX * baseX + baseY * baseY + baseZ * baseZ);
        if (baseLen < 1.0E-4D) {
            baseX = 1.0D;
            baseY = 0.2D;
            baseZ = 0.0D;
            baseLen = Math.sqrt(baseX * baseX + baseY * baseY + baseZ * baseZ);
        }
        baseX /= baseLen;
        baseZ /= baseLen;

        var yawJitter = Math.toRadians((random.nextDouble() * 2.0D - 1.0D) * 35.0D);
        var cos = Math.cos(yawJitter);
        var sin = Math.sin(yawJitter);

        var dirX = baseX * cos - baseZ * sin;
        var dirZ = baseX * sin + baseZ * cos;
        var dirY = Mth.clamp(baseY + (random.nextDouble() * 2.0D - 1.0D) * 0.15D, -0.6D, 0.3D);

        var length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1.0E-4D)
            return null;
        dirX /= length;
        dirY /= length;
        dirZ /= length;

        var startPos = BlockPos.containing(
            domeCenter.getX() + dirX * DOME_RADIUS,
            domeCenter.getY() + dirY * DOME_RADIUS,
            domeCenter.getZ() + dirZ * DOME_RADIUS
        );

        if (mobPos.distSqr(startPos) > TUNNEL_CAPTURE_RADIUS * TUNNEL_CAPTURE_RADIUS * 4)
            return null; // safety net — jitter was too wide this roll to land anywhere near the mob after all

        var stepsTotal = TUNNEL_MIN_LENGTH + random.nextInt(TUNNEL_MAX_LENGTH - TUNNEL_MIN_LENGTH + 1);
        return new HiveMemory.TunnelState(startPos, dirX, dirY, dirZ, stepsTotal);
    }

    /**
     * Carves {@code tunnel} forward by up to {@link #MAX_TUNNEL_STEPS_PER_TICK} steps, occasionally jittering its
     * direction so the path winds organically instead of running dead straight. Terminates (and removes the tunnel from
     * {@code hiveMemory}) early if it runs out of step budget or hits something that can't be carved through.
     *
     * @return how many blocks were actually placed/cleared this activation
     */
    private int extendTunnel(E mob, HiveMemory hiveMemory, HiveMemory.TunnelState tunnel) {
        var level = mob.level();
        var random = mob.getRandom();
        var placedCount = 0;

        for (var step = 0; step < MAX_TUNNEL_STEPS_PER_TICK; step++) {
            if (tunnel.isExhausted())
                break;

            var tip = tunnel.tip();
            var floor = tip.below();
            var head = tip.above();

            var tipState = level.getBlockState(tip);
            var headState = level.getBlockState(head);

            if (!isValidReplacementTarget(level, tip) && !tipState.isAir()) {
                break; // ran into something this tunnel can't carve through — let it die here
            }

            // Carve a 2-tall passage at the tip so mobs can actually walk through it.
            if (!tipState.isAir())
                level.setBlockAndUpdate(tip, Blocks.CAVE_AIR.defaultBlockState());
            if (!headState.isAir() && isValidReplacementTarget(level, head))
                level.setBlockAndUpdate(head, Blocks.CAVE_AIR.defaultBlockState());

            var floorState = level.getBlockState(floor);
            if (!floorState.isFaceSturdy(level, floor, Direction.UP)) {
                // No natural floor here (we just carved through open space) — lay a resin floor instead of leaving
                // the mob to fall through, and occasionally decorate it as a web cross waypoint.
                var placeCross = random.nextFloat() < 0.10F;
                var floorBlockState = placeCross
                    ? BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState()
                    : BlockRegistry.RESIN.get().defaultBlockState().setValue(ResinBlock.LAYERS, 8);
                level.setBlockAndUpdate(floor, floorBlockState);
                if (placeCross)
                    hiveMemory.trackBlock(floor);
            }

            placedCount++;

            // Wander the direction a little each step so the tunnel curves organically rather than running
            // perfectly straight, while keeping the overall heading (renormalized after the jitter).
            var jitter = 0.35D;
            var newDirX = tunnel.dirX() + (random.nextDouble() * 2.0D - 1.0D) * jitter;
            var newDirY = Mth.clamp(tunnel.dirY() + (random.nextDouble() * 2.0D - 1.0D) * (jitter * 0.4D), -0.6D, 0.3D);
            var newDirZ = tunnel.dirZ() + (random.nextDouble() * 2.0D - 1.0D) * jitter;

            var len = Math.sqrt(newDirX * newDirX + newDirY * newDirY + newDirZ * newDirZ);
            if (len < 1.0E-4D) {
                tunnel.advance(tip, tunnel.dirX(), tunnel.dirY(), tunnel.dirZ());
                continue;
            }
            newDirX /= len;
            newDirY /= len;
            newDirZ /= len;

            var nextTip = BlockPos.containing(tip.getX() + newDirX, tip.getY() + newDirY, tip.getZ() + newDirZ);
            if (nextTip.equals(tip)) {
                // Direction too shallow to actually move a full block this step; still count the step so a
                // pathological run of near-zero movement can't loop forever within this activation.
                tunnel.advance(tip, newDirX, newDirY, newDirZ);
                continue;
            }

            tunnel.advance(nextTip, newDirX, newDirY, newDirZ);
        }

        if (tunnel.isExhausted()) {
            hiveMemory.getActiveTunnels().remove(tunnel);
        }

        return placedCount;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Shared candidate validity / placement
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A block is a valid dig/build target if it's naturally replaceable (air, grass, flowers, ...) or tagged
     * {@link ModTags#WEAK_BLOCKS} with sane hardness — mirroring the "soft enough to carve through" category used
     * elsewhere in the mod (e.g. {@code BreakToTargetAction}) — and isn't too brightly lit. Naturally-replaceable
     * candidates additionally require an adjacent solid face so the dome doesn't sprout floating resin in open air;
     * weak-tagged solid blocks are inherently backed by whatever's around them, so that check is skipped for them.
     */
    private boolean isValidReplacementTarget(Level level, BlockPos pos) {
        if (level.getMaxLocalRawBrightness(pos) > MAX_LIGHT_LEVEL)
            return false;

        var state = level.getBlockState(pos);
        var isWeak = state.is(ModTags.WEAK_BLOCKS);
        var isReplaceable = state.canBeReplaced();

        if (!isReplaceable && !isWeak)
            return false;

        if (isWeak) {
            var hardness = state.getDestroySpeed(level, pos);
            return !(hardness < 0f); // tagged but unbreakable regardless — don't touch it
        }

        return hasAdjacentSolid(level, pos);
    }

    private int placeBatch(E mob, HiveMemory hiveMemory, List<BlockPos> candidates) {
        var count = 0;

        for (var attempt = 0; attempt < PLACEMENT_ROLL_RETRIES && count == 0; attempt++) {
            Collections.shuffle(candidates, new Random(mob.getRandom().nextLong()));

            for (var pos : candidates) {
                if (count >= 12)
                    break;
                if (mob.getRandom().nextFloat() > 0.35F)
                    continue;

                var placeResinCross = mob.getRandom().nextFloat() < 0.125F;
                var newState = placeResinCross
                    ? BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState()
                    : BlockRegistry.RESIN.get()
                        .defaultBlockState()
                        .setValue(ResinBlock.LAYERS, 1 + mob.getRandom().nextInt(8));

                mob.level().setBlockAndUpdate(pos, newState);
                hiveMemory.trackBlock(pos);
                count++;
            }
        }

        return count;
    }

    private boolean hasAdjacentSolid(Level level, BlockPos target) {
        var below = target.below();
        var belowState = level.getBlockState(below);

        if (belowState.isFaceSturdy(level, below, Direction.UP))
            return true;

        for (var dir : Direction.Plane.HORIZONTAL) {
            var adj = target.relative(dir);
            var adjState = level.getBlockState(adj);
            if (adjState.isFaceSturdy(level, adj, dir.getOpposite()))
                return true;
        }
        return false;
    }

    private HiveMemory getOrCreateHiveMemory(Blackboard blackboard) {
        var existing = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (existing != null)
            return existing;
        var fresh = new HiveMemory();
        blackboard.set(AiKeys.HIVE_MEMORY, fresh);
        return fresh;
    }
}
