package mod.azure.xenogenesis.client.chestbuster;

import mod.azure.azurelib.common.animation.AzAnimatorConfig;
import mod.azure.azurelib.common.animation.controller.AzAnimationController;
import mod.azure.azurelib.common.animation.controller.AzAnimationControllerContainer;
import mod.azure.azurelib.common.animation.impl.AzEntityAnimator;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.entities.chestburster.ChestbursterEntity;
import mod.azure.xenogenesis.util.CommonStrings;

public class ChestbursterAnimator extends AzEntityAnimator<ChestbursterEntity> {

    private static final ResourceLocation ANIMATIONS = CommonMod.modResource(
        "animations/entity/chestburster.animation.json"
    );

    public ChestbursterAnimator() {
        super(AzAnimatorConfig.defaultConfig());
    }

    @Override
    public void registerControllers(AzAnimationControllerContainer<ChestbursterEntity> animationControllerContainer) {
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.BASE_CONTROLLER).build()
        );
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.ATTACK_CONTROLLER).build()
        );
    }

    @Override
    public @NotNull ResourceLocation getAnimationLocation(ChestbursterEntity chestburster) {
        return ANIMATIONS;
    }
}
