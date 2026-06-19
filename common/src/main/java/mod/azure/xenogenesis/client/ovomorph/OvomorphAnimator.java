package mod.azure.xenogenesis.client.ovomorph;

import mod.azure.azurelib.common.animation.AzAnimatorConfig;
import mod.azure.azurelib.common.animation.controller.AzAnimationController;
import mod.azure.azurelib.common.animation.controller.AzAnimationControllerContainer;
import mod.azure.azurelib.common.animation.impl.AzEntityAnimator;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.entities.ovomorph.OvomorphEntity;
import mod.azure.xenogenesis.util.CommonStrings;

public class OvomorphAnimator extends AzEntityAnimator<OvomorphEntity> {

    private static final ResourceLocation ANIMATIONS = CommonMod.modResource(
        "animations/entity/ovomorph.animation.json"
    );

    public OvomorphAnimator() {
        super(AzAnimatorConfig.defaultConfig());
    }

    @Override
    public void registerControllers(AzAnimationControllerContainer<OvomorphEntity> animationControllerContainer) {
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.BASE_CONTROLLER).build()
        );
    }

    @Override
    public @NotNull ResourceLocation getAnimationLocation(OvomorphEntity ovomorph) {
        return ANIMATIONS;
    }
}
