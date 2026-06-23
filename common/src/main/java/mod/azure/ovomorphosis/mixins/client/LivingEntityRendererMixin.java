package mod.azure.ovomorphosis.mixins.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mod.azure.ovomorphosis.client.layer.EggmorphResinLayer;

@SuppressWarnings("unchecked")
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Shadow
    protected abstract boolean addLayer(RenderLayer<T, M> feature);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void gigeresque$init(EntityRendererProvider.Context ctx, M model, float shadowRadius, CallbackInfo ci) {
        this.addLayer(new EggmorphResinLayer<>((RenderLayerParent<T, M>) this));
    }
}
