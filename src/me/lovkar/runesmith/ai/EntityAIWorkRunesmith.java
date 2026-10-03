package me.lovkar.runesmith.ai;

import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIInteract;
import me.lovkar.runesmith.Runesmith;
import me.lovkar.runesmith.colony.BuildingRunesmith;
import me.lovkar.runesmith.colony.JobRunesmith;
import me.lovkar.runesmith.compat.Requests;
import me.lovkar.runesmith.logic.EnchantApplier;
import me.lovkar.runesmith.logic.RunesmithPolicy;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Runesmith at work: pick a piece of gear in the racks and a book the anvil rules accept for
 * it, walk to the anvil, work a few seconds, then in one step on the server thread take the book
 * (and the lapis), put the enchanted piece back where the old one lay, read it back and log it.
 *
 * <p>Nothing is ever taken before the result is known and checked again; if anything moved while
 * the worker was busy, the work is simply dropped and chosen anew. A pair the rules reject is
 * reported once and not reported again until the inventory changes.</p>
 */
public class EntityAIWorkRunesmith extends AbstractEntityAIInteract<JobRunesmith, BuildingRunesmith> {

    /** The Runesmith's own states. */
    public enum State implements IAIState {
        WALK_TO_WORK,
        ENCHANTING;

        @Override
        public boolean isOkayToEat() {
            return true;
        }
    }

    private static final int DECIDE_RATE = 20;
    private static final int WORK_RATE = 5;
    /** Idle pause when there is nothing to do, in ticks. */
    private static final int IDLE_DELAY = 60;
    private static final int BOOKS_PER_REQUEST = 4;
    /** Enchanted books the hut may hold before it stops asking the colony for more. */
    private static final int BOOK_STOCK_LIMIT = 8;
    private static final int LAPIS_PER_REQUEST = 16;
    private static final double XP_PER_BOOK = 2.0;

    /** Where a stack lies: the hut's racks, or the worker's own pack. */
    private record Slot(boolean rack, int index) {
        String where() {
            return rack ? "rack" : "pack";
        }
    }

    private @Nullable Slot gearSlot;
    private @Nullable Slot bookSlot;
    private ItemStack gearExpected = ItemStack.EMPTY;
    private ItemStack bookExpected = ItemStack.EMPTY;
    private int progress;
    private final Set<String> reported = new HashSet<>();
    private int reportedFor;

    public EntityAIWorkRunesmith(@NotNull final JobRunesmith job) {
        super(job);
        super.registerTargets(
                new AITarget<IAIState>(AIWorkerState.IDLE, () -> AIWorkerState.START_WORKING, DECIDE_RATE),
                new AITarget<IAIState>(AIWorkerState.START_WORKING, this::decide, DECIDE_RATE),
                new AITarget<IAIState>(State.WALK_TO_WORK, this::walkToWork, DECIDE_RATE / 2),
                new AITarget<IAIState>(State.ENCHANTING, this::enchant, WORK_RATE));
    }

    @Override
    public Class<BuildingRunesmith> getExpectedBuildingClass() {
        return BuildingRunesmith.class;
    }

    // ---------------------------------------------------------------- choosing

    private IAIState decide() {
        clearPair();
        if (!walkToBuilding()) {
            return AIWorkerState.START_WORKING;
        }
        final IItemHandler racks = racks();
        if (racks == null) {
            return idle();
        }
        final IItemHandler pack = worker.getInventoryCitizen();
        forgetReportsIfChanged(racks, pack);

        final List<Slot> gear = find(racks, null, BuildingRunesmith::isGear);
        if (gear.isEmpty()) {
            return idle();
        }
        final List<Slot> books = find(racks, pack, BuildingRunesmith::isBook);
        final int lapisHeld = count(racks, pack, BuildingRunesmith::isLapis);
        final RunesmithPolicy policy = building.policy();

        int lapisShort = 0;
        for (final Slot g : gear) {
            final ItemStack gearStack = stack(g, racks, pack);
            for (final Slot b : books) {
                final ItemStack bookStack = stack(b, racks, pack);
                final EnchantApplier.Result result = EnchantApplier.apply(gearStack, bookStack, policy);
                if (!result.ok()) {
                    report(gearStack, bookStack, result.reason());
                    continue;
                }
                if (result.lapis() > lapisHeld) {
                    lapisShort = Math.max(lapisShort, result.lapis());
                    continue;
                }
                gearSlot = g;
                bookSlot = b;
                gearExpected = gearStack.copy();
                bookExpected = bookStack.copy();
                return State.WALK_TO_WORK;
            }
        }
        if (lapisShort > 0) {
            requestLapis(lapisShort);
        } else if (books.size() < BOOK_STOCK_LIMIT && !Requests.pending(building, worker.getCitizenData(), Items.ENCHANTED_BOOK)) {
            Requests.requestBooks(worker.getCitizenData(), BOOKS_PER_REQUEST);
            Runesmith.LOGGER.info("[Runesmith] requested {} enchanted book(s) hut={}", BOOKS_PER_REQUEST, building.getPosition().toShortString());
        }
        return idle();
    }

    private void requestLapis(final int needed) {
        if (Requests.pending(building, worker.getCitizenData(), Items.LAPIS_LAZULI)) {
            return;
        }
        checkIfRequestForItemExistOrCreateAsync(new ItemStack(Items.LAPIS_LAZULI), Math.max(LAPIS_PER_REQUEST, needed), needed);
        Runesmith.LOGGER.info("[Runesmith] requested {} lapis lazuli hut={}", Math.max(LAPIS_PER_REQUEST, needed),
                building.getPosition().toShortString());
    }

    private IAIState idle() {
        setDelay(IDLE_DELAY);
        return AIWorkerState.IDLE;
    }

    // ---------------------------------------------------------------- working

    private IAIState walkToWork() {
        if (gearSlot == null) {
            return AIWorkerState.START_WORKING;
        }
        if (!walkToTaggedWorkPos()) {
            return getState();
        }
        progress = 0;
        world.playSound(null, worker.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.NEUTRAL, 0.6F, 1.0F);
        return State.ENCHANTING;
    }

    /** Work calls (one every {@value #WORK_RATE} ticks): 10 s at hut level 1, 4 s at level 5. */
    private int workCalls() {
        return Math.max(16, 40 - 6 * (building.getBuildingLevel() - 1));
    }

    private IAIState enchant() {
        if (gearSlot == null || bookSlot == null) {
            return AIWorkerState.START_WORKING;
        }
        if (++progress < workCalls()) {
            worker.swing(progress % 2 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            if (world instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.ENCHANT, worker.getX(), worker.getY() + 1.2, worker.getZ(), 6, 0.5, 0.4, 0.5, 0.6);
            }
            return getState();
        }
        applyNow();
        clearPair();
        return AIWorkerState.START_WORKING;
    }

    /**
     * The one step that changes anything. Everything is checked again first; the book and the lapis
     * are taken before the gear is replaced, and given back if the replacement cannot happen.
     */
    private void applyNow() {
        final IItemHandler racks = racks();
        final IItemHandler pack = worker.getInventoryCitizen();
        if (racks == null || gearSlot == null || bookSlot == null) {
            return;
        }
        final IItemHandler gearHandler = racks;
        final IItemHandler bookHandler = bookSlot.rack() ? racks : pack;
        final ItemStack gearNow = gearHandler.getStackInSlot(gearSlot.index());
        final ItemStack bookNow = bookHandler.getStackInSlot(bookSlot.index());
        if (!ItemStack.matches(gearNow, gearExpected) || !ItemStack.isSameItemSameComponents(bookNow, bookExpected) || bookNow.isEmpty()) {
            return; // something moved while the worker was busy: choose again
        }
        final EnchantApplier.Result result = EnchantApplier.apply(gearNow, bookNow, building.policy());
        if (!result.ok()) {
            report(gearNow, bookNow, result.reason());
            return;
        }
        if (result.lapis() > count(racks, pack, BuildingRunesmith::isLapis)) {
            return;
        }
        final ItemStack gearBefore = gearNow.copy();
        final ItemStack bookTaken = bookHandler.extractItem(bookSlot.index(), 1, false);
        if (bookTaken.isEmpty()) {
            return;
        }
        final List<ItemStack> lapisTaken = takeLapis(racks, pack, result.lapis());
        if (lapisTaken == null) {
            giveBack(bookHandler, bookSlot.index(), bookTaken, racks, pack);
            return;
        }
        if (!replace(gearHandler, gearSlot.index(), result.gear())) {
            giveBack(bookHandler, bookSlot.index(), bookTaken, racks, pack);
            for (final ItemStack l : lapisTaken) {
                giveBack(racks, -1, l, racks, pack);
            }
            Runesmith.LOGGER.warn("[Runesmith] could not put the enchanted {} back into its rack slot; book and lapis returned hut={}",
                    name(gearBefore), building.getPosition().toShortString());
            return;
        }
        final ItemStack readBack = gearHandler.getStackInSlot(gearSlot.index());
        if (!ItemStack.matches(readBack, result.gear())) {
            Runesmith.LOGGER.error("[Runesmith] read-back mismatch after enchanting {}: rack slot holds {} hut={}",
                    name(gearBefore), describe(readBack), building.getPosition().toShortString());
        }
        world.playSound(null, worker.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.5F, 1.2F);
        Runesmith.LOGGER.info("[Runesmith] applied {} via {}{} to {} -> {} ({}) hut={} worker={}",
                EnchantApplier.describe(EnchantmentHelper.getEnchantmentsForCrafting(bookTaken)), describe(bookTaken),
                result.lapis() > 0 ? " + " + result.lapis() + " lapis" : "", describe(gearBefore), describe(readBack),
                gearSlot.where(), building.getPosition().toShortString(), worker.getCitizenData().getName());
        incrementActionsDoneAndDecSaturation();
        worker.getCitizenExperienceHandler().addExperience(XP_PER_BOOK);
    }

    /** Takes exactly {@code amount} lapis, pack first; null (and nothing taken) if there is not enough. */
    private static @Nullable List<ItemStack> takeLapis(final IItemHandler racks, final IItemHandler pack, final int amount) {
        final List<ItemStack> taken = new ArrayList<>();
        if (amount <= 0) {
            return taken;
        }
        if (count(racks, pack, BuildingRunesmith::isLapis) < amount) {
            return null;
        }
        int left = amount;
        for (final IItemHandler h : List.of(pack, racks)) {
            for (int i = 0; i < h.getSlots() && left > 0; i++) {
                if (BuildingRunesmith.isLapis(h.getStackInSlot(i))) {
                    final ItemStack got = h.extractItem(i, left, false);
                    left -= got.getCount();
                    if (!got.isEmpty()) {
                        taken.add(got);
                    }
                }
            }
        }
        return taken;
    }

    /** Puts the enchanted piece into the slot the old one occupied, through the container itself. */
    private static boolean replace(final IItemHandler handler, final int slot, final ItemStack enchanted) {
        if (handler instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(slot, enchanted.copy());
            return ItemStack.matches(handler.getStackInSlot(slot), enchanted);
        }
        final ItemStack old = handler.extractItem(slot, 1, false);
        if (old.isEmpty()) {
            return false;
        }
        final ItemStack rest = handler.insertItem(slot, enchanted.copy(), false);
        if (!rest.isEmpty()) {
            handler.insertItem(slot, old, false);
            return false;
        }
        return true;
    }

    /** Returns a taken stack: to its slot if it still fits there, else anywhere in the racks, else the pack. */
    private static void giveBack(final IItemHandler handler, final int slot, final ItemStack stack, final IItemHandler racks, final IItemHandler pack) {
        ItemStack rest = slot >= 0 ? handler.insertItem(slot, stack, false) : stack;
        if (!rest.isEmpty()) {
            rest = InventoryUtils.addItemStackToItemHandlerWithResult(racks, rest);
        }
        if (!rest.isEmpty()) {
            InventoryUtils.addItemStackToItemHandler(pack, rest);
        }
    }

    // ---------------------------------------------------------------- inventory helpers

    private @Nullable IItemHandler racks() {
        return building.getItemHandlerCap((Direction) null);
    }

    private static List<Slot> find(final IItemHandler racks, @Nullable final IItemHandler pack, final Predicate<ItemStack> what) {
        final List<Slot> out = new ArrayList<>();
        for (int i = 0; i < racks.getSlots(); i++) {
            if (what.test(racks.getStackInSlot(i))) {
                out.add(new Slot(true, i));
            }
        }
        if (pack != null) {
            for (int i = 0; i < pack.getSlots(); i++) {
                if (what.test(pack.getStackInSlot(i))) {
                    out.add(new Slot(false, i));
                }
            }
        }
        return out;
    }

    private static ItemStack stack(final Slot slot, final IItemHandler racks, final IItemHandler pack) {
        return (slot.rack() ? racks : pack).getStackInSlot(slot.index());
    }

    private static int count(final IItemHandler racks, final IItemHandler pack, final Predicate<ItemStack> what) {
        return InventoryUtils.getItemCountInItemHandler(racks, what) + InventoryUtils.getItemCountInItemHandler(pack, what);
    }

    private void clearPair() {
        gearSlot = null;
        bookSlot = null;
        gearExpected = ItemStack.EMPTY;
        bookExpected = ItemStack.EMPTY;
        progress = 0;
    }

    // ---------------------------------------------------------------- reporting

    /** A rejected pair is logged once, and again only after the hut's or the pack's contents change. */
    private void report(final ItemStack gear, final ItemStack book, final EnchantApplier.Reason reason) {
        final String key = BuiltInRegistries.ITEM.getKey(gear.getItem()) + "#" + gear.getComponents().hashCode() + "|"
                + book.getComponents().hashCode() + "|" + reason;
        if (reported.add(key)) {
            Runesmith.LOGGER.info("[Runesmith] skipped {} + {}: {}", describe(gear), describe(book), reason);
        }
    }

    private void forgetReportsIfChanged(final IItemHandler racks, final IItemHandler pack) {
        int h = 1;
        for (final IItemHandler handler : List.of(racks, pack)) {
            for (int i = 0; i < handler.getSlots(); i++) {
                final ItemStack s = handler.getStackInSlot(i);
                if (!s.isEmpty()) {
                    h = 31 * h + i;
                    h = 31 * h + s.getItem().hashCode();
                    h = 31 * h + s.getCount();
                    h = 31 * h + s.getComponents().hashCode();
                }
            }
        }
        if (h != reportedFor) {
            reportedFor = h;
            reported.clear();
        }
    }

    private static String name(final ItemStack stack) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** "minecraft:iron_sword [minecraft:sharpness 3]" - an item with its enchantments, for logs. */
    public static String describe(final ItemStack stack) {
        final ItemEnchantments e = stack.is(Items.ENCHANTED_BOOK)
                ? stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY)
                : stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        return name(stack) + " [" + EnchantApplier.describe(e) + "]";
    }
}
