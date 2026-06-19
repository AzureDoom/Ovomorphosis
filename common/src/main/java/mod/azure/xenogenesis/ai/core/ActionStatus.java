package mod.azure.xenogenesis.ai.core;

/**
 * Describes the outcome of a single {@link Action#tick} call or the reason an action was stopped.
 */
public enum ActionStatus {

    /** The action is still executing and should continue to be ticked. */
    RUNNING,

    /** The action completed its goal successfully. */
    SUCCESS,

    /** The action was unable to complete its goal and has given up. */
    FAILURE,

    /** The action was forcibly stopped by the brain before it could finish. */
    INTERRUPTED
}
