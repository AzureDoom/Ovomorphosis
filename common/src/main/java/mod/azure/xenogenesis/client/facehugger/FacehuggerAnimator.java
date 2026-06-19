package mod.azure.xenogenesis.client.facehugger;

import mod.azure.azurelib.common.animation.AzAnimatorConfig;
import mod.azure.azurelib.common.animation.controller.AzAnimationController;
import mod.azure.azurelib.common.animation.controller.AzAnimationControllerContainer;
import mod.azure.azurelib.common.animation.impl.AzEntityAnimator;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;
import mod.azure.xenogenesis.util.CommonStrings;

public class FacehuggerAnimator extends AzEntityAnimator<FacehuggerEntity> {

    private static final ResourceLocation ANIMATIONS = CommonMod.modResource(
        "animations/entity/facehugger.animation.json"
    );

    public FacehuggerAnimator() {
        super(AzAnimatorConfig.defaultConfig());
    }

    @Override
    public void registerControllers(AzAnimationControllerContainer<FacehuggerEntity> animationControllerContainer) {
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.BASE_CONTROLLER)
                .setTransitionLength(5)
                // .setKeyframeCallbacks(
                // AzKeyframeCallbacks.<FacehuggerEntity>builder()
                // .setSoundKeyframeHandler(
                // event -> {
                // if (event.getKeyframeData().getSound().equals("huggingSoundkey")) {
                // event.getAnimatable()
                // .level()
                // .playLocalSound(
                // event.getAnimatable().getX(),
                // event.getAnimatable().getY(),
                // event.getAnimatable().getZ(),
                // SoundRegistry.HUGGER_IMPLANT.get(),
                // SoundSource.HOSTILE,
                // 0.25F,
                // 1.0F,
                // true
                // );
                // }
                // }
                // )
                // .build()
                // )
                .build()
        );
    }

    @Override
    public @NotNull ResourceLocation getAnimationLocation(FacehuggerEntity animatable) {
        return ANIMATIONS;
    }
}
