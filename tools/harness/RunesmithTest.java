package me.lovkar.runesmithtest;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.PlaceStructureOperation;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.ChangeStorage;
import com.ldtteam.structurize.util.ITickedWorldOperation;
import com.minecolonies.api.blocks.AbstractColonyBlock;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.ITickingStateAI;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.buildings.modules.EnchanterStationsModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Headless scenario runner for Runesmith.
 *
 * The scenario comes from {@code runesmith-test.txt} in the server's working directory, as words:
 * {@code scenario=<name>} plus optional {@code rate=<ticks per second>}. The harness builds a colony
 * on the flat test world, runs the scenario's steps from the server tick, logs
 * {@code [runesmithtest] STEP / CHECK / RESULT} lines and stops the server itself, so the driver
 * never needs the console.
 */
@Mod("runesmithtest")
public final class RunesmithTest {
    private static final Logger LOG = LoggerFactory.getLogger("runesmithtest");
    private static final String TAG = "[runesmithtest] ";

    private static final Predicate<ItemStack> ENCHANTED_BOOK = s -> s.is(Items.ENCHANTED_BOOK)
            && !s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty();
    private static final Predicate<ItemStack> ENCHANTED_GEAR = s -> !s.is(Items.ENCHANTED_BOOK) && s.isEnchanted();

    /** One step of a scenario. Returns true when the step is finished. */
    @FunctionalInterface
    private interface Body {
        boolean run(ServerLevel level) throws Exception;
    }

    private record Step(String name, int timeoutTicks, Body body) {}

    private final String mode = readMode();
    private final String scenario = word("scenario=", "boot");
    private final List<Step> steps = new ArrayList<>();
    private final Map<BlockPos, Blueprint> blueprints = new HashMap<>();
    private final Map<BlockPos, String[]> sources = new HashMap<>();
    private final java.util.Set<BlockPos> pasting = new java.util.HashSet<>();
    private int tick;
    private int index = -1;
    private int stepStart;
    private int failures;
    private boolean finished;

    private IColony colony;
    private ServerPlayer owner;
    private java.util.Set<ServerPlayer> subscribers;
    private boolean subscriberLogged;
    private BlockPos center;
    private IBuilding enchanter;
    private IBuilding builder;
    private ICitizenData enchanterWorker;
    private ICitizenData builderWorker;
    private int manaBefore;
    private int xpChecks;

    public RunesmithTest(final IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onLeave);
        switch (scenario) {
            case "boot" -> boot();
            case "drain" -> drain();
            default -> steps.add(new Step("unknown scenario '" + scenario + "'", 1, l -> {
                check("scenario known", false, scenario);
                return true;
            }));
        }
        LOG.info(TAG + "scenario {} with {} steps, mode '{}'", scenario, steps.size(), mode);
    }

    // ---------------------------------------------------------------- scenarios

    /** Phase 0 boot: a colony, an Enchanter from MineColonies' own pack, an Ancient Tome, a book. */
    private void boot() {
        world();
        steps.add(new Step("paste the Enchanter (Medieval Oak, level 1)", 20,
                l -> paste(l, "Medieval Oak", "mystic/enchanter1.blueprint", enchanterPos())));
        steps.add(new Step("Enchanter pasted", 6000, l -> pasted(l, enchanterPos())));
        steps.add(new Step("register the Enchanter", 200, l -> (enchanter = register(l, enchanterPos(), "enchanter")) != null));
        steps.add(new Step("hire an Enchanter", 200, l -> {
            enchanterWorker = hire(l, enchanter);
            return jobIs(enchanterWorker, "JobEnchanter");
        }));
        steps.add(new Step("skills and an Ancient Tome", 100, l -> {
            setSkill(enchanterWorker, Skill.Mana, 20);
            setSkill(enchanterWorker, Skill.Knowledge, 20);
            manaBefore = skill(enchanterWorker, Skill.Mana);
            final boolean ok = InventoryUtils.addItemStackToProvider(enchanter, new ItemStack(item("minecolonies:ancienttome")));
            check("ancient tome stocked", ok, "Mana " + manaBefore + ", Knowledge " + skill(enchanterWorker, Skill.Knowledge));
            return true;
        }));
        steps.add(new Step("an enchanted book appears", 12000, l -> {
            if (tick % 40 != 0) {
                return false;
            }
            final int inHut = countBuilding(enchanter, ENCHANTED_BOOK);
            final int inPack = countWorker(enchanterWorker, ENCHANTED_BOOK);
            if (tick % 400 == 0 || tick - stepStart < 1200 && tick % 120 == 0) {
                LOG.info(TAG + "waiting: books hut={} pack={}, tomes hut={} pack={}, AI {}, Mana {}, {}", inHut, inPack,
                        countBuilding(enchanter, s -> s.is(item("minecolonies:ancienttome"))),
                        countWorker(enchanterWorker, s -> s.is(item("minecolonies:ancienttome"))),
                        aiState(enchanterWorker), skill(enchanterWorker, Skill.Mana), who(enchanterWorker));
            }
            if (inHut + inPack == 0) {
                return false;
            }
            final ItemStack book = first(enchanter, enchanterWorker, ENCHANTED_BOOK);
            check("enchanted book crafted", true, describeBook(book) + " (hut " + inHut + ", pack " + inPack + ")");
            LOG.info(TAG + "OBSERVE Mana before={} after={} book level {} (a working decrement would lower it by that level)",
                    manaBefore, skill(enchanterWorker, Skill.Mana), maxLevel(book));
            return true;
        }));
    }

    /**
     * Phase 0 Q3: the Enchanter's drain visit. A builder's hut is his only station; the builder carries
     * iron swords, the Enchanter iron pickaxes. Whose gear ends up enchanted, and what Mana does.
     */
    private void drain() {
        world();
        steps.add(new Step("paste the Enchanter and a builder's hut", 20, l -> paste(l, "Medieval Oak", "mystic/enchanter1.blueprint", enchanterPos())
                && paste(l, "Medieval Oak", "fundamentals/builder1.blueprint", builderPos())));
        steps.add(new Step("both huts pasted", 8000, l -> pasted(l, enchanterPos()) && pasted(l, builderPos())));
        steps.add(new Step("register both", 200, l -> (enchanter = register(l, enchanterPos(), "enchanter")) != null
                && (builder = register(l, builderPos(), "builder")) != null));
        steps.add(new Step("hire both", 200, l -> {
            enchanterWorker = hire(l, enchanter);
            builderWorker = hire(l, builder);
            return jobIs(enchanterWorker, "JobEnchanter") & jobIs(builderWorker, "JobBuilder");
        }));
        steps.add(new Step("station, books, gear, skills", 100, l -> {
            setSkill(enchanterWorker, Skill.Mana, 5);
            setSkill(enchanterWorker, Skill.Knowledge, 50);
            manaBefore = skill(enchanterWorker, Skill.Mana);
            final EnchanterStationsModule stations = enchanter.getFirstModuleOccurance(EnchanterStationsModule.class);
            stations.addWorker(builder.getPosition());
            int books = 0;
            for (int i = 0; i < 4; i++) {
                books += InventoryUtils.addItemStackToProvider(enchanter, new ItemStack(Items.BOOK)) ? 1 : 0;
            }
            int swords = 0;
            int picks = 0;
            for (int i = 0; i < 20; i++) {
                swords += give(builderWorker, new ItemStack(Items.IRON_SWORD)) ? 1 : 0;
            }
            for (int i = 0; i < 10; i++) {
                picks += give(enchanterWorker, new ItemStack(Items.IRON_PICKAXE)) ? 1 : 0;
            }
            check("drain set up", books == 4 && swords > 0 && picks > 0, "stations " + stations.getBuildingsToGatherFrom()
                    + ", books " + books + ", builder swords " + swords + ", enchanter pickaxes " + picks
                    + ", Mana " + manaBefore + " (threshold " + 10 * enchanter.getBuildingLevel() + "), Knowledge 50");
            return true;
        }));
        steps.add(new Step("a drain visit happens (Mana rises)", 14000, l -> {
            if (tick % 40 != 0) {
                return false;
            }
            final int mana = skill(enchanterWorker, Skill.Mana);
            if (tick % 400 == 0) {
                LOG.info(TAG + "waiting: Mana {}, AI {}, books hut={} pack={}, {}", mana, aiState(enchanterWorker),
                        countBuilding(enchanter, s -> s.is(Items.BOOK)), countWorker(enchanterWorker, s -> s.is(Items.BOOK)),
                        who(enchanterWorker));
            }
            if (mana <= manaBefore && ++xpChecks < 350) {
                return false;
            }
            final int swordsEnchanted = countWorker(builderWorker, ENCHANTED_GEAR) + countBuilding(builder, ENCHANTED_GEAR);
            final int picksEnchanted = countWorker(enchanterWorker, ENCHANTED_GEAR) + countBuilding(enchanter, ENCHANTED_GEAR);
            LOG.info(TAG + "OBSERVE drain: Mana before={} after={}; enchanted gear: builder side {} (of 20 swords), enchanter side {} (of 10 pickaxes); books left hut={} pack={}",
                    manaBefore, mana, swordsEnchanted, picksEnchanted, countBuilding(enchanter, s -> s.is(Items.BOOK)),
                    countWorker(enchanterWorker, s -> s.is(Items.BOOK)));
            check("drain visit observed", mana > manaBefore, "Mana " + manaBefore + " -> " + mana);
            return true;
        }));
    }

    // ---------------------------------------------------------------- building blocks

    private BlockPos enchanterPos() {
        return center.offset(28, 0, 0);
    }

    private BlockPos builderPos() {
        return center.offset(-28, 0, 0);
    }

    private void world() {
        steps.add(new Step("world and colony", 40, this::makeWorld));
        steps.add(new Step("colony active", 1200, l -> {
            keepColonyActive();
            return colony.isActive();
        }));
        steps.add(new Step("paste the town hall (Medieval Oak, level 1)", 20,
                l -> paste(l, "Medieval Oak", "fundamentals/townhall1.blueprint", center)));
        steps.add(new Step("town hall pasted", 6000, l -> pasted(l, center)));
        steps.add(new Step("register the town hall", 200, l -> register(l, center, "town hall") != null));
    }

    private boolean makeWorld(final ServerLevel level) {
        final MinecraftServer server = level.getServer();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(1000);
        final float rate = Float.parseFloat(word("rate=", "20"));
        if (rate != 20F) {
            server.tickRateManager().setTickRate(rate);
        }
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
        center = new BlockPos(0, y, 0);
        for (int cx = -6; cx <= 6; cx++) {
            for (int cz = -6; cz <= 6; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        final ServerPlayer owner = FakePlayerFactory.getMinecraft(level);
        owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Runesmith Test", "Medieval Oak");
        this.owner = owner;
        check("colony created", colony != null, "colony " + colony.getID() + " at " + center + ", tick rate " + rate);
        return true;
    }

    /**
     * A colony with no close subscriber is INACTIVE and unloads its citizens. MineColonies 1.1.1403's
     * addCloseSubscriber refuses fake players, so the harness puts its fake owner into the set itself;
     * updateClosePlayers keeps it as long as it stands, alive, in a loaded chunk the colony owns.
     */
    @SuppressWarnings("unchecked")
    private void keepColonyActive() {
        if (colony == null || owner == null) {
            return;
        }
        try {
            if (subscribers == null) {
                final java.lang.reflect.Field f = colony.getPackageManager().getClass().getDeclaredField("closeSubscribers");
                f.setAccessible(true);
                subscribers = (java.util.Set<ServerPlayer>) f.get(colony.getPackageManager());
            }
            if (!subscribers.contains(owner)) {
                subscribers.add(owner);
                if (!subscriberLogged) {
                    subscriberLogged = true;
                    LOG.info(TAG + "fake owner added to the colony's close subscribers; colony state {}", colony.isActive() ? "active" : "inactive");
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            if (!subscriberLogged) {
                subscriberLogged = true;
                check("colony kept active", false, e.toString());
            }
        }
    }

    /**
     * Pastes a blueprint with its anchor (the hut block) at pos. Not "fancy": a fancy paste makes the
     * hut register itself mid-paste with the path MineColonies derives from the jar (missing the pack
     * root and the extension), which logs a blueprint load error. The harness registers the hut itself
     * afterwards, with the pack and path it pasted from (see register).
     */
    private boolean paste(final ServerLevel level, final String pack, final String path, final BlockPos pos) {
        final Blueprint bp = StructurePacks.getBlueprint(pack, path, level.registryAccess());
        if (bp == null) {
            check("blueprint " + pack + "/" + path, false, "not found");
            return true;
        }
        bp.setRotationMirror(RotationMirror.NONE, level);
        blueprints.put(pos, bp);
        sources.put(pos, new String[] {pack, path});
        final CreativeStructureHandler handler = new CreativeStructureHandler(level, pos, bp, RotationMirror.NONE, false);
        final PlaceStructureOperation op = new PlaceStructureOperation(new StructurePlacer(handler), FakePlayerFactory.getMinecraft(level));
        pasting.add(pos);
        // wrapped to learn when the paste is complete: the placer clears entities inside the
        // structure while it works, so nobody may be spawned there before it is done
        Manager.addToQueue(new ITickedWorldOperation() {
            @Override
            public boolean apply(final ServerLevel l) {
                final boolean done = op.apply(l);
                if (done) {
                    pasting.remove(pos);
                    LOG.info(TAG + "pasted {}/{} at {} (tick {})", pack, path, pos, tick);
                }
                return done;
            }

            @Override
            public ChangeStorage getChangeStorage() {
                return op.getChangeStorage();
            }
        });
        LOG.info(TAG + "placing {}/{} at {}", pack, path, pos);
        return true;
    }

    /** The paste at pos is complete and its hut block entity stands there. */
    private boolean pasted(final ServerLevel level, final BlockPos pos) {
        return !pasting.contains(pos) && level.getBlockEntity(pos) instanceof AbstractTileEntityColonyBuilding;
    }

    private IBuilding register(final ServerLevel level, final BlockPos pos, final String what) {
        if (!(level.getBlockEntity(pos) instanceof AbstractTileEntityColonyBuilding hut)) {
            check("register " + what, false, "no hut block entity at " + pos);
            return null;
        }
        // what AbstractColonyBlock.setPlacedBy would have done on a fancy paste, with the right path
        final String[] source = sources.get(pos);
        if (source != null && hut instanceof IBlueprintDataProviderBE data) {
            data.setPackName(source[0]);
            data.setBlueprintPath(source[1]);
        }
        if (hut instanceof TileEntityColonyBuilding te && level.getBlockState(pos).getBlock() instanceof AbstractColonyBlock<?> block) {
            te.registryName = block.getBuildingEntry().getRegistryName();
        }
        IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
        if (building == null) {
            building = colony.getServerBuildingManager().addNewBuilding(hut, level);
        }
        if (building == null) {
            check("register " + what, false, "addNewBuilding returned null");
            return null;
        }
        if (building.getBuildingLevel() < 1) {
            building.upgradeBuildingLevelToSchematicData();
        }
        if (building.getBuildingLevel() < 1) {
            building.setBuildingLevel(1);
            building.onUpgradeComplete(blueprints.get(pos), 1);
        }
        check("register " + what, building.getBuildingLevel() >= 1, building.getClass().getSimpleName() + " level "
                + building.getBuildingLevel() + " schematic " + building.getSchematicName() + " at " + pos);
        return building;
    }

    /** Hires a new citizen into the building; the citizen spawns at the town hall and walks over. */
    private ICitizenData hire(final ServerLevel level, final IBuilding building) {
        final WorkerBuildingModule module = building.getFirstModuleOccurance(WorkerBuildingModule.class);
        if (module != null && !module.getAssignedCitizen().isEmpty()) {
            final ICitizenData already = module.getAssignedCitizen().get(0);
            LOG.info(TAG + "{} already works at {} (hired by the colony): {}", already.getName(), building.getSchematicName(), who(already));
            return already;
        }
        final ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCitizen(data, level, spawnPoint(level));
        final boolean ok = module != null && module.assignCitizen(data);
        LOG.info(TAG + "{} hired into {}: {} ({})", data.getName(), building.getSchematicName(), ok, who(data));
        return data;
    }

    /** Open ground between the town hall and the huts, on top of whatever stands there. */
    private BlockPos spawnPoint(final ServerLevel level) {
        final int x = center.getX() + 14;
        final int z = center.getZ() + 4;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
    }

    private String who(final ICitizenData data) {
        if (data == null) {
            return "nobody";
        }
        final Optional<AbstractEntityCitizen> e = data.getEntity();
        if (e.isEmpty()) {
            return data.getName() + " without an entity, colony " + (colony.isActive() ? "active" : "inactive");
        }
        return data.getName() + " at " + e.get().blockPosition().toShortString() + (e.get().isAlive() ? " alive" : " dead")
                + " hp " + (int) e.get().getHealth() + ", colony " + (colony.isActive() ? "active" : "inactive");
    }

    private boolean jobIs(final ICitizenData data, final String simpleName) {
        final String job = data == null || data.getJob() == null ? "none" : data.getJob().getClass().getSimpleName();
        check("job " + simpleName, job.equals(simpleName), (data == null ? "?" : data.getName()) + " -> " + job);
        return true;
    }

    private static void setSkill(final ICitizenData data, final Skill skill, final int target) {
        final int now = data.getCitizenSkillHandler().getLevel(skill);
        data.getCitizenSkillHandler().incrementLevel(skill, target - now);
    }

    private static int skill(final ICitizenData data, final Skill skill) {
        return data.getCitizenSkillHandler().getLevel(skill);
    }

    private static boolean give(final ICitizenData data, final ItemStack stack) {
        final Optional<AbstractEntityCitizen> entity = data.getEntity();
        return entity.isPresent() && InventoryUtils.addItemStackToItemHandler(entity.get().getInventoryCitizen(), stack);
    }

    /** Items in the building's racks and hut block (not the workers' packs: getHandlers() would add those). */
    private static int countBuilding(final IBuilding building, final Predicate<ItemStack> what) {
        final IItemHandler racks = building.getItemHandlerCap((net.minecraft.core.Direction) null);
        return racks == null ? 0 : InventoryUtils.getItemCountInItemHandler(racks, what);
    }

    private static int countWorker(final ICitizenData data, final Predicate<ItemStack> what) {
        return data == null ? 0 : InventoryUtils.getItemCountInItemHandler(data.getInventory(), what);
    }

    private static ItemStack first(final IBuilding building, final ICitizenData data, final Predicate<ItemStack> what) {
        final List<IItemHandler> handlers = new ArrayList<>();
        handlers.add(building.getItemHandlerCap((net.minecraft.core.Direction) null));
        handlers.add(data.getInventory());
        for (final IItemHandler handler : handlers) {
            for (int i = 0; handler != null && i < handler.getSlots(); i++) {
                if (what.test(handler.getStackInSlot(i))) {
                    return handler.getStackInSlot(i);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static String aiState(final ICitizenData data) {
        if (data == null || data.getJob() == null) {
            return "-";
        }
        final Object ai = data.getJob().getWorkerAI();
        return ai instanceof ITickingStateAI t ? String.valueOf(t.getState()) : String.valueOf(ai);
    }

    private static String describeBook(final ItemStack book) {
        final StringBuilder sb = new StringBuilder();
        for (final var e : book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
            final Holder<Enchantment> h = e.getKey();
            sb.append(sb.isEmpty() ? "" : ", ").append(h.getRegisteredName()).append(' ').append(e.getIntValue());
        }
        return "enchanted book [" + sb + "]";
    }

    private static int maxLevel(final ItemStack book) {
        int max = 0;
        for (final var e : book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
            max = Math.max(max, e.getIntValue());
        }
        return max;
    }

    private static Item item(final String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    /** Diagnostics: a citizen entity leaving the level is logged once with its reason and the call path. */
    private void onLeave(final net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractEntityCitizen citizen)) {
            return;
        }
        final Throwable where = new Throwable("call path");
        final StringBuilder sb = new StringBuilder();
        for (final StackTraceElement e : where.getStackTrace()) {
            if (sb.length() > 1400) {
                break;
            }
            sb.append(" < ").append(e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
        }
        LOG.info(TAG + "DIAG citizen {} left the level at {} (reason {}, tick {}){}", citizen.getName().getString(),
                citizen.blockPosition().toShortString(), citizen.getRemovalReason(), tick, sb);
    }

    // ---------------------------------------------------------------- runner

    private void onTick(final ServerTickEvent.Post event) {
        if (finished) {
            return;
        }
        tick++;
        if (tick < 40) {
            return;
        }
        final ServerLevel level = event.getServer().overworld();
        try {
            if (tick % 20 == 0) {
                keepColonyActive();
            }
            if (index < 0) {
                index = 0;
                begin();
            }
            final Step step = steps.get(index);
            if (step.body().run(level)) {
                index++;
                if (index >= steps.size() || failures > 0 && mode.contains("stopfirst")) {
                    finish(level);
                    return;
                }
                begin();
            } else if (tick - stepStart > step.timeoutTicks()) {
                check(step.name(), false, "timed out after " + step.timeoutTicks() + " ticks");
                finish(level);
            }
        } catch (final Throwable t) {
            LOG.error(TAG + "EXCEPTION in step '" + (index >= 0 && index < steps.size() ? steps.get(index).name() : "?") + "'", t);
            check("no exception", false, t.toString());
            finish(level);
        }
    }

    private void begin() {
        stepStart = tick;
        LOG.info(TAG + "STEP {}/{} {} (tick {})", index + 1, steps.size(), steps.get(index).name(), tick);
    }

    private void check(final String name, final boolean ok, final String detail) {
        if (!ok) {
            failures++;
        }
        LOG.info(TAG + "CHECK {}: {} {}", name, ok ? "OK" : "FAIL", detail);
    }

    private void finish(final ServerLevel level) {
        finished = true;
        LOG.info(TAG + "RESULT {}: {}", scenario, failures == 0 ? "PASS" : "FAIL");
        final MinecraftServer server = level.getServer();
        server.execute(() -> server.halt(false));
    }

    private String word(final String key, final String fallback) {
        for (final String w : mode.split("\\s+")) {
            if (w.startsWith(key)) {
                return w.substring(key.length());
            }
        }
        return fallback;
    }

    private static String readMode() {
        try {
            return Files.readString(Path.of("runesmith-test.txt")).trim().toLowerCase();
        } catch (final Exception e) {
            return "";
        }
    }
}
