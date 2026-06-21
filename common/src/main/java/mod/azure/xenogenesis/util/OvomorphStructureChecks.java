package mod.azure.xenogenesis.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public final class OvomorphStructureChecks {

    private OvomorphStructureChecks() {}

    public static boolean isInTargetStructure(ServerLevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        var structureRegistry =
            serverLevel.registryAccess().registryOrThrow(Registries.STRUCTURE);

        for (var holder : structureRegistry.getTagOrEmpty(ModTags.INFESTABLE_STRUCTURES)) {
            var structure = holder.value();

            var start =
                serverLevel.structureManager().getStructureAt(pos, structure);

            if (start != StructureStart.INVALID_START && start.isValid()) {
                return true;
            }
        }

        return false;
    }
}
