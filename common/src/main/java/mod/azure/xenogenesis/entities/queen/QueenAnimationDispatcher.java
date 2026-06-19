package mod.azure.xenogenesis.entities.queen;

import mod.azure.azurelib.common.animation.dispatch.command.AzCommand;
import mod.azure.azurelib.common.animation.play_behavior.AzPlayBehaviors;

import mod.azure.xenogenesis.util.CommonStrings;

public class QueenAnimationDispatcher {

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

    private final AzCommand attackCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.ATTACK_ANIMATION_NAME,
        AzPlayBehaviors.PLAY_ONCE
    );

    private final AzCommand tailCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "tail_attack",
        AzPlayBehaviors.PLAY_ONCE
    );

    private final AzCommand layEggCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "layegg",
        AzPlayBehaviors.PLAY_ONCE
    );

    private final QueenEntity queen;

    public QueenAnimationDispatcher(QueenEntity queen) {
        this.queen = queen;
    }

    public void clientIdle() {
        idleCommand.sendForEntity(queen);
    }

    public void clientDeath() {
        deathCommand.sendForEntity(queen);
    }

    public void clientWalk() {
        walkCommand.sendForEntity(queen);
    }

    public void clientRun() {
        runCommand.sendForEntity(queen);
    }

    public void serverAttack() {
        attackCommand.sendForEntity(queen);
    }

    public void serverTailAttack() {
        tailCommand.sendForEntity(queen);
    }

    public void serverLayEgg() {
        layEggCommand.sendForEntity(queen);
    }
}
