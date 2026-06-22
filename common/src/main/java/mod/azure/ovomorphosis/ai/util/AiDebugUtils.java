package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.CommonMod;

/**
 * Utility methods for visualizing AI state with server-side particles during development.
 */
public final class AiDebugUtils {

    private static final int SEGMENT_STEPS = 4;

    private AiDebugUtils() {}

    public static void sendParticlePath(Mob mob, Vec3 from, Vec3 to) {
        if (!CommonMod.getConfig().enablePathfindingDebug)
            return;
        if (!(mob.level() instanceof ServerLevel serverLevel))
            return;

        sendNodeMarker(serverLevel, mob, from);
        sendNodeMarker(serverLevel, mob, to);

        for (var i = 0; i <= SEGMENT_STEPS; i++) {
            var t = i / (double) SEGMENT_STEPS;
            serverLevel.sendParticles(
                ParticleTypes.HAPPY_VILLAGER,
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t,
                from.z + (to.z - from.z) * t,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D
            );
        }
    }

    private static void sendNodeMarker(ServerLevel serverLevel, Mob mob, Vec3 pos) {
        var blockPos = BlockPos.containing(pos);
        var level = mob.level();

        var isClimb = MovementUtils.isSafeClimbNode(level, mob, blockPos);
        var isWalk = CustomAStar.canStandAt(level, mob, blockPos);

        var marker = isClimb && !isWalk
            ? ParticleTypes.DRIPPING_WATER
            : isWalk && !isClimb
                ? ParticleTypes.FLAME
                : ParticleTypes.END_ROD;

        serverLevel.sendParticles(marker, pos.x, pos.y + 0.35D, pos.z, 3, 0.0D, 0.0D, 0.0D, 0.0D);
    }
}
