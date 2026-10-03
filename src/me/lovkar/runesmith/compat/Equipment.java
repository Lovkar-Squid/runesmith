package me.lovkar.runesmith.compat;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.api.util.ItemStackUtils;
import net.minecraft.world.item.ItemStack;

/**
 * MineColonies' equipment levels: a worker uses a tool only while its material tier plus
 * {@code max(highest enchantment level - 1, 0)} stays within his hut's maximum equipment level.
 * An enchantment can therefore make a worker put down the very tool it was meant to improve.
 */
public final class Equipment {
    private Equipment() {
    }

    /**
     * Whether the enchanted piece would fail, as any equipment type, the test the unenchanted piece
     * passed for this citizen's workplace - MineColonies' own test, asked for both.
     */
    public static boolean wouldOutgrowWorkplace(final ICitizenData citizen, final ItemStack before, final ItemStack after) {
        final IBuilding work = citizen.getWorkBuilding();
        if (work == null) {
            return false;
        }
        final int max = work.getMaxEquipmentLevel();
        for (final EquipmentTypeEntry type : ModEquipmentTypes.getRegistry()) {
            if (ItemStackUtils.hasEquipmentLevel(before, type, 0, max) && !ItemStackUtils.hasEquipmentLevel(after, type, 0, max)) {
                return true;
            }
        }
        return false;
    }
}
