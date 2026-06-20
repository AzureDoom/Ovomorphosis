package mod.azure.xenogenesis.config;

import mod.azure.azurelib.common.config.Config;
import mod.azure.azurelib.common.config.Configurable;

import mod.azure.xenogenesis.CommonMod;

@Config(id = CommonMod.MOD_ID)
public class XenogenesisConfig {

    @Configurable
    @Configurable.Synchronized
    public boolean enablePathfindingDebug = false;

    @Configurable
    @Configurable.Synchronized
    @Configurable.Range(min = 1200)
    public float eggmorphTotalTicks = 1200;

    @Configurable
    @Configurable.Synchronized
    @Configurable.Range(min = 2400)
    public int infectionMinTicks = 2400;

    @Configurable
    @Configurable.Synchronized
    @Configurable.Range(min = 6000)
    public int infectionMaxTicks = 6000;

    @Configurable
    @Configurable.Synchronized
    public boolean enableAcidBlockBreaking = true;

    @Configurable
    @Configurable.Synchronized
    public boolean enableAcidItemBreaking = true;

    @Configurable
    @Configurable.Synchronized
    @Configurable.Range(min = 1)
    public int acidDestroySpeedMultiplier = 3;

    @Configurable
    @Configurable.Synchronized
    public BlockConfigs blockConfigs = new BlockConfigs();

    public static class BlockConfigs {

        @Configurable
        @Configurable.Synchronized
        public boolean enableResinBlockTicking = true;
    }

    @Configurable
    @Configurable.Synchronized
    public EntityConfigs entityConfigs = new EntityConfigs();

    public static class EntityConfigs {

        @Configurable
        @Configurable.Synchronized
        public OvomorphConfigs ovomorphConfigs = new OvomorphConfigs();

        public static class OvomorphConfigs {

        }

        @Configurable
        @Configurable.Synchronized
        public FacehuggerConfigs facehuggerConfigs = new FacehuggerConfigs();

        public static class FacehuggerConfigs {

            @Configurable
            @Configurable.Synchronized
            @Configurable.Range(min = 1200)
            public float facehuggerAttachMaxTicks = 1200;
        }

        @Configurable
        @Configurable.Synchronized
        public ChestbursterConfigs chestbursterConfigs = new ChestbursterConfigs();

        public static class ChestbursterConfigs {

            @Configurable
            @Configurable.Synchronized
            @Configurable.Range(min = 1)
            public float chestbursterFoodGrowthValue = 10;
        }

        @Configurable
        @Configurable.Synchronized
        public XenomorphConfigs xenomorphConfigs = new XenomorphConfigs();

        public static class XenomorphConfigs {

        }
    }
}
