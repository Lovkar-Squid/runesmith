package me.lovkar.runesmith.block;

import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import me.lovkar.runesmith.Runesmith;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The Runesmith's hut block. */
public class BlockHutRunesmith extends AbstractBlockHut<BlockHutRunesmith> {

    @Override
    public String getHutName() {
        return Runesmith.HUT_NAME;
    }

    @Override
    public BuildingEntry getBuildingEntry() {
        return Runesmith.BUILDING.get();
    }

    @Override
    public @NotNull ResourceLocation getRegistryName() {
        return Runesmith.id(Runesmith.HUT_NAME);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(@NotNull final BlockPos pos, @NotNull final BlockState state) {
        final TileEntityColonyBuilding te = Runesmith.BUILDING_BE.get().create(pos, state);
        if (te != null) {
            te.registryName = getBuildingEntry().getRegistryName();
        }
        return te;
    }
}
