package mod.azure.ovomorphosis.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.NonNull;

import mod.azure.ovomorphosis.entities.AcidEntity;

public class AcidEntityRender<T extends Entity> extends EntityRenderer<AcidEntity, EntityRenderState> {

    public AcidEntityRender(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public @NonNull EntityRenderState createRenderState() {
        return new EntityRenderState();
    }
}
