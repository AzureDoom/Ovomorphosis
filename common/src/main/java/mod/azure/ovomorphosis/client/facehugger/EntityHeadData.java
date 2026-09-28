package mod.azure.ovomorphosis.client.facehugger;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Credit to Boston for this code
 */
public record EntityHeadData(
    Vec3 size,
    Vec3 position,
    Vec3 pivot
) {

    private static final double MULTIPLIER = 1 / 16.0;

    private static final EntityHeadData COW = adjust(vec3(8, 8, 6), vec3(-4, 16, -14), vec3(0, 20, -8));

    private static final EntityHeadData HORSE = adjust(vec3(6, 5, 7), vec3(-3, 28, -11), vec3(0, 22, -9));

    private static final EntityHeadData LLAMA = adjust(vec3(4, 4, 9), vec3(-2, 27, -16), vec3(0, 17, -11));

    private static final EntityHeadData PIGLIN = adjust(vec3(10, 8, 8), vec3(-5, 24, -4), vec3(0, 24, 0));

    private static final EntityHeadData VILLAGER = adjust(vec3(8, 10, 8), vec3(-4, 24, -4), vec3(0, 24, 0));

    public static final Map<EntityType<?>, EntityHeadData> ENTITY_HEAD_DATA_BY_TYPE = new HashMap<>(
        Map.ofEntries(
            Map.entry(EntityTypes.CAMEL, adjust(vec3(7, 8, 19), vec3(-3.5, 22, -24), vec3(0, 23, -9))),
            Map.entry(EntityTypes.COW, COW),
            Map.entry(EntityTypes.DONKEY, HORSE),
            Map.entry(EntityTypes.DOLPHIN, adjust(vec3(2, 2, 4), vec3(-1, 0, -13), vec3(0, 0, -3))),
            Map.entry(EntityTypes.EVOKER, VILLAGER),
            Map.entry(EntityTypes.FOX, adjust(vec3(8, 6, 6), vec3(-4, 3.5, -8), vec3(1, 7.5, -3))),
            Map.entry(EntityTypes.GOAT, adjust(vec3(5, 7, 10), vec3(-3, 16, -14), vec3(-0.5, 10, 0))),
            Map.entry(EntityTypes.HOGLIN, adjust(vec3(14, 6, 19), vec3(-7, 21, -24), vec3(0, 22, -5))),
            Map.entry(EntityTypes.HORSE, HORSE),
            Map.entry(EntityTypes.ILLUSIONER, VILLAGER),
            Map.entry(EntityTypes.LLAMA, LLAMA),
            Map.entry(EntityTypes.MOOSHROOM, COW),
            Map.entry(EntityTypes.MULE, HORSE),
            Map.entry(EntityTypes.PANDA, adjust(vec3(13, 10, 9), vec3(-6.5, 7.5, -21), vec3(0, 12.5, -17))),
            Map.entry(EntityTypes.PIG, adjust(vec3(8, 8, 8), vec3(-4, 8, -14), vec3(0, 12, -6))),
            Map.entry(EntityTypes.PIGLIN, PIGLIN),
            Map.entry(EntityTypes.PIGLIN_BRUTE, PIGLIN),
            Map.entry(EntityTypes.PILLAGER, VILLAGER),
            Map.entry(EntityTypes.PLAYER, adjust(vec3(8, 8, 8), vec3(-4, 24, -4), vec3(0, 24, 0))),
            Map.entry(EntityTypes.POLAR_BEAR, adjust(vec3(7, 7, 7), vec3(-3.5, 10, -19), vec3(0, 14, -16 - 3))),
            Map.entry(EntityTypes.RAVAGER, adjust(vec3(16, 20, 16), vec3(-8, 14, -24), vec3(0, 14, -10 - 2.5))),
            Map.entry(EntityTypes.SHEEP, adjust(vec3(6, 6, 8), vec3(-3, 16, -14), vec3(0, 18, -8))),
            Map.entry(EntityTypes.SNIFFER, adjust(vec3(13, 18, 11), vec3(-6.5, 5, -31), vec3(0, 12.5, -19.5))),
            Map.entry(EntityTypes.TRADER_LLAMA, LLAMA),
            Map.entry(EntityTypes.VILLAGER, VILLAGER),
            Map.entry(EntityTypes.VINDICATOR, VILLAGER),
            Map.entry(EntityTypes.WITCH, VILLAGER),
            Map.entry(EntityTypes.WANDERING_TRADER, VILLAGER),
            Map.entry(EntityTypes.WOLF, adjust(vec3(6, 6, 4), vec3(-3, 7.5, -9), vec3(1, 10.5, -7))),
            Map.entry(EntityTypes.ZOGLIN, adjust(vec3(14, 6, 19), vec3(-7, 21, -24), vec3(0, 22, -5))),
            Map.entry(EntityTypes.ZOMBIE_VILLAGER, VILLAGER)
        )
    );

    private static EntityHeadData adjust(Vec3 size, Vec3 position, Vec3 pivot) {
        return new EntityHeadData(size.scale(MULTIPLIER), position.scale(MULTIPLIER), pivot.scale(MULTIPLIER));
    }

    private static Vec3 vec3(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

}
