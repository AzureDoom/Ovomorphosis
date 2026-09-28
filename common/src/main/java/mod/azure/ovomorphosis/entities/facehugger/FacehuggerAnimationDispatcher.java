package mod.azure.ovomorphosis.entities.facehugger;

import mod.azure.azurelib.animation.dispatch.command.AzCommand;
import mod.azure.azurelib.animation.play_behavior.AzPlayBehaviors;

import mod.azure.ovomorphosis.util.CommonStrings;

public class FacehuggerAnimationDispatcher {

    private final AzCommand idleCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.IDLE_ANIMATION_NAME,
        AzPlayBehaviors.LOOP
    );

    private final AzCommand deathCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.DEATH_ANIMATION_NAME,
        AzPlayBehaviors.HOLD_ON_LAST_FRAME
    );

    private final AzCommand walkCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.WALK_ANIMATION_NAME,
        AzPlayBehaviors.LOOP
    );

    private final AzCommand runCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.RUN_ANIMATION_NAME,
        AzPlayBehaviors.LOOP
    );

    private final AzCommand swimCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.SWIMMING_ANIMATION_NAME,
        AzPlayBehaviors.LOOP
    );

    private final AzCommand inAirCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "in_air",
        AzPlayBehaviors.LOOP
    );

    private final AzCommand windUpCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "windup",
        AzPlayBehaviors.PLAY_ONCE
    );

    private final AzCommand faceHugCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "facehug",
        AzPlayBehaviors.HOLD_ON_LAST_FRAME
    );

    private final FacehuggerEntity facehugger;

    public FacehuggerAnimationDispatcher(FacehuggerEntity facehugger) {
        this.facehugger = facehugger;
    }

    public void clientIdle() {
        idleCommand.sendForEntity(facehugger);
    }

    public void clientDeath() {
        deathCommand.sendForEntity(facehugger);
    }

    public void clientWalk() {
        walkCommand.sendForEntity(facehugger);
    }

    public void clientRun() {
        runCommand.sendForEntity(facehugger);
    }

    public void clientSwim() {
        swimCommand.sendForEntity(facehugger);
    }

    public void clientInAir() {
        inAirCommand.sendForEntity(facehugger);
    }

    public void sendFaceHug() {
        faceHugCommand.sendForEntity(facehugger);
    }

    public void serverWindUp() {
        windUpCommand.sendForEntity(facehugger);
    }
}
