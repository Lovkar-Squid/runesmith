package me.lovkar.runesmith.compat;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.util.constant.TypeConstants;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * Every request-system call the Runesmith makes, in one place: these are the MineColonies
 * signatures most likely to move between snapshots.
 *
 * <p>Books are asked for as a plain {@link Stack} of {@code minecraft:enchanted_book} with
 * component matching off: the warehouse then delivers any enchanted book it has, and the worker
 * sorts out which ones the anvil rules accept. (Matching on components would only ever match one
 * exact enchantment and level.)</p>
 */
public final class Requests {
    private Requests() {
    }

    /** An item request for {@code count} of anything that is an enchanted book, at least one. */
    public static Stack anyEnchantedBook(final int count) {
        return new Stack(new ItemStack(Items.ENCHANTED_BOOK), count, 1, false);
    }

    /** Asks the colony for enchanted books on behalf of this citizen, without blocking the AI. */
    public static void requestBooks(final ICitizenData citizen, final int count) {
        citizen.createRequestAsync(anyEnchantedBook(count));
    }

    /** An open or delivered-but-unclaimed request of this citizen for this item. */
    public static boolean pending(final IBuilding building, final ICitizenData citizen, final Item item) {
        final Predicate<IRequest<? extends IDeliverable>> forItem =
                r -> r.getRequest() instanceof Stack stack && stack.getStack().is(item);
        return !building.getOpenRequestsOfTypeFiltered(citizen, TypeConstants.DELIVERABLE, forItem).isEmpty()
                || !building.getCompletedRequestsOfTypeFiltered(citizen, TypeConstants.DELIVERABLE, forItem).isEmpty();
    }
}
