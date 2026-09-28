package mod.azure.ovomorphosis.mixins.client;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import mod.azure.ovomorphosis.client.layer.EggmorphRenderStateAccess;

@Mixin(LivingEntityRenderState.class)
public class LivingEntityRenderStateMixin implements EggmorphRenderStateAccess {

    @Unique
    private float ovomorphosis$eggmorphProgress;

    @Override
    public float ovomorphosis$getEggmorphProgress() {
        return ovomorphosis$eggmorphProgress;
    }

    @Override
    public void ovomorphosis$setEggmorphProgress(float progress) {
        this.ovomorphosis$eggmorphProgress = progress;
    }
}
