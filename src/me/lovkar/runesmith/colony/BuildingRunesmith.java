package me.lovkar.runesmith.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import me.lovkar.runesmith.logic.GearCategory;
import me.lovkar.runesmith.logic.RunesmithPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Tuple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Runesmith's hut.
 *
 * <p>Gear and enchanted books in its racks belong to the player or are waiting for the worker, so
 * no courier may take them: they are on the keep-list without limit, and (because MineColonies
 * keeps by the first matching entry of a HashMap, and the hut's own book deliveries add an entry of
 * their own) {@link #buildingRequiresCertainAmountOfItem} also answers 0 for them outright.
 * A delivery into a full hut never swaps them out either ({@link #isItemStackInRequest}).</p>
 *
 * <p>The one exception is finished gear (setting {@code sendfinished}, default on): a piece the
 * Runesmith has enchanted in the racks, which no book in stock has improved for a while, is let go
 * to a courier, who carries it to the warehouse. There the colonists can ask for it. The building
 * remembers which pieces the Runesmith enchanted (item and enchantments, and when), saved with the
 * building; which of them are finished right now is the AI's call.</p>
 */
public class BuildingRunesmith extends AbstractBuilding {
    public static final String SCHEMATIC_NAME = "runesmith";
    /** The blueprint tag on the block the Runesmith works at (an anvil). */
    public static final String TAG_WORK = "work";
    public static final int MAX_LEVEL = 5;
    private static final String TAG_ENCHANTED = "runesmithEnchanted";
    /** How many enchanted pieces the hut remembers; the oldest are forgotten first. */
    private static final int ENCHANTED_MAX = 256;

    /** Rack gear the Runesmith enchanted: its fingerprint and the game time of the last book he put on it. */
    private final Map<String, Long> enchantedAt = new LinkedHashMap<>();
    /** Rack gear that is finished and may go to a courier now; worked out by the AI, not saved. */
    private final Set<String> releasable = new HashSet<>();
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
        if (!inventory && isGear(stack) && sendFinished() && releasable.contains(fingerprint(stack))) {
            return stack.getCount(); // finished: the courier may take it to the warehouse
        }
        if (!inventory && (isGear(stack) || isBook(stack))) {
            return 0;
        }
        if (inventory && onLoan(stack)) {
            return 0; // a warehouse piece on loan stays in the pack until it goes back
        }
        return super.buildingRequiresCertainAmountOfItem(stack, localAlreadyKept, inventory, jobEntry);
    }

    @Override
    public boolean isItemStackInRequest(@Nullable final ItemStack stack) {
        return stack != null && (isGear(stack) || isBook(stack)) || super.isItemStackInRequest(stack);
    }

    /** Whether a piece like this is out on loan from a warehouse with one of this hut's workers. */
    public boolean onLoan(final ItemStack stack) {
        for (final ICitizenData c : getAllAssignedCitizen()) {
            if (c.getJob() instanceof JobRunesmith job && job.onLoan(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the Runesmith may borrow gear from the warehouses (setting, default off). */
    public boolean warehouseAllowed() {
        return getSetting(RunesmithSettings.WAREHOUSE).getValue();
    }

    /** Whether finished gear goes to the warehouse (setting, default on). */
    public boolean sendFinished() {
        return getSetting(RunesmithSettings.SEND_FINISHED).getValue();
    }

    /** A piece's item and enchantments, the same for every copy of it and from one start of the server to the next. */
    public static String fingerprint(final ItemStack stack) {
        final List<String> parts = new ArrayList<>();
        stack.getEnchantments().entrySet().forEach(e -> parts.add(e.getKey().getRegisteredName() + "=" + e.getIntValue()));
        parts.sort(null);
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + String.join(",", parts);
    }

    /** The Runesmith has just put a book on this piece in the racks. */
    public void markEnchanted(final ItemStack piece, final long gameTime) {
        final String key = fingerprint(piece);
        enchantedAt.remove(key); // re-inserted last: the map's order is the age
        enchantedAt.put(key, gameTime);
        while (enchantedAt.size() > ENCHANTED_MAX) {
            enchantedAt.remove(enchantedAt.keySet().iterator().next());
        }
        markDirty();
    }

    /** When the Runesmith last enchanted a piece like this in the racks, or null if he never did. */
    public @Nullable Long enchantedAt(final ItemStack piece) {
        return enchantedAt.get(fingerprint(piece));
    }

    /** The pieces a courier may take now (fingerprints). */
    public void setReleasable(final Set<String> fingerprints) {
        releasable.clear();
        releasable.addAll(fingerprints);
    }

    @Override
    public CompoundTag serializeNBT(@NotNull final HolderLookup.Provider provider) {
        final CompoundTag tag = super.serializeNBT(provider);
        final ListTag list = new ListTag();
        enchantedAt.forEach((key, time) -> {
            final CompoundTag t = new CompoundTag();
            t.putString("piece", key);
            t.putLong("time", time);
            list.add(t);
        });
        tag.put(TAG_ENCHANTED, list);
        return tag;
    }

    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag tag) {
        super.deserializeNBT(provider, tag);
        enchantedAt.clear();
        for (final Tag t : tag.getList(TAG_ENCHANTED, Tag.TAG_COMPOUND)) {
            final CompoundTag c = (CompoundTag) t;
            enchantedAt.put(c.getString("piece"), c.getLong("time"));
        }
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
