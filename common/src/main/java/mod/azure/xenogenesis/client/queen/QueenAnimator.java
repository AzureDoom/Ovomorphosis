package mod.azure.xenogenesis.client.queen;

import mod.azure.azurelib.common.animation.AzAnimatorConfig;
import mod.azure.azurelib.common.animation.controller.AzAnimationController;
import mod.azure.azurelib.common.animation.controller.AzAnimationControllerContainer;
import mod.azure.azurelib.common.animation.impl.AzEntityAnimator;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.entities.queen.QueenEntity;
import mod.azure.xenogenesis.util.CommonStrings;

public class QueenAnimator extends AzEntityAnimator<QueenEntity> {

    private static final ResourceLocation ANIMATIONS = CommonMod.modResource(
        "animations/entity/queen.animation.json"
    );

    public QueenAnimator() {
        super(AzAnimatorConfig.defaultConfig());
    }

    @Override
    public void registerControllers(AzAnimationControllerContainer<QueenEntity> animationControllerContainer) {
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.BASE_CONTROLLER).build()
        );
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.ATTACK_CONTROLLER).build()
        );
    }

    @Override
    public @NotNull ResourceLocation getAnimationLocation(QueenEntity queen) {
        return ANIMATIONS;
    }
}
