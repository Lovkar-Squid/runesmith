package me.lovkar.runesmith.compat;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.StackList;
import com.minecolonies.api.util.constant.TypeConstants;
import me.lovkar.runesmith.logic.EnchantApplier;
import me.lovkar.runesmith.logic.RunesmithPolicy;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Every request-system call the Runesmith makes, in one place: these are the MineColonies
 * signatures most likely to move between snapshots.
 *
 * <p>Books are asked for as a {@link StackList}: the books that would improve a piece of gear the
 * Runesmith knows about right now, one enchantment and level each, as the Enchanter makes them,
 * compared with their components. Two things depend on that. An empty or useless enchanted book in
 * the warehouse is never sent. A useless book already in the hut's own racks cannot answer the
 * request either: MineColonies first tries to satisfy a request from the requesting building's own
 * inventory, and a request for "any enchanted book" was answered on the spot by the books the
 * Runesmith could not use, closed, and asked again a few seconds later (seen in a real colony).</p>
 */
public final class Requests {
    private Requests() {
    }

    /** The line the request shows in MineColonies' request lists: a translation key, as MineColonies' own lists use. */
    public static final String BOOKS_DESCRIPTION = "com.runesmith.request.books";

    /**
     * The enchanted books that would improve at least one of these pieces under this policy: one
     * book per enchantment and level, as the Enchanter makes them, curses left out, at most
     * {@code max} of them (higher levels first within an enchantment). Empty if no book could help.
     */
    public static List<ItemStack> usefulBooks(final HolderLookup.Provider registries, final List<ItemStack> gear, final RunesmithPolicy policy,
            final int max) {
        final List<ItemStack> out = new ArrayList<>();
        if (gear.isEmpty()) {
            return out;
        }
        final HolderLookup.RegistryLookup<Enchantment> enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
        for (final Holder<Enchantment> e : enchantments.listElements().toList()) {
            if (e.is(EnchantmentTags.CURSE)) {
                continue;
            }
            for (int level = e.value().getMaxLevel(); level >= 1; level--) {
                final ItemStack book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(e, level));
                for (final ItemStack piece : gear) {
                    if (EnchantApplier.apply(piece, book, policy).ok()) {
                        out.add(book);
                        break;
                    }
                }
                if (out.size() >= max) {
                    return out;
                }
            }
        }
        return out;
    }

    /** Asks the colony for up to {@code count} of these books (any of them), without blocking the AI. */
    public static void requestBooks(final ICitizenData citizen, final List<ItemStack> books, final int count) {
        citizen.createRequestAsync(new StackList(books, BOOKS_DESCRIPTION, count, 1));
    }

    /** An open or delivered-but-unclaimed request of this citizen for this item. */
    public static boolean pending(final IBuilding building, final ICitizenData citizen, final Item item) {
        final Predicate<IRequest<? extends IDeliverable>> forItem =
                r -> r.getRequest() instanceof Stack stack && stack.getStack().is(item)
                        || r.getRequest() instanceof StackList list && list.getStacks().stream().anyMatch(s -> s.is(item));
        return !building.getOpenRequestsOfTypeFiltered(citizen, TypeConstants.DELIVERABLE, forItem).isEmpty()
                || !building.getCompletedRequestsOfTypeFiltered(citizen, TypeConstants.DELIVERABLE, forItem).isEmpty();
    }
}
