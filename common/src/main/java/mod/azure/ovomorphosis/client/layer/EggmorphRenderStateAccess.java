package mod.azure.ovomorphosis.client.layer;

/**
 * Implemented on {@code LivingEntityRenderState} via {@code LivingEntityRenderStateMixin}. Carries the eggmorph
 * progress from extraction (where the entity is available) to {@link EggmorphResinLayer} (where only the render state
 * is).
 */
public interface EggmorphRenderStateAccess {

    float ovomorphosis$getEggmorphProgress();

    void ovomorphosis$setEggmorphProgress(float progress);
}
