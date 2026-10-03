package me.lovkar.runesmith.ai;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIInteract;
import me.lovkar.runesmith.Runesmith;
import me.lovkar.runesmith.colony.BuildingRunesmith;
import me.lovkar.runesmith.colony.JobRunesmith;
import me.lovkar.runesmith.compat.Citizens;
import me.lovkar.runesmith.compat.Equipment;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Runesmith at work. Two sources of gear, in this order:
 * <ol>
 * <li>the hut's racks: pick a piece and a book the anvil rules accept for it, walk to the anvil,
 *     work a few seconds, then put the enchanted piece back into the rack slot it came from;</li>
 * <li>the colonists (setting, default on): guards first, then the nearest; walk to one, face
 *     them, channel three seconds, then enchant the armor they wear or the tool they hold.</li>
 * </ol>
 *
 * <p>Every change happens in one step on the server thread, after everything has been checked
 * again: the book (and the lapis) are taken first, the enchanted piece goes where the old one was
 * through the container's own setter, and it is read back before the line is logged. If anything
 * moved while the worker was busy, or the colonist walked away, nothing is taken and the work is
 * chosen anew. A pair the rules reject in the racks is reported once, and again only after the
 * hut's or the pack's contents change.</p>
 */
public class EntityAIWorkRunesmith extends AbstractEntityAIInteract<JobRunesmith, BuildingRunesmith> {

    /** The Runesmith's own states. */
    public enum State implements IAIState {
        WALK_TO_WORK,
        ENCHANTING,
        WALK_TO_CITIZEN,
        CHANNEL;

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
    /** A visit: calls of the walk (one every 10 ticks) before the colonist counts as out of reach. */
    private static final int MAX_VISIT_CALLS = 90;
    /** A visit: the channel, in work calls (60 ticks). */
    private static final int CHANNEL_CALLS = 12;
    /** A visit: how close the Runesmith must stand, in blocks. */
    private static final double REACH = 4.0;

    /** Where a stack lies: the hut's racks, or the worker's own pack. */
    private record Slot(boolean rack, int index) {
        String where() {
            return rack ? "rack" : "pack";
        }
    }

    /** A piece a colonist wears or holds, as it was when the work was chosen. */
    private record Worn(ICitizenData citizen, Citizens.Place place, ItemStack piece) {}

    private @Nullable Slot gearSlot;
    private @Nullable Slot bookSlot;
    private ItemStack gearExpected = ItemStack.EMPTY;
    private ItemStack bookExpected = ItemStack.EMPTY;
    private int progress;
    private int visitCitizen = -1;
    private Citizens.Place visitPlace = Citizens.Place.HAND;
    private int visitCalls;
    private final Set<String> reported = new HashSet<>();
    private int reportedFor;
    /** citizen id and place -> {day of the last visit, hash of the piece then}. */
    private final Map<String, long[]> visited = new HashMap<>();

    public EntityAIWorkRunesmith(@NotNull final JobRunesmith job) {
        super(job);
        super.registerTargets(
                new AITarget<IAIState>(AIWorkerState.IDLE, () -> AIWorkerState.START_WORKING, DECIDE_RATE),
                new AITarget<IAIState>(AIWorkerState.START_WORKING, this::decide, DECIDE_RATE),
                new AITarget<IAIState>(State.WALK_TO_WORK, this::walkToWork, DECIDE_RATE / 2),
                new AITarget<IAIState>(State.ENCHANTING, this::enchant, WORK_RATE),
                new AITarget<IAIState>(State.WALK_TO_CITIZEN, this::walkToCitizen, DECIDE_RATE / 2),
                new AITarget<IAIState>(State.CHANNEL, this::channel, WORK_RATE));
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
        final List<Worn> worn = building.colonistsAllowed() ? worn() : List.of();
        if (gear.isEmpty() && worn.isEmpty()) {
            return idle();
        }
        final List<Slot> books = find(racks, pack, BuildingRunesmith::isBook);
        final int lapisHeld = count(racks, pack, BuildingRunesmith::isLapis);
        final RunesmithPolicy policy = building.policy();
        int lapisShort = 0;

        // 1. the racks: what somebody brought to the hut comes first
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
        // 2. the colonists: guards first, then the nearest (rejections here are not logged:
        //    every colonist's every piece against every book would flood the log)
        for (final Worn w : worn) {
            for (final Slot b : books) {
                final ItemStack bookStack = stack(b, racks, pack);
                final EnchantApplier.Result result = EnchantApplier.apply(w.piece(), bookStack, policy);
                if (!result.ok()) {
                    continue;
                }
                if (result.lapis() > lapisHeld) {
                    lapisShort = Math.max(lapisShort, result.lapis());
                    continue;
                }
                if (w.place() == Citizens.Place.HAND && Equipment.wouldOutgrowWorkplace(w.citizen(), w.piece(), result.gear())) {
                    report(w.piece(), bookStack, EnchantApplier.Reason.ABOVE_WORKER_LEVEL);
                    continue;
                }
                bookSlot = b;
                bookExpected = bookStack.copy();
                gearExpected = w.piece().copy();
                visitCitizen = w.citizen().getId();
                visitPlace = w.place();
                visitCalls = 0;
                return State.WALK_TO_CITIZEN;
            }
        }
        // 3. nothing to do now: ask for what is missing
        if (lapisShort > 0) {
            requestLapis(lapisShort);
        } else if (books.size() < BOOK_STOCK_LIMIT && !Requests.pending(building, worker.getCitizenData(), Items.ENCHANTED_BOOK)) {
            Requests.requestBooks(worker.getCitizenData(), BOOKS_PER_REQUEST);
            Runesmith.LOGGER.info("[Runesmith] requested {} enchanted book(s) hut={}", BOOKS_PER_REQUEST, building.getPosition().toShortString());
        }
        return idle();
    }

    /** What the colonists wear and hold that is gear, minus what was visited today and has not changed since. */
    private List<Worn> worn() {
        final List<Worn> out = new ArrayList<>();
        final long day = day();
        for (final ICitizenData c : Citizens.visitable(building.getColony(), worker.getCitizenData(), building.getPosition())) {
            for (final Citizens.Place p : Citizens.Place.values()) {
                final ItemStack piece = Citizens.piece(c, p);
                if (BuildingRunesmith.isGear(piece) && !visitedToday(c, p, piece, day)) {
                    out.add(new Worn(c, p, piece.copy()));
                }
            }
        }
        return out;
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

    // ---------------------------------------------------------------- the racks

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
        applyInRack();
        clearPair();
        return AIWorkerState.START_WORKING;
    }

    /**
     * The one step that changes anything in the racks. Everything is checked again first; the book
     * and the lapis are taken before the gear is replaced, and given back if it cannot be.
     */
    private void applyInRack() {
        final IItemHandler racks = racks();
        final IItemHandler pack = worker.getInventoryCitizen();
        if (racks == null || gearSlot == null || bookSlot == null) {
            return;
        }
        final ItemStack gearNow = racks.getStackInSlot(gearSlot.index());
        if (!ItemStack.matches(gearNow, gearExpected)) {
            return; // something moved while the worker was busy: choose again
        }
        final Taken taken = take(racks, pack, gearNow, null);
        if (taken == null) {
            return;
        }
        final ItemStack gearBefore = gearNow.copy();
        if (!replace(racks, gearSlot.index(), taken.result.gear())) {
            taken.giveBack(racks, pack);
            Runesmith.LOGGER.warn("[Runesmith] could not put the enchanted {} back into its rack slot; book and lapis returned hut={}",
                    name(gearBefore), building.getPosition().toShortString());
            return;
        }
        final ItemStack readBack = racks.getStackInSlot(gearSlot.index());
        if (!ItemStack.matches(readBack, taken.result.gear())) {
            Runesmith.LOGGER.error("[Runesmith] read-back mismatch after enchanting {}: rack slot holds {} hut={}",
                    name(gearBefore), describe(readBack), building.getPosition().toShortString());
        }
        done(taken, gearBefore, readBack, gearSlot.where());
    }

    // ---------------------------------------------------------------- the colonists

    private @Nullable ICitizenData visited() {
        return visitCitizen < 0 ? null : Citizens.byId(building.getColony(), visitCitizen);
    }

    private static @Nullable AbstractEntityCitizen alive(@Nullable final ICitizenData c) {
        return c == null ? null : c.getEntity().filter(LivingEntity::isAlive).orElse(null);
    }

    private IAIState walkToCitizen() {
        final ICitizenData c = visited();
        final AbstractEntityCitizen e = alive(c);
        if (e == null || bookSlot == null) {
            endVisit(c);
            return AIWorkerState.START_WORKING;
        }
        if (++visitCalls > MAX_VISIT_CALLS) {
            endVisit(c);
            return AIWorkerState.START_WORKING;
        }
        if (worker.distanceTo(e) <= REACH || walkToSafePos(e.blockPosition())) {
            progress = 0;
            world.playSound(null, worker.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.NEUTRAL, 0.6F, 1.2F);
            return State.CHANNEL;
        }
        return getState();
    }

    private IAIState channel() {
        final ICitizenData c = visited();
        final AbstractEntityCitizen e = alive(c);
        if (e == null || bookSlot == null) {
            endVisit(c);
            return AIWorkerState.START_WORKING;
        }
        if (worker.distanceTo(e) > REACH + 2.0) {
            return State.WALK_TO_CITIZEN; // they walked off: follow (the walk keeps counting its calls)
        }
        worker.getLookControl().setLookAt(e, 30.0F, 30.0F);
        if (++progress < CHANNEL_CALLS) {
            worker.swing(progress % 2 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            if (world instanceof ServerLevel level) {
                final Vec3 from = worker.position().add(0, 1.4, 0);
                final Vec3 to = e.position().add(0, 1.0, 0);
                for (int i = 1; i <= 4; i++) {
                    final Vec3 at = from.lerp(to, i / 5.0);
                    level.sendParticles(ParticleTypes.ENCHANT, at.x, at.y, at.z, 2, 0.1, 0.1, 0.1, 0.2);
                }
            }
            return getState();
        }
        applyToCitizen(c);
        endVisit(c);
        return AIWorkerState.START_WORKING;
    }

    /** The one step that changes a colonist's piece, checked again first, read back from inventory and entity. */
    private void applyToCitizen(final ICitizenData c) {
        final IItemHandler racks = racks();
        final IItemHandler pack = worker.getInventoryCitizen();
        if (racks == null) {
            return;
        }
        final ItemStack pieceNow = Citizens.piece(c, visitPlace).copy();
        // the same piece, worn a little more since is still the same piece
        if (!sameButWear(pieceNow, gearExpected) || !Citizens.shows(c, visitPlace, pieceNow)) {
            return;
        }
        final Taken taken = take(racks, pack, pieceNow, c);
        if (taken == null) {
            return;
        }
        if (!Citizens.replace(c, visitPlace, pieceNow, taken.result.gear())) {
            if (Citizens.shows(c, visitPlace, pieceNow)) {
                taken.giveBack(racks, pack);
                Runesmith.LOGGER.warn("[Runesmith] could not give {} the enchanted {}; book and lapis returned hut={}",
                        c.getName(), name(pieceNow), building.getPosition().toShortString());
                return;
            }
            Runesmith.LOGGER.error("[Runesmith] read-back mismatch after enchanting {}'s {}: inventory {} entity {} hut={}", c.getName(),
                    name(pieceNow), describe(Citizens.piece(c, visitPlace)),
                    c.getEntity().map(e -> describe(e.getItemBySlot(visitPlace.slot))).orElse("gone"), building.getPosition().toShortString());
        }
        done(taken, pieceNow, Citizens.piece(c, visitPlace), "citizen " + c.getName());
    }

    private void endVisit(@Nullable final ICitizenData c) {
        if (c != null) {
            visited.put(c.getId() + ":" + visitPlace, new long[] {day(), pieceHash(Citizens.piece(c, visitPlace))});
        }
        clearPair();
    }

    private boolean visitedToday(final ICitizenData c, final Citizens.Place p, final ItemStack piece, final long day) {
        final long[] last = visited.get(c.getId() + ":" + p);
        return last != null && last[0] == day && last[1] == pieceHash(piece);
    }

    /** The piece's identity for the daily visit: its item and its enchantments (not its wear). */
    private static long pieceHash(final ItemStack s) {
        return 31L * BuiltInRegistries.ITEM.getKey(s.getItem()).hashCode() + EnchantmentHelper.getEnchantmentsForCrafting(s).hashCode();
    }

    private static boolean sameButWear(final ItemStack a, final ItemStack b) {
        return a.is(b.getItem()) && a.getCount() == b.getCount()
                && EnchantApplier.sameEnchantments(EnchantmentHelper.getEnchantmentsForCrafting(a), EnchantmentHelper.getEnchantmentsForCrafting(b));
    }

    private long day() {
        return world.getDayTime() / 24000L;
    }

    // ---------------------------------------------------------------- taking and giving

    /** The book and the lapis, taken out, and the result they buy; {@link #giveBack} returns them. */
    private final class Taken {
        final EnchantApplier.Result result;
        final ItemStack book;
        final IItemHandler bookHandler;
        final int bookIndex;
        final List<ItemStack> lapis;

        Taken(final EnchantApplier.Result result, final ItemStack book, final IItemHandler bookHandler, final int bookIndex,
                final List<ItemStack> lapis) {
            this.result = result;
            this.book = book;
            this.bookHandler = bookHandler;
            this.bookIndex = bookIndex;
            this.lapis = lapis;
        }

        void giveBack(final IItemHandler racks, final IItemHandler pack) {
            EntityAIWorkRunesmith.giveBack(bookHandler, bookIndex, book, racks, pack);
            for (final ItemStack l : lapis) {
                EntityAIWorkRunesmith.giveBack(racks, -1, l, racks, pack);
            }
        }
    }

    /**
     * Checks the book and the price again for this piece and takes them; null (and nothing taken)
     * if anything no longer holds. A colonist's tool is also checked against his workplace.
     */
    private @Nullable Taken take(final IItemHandler racks, final IItemHandler pack, final ItemStack piece, @Nullable final ICitizenData colonist) {
        if (bookSlot == null) {
            return null;
        }
        final IItemHandler bookHandler = bookSlot.rack() ? racks : pack;
        final ItemStack bookNow = bookHandler.getStackInSlot(bookSlot.index());
        if (bookNow.isEmpty() || !ItemStack.isSameItemSameComponents(bookNow, bookExpected)) {
            return null;
        }
        final EnchantApplier.Result result = EnchantApplier.apply(piece, bookNow, building.policy());
        if (!result.ok()) {
            report(piece, bookNow, result.reason());
            return null;
        }
        if (colonist != null && visitPlace == Citizens.Place.HAND && Equipment.wouldOutgrowWorkplace(colonist, piece, result.gear())) {
            report(piece, bookNow, EnchantApplier.Reason.ABOVE_WORKER_LEVEL);
            return null;
        }
        if (result.lapis() > count(racks, pack, BuildingRunesmith::isLapis)) {
            return null;
        }
        final ItemStack book = bookHandler.extractItem(bookSlot.index(), 1, false);
        if (book.isEmpty()) {
            return null;
        }
        final List<ItemStack> lapis = takeLapis(racks, pack, result.lapis());
        if (lapis == null) {
            giveBack(bookHandler, bookSlot.index(), book, racks, pack);
            return null;
        }
        return new Taken(result, book, bookHandler, bookSlot.index(), lapis);
    }

    private void done(final Taken taken, final ItemStack before, final ItemStack after, final String where) {
        world.playSound(null, worker.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.5F, 1.2F);
        Runesmith.LOGGER.info("[Runesmith] applied {} via {}{} to {} -> {} ({}) hut={} worker={}",
                EnchantApplier.describe(EnchantmentHelper.getEnchantmentsForCrafting(taken.book)), describe(taken.book),
                taken.result.lapis() > 0 ? " + " + taken.result.lapis() + " lapis" : "", describe(before), describe(after),
                where, building.getPosition().toShortString(), worker.getCitizenData().getName());
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
        visitCitizen = -1;
        visitCalls = 0;
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
