package mod.azure.ovomorphosis.entities.runner;

import mod.azure.azurelib.animation.dispatch.command.AzCommand;
import mod.azure.azurelib.animation.play_behavior.AzPlayBehaviors;

import mod.azure.ovomorphosis.util.CommonStrings;

public class RunnerAnimationDispatcher {

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

    private final AzCommand swimCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.SWIMMING_ANIMATION_NAME,
        AzPlayBehaviors.LOOP
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
        CommonStrings.ATTACK_CONTROLLER,
        CommonStrings.ATTACK_ANIMATION_NAME,
        AzPlayBehaviors.PLAY_ONCE
    );

    private final AzCommand tailCommand = AzCommand.create(
        CommonStrings.ATTACK_CONTROLLER,
        "tail_attack",
        AzPlayBehaviors.PLAY_ONCE
    );

    private final AzCommand windUpCommand = AzCommand.create(
        CommonStrings.ATTACK_CONTROLLER,
        "windup",
        AzPlayBehaviors.PLAY_ONCE
    );

    private final AzCommand inAirCommand = AzCommand.create(
        CommonStrings.ATTACK_CONTROLLER,
        "in_air",
        AzPlayBehaviors.LOOP
    );

    private final AzCommand chrysalisCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "chrysalispose",
        AzPlayBehaviors.LOOP
    );

    private final RunnerEntity runner;

    public RunnerAnimationDispatcher(RunnerEntity runner) {
        this.runner = runner;
    }

    public void clientIdle() {
        idleCommand.sendForEntity(runner);
    }

    public void clientDeath() {
        deathCommand.sendForEntity(runner);
    }

    public void clientWalk() {
        walkCommand.sendForEntity(runner);
    }

    public void clientRun() {
        runCommand.sendForEntity(runner);
    }

    public void clientSwim() {
        swimCommand.sendForEntity(runner);
    }

    public void clientInAir() {
        inAirCommand.sendForEntity(runner);
    }

    public void clientChrysalispose() {
        chrysalisCommand.sendForEntity(runner);
    }

    public void serverAttack() {
        attackCommand.sendForEntity(runner);
    }

    public void serverTailAttack() {
        tailCommand.sendForEntity(runner);
    }

    public void serverWindUp() {
        windUpCommand.sendForEntity(runner);
    }
}
