package mod.azure.xenogenesis.entities.chestburster;

import mod.azure.azurelib.common.animation.dispatch.command.AzCommand;
import mod.azure.azurelib.common.animation.play_behavior.AzPlayBehaviors;

import mod.azure.xenogenesis.util.CommonStrings;

public class ChestbursterAnimationDispatcher {

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

    private final AzCommand eatingCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "eat",
        AzPlayBehaviors.PLAY_ONCE
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

    private final ChestbursterEntity chestburster;

    public ChestbursterAnimationDispatcher(ChestbursterEntity chestburster) {
        this.chestburster = chestburster;
    }

    public void clientIdle() {
        idleCommand.sendForEntity(chestburster);
    }

    public void clientDeath() {
        deathCommand.sendForEntity(chestburster);
    }

    public void clientWalk() {
        walkCommand.sendForEntity(chestburster);
    }

    public void serverEating() {
        eatingCommand.sendForEntity(chestburster);
    }
}
