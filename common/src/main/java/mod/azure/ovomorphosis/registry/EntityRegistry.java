package mod.azure.ovomorphosis.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.function.Supplier;

import mod.azure.ovomorphosis.entities.AcidEntity;
import mod.azure.ovomorphosis.entities.SilencedEntityTypeBuilder;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.services.XenoServices;

public class EntityRegistry {

    public static final Supplier<EntityType<FacehuggerEntity>> FACEHUGGER = registerEntity(
        "facehugger",
        FacehuggerEntity::new,
        MobCategory.MONSTER,
        0.95f,
        0.3f,
        false
    );

    public static final Supplier<EntityType<OvomorphEntity>> OVOMORPH = registerEntity(
        "ovomorph",
        OvomorphEntity::new,
        MobCategory.MONSTER,
        1.0F,
        1.0F,
        false
    );

    public static final Supplier<EntityType<ChestbursterEntity>> CHESTBURSTER = registerEntity(
        "chestburster",
        ChestbursterEntity::new,
        MobCategory.MONSTER,
        0.5f,
        0.25f,
        false
    );

    public static final Supplier<EntityType<XenomorphEntity>> XENOMORPH = registerEntity(
        "xenomorph",
        XenomorphEntity::new,
        MobCategory.MONSTER,
        0.9f,
        2.9f,
        false
    );

    public static final Supplier<EntityType<AcidEntity>> ACID = registerEntity(
        "acid",
        AcidEntity::new,
        MobCategory.MISC,
        0.8F,
        0.05F,
        true
    );

    private EntityRegistry() {}

    static <T extends Entity> Supplier<EntityType<T>> registerEntity(
        String entityName,
        EntityType.EntityFactory<T> entity,
        MobCategory mobCategory,
        float width,
        float height,
        boolean noSummon
    ) {
        return XenoServices.COMMON_REGISTRY.register(
            BuiltInRegistries.ENTITY_TYPE,
            entityName,
            () -> create(entity, mobCategory, width, height, noSummon).buildWithoutDataFixerCheck()
        );
    }

    static <T extends Entity> SilencedEntityTypeBuilder create(
        EntityType.EntityFactory<T> entity,
        MobCategory mobCategory,
        float width,
        float height,
        boolean noSummon
    ) {
        var builder = EntityType.Builder.of(entity, mobCategory).sized(width, height);
        if (noSummon)
            builder.noSummon();
        return (SilencedEntityTypeBuilder) builder;
    }

    public static void initialize() {}
}
