package me.lovkar.runesmith.block;

import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import me.lovkar.runesmith.Runesmith;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the Runesmith's hut. Behaves exactly like a MineColonies hut block entity; it
 * exists only so the hut block is a valid block for a block-entity type of our own.
 */
public class RunesmithTileEntity extends TileEntityColonyBuilding {

    public RunesmithTileEntity(final BlockPos pos, final BlockState state) {
        super(Runesmith.BUILDING_BE.get(), pos, state);
    }
}
