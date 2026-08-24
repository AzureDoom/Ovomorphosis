package mod.azure.ovomorphosis.ai.nav;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

public class CrawlingManager {

    private final AbstractAlienEntity entity;

    private final EntityDataAccessor<Boolean> isCrawlingEDA;

    private final EntityDataAccessor<Float> crawlForwardXEDA;

    private final EntityDataAccessor<Float> crawlForwardYEDA;

    private final EntityDataAccessor<Float> crawlForwardZEDA;

    private final EntityDataAccessor<Float> crawlUpXEDA;

    private final EntityDataAccessor<Float> crawlUpYEDA;

    private final EntityDataAccessor<Float> crawlUpZEDA;

    private final EntityDataAccessor<Float> crawlDistFromBlockEDA;

    private Vec3 oldCrawlForward = new Vec3(0.0D, 0.0D, 1.0D);

    private Vec3 oldCrawlUp = new Vec3(0.0D, 1.0D, 0.0D);

    private double oldCrawlDistFromBlock = 0.0D;

    private int wallCrawlGraceTicks = 0;

    public CrawlingManager(
        AbstractAlienEntity entity,
        EntityDataAccessor<Boolean> isCrawlingEDA,
        EntityDataAccessor<Float> crawlForwardXEDA,
        EntityDataAccessor<Float> crawlForwardYEDA,
        EntityDataAccessor<Float> crawlForwardZEDA,
        EntityDataAccessor<Float> crawlUpXEDA,
        EntityDataAccessor<Float> crawlUpYEDA,
        EntityDataAccessor<Float> crawlUpZEDA,
        EntityDataAccessor<Float> crawlDistFromBlockEDA
    ) {
        this.entity = entity;
        this.isCrawlingEDA = isCrawlingEDA;
        this.crawlForwardXEDA = crawlForwardXEDA;
        this.crawlForwardYEDA = crawlForwardYEDA;
        this.crawlForwardZEDA = crawlForwardZEDA;
        this.crawlUpXEDA = crawlUpXEDA;
        this.crawlUpYEDA = crawlUpYEDA;
        this.crawlUpZEDA = crawlUpZEDA;
        this.crawlDistFromBlockEDA = crawlDistFromBlockEDA;
    }

    public void tick() {
        syncOldCrawlRenderState();

        if (wallCrawlGraceTicks > 0) {
            wallCrawlGraceTicks--;
        }
    }

    private void syncOldCrawlRenderState() {
        this.oldCrawlForward = getCrawlForward();
        this.oldCrawlUp = getCrawlUp();
        this.oldCrawlDistFromBlock = getCrawlDistFromBlock();
    }

    public boolean isWallCrawling() {
        return entity.getEntityData().get(isCrawlingEDA);
    }

    public void setWallCrawling(boolean crawling) {
        entity.getEntityData().set(isCrawlingEDA, crawling);
        if (crawling) {
            this.wallCrawlGraceTicks = 4;
        }
    }

    public int getWallCrawlGraceTicks() {
        return wallCrawlGraceTicks;
    }

    public void setWallCrawlGraceTicks(int ticks) {
        this.wallCrawlGraceTicks = ticks;
    }

    public Vec3 getCrawlForward() {
        return new Vec3(
            entity.getEntityData().get(crawlForwardXEDA),
            entity.getEntityData().get(crawlForwardYEDA),
            entity.getEntityData().get(crawlForwardZEDA)
        );
    }

    public Vec3 getCrawlUp() {
        return new Vec3(
            entity.getEntityData().get(crawlUpXEDA),
            entity.getEntityData().get(crawlUpYEDA),
            entity.getEntityData().get(crawlUpZEDA)
        );
    }

    public double getCrawlDistFromBlock() {
        return entity.getEntityData().get(crawlDistFromBlockEDA);
    }

    public void setCrawlOrientation(Vec3 forward, Vec3 up, double distFromBlock) {
        if (forward.lengthSqr() > 0.0001D) {
            var f = forward.normalize();
            entity.getEntityData().set(crawlForwardXEDA, (float) f.x);
            entity.getEntityData().set(crawlForwardYEDA, (float) f.y);
            entity.getEntityData().set(crawlForwardZEDA, (float) f.z);
        }

        if (up.lengthSqr() > 0.0001D) {
            var u = up.normalize();
            entity.getEntityData().set(crawlUpXEDA, (float) u.x);
            entity.getEntityData().set(crawlUpYEDA, (float) u.y);
            entity.getEntityData().set(crawlUpZEDA, (float) u.z);
        }

        entity.getEntityData().set(crawlDistFromBlockEDA, (float) distFromBlock);
    }

    public Vec3 getOldCrawlForward() {
        return oldCrawlForward;
    }

    public Vec3 getOldCrawlUp() {
        return oldCrawlUp;
    }

    public double getOldCrawlDistFromBlock() {
        return oldCrawlDistFromBlock;
    }
}
