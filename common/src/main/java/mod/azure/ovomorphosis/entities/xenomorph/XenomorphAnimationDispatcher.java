package mod.azure.ovomorphosis.entities.xenomorph;

import mod.azure.azurelib.animation.dispatch.command.AzCommand;
import mod.azure.azurelib.animation.play_behavior.AzPlayBehaviors;

import mod.azure.ovomorphosis.util.CommonStrings;

public class XenomorphAnimationDispatcher {

    private final AzCommand idleCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.IDLE_ANIMATION_NAME,
        AzPlayBehaviors.LOOP
    );

    private final AzCommand idle2Command = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "look_around",
        AzPlayBehaviors.LOOP
    );

    private final AzCommand deathCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        CommonStrings.DEATH_ANIMATION_NAME,
        AzPlayBehaviors.HOLD_ON_LAST_FRAME
    );

    private final AzCommand crawlingCommand = AzCommand.create(
        CommonStrings.BASE_CONTROLLER,
        "crawling",
        AzPlayBehaviors.LOOP
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

    private final AzCommand executeCommand = AzCommand.create(
        CommonStrings.ATTACK_CONTROLLER,
        "execute",
        AzPlayBehaviors.PLAY_ONCE
    );

    private final AzCommand carryingCommand = AzCommand.create(
        CommonStrings.ATTACK_CONTROLLER,
        "carrying",
        AzPlayBehaviors.LOOP
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

    private final XenomorphEntity xenomorph;

    public XenomorphAnimationDispatcher(XenomorphEntity xenomorph) {
        this.xenomorph = xenomorph;
    }

    public void clientIdle() {
        idleCommand.sendForEntity(xenomorph);
    }

    public void clientIdle2() {
        idle2Command.sendForEntity(xenomorph);
    }

    public void clientDeath() {
        deathCommand.sendForEntity(xenomorph);
    }

    public void clientCrawling() {
        crawlingCommand.sendForEntity(xenomorph);
    }

    public void clientWalk() {
        walkCommand.sendForEntity(xenomorph);
    }

    public void clientRun() {
        runCommand.sendForEntity(xenomorph);
    }

    public void clientSwim() {
        swimCommand.sendForEntity(xenomorph);
    }

    public void clientInAir() {
        inAirCommand.sendForEntity(xenomorph);
    }

    public void serverAttack() {
        attackCommand.sendForEntity(xenomorph);
    }

    public void serverTailAttack() {
        tailCommand.sendForEntity(xenomorph);
    }

    public void serverExecute() {
        executeCommand.sendForEntity(xenomorph);
    }

    public void serverCarry() {
        carryingCommand.sendForEntity(xenomorph);
    }

    public void serverWindUp() {
        windUpCommand.sendForEntity(xenomorph);
    }
}
