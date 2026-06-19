package mod.azure.xenogenesis.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

import java.util.function.Supplier;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.services.XenoServices;

public class SoundRegistry {

    private SoundRegistry() {}

    public static final Supplier<SoundEvent> ACID = registerSound("acid");

    public static final Supplier<SoundEvent> CHEST_BURST = registerSound("chest_burst");

    public static final Supplier<SoundEvent> CHESTBUSTER_FOOTSTEP = registerSound("chestburster_footstep");

    public static final Supplier<SoundEvent> CHESTBUSTER_IDLE = registerSound("chestbuster_idle");

    public static final Supplier<SoundEvent> CHESTBUSTER_PAIN = registerSound("chestbuster_pain");

    public static final Supplier<SoundEvent> FACEHUGGER_DEATH = registerSound("facehugger_death");

    public static final Supplier<SoundEvent> FACEHUGGER_HURT = registerSound("facehugger_hurt");

    public static final Supplier<SoundEvent> FACEHUGGER_IMPLANT = registerSound("facehugger_implant");

    public static final Supplier<SoundEvent> FACEHUGGER_RUN = registerSound("facehugger_run");

    public static final Supplier<SoundEvent> OVOMORPH_OPEN = registerSound("ovomorph_open");

    public static final Supplier<SoundEvent> XENOMORPH_DEATH = registerSound("xenomorph_death");

    public static final Supplier<SoundEvent> XENOMORPH_FOOTSTEP = registerSound("xenomorph_footstep");

    public static final Supplier<SoundEvent> XENOMORPH_HURT = registerSound("xenomorph_hurt");

    public static final Supplier<SoundEvent> XENOMORPH_IDLE = registerSound("xenomorph_idle");

    static Supplier<SoundEvent> registerSound(String soundName) {
        return XenoServices.COMMON_REGISTRY.register(
            BuiltInRegistries.SOUND_EVENT,
            soundName,
            () -> SoundEvent.createVariableRangeEvent(CommonMod.modResource(soundName))
        );
    }

    public static void initialize() {}
}
