package me.lovkar.runesmith.ai;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIInteract;
import me.lovkar.runesmith.Runesmith;
import me.lovkar.runesmith.colony.BuildingRunesmith;
import me.lovkar.runesmith.colony.JobRunesmith;
import me.lovkar.runesmith.colony.JobRunesmith.Loan;
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
import java.util.Comparator;
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
 *     them, channel three seconds, then enchant the armor they wear or the tool they hold;</li>
 * <li>the warehouses (setting, default off): borrow one piece, enchant it at the anvil and put
 *     it back into the warehouse slot it came from. A piece on loan is written into the job, so
 *     it goes back even after a restart, and it goes back before anything new is started.</li>
 * </ol>
 *
 * <p>Every change happens in one step on the server thread, after everything has been checked
 * again: the book (and the lapis) are taken first, the enchanted piece goes where the old one was
 * through the container's own setter, and it is read back before the line is logged. If anything
 * moved while the worker was busy, or the colonist walked away, nothing is taken and the work is
 * chosen anew. A pair the rules reject in the racks is reported once, and again only after a
 * setting or the building's level changes.</p>
 */
public class EntityAIWorkRunesmith extends AbstractEntityAIInteract<JobRunesmith, BuildingRunesmith> {

    /** The Runesmith's own states. */
    public enum State implements IAIState {
        WALK_TO_WORK,
        ENCHANTING,
        WALK_TO_CITIZEN,
        CHANNEL,
        LOAN_FETCH,
        LOAN_RETURN,
        POTTER;

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
    /** Enchanted books the hut may hold (useless ones included) before it stops asking the colony for more. */
    private static final int BOOK_STOCK_LIMIT = 32;
    /** The most kinds of book one request lists. */
    private static final int MAX_BOOK_KINDS = 128;
    /** A safety net under the open-request check: at most one book request, and one lapis request, a minute. */
    private static final long REQUEST_PAUSE_TICKS = 1200;
    private static final int LAPIS_PER_REQUEST = 16;
    /** A piece he enchanted in the racks is finished when no book in stock has improved it for this long, in ticks. */
    private static final long FINISHED_TICKS = 2400;
    /** A courier is asked to fetch finished gear at most this often while it waits, in ticks. */
    private static final long PICKUP_PAUSE_TICKS = 2400;
    /** Idle: about one idle pause in this many sends him pottering about the hut instead of standing still. */
    private static final int POTTER_ODDS = 3;
    /** Pottering: walk calls (one a second) before he gives the spot up. */
    private static final int POTTER_MAX_CALLS = 30;
    private static final double XP_PER_BOOK = 2.0;
    /** A visit: calls of the walk (one every 10 ticks) before the colonist counts as out of reach. */
    private static final int MAX_VISIT_CALLS = 150;
    /** A visit: the channel, in work calls (60 ticks), counted only while the colonist is in range. */
    private static final int CHANNEL_CALLS = 12;
    /** A visit: how close the Runesmith walks up, in blocks. */
    private static final double REACH = 4.0;
    /** A visit: how far the colonist may stray during the channel before the Runesmith follows. */
    private static final double CHANNEL_RANGE = 8.0;
    /** After a visit that did not get through, the same colonist and slot wait this long, in ticks. */
    private static final long RETRY_TICKS = 2400;
    /** The warehouses are looked through at most this often (they can hold thousands of slots). */
    private static final int WAREHOUSE_SCAN_TICKS = 600;
    /** A trip to a warehouse: walk calls (one every 10 ticks) before giving up. */
    private static final int MAX_TRIP_CALLS = 180;

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
    /** Rejected pairs already logged under {@link #reportedFor}; capped so a huge stock cannot grow it without end. */
    private final Set<String> reported = new HashSet<>();
    private @Nullable RunesmithPolicy reportedFor;
    private static final int REPORTED_MAX = 4096;
    /** citizen id and place -> {day of the last visit, hash of the piece then, game time to retry after (0 = it got through)}. */
    private final Map<String, long[]> visited = new HashMap<>();
    /** A warehouse piece chosen but not yet taken. */
    private @Nullable LoanPlan loanPlan;
    private long nextWarehouseScan;
    private boolean warehouseHasGear;
    /** The warehouse gear seen at the last scan, for what books to ask for. */
    private final List<ItemStack> warehouseGearSeen = new ArrayList<>();
    private int tripCalls;
    private long nextBookRequest;
    private long nextLapisRequest;
    private long nextPickupRequest;
    /** The finished pieces a courier was last asked to fetch. */
    private final Set<String> sentFinished = new HashSet<>();
    private net.minecraft.core.BlockPos potterTarget;
    private int potterCalls;
    private int potterStay;

    /** The warehouse slot and the piece in it, and the book chosen for it. */
    private record LoanPlan(net.minecraft.core.BlockPos warehouse, net.minecraft.core.BlockPos rack, int slot, ItemStack piece, Slot book,
            ItemStack bookStack) {}

    public EntityAIWorkRunesmith(@NotNull final JobRunesmith job) {
        super(job);
        super.registerTargets(
                new AITarget<IAIState>(AIWorkerState.IDLE, () -> AIWorkerState.START_WORKING, DECIDE_RATE),
                new AITarget<IAIState>(AIWorkerState.START_WORKING, this::decide, DECIDE_RATE),
                new AITarget<IAIState>(State.WALK_TO_WORK, this::walkToWork, DECIDE_RATE / 2),
                new AITarget<IAIState>(State.ENCHANTING, this::enchant, WORK_RATE),
                new AITarget<IAIState>(State.WALK_TO_CITIZEN, this::walkToCitizen, DECIDE_RATE / 2),
                new AITarget<IAIState>(State.CHANNEL, this::channel, WORK_RATE),
                new AITarget<IAIState>(State.LOAN_FETCH, this::fetchLoan, DECIDE_RATE / 2),
                new AITarget<IAIState>(State.LOAN_RETURN, this::returnLoan, DECIDE_RATE / 2),
                new AITarget<IAIState>(State.POTTER, this::potter, DECIDE_RATE));
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
        final RunesmithPolicy policy = building.policy();
        forgetReportsIfPolicyChanged(policy);
        // 0. a warehouse piece on loan goes back before anything new is started
        if (!job.loans().isEmpty()) {
            tripCalls = 0;
            return State.LOAN_RETURN;
        }

        final List<Slot> gear = find(racks, null, BuildingRunesmith::isGear);
        final List<Worn> worn = building.colonistsAllowed() ? worn() : List.of();
        final boolean warehouses = building.warehouseAllowed();
        if (gear.isEmpty() && worn.isEmpty() && !warehouses) {
            return idle();
        }
        final List<Slot> books = find(racks, pack, BuildingRunesmith::isBook);
        final int lapisHeld = count(racks, pack, BuildingRunesmith::isLapis);
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
        // 3. the warehouses: one piece at a time, looked through at most every 30 seconds
        if (warehouses && world.getGameTime() >= nextWarehouseScan) {
            nextWarehouseScan = world.getGameTime() + WAREHOUSE_SCAN_TICKS;
            final int[] shortOf = {0};
            if (planLoan(books, racks, pack, policy, lapisHeld, shortOf)) {
                tripCalls = 0;
                return State.LOAN_FETCH;
            }
            lapisShort = Math.max(lapisShort, shortOf[0]);
        }
        // 4. nothing to do now: let finished gear go, and ask for what is missing
        final Set<String> finished = releaseFinished(gear, racks, pack, lapisShort);
        final boolean anyGear = !gear.isEmpty() || !worn.isEmpty() || warehouses && warehouseHasGear;
        if (lapisShort > 0) {
            requestLapis(lapisShort);
        } else if (anyGear && books.size() < BOOK_STOCK_LIMIT && world.getGameTime() >= nextBookRequest
                && !Requests.pending(building, worker.getCitizenData(), Items.ENCHANTED_BOOK)) {
            // only books that would improve something: a useless one in the racks cannot answer the request
            final List<ItemStack> pieces = new ArrayList<>();
            for (final Slot g : gear) {
                final ItemStack piece = stack(g, racks, pack);
                if (!finished.contains(BuildingRunesmith.fingerprint(piece))) {
                    pieces.add(piece); // no books for gear on its way to the warehouse
                }
            }
            worn.forEach(w -> pieces.add(w.piece()));
            if (warehouses) {
                pieces.addAll(warehouseGearSeen);
            }
            final List<ItemStack> wanted = Requests.usefulBooks(world.registryAccess(), pieces, policy, MAX_BOOK_KINDS);
            nextBookRequest = world.getGameTime() + REQUEST_PAUSE_TICKS;
            if (!wanted.isEmpty()) {
                Requests.requestBooks(worker.getCitizenData(), wanted, BOOKS_PER_REQUEST);
                Runesmith.LOGGER.info("[Runesmith] requested {} enchanted book(s), any of {} kinds the gear can take hut={}", BOOKS_PER_REQUEST,
                        wanted.size(), building.getPosition().toShortString());
            }
        }
        return idle();
    }

    /**
     * What the colonists wear and hold that is gear, minus what was visited today and has not changed
     * since. Armor counts only when the entity wears it too; the held piece is the inventory's held
     * slot (see {@link Citizens#shows}).
     */
    private List<Worn> worn() {
        final List<Worn> out = new ArrayList<>();
        final long day = day();
        for (final ICitizenData c : Citizens.visitable(building.getColony(), worker.getCitizenData(), building.getPosition())) {
            for (final Citizens.Place p : Citizens.Place.values()) {
                final ItemStack piece = Citizens.piece(c, p);
                if (BuildingRunesmith.isGear(piece) && Citizens.shows(c, p, piece) && !visitedToday(c, p, piece, day)) {
                    out.add(new Worn(c, p, piece.copy()));
                }
            }
        }
        return out;
    }

    /**
     * Finished rack gear goes to the warehouse (setting, default on): a piece the Runesmith enchanted
     * here that no book in stock improves (there is no pair to work on, or this is not called) and
     * that has waited {@link #FINISHED_TICKS} since its last book, so books on their way still find
     * it. The building lets a courier take it, and a courier is asked for. Returns the finished pieces.
     */
    private Set<String> releaseFinished(final List<Slot> gear, final IItemHandler racks, final IItemHandler pack, final int lapisShort) {
        final Set<String> finished = new HashSet<>();
        if (building.sendFinished() && lapisShort == 0) {
            final long now = world.getGameTime();
            for (final Slot g : gear) {
                final ItemStack piece = stack(g, racks, pack);
                final Long at = building.enchantedAt(piece);
                if (at != null && now - at >= FINISHED_TICKS) {
                    finished.add(BuildingRunesmith.fingerprint(piece));
                }
            }
        }
        building.setReleasable(finished);
        final boolean news = !sentFinished.containsAll(finished);
        if (!finished.isEmpty() && (news || world.getGameTime() >= nextPickupRequest)) {
            building.createPickupRequest(finished.size(), true);
            nextPickupRequest = world.getGameTime() + PICKUP_PAUSE_TICKS;
            if (news) {
                Runesmith.LOGGER.info("[Runesmith] sending {} finished piece(s) to the warehouse: {} hut={}", finished.size(),
                        String.join(", ", finished), building.getPosition().toShortString());
            }
        }
        sentFinished.clear();
        sentFinished.addAll(finished);
        return finished;
    }

    private void requestLapis(final int needed) {
        if (world.getGameTime() < nextLapisRequest || Requests.pending(building, worker.getCitizenData(), Items.LAPIS_LAZULI)) {
            return;
        }
        nextLapisRequest = world.getGameTime() + REQUEST_PAUSE_TICKS;
        checkIfRequestForItemExistOrCreateAsync(new ItemStack(Items.LAPIS_LAZULI), Math.max(LAPIS_PER_REQUEST, needed), needed);
        Runesmith.LOGGER.info("[Runesmith] requested {} lapis lazuli hut={}", Math.max(LAPIS_PER_REQUEST, needed),
                building.getPosition().toShortString());
    }

    /** Nothing to do: wait a little, and now and then potter about the hut rather than stand still. */
    private IAIState idle() {
        if (world.random.nextInt(POTTER_ODDS) == 0) {
            potterTarget = potterSpot();
            potterCalls = 0;
            potterStay = 2 + world.random.nextInt(4);
            return State.POTTER;
        }
        setDelay(IDLE_DELAY);
        return AIWorkerState.IDLE;
    }

    /** The anvil, one of the racks or the hut block, picked at random (the hut block is always there). */
    private net.minecraft.core.BlockPos potterSpot() {
        final List<net.minecraft.core.BlockPos> spots = new ArrayList<>(building.getLocationsFromTag(BuildingRunesmith.TAG_WORK));
        for (final net.minecraft.core.BlockPos c : building.getContainers()) {
            if (!c.equals(building.getPosition())) {
                spots.add(c);
            }
        }
        spots.add(building.getPosition());
        return spots.get(world.random.nextInt(spots.size()));
    }

    /** Walks to the chosen spot and stays a few seconds; at the anvil he gives it a tap or two. */
    private IAIState potter() {
        final net.minecraft.core.BlockPos target = potterTarget;
        if (target == null || ++potterCalls > POTTER_MAX_CALLS) {
            potterTarget = null;
            return AIWorkerState.START_WORKING;
        }
        if (!walkToSafePos(target)) {
            return getState();
        }
        worker.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (potterStay-- > 0) {
            if (building.getLocationsFromTag(BuildingRunesmith.TAG_WORK).contains(target) && world.random.nextBoolean()) {
                worker.swing(InteractionHand.MAIN_HAND);
            }
            return getState();
        }
        potterTarget = null;
        return AIWorkerState.START_WORKING;
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
        applyInHut();
        clearPair();
        return AIWorkerState.START_WORKING;
    }

    /**
     * The one step that changes a piece at the anvil (a rack's, or a borrowed one in the pack).
     * Everything is checked again first; the book and the lapis are taken before the gear is
     * replaced, and given back if it cannot be.
     */
    private void applyInHut() {
        final IItemHandler racks = racks();
        final IItemHandler pack = worker.getInventoryCitizen();
        if (racks == null || gearSlot == null || bookSlot == null) {
            return;
        }
        final IItemHandler gearHandler = gearSlot.rack() ? racks : pack;
        final ItemStack gearNow = gearHandler.getStackInSlot(gearSlot.index());
        if (!ItemStack.matches(gearNow, gearExpected)) {
            return; // something moved while the worker was busy: choose again
        }
        final Taken taken = take(racks, pack, gearNow, null);
        if (taken == null) {
            return;
        }
        final ItemStack gearBefore = gearNow.copy();
        if (!replace(gearHandler, gearSlot.index(), taken.result.gear())) {
            taken.giveBack(racks, pack);
            Runesmith.LOGGER.warn("[Runesmith] could not put the enchanted {} back into its {} slot; book and lapis returned hut={}",
                    name(gearBefore), gearSlot.where(), building.getPosition().toShortString());
            return;
        }
        final ItemStack readBack = gearHandler.getStackInSlot(gearSlot.index());
        if (!ItemStack.matches(readBack, taken.result.gear())) {
            Runesmith.LOGGER.error("[Runesmith] read-back mismatch after enchanting {}: {} slot holds {} hut={}",
                    name(gearBefore), gearSlot.where(), describe(readBack), building.getPosition().toShortString());
        }
        done(taken, gearBefore, readBack, gearSlot.rack() ? "rack" : "warehouse");
    }

    // ---------------------------------------------------------------- the warehouses

    private List<IWareHouse> warehousesNearestFirst() {
        final List<IWareHouse> out = new ArrayList<>(building.getColony().getServerBuildingManager().getWareHouses());
        out.sort(Comparator.comparingDouble(w -> w.getPosition().distSqr(building.getPosition())));
        return out;
    }

    /**
     * Chooses one warehouse piece a book can improve; true if one was chosen. Notes whether there is
     * gear at all. Only the warehouse's racks are looked through: the Runesmith walks up to the rack,
     * as a courier does (the hut block itself can stand where nobody reaches it).
     */
    private boolean planLoan(final List<Slot> books, final IItemHandler racks, final IItemHandler pack, final RunesmithPolicy policy,
            final int lapisHeld, final int[] lapisShort) {
        warehouseHasGear = false;
        warehouseGearSeen.clear();
        for (final IWareHouse warehouse : warehousesNearestFirst()) {
            for (final net.minecraft.core.BlockPos rackPos : warehouse.getContainers()) {
                final IItemHandler h = rackInventory(rackPos);
                if (h == null) {
                    continue;
                }
                for (int i = 0; i < h.getSlots(); i++) {
                    final ItemStack piece = h.getStackInSlot(i);
                    if (!BuildingRunesmith.isGear(piece)) {
                        continue;
                    }
                    warehouseHasGear = true;
                    if (warehouseGearSeen.size() < 64) {
                        warehouseGearSeen.add(piece.copy());
                    }
                    for (final Slot b : books) {
                        final ItemStack bookStack = stack(b, racks, pack);
                        final EnchantApplier.Result result = EnchantApplier.apply(piece, bookStack, policy);
                        if (!result.ok()) {
                            continue;
                        }
                        if (result.lapis() > lapisHeld) {
                            lapisShort[0] = Math.max(lapisShort[0], result.lapis());
                            continue;
                        }
                        loanPlan = new LoanPlan(warehouse.getPosition(), rackPos.immutable(), i, piece.copy(), b, bookStack.copy());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The inventory of the rack at {@code pos}, or null when it is not loaded or no longer a rack. */
    private @Nullable IItemHandler rackInventory(final net.minecraft.core.BlockPos pos) {
        if (!WorldUtil.isBlockLoaded(world, pos)) {
            return null;
        }
        return world.getBlockEntity(pos) instanceof AbstractTileEntityRack rack ? rack.getInventory() : null;
    }

    /** Walks to the warehouse and takes the chosen piece into the pack, writing the loan into the job first. */
    private IAIState fetchLoan() {
        final LoanPlan plan = loanPlan;
        final IBuilding warehouse = plan == null ? null : building.getColony().getServerBuildingManager().getBuilding(plan.warehouse());
        if (warehouse == null || ++tripCalls > MAX_TRIP_CALLS) {
            loanPlan = null;
            return AIWorkerState.START_WORKING;
        }
        if (!walkToSafePos(plan.rack())) {
            return getState();
        }
        loanPlan = null;
        final IItemHandler h = rackInventory(plan.rack());
        final IItemHandler pack = worker.getInventoryCitizen();
        if (h == null || plan.slot() >= h.getSlots() || !ItemStack.matches(h.getStackInSlot(plan.slot()), plan.piece())) {
            return AIWorkerState.START_WORKING; // somebody took it first
        }
        int free = -1;
        for (int i = 0; i < pack.getSlots() && free < 0; i++) {
            if (pack.getStackInSlot(i).isEmpty()) {
                free = i;
            }
        }
        if (free < 0) {
            return AIWorkerState.START_WORKING;
        }
        final ItemStack piece = h.extractItem(plan.slot(), 1, false);
        if (piece.isEmpty()) {
            return AIWorkerState.START_WORKING;
        }
        final Loan loan = new Loan(plan.warehouse(), plan.rack(), plan.slot(), name(piece));
        job.addLoan(loan); // written before the piece moves: a crash in between still finds it
        final ItemStack rest = pack.insertItem(free, piece, false);
        if (!rest.isEmpty()) {
            h.insertItem(plan.slot(), rest, false);
            job.removeLoan(loan);
            return AIWorkerState.START_WORKING;
        }
        Runesmith.LOGGER.info("[Runesmith] borrowed {} from the warehouse at {} hut={}", describe(piece), plan.warehouse().toShortString(),
                building.getPosition().toShortString());
        gearSlot = new Slot(false, free);
        gearExpected = piece.copy();
        bookSlot = plan.book();
        bookExpected = plan.bookStack();
        return State.WALK_TO_WORK;
    }

    /** Takes a borrowed piece back to its rack: into its own slot if that is free, else anywhere in the rack, else anywhere in the warehouse. */
    private IAIState returnLoan() {
        if (job.loans().isEmpty()) {
            return AIWorkerState.START_WORKING;
        }
        final Loan loan = job.loans().get(0);
        final IItemHandler racks = racks();
        final IItemHandler pack = worker.getInventoryCitizen();
        final Slot where = findLoanPiece(loan, racks, pack);
        if (where == null) {
            job.removeLoan(loan);
            Runesmith.LOGGER.warn("[Runesmith] the {} borrowed from the warehouse at {} is no longer with the Runesmith hut={}", loan.item(),
                    loan.warehouse().toShortString(), building.getPosition().toShortString());
            return AIWorkerState.START_WORKING;
        }
        final IBuilding warehouse = building.getColony().getServerBuildingManager().getBuilding(loan.warehouse());
        final IItemHandler h = warehouse == null ? null : warehouse.getItemHandlerCap((Direction) null);
        if (h == null || ++tripCalls > MAX_TRIP_CALLS) {
            // the warehouse is gone or out of reach: the piece stays in the Runesmith's racks, nothing is lost
            if (!where.rack() && racks != null) {
                final ItemStack piece = pack.extractItem(where.index(), 1, false);
                giveBack(racks, -1, piece, racks, pack);
            }
            job.removeLoan(loan);
            Runesmith.LOGGER.warn("[Runesmith] could not take the borrowed {} back to the warehouse at {}; it stays in the Runesmith's hut hut={}",
                    loan.item(), loan.warehouse().toShortString(), building.getPosition().toShortString());
            return AIWorkerState.START_WORKING;
        }
        if (!walkToSafePos(loan.rack())) {
            return getState();
        }
        final IItemHandler from = where.rack() ? racks : pack;
        final ItemStack piece = from.extractItem(where.index(), 1, false);
        if (piece.isEmpty()) {
            return AIWorkerState.START_WORKING;
        }
        final IItemHandler rack = rackInventory(loan.rack());
        ItemStack rest = piece;
        if (rack != null) {
            rest = loan.slot() < rack.getSlots() && rack.getStackInSlot(loan.slot()).isEmpty() ? rack.insertItem(loan.slot(), rest, false) : rest;
            if (!rest.isEmpty()) {
                rest = InventoryUtils.addItemStackToItemHandlerWithResult(rack, rest);
            }
        }
        if (!rest.isEmpty()) {
            rest = InventoryUtils.addItemStackToItemHandlerWithResult(h, rest);
        }
        if (!rest.isEmpty()) {
            from.insertItem(where.index(), rest, false); // the warehouse is full: try again later
            return idle();
        }
        job.removeLoan(loan);
        Runesmith.LOGGER.info("[Runesmith] returned {} to the warehouse at {} hut={}", describe(piece), loan.warehouse().toShortString(),
                building.getPosition().toShortString());
        return AIWorkerState.START_WORKING;
    }

    /** The borrowed piece: in the pack, or in the hut's racks if a full pack was emptied there. */
    private static @Nullable Slot findLoanPiece(final Loan loan, @Nullable final IItemHandler racks, final IItemHandler pack) {
        for (int i = 0; i < pack.getSlots(); i++) {
            if (name(pack.getStackInSlot(i)).equals(loan.item())) {
                return new Slot(false, i);
            }
        }
        if (racks != null) {
            for (int i = 0; i < racks.getSlots(); i++) {
                if (name(racks.getStackInSlot(i)).equals(loan.item())) {
                    return new Slot(true, i);
                }
            }
        }
        return null;
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
            endVisit(c, false);
            return AIWorkerState.START_WORKING;
        }
        if (++visitCalls > MAX_VISIT_CALLS) {
            endVisit(c, false);
            return AIWorkerState.START_WORKING;
        }
        if (worker.distanceTo(e) <= REACH || walkToSafePos(e.blockPosition())) {
            if (progress == 0) {
                world.playSound(null, worker.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.NEUTRAL, 0.6F, 1.2F);
            }
            return State.CHANNEL; // a channel cut short by the colonist walking off goes on where it was
        }
        return getState();
    }

    private IAIState channel() {
        final ICitizenData c = visited();
        final AbstractEntityCitizen e = alive(c);
        if (e == null || bookSlot == null) {
            endVisit(c, false);
            return AIWorkerState.START_WORKING;
        }
        if (worker.distanceTo(e) > CHANNEL_RANGE) {
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
        endVisit(c, applyToCitizen(c));
        return AIWorkerState.START_WORKING;
    }

    /**
     * The one step that changes a colonist's piece, checked again first, read back from inventory and
     * entity. Returns whether the visit got through: false when the piece is no longer shown as it was
     * (put away, swapped) or the book or lapis is gone, so the visit is tried again later.
     */
    private boolean applyToCitizen(final ICitizenData c) {
        final IItemHandler racks = racks();
        final IItemHandler pack = worker.getInventoryCitizen();
        if (racks == null) {
            return false;
        }
        final ItemStack pieceNow = Citizens.piece(c, visitPlace).copy();
        // the same piece, worn a little more since is still the same piece
        if (!sameButWear(pieceNow, gearExpected) || !Citizens.shows(c, visitPlace, pieceNow)) {
            return false;
        }
        final Taken taken = take(racks, pack, pieceNow, c);
        if (taken == null) {
            return false;
        }
        if (!Citizens.replace(c, visitPlace, pieceNow, taken.result.gear())) {
            if (Citizens.shows(c, visitPlace, pieceNow)) {
                taken.giveBack(racks, pack);
                Runesmith.LOGGER.warn("[Runesmith] could not give {} the enchanted {}; book and lapis returned hut={}",
                        c.getName(), name(pieceNow), building.getPosition().toShortString());
                return false;
            }
            Runesmith.LOGGER.error("[Runesmith] read-back mismatch after enchanting {}'s {}: inventory {} entity {} hut={}", c.getName(),
                    name(pieceNow), describe(Citizens.piece(c, visitPlace)),
                    c.getEntity().map(e -> describe(e.getItemBySlot(visitPlace.slot))).orElse("gone"), building.getPosition().toShortString());
        }
        done(taken, pieceNow, Citizens.piece(c, visitPlace), "citizen " + c.getName());
        return true;
    }

    /**
     * Ends a visit. One that got through is not repeated the same day unless the piece changes; one
     * that did not (the colonist gone or out of reach) is tried again after {@value #RETRY_TICKS} ticks.
     */
    private void endVisit(@Nullable final ICitizenData c, final boolean gotThrough) {
        if (c != null) {
            visited.put(c.getId() + ":" + visitPlace, new long[] {day(), pieceHash(Citizens.piece(c, visitPlace)),
                    gotThrough ? 0L : world.getGameTime() + RETRY_TICKS});
        }
        clearPair();
    }

    private boolean visitedToday(final ICitizenData c, final Citizens.Place p, final ItemStack piece, final long day) {
        final long[] last = visited.get(c.getId() + ":" + p);
        if (last == null) {
            return false;
        }
        if (last[2] != 0L) {
            return world.getGameTime() < last[2];
        }
        return last[0] == day && last[1] == pieceHash(piece);
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
        if ("rack".equals(where)) {
            building.markEnchanted(after, world.getGameTime());
        }
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

    /**
     * A rejected pair is logged once. The reason depends only on the piece, the book and the policy,
     * so the same pair is not logged again until a setting or the building's level changes. (Forgetting
     * on every change of the racks' contents logged the whole stock again after each applied book:
     * 25,093 lines in the first fuzz run.)
     */
    private void report(final ItemStack gear, final ItemStack book, final EnchantApplier.Reason reason) {
        final String key = BuiltInRegistries.ITEM.getKey(gear.getItem()) + "#" + gear.getComponents().hashCode() + "|"
                + book.getComponents().hashCode() + "|" + reason;
        if (reported.size() >= REPORTED_MAX) {
            reported.clear();
        }
        if (reported.add(key)) {
            Runesmith.LOGGER.info("[Runesmith] skipped {} + {}: {}", describe(gear), describe(book), reason);
        }
    }

    private void forgetReportsIfPolicyChanged(final RunesmithPolicy policy) {
        if (!policy.equals(reportedFor)) {
            reportedFor = policy;
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
