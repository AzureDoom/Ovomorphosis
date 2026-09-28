package mod.azure.ovomorphosis.entities.ovomorph;

import mod.azure.azurelib.animation.dispatch.command.AzCommand;
import mod.azure.azurelib.animation.play_behavior.AzPlayBehaviors;

import mod.azure.ovomorphosis.util.CommonStrings;

public class OvomorphAnimationDispatcher {

    private final AzCommand idleCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.IDLE_ANIMATION_NAME,
        AzPlayBehaviors.LOOP
    );

    private final AzCommand hatchingCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "hatching",
        AzPlayBehaviors.HOLD_ON_LAST_FRAME
    );

    private final AzCommand hatchedCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "hatched",
        AzPlayBehaviors.HOLD_ON_LAST_FRAME
    );

    private final OvomorphEntity ovomorph;

    public OvomorphAnimationDispatcher(OvomorphEntity ovomorph) {
        this.ovomorph = ovomorph;
    }

    public void clientIdle() {
        idleCommand.sendForEntity(ovomorph);
    }

    public void clientHatching() {
        hatchingCommand.sendForEntity(ovomorph);
    }

    public void clientHatched() {
        hatchedCommand.sendForEntity(ovomorph);
    }
}
