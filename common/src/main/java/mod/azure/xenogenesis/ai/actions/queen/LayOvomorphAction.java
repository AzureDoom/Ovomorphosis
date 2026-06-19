package mod.azure.xenogenesis.ai.actions.queen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.function.Consumer;

import mod.azure.xenogenesis.ai.core.*;
import mod.azure.xenogenesis.entities.AbstractAlienEntity;
import mod.azure.xenogenesis.entities.ovomorph.OvomorphEntity;
import mod.azure.xenogenesis.registry.EntityRegistry;

public final class LayOvomorphAction<E extends Mob> implements Action<E> {

    public static final String LAY_COOLDOWN = "queen:lay_ovomorph";

    public static final int LAY_COOLDOWN_TICKS = 1200;

    private static final int LAY_ANIM_TICKS = 40;

    private static final double LAY_RADIUS = 3.5D;

    private static final int RING_SLOTS = 8;

    private static final double BLOCK_SCAN_RADIUS = LAY_RADIUS + 2.0D;

    private static final String SLOT_KEY = "queen:lay_slot_index";

    private final int priority;

    private final Consumer<E> animationCallback;

    private int age = 0;

    public LayOvomorphAction(int priority, Consumer<E> animationCallback) {
        this.priority = priority;
        this.animationCallback = animationCallback;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        age = 0;
        animationCallback.accept(mob);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionStatus.INTERRUPTED;
        }

        age++;

        if (age < LAY_ANIM_TICKS) {
            return ActionStatus.RUNNING;
        }

        var slotIndex = blackboard.get(SLOT_KEY, Integer.class);
        if (slotIndex == null)
            slotIndex = 0;

        BlockPos spawnPos = null;

        for (var attempt = 0; attempt < RING_SLOTS; attempt++) {
            var candidateSlot = (slotIndex + attempt) % RING_SLOTS;
            var angle = (2.0 * Math.PI / RING_SLOTS) * candidateSlot;

            var dx = Math.cos(angle) * LAY_RADIUS;
            var dz = Math.sin(angle) * LAY_RADIUS;

            var candidate = findGroundAt(mob, dx, dz);
            if (candidate != null && isSlotFree(mob, candidate)) {
                spawnPos = candidate;
                blackboard.set(SLOT_KEY, (candidateSlot + 1) % RING_SLOTS);
                break;
            }
        }

        if (spawnPos == null) {
            cooldowns.set(LAY_COOLDOWN, LAY_COOLDOWN_TICKS);
            return ActionStatus.FAILURE;
        }

        if (mob.level() instanceof ServerLevel serverLevel) {
            var egg = new OvomorphEntity(EntityRegistry.OVOMORPH.get(), serverLevel);

            float yaw = mob.getRandom().nextInt(4) * 90.0f;
            egg.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            egg.setYRot(yaw);
            egg.yBodyRot = yaw;

            serverLevel.addFreshEntityWithPassengers(egg);
        }

        cooldowns.set(LAY_COOLDOWN, LAY_COOLDOWN_TICKS);
        return ActionStatus.SUCCESS;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
        age = 0;
    }

    @Override
    public boolean isInterruptible() {
        return age < LAY_ANIM_TICKS;
    }

    @Override
    public int priority() {
        return priority;
    }

    private boolean hasNearbyAliens(E mob) {
        return !mob.level()
            .getEntitiesOfClass(
                AbstractAlienEntity.class,
                new AABB(mob.blockPosition()).inflate(BLOCK_SCAN_RADIUS),
                e -> e != mob && e.isAlive()
            )
            .isEmpty();
    }

    private boolean isSlotFree(E mob, BlockPos pos) {
        var level = mob.level();

        var feet = level.getBlockState(pos);
        var head = level.getBlockState(pos.above());
        var below = level.getBlockState(pos.below());

        if (isNotClearBlock(level, pos, feet))
            return false;
        if (isNotClearBlock(level, pos.above(), head))
            return false;
        if (below.getCollisionShape(level, pos.below()).isEmpty())
            return false;

        var footBox = new AABB(pos);
        return level.getEntities(mob, footBox).isEmpty();
    }

    private boolean isNotClearBlock(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    private BlockPos findGroundAt(E mob, double dx, double dz) {
        var origin = mob.blockPosition();
        var baseX = (int) Math.floor(origin.getX() + dx);
        var baseZ = (int) Math.floor(origin.getZ() + dz);

        for (var yOffset = 3; yOffset >= -4; yOffset--) {
            var feet = new BlockPos(baseX, origin.getY() + yOffset, baseZ);
            var below = feet.below();
            var head = feet.above();

            var level = mob.level();

            var feetState = level.getBlockState(feet);
            var headState = level.getBlockState(head);
            var belowState = level.getBlockState(below);

            if (
                feetState.getCollisionShape(level, feet).isEmpty()
                    && headState.getCollisionShape(level, head).isEmpty()
                    && !belowState.getCollisionShape(level, below).isEmpty()
            ) {
                return feet;
            }
        }

        return null;
    }
}
