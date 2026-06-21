package mod.azure.xenogenesis.ai.util;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import mod.azure.xenogenesis.CommonMod;

/**
 * Utility methods for visualizing AI state with server-side particles during development.
 */
public final class AiDebugUtils {

    private AiDebugUtils() {}

    /**
     * Spawns a line of dust particles between {@code from} and {@code to} so that pathfinding decisions can be
     * inspected in-game.
     * <p>
     * Only sends particles when the mob is in a {@code ServerLevel}.
     *
     * @param mob  the mob whose level receives the particles
     * @param from the start of the path segment
     * @param to   the end of the path segment
     */
    public static void sendParticlePath(Mob mob, Vec3 from, Vec3 to) {
        if (!CommonMod.getConfig().enablePathfindingDebug)
            return;
        if (!(mob.level() instanceof ServerLevel serverLevel))
            return;

        var color = new Vector3f(1.0F, 0.2F, 0.1F);
        var particle = new DustParticleOptions(color, 0.5F);

        var steps = 24;

        for (var i = 0; i <= steps; i++) {
            var t = i / (double) steps;

            var x = from.x + (to.x - from.x) * t;
            var y = from.y + (to.y - from.y) * t;
            var z = from.z + (to.z - from.z) * t;

            // serverLevel.sendParticles(
            // particle,
            // x,
            // y + 0.35D,
            // z,
            // 1,
            // 0.0D,
            // 0.0D,
            // 0.0D,
            // 0.0D
            // );
        }
    }
}
