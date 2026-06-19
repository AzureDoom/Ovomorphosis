package mod.azure.xenogenesis.client.xenomorph;

import mod.azure.azurelib.common.animation.AzAnimatorConfig;
import mod.azure.azurelib.common.animation.controller.AzAnimationController;
import mod.azure.azurelib.common.animation.controller.AzAnimationControllerContainer;
import mod.azure.azurelib.common.animation.impl.AzEntityAnimator;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.entities.xenomorph.XenomorphEntity;
import mod.azure.xenogenesis.util.CommonStrings;

public class XenomorphAnimator extends AzEntityAnimator<XenomorphEntity> {

    private static final ResourceLocation ANIMATIONS = CommonMod.modResource(
        "animations/entity/xenomorph.animation.json"
    );

    public XenomorphAnimator() {
        super(AzAnimatorConfig.defaultConfig());
    }

    @Override
    public void registerControllers(AzAnimationControllerContainer<XenomorphEntity> animationControllerContainer) {
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.BASE_CONTROLLER).build()
        );
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.ATTACK_CONTROLLER).build()
        );
    }

    @Override
    public @NotNull ResourceLocation getAnimationLocation(XenomorphEntity xenomorph) {
        return ANIMATIONS;
    }
}
