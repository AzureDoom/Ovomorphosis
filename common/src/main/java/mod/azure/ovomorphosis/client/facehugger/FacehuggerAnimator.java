package mod.azure.ovomorphosis.client.facehugger;

import mod.azure.azurelib.animation.AzAnimatorConfig;
import mod.azure.azurelib.animation.controller.AzAnimationController;
import mod.azure.azurelib.animation.controller.AzAnimationControllerContainer;
import mod.azure.azurelib.animation.impl.AzEntityAnimator;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.util.CommonStrings;

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
