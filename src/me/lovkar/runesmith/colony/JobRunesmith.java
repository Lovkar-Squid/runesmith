package me.lovkar.runesmith.colony;

import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.jobs.AbstractJob;
import me.lovkar.runesmith.ai.EntityAIWorkRunesmith;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** The Runesmith's job. In 1.0 the worker wears the Enchanter's robes. */
public class JobRunesmith extends AbstractJob<EntityAIWorkRunesmith, JobRunesmith> {

    public JobRunesmith(final ICitizenData citizen) {
        super(citizen);
    }

    @Override
    public EntityAIWorkRunesmith generateAI() {
        return new EntityAIWorkRunesmith(this);
    }

    @Override
    public @NotNull ResourceLocation getModel() {
        return ModModelTypes.ENCHANTER_ID;
    }
}
