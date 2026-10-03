package me.lovkar.runesmith.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import me.lovkar.runesmith.logic.GearCategory;
import me.lovkar.runesmith.logic.RunesmithPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Runesmith's hut.
 *
 * <p>Gear and enchanted books in its racks belong to the player or are waiting for the worker, so
 * no courier may take them: they are on the keep-list without limit, and (because MineColonies
 * keeps by the first matching entry of a HashMap, and the hut's own book deliveries add an entry of
 * their own) {@link #buildingRequiresCertainAmountOfItem} also answers 0 for them outright.
 * A delivery into a full hut never swaps them out either ({@link #isItemStackInRequest}).</p>
 */
public class BuildingRunesmith extends AbstractBuilding {
    public static final String SCHEMATIC_NAME = "runesmith";
    /** The blueprint tag on the block the Runesmith works at (an anvil). */
    public static final String TAG_WORK = "work";
    public static final int MAX_LEVEL = 5;
    /** Lapis the hut keeps for its own use. */
    public static final int LAPIS_KEPT = 64;

    public BuildingRunesmith(final IColony colony, final BlockPos pos) {
        super(colony, pos);
        keepX.put(BuildingRunesmith::isGear, new Tuple<>(Integer.MAX_VALUE, false));
        keepX.put(BuildingRunesmith::isBook, new Tuple<>(Integer.MAX_VALUE, false));
        keepX.put(BuildingRunesmith::isLapis, new Tuple<>(LAPIS_KEPT, false));
    }

    @Override
    public String getSchematicName() {
        return SCHEMATIC_NAME;
    }

    @Override
    public int getMaxBuildingLevel() {
        return MAX_LEVEL;
    }

    /** Armor, a weapon or a tool by the vanilla enchantable tags (see {@link GearCategory}). */
    public static boolean isGear(final ItemStack stack) {
        return !stack.isEmpty() && !stack.is(Items.ENCHANTED_BOOK) && !GearCategory.of(stack).isEmpty();
    }

    public static boolean isBook(final ItemStack stack) {
        return stack.is(Items.ENCHANTED_BOOK);
    }

    public static boolean isLapis(final ItemStack stack) {
        return stack.is(Items.LAPIS_LAZULI);
    }

    @Override
    public int buildingRequiresCertainAmountOfItem(final ItemStack stack, final List<ItemStorage> localAlreadyKept, final boolean inventory,
            final JobEntry jobEntry) {
        if (!inventory && (isGear(stack) || isBook(stack))) {
            return 0;
        }
        return super.buildingRequiresCertainAmountOfItem(stack, localAlreadyKept, inventory, jobEntry);
    }

    @Override
    public boolean isItemStackInRequest(@Nullable final ItemStack stack) {
        return stack != null && (isGear(stack) || isBook(stack)) || super.isItemStackInRequest(stack);
    }

    /** Whether the Runesmith may visit colonists (setting, default on). */
    public boolean colonistsAllowed() {
        return getSetting(RunesmithSettings.COLONISTS).getValue();
    }

    /** The settings as a policy for {@code EnchantApplier}, read now. */
    public RunesmithPolicy policy() {
        final boolean cap = getSetting(RunesmithSettings.LEVEL_CAP).getValue();
        return new RunesmithPolicy(
                getSetting(RunesmithSettings.ARMOR).getValue(),
                getSetting(RunesmithSettings.WEAPONS).getValue(),
                getSetting(RunesmithSettings.TOOLS).getValue(),
                cap ? Math.max(1, getBuildingLevel()) : 0,
                getSetting(RunesmithSettings.ONLY_UNENCHANTED).getValue(),
                getSetting(RunesmithSettings.LAPIS).getValue() ? 1 : 0);
    }
}
