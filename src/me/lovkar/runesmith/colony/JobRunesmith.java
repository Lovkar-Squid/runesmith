package me.lovkar.runesmith.colony;

import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.jobs.AbstractJob;
import me.lovkar.runesmith.ai.EntityAIWorkRunesmith;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The Runesmith's job. In 1.0 the worker wears the Enchanter's robes.
 *
 * <p>It also remembers the warehouse pieces the Runesmith has on loan (Phase 3 source): which
 * warehouse, which rack and slot, and which piece. A loan is saved with the job, so a piece in the
 * worker's pack when the server stops still goes back to its warehouse after the restart.</p>
 */
public class JobRunesmith extends AbstractJob<EntityAIWorkRunesmith, JobRunesmith> {
    private static final String TAG_LOANS = "runesmithLoans";

    /** A piece taken from a slot of a warehouse rack, to be enchanted and returned. {@code item} is its registry name. */
    public record Loan(BlockPos warehouse, BlockPos rack, int slot, String item) {}

    private final List<Loan> loans = new ArrayList<>();

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

    public List<Loan> loans() {
        return loans;
    }

    public void addLoan(final Loan loan) {
        loans.add(loan);
        getCitizen().markDirty(20);
    }

    public void removeLoan(final Loan loan) {
        loans.remove(loan);
        getCitizen().markDirty(20);
    }

    /** Whether a piece of this kind is out on loan (the worker must not dump it into his own racks). */
    public boolean onLoan(final ItemStack stack) {
        if (loans.isEmpty() || stack.isEmpty()) {
            return false;
        }
        final String item = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return loans.stream().anyMatch(l -> l.item().equals(item));
    }

    @Override
    public CompoundTag serializeNBT(final @NotNull HolderLookup.Provider provider) {
        final CompoundTag tag = super.serializeNBT(provider);
        final ListTag list = new ListTag();
        for (final Loan l : loans) {
            final CompoundTag t = new CompoundTag();
            t.putLong("warehouse", l.warehouse().asLong());
            t.putLong("rack", l.rack().asLong());
            t.putInt("slot", l.slot());
            t.putString("item", l.item());
            list.add(t);
        }
        tag.put(TAG_LOANS, list);
        return tag;
    }

    @Override
    public void deserializeNBT(final @NotNull HolderLookup.Provider provider, final CompoundTag tag) {
        super.deserializeNBT(provider, tag);
        loans.clear();
        for (final Tag t : tag.getList(TAG_LOANS, Tag.TAG_COMPOUND)) {
            final CompoundTag c = (CompoundTag) t;
            final BlockPos warehouse = BlockPos.of(c.getLong("warehouse"));
            final BlockPos rack = c.contains("rack", Tag.TAG_LONG) ? BlockPos.of(c.getLong("rack")) : warehouse;
            loans.add(new Loan(warehouse, rack, c.getInt("slot"), c.getString("item")));
        }
    }
}
