package mod.azure.ovomorphosis.client.xenomorph;

import mod.azure.azurelib.animation.AzAnimatorConfig;
import mod.azure.azurelib.animation.controller.AzAnimationController;
import mod.azure.azurelib.animation.controller.AzAnimationControllerContainer;
import mod.azure.azurelib.animation.controller.keyframe.AzKeyframeCallbacks;
import mod.azure.azurelib.animation.impl.AzEntityAnimator;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.util.CommonStrings;

public class XenomorphAnimator extends AzEntityAnimator<XenomorphEntity> {

    private static final Identifier ANIMATIONS = CommonMod.modResource(
        "animations/entity/xenomorph.animation.json"
    );

    public XenomorphAnimator() {
        super(AzAnimatorConfig.defaultConfig());
    }

    @Override
    public void registerControllers(AzAnimationControllerContainer<XenomorphEntity> animationControllerContainer) {
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.BASE_CONTROLLER)
                .setTransitionLength(5)
                .setKeyframeCallbacks(
                    AzKeyframeCallbacks.<XenomorphEntity>builder()
                        .setSoundKeyframeHandler(
                            event -> {
                                if (event.getKeyframeData().getSound().equals("footStep")) {
                                    event.getAnimatable()
                                        .level()
                                        .playLocalSound(
                                            event.getAnimatable().getX(),
                                            event.getAnimatable().getY(),
                                            event.getAnimatable().getZ(),
                                            SoundRegistry.XENOMORPH_FOOTSTEP.get(),
                                            SoundSource.HOSTILE,
                                            0.5F,
                                            1.0F,
                                            true
                                        );
                                }
                            }
                        )
                        .build()
                )
                .build()
        );
        animationControllerContainer.add(
            AzAnimationController.builder(this, CommonStrings.ATTACK_CONTROLLER).build()
        );
    }

    @Override
    public @NotNull Identifier getAnimationLocation(XenomorphEntity xenomorph) {
        return ANIMATIONS;
    }
}
