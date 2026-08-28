package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * A single known vent-block position tracked by a {@link HiveMemory}.
 * <p>
 * There's deliberately no separate "hive ID / cluster" field here: which {@link HiveMemory} a node belongs to is
 * already implicit in which hive's map it's stored under, so a node is only ever reasoned about in the context of the
 * one hive that placed it — exactly what keeps vent travel a hive-territory perk rather than a global shortcut network
 * (see {@link HiveMemory#findVentShortcut}).
 * <p>
 * {@link #linkedExits} is a cache, not a source of truth: it's recomputed by {@link HiveMemory#relinkVentCluster}
 * whenever a new vent block registers nearby, grouping vent blocks within {@link HiveMemory#VENT_LINK_RADIUS} of each
 * other (transitively) into the same network. Two vent blocks in the same network are treated as mutually reachable
 * "entrance" and "exit" points for {@code VentTraversalAction}.
 *
 * @param position     this node's world position
 * @param linkedExits  other vent positions in the same connected network, including transitively-linked ones
 * @param lastUsedTick game tick this vent was last used for a traversal, or {@code 0} if never used
 * @param blocked      {@code true} while a player has line of sight on this position, making it temporarily unusable
 *                     (see {@code VentTraversalAction}'s visibility gating)
 */
public record HiveVentNode(
    BlockPos position,
    List<BlockPos> linkedExits,
    long lastUsedTick,
    boolean blocked
) {

    public HiveVentNode withLinkedExits(List<BlockPos> newLinkedExits) {
        return new HiveVentNode(position, List.copyOf(newLinkedExits), lastUsedTick, blocked);
    }

    public HiveVentNode withLastUsedTick(long tick) {
        return new HiveVentNode(position, linkedExits, tick, blocked);
    }

    public HiveVentNode withBlocked(boolean isBlocked) {
        return new HiveVentNode(position, linkedExits, lastUsedTick, isBlocked);
    }
}
