package mod.azure.ovomorphosis.mixins.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mod.azure.ovomorphosis.client.layer.EggmorphRenderState;
import mod.azure.ovomorphosis.client.layer.EggmorphRenderStateAccess;
import mod.azure.ovomorphosis.client.layer.EggmorphResinLayer;

@SuppressWarnings("unchecked")
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {

    @Shadow
    protected abstract boolean addLayer(RenderLayer<S, M> layer);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ovomorphosis$init(EntityRendererProvider.Context ctx, M model, float shadowRadius, CallbackInfo ci) {
        this.addLayer(new EggmorphResinLayer<>((RenderLayerParent<S, M>) (Object) this));
    }

    /**
     * Copies eggmorph progress into the render state while the entity is still available. Always writes a value (0 when
     * not eggmorphing) because render state instances are reused between entities, so a stale value would otherwise
     * leak onto whichever entity reuses the state next.
     */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void ovomorphosis$extractEggmorph(
        LivingEntity entity,
        LivingEntityRenderState state,
        float partialTicks,
        CallbackInfo ci
    ) {
        var id = entity.getId();
        var progress = EggmorphRenderState.isEggmorphing(id) ? EggmorphRenderState.get(id) : 0f;
        ((EggmorphRenderStateAccess) state).ovomorphosis$setEggmorphProgress(progress);
    }
}
