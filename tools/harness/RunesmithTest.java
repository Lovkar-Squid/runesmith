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
import net.minecraft.world.item.enchantment.Enchantments;
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
import me.lovkar.runesmith.colony.RunesmithSettings;
import me.lovkar.runesmith.colony.BuildingRunesmith;
import net.minecraft.resources.ResourceKey;
import java.util.function.Function;

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
            case "anvil" -> anvil();
            case "rules" -> rules();
            case "a" -> scenarioA();
            case "b" -> scenarioB();
            case "c" -> scenarioC();
            case "d" -> scenarioD();
            case "e" -> scenarioE();
            case "f" -> scenarioF();
            case "g" -> scenarioG();
            case "n" -> scenarioN();
            case "j1" -> scenarioJ1();
            case "j2" -> scenarioJ2();
            case "j3" -> scenarioJ3();
            case "j4" -> scenarioJ4();
            case "j5" -> scenarioJ5();
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

    /** Phase 1: the vanilla anvil judges EnchantApplier on every gear item and every single-enchantment book. */
    private void anvil() {
        steps.add(new Step("anvil oracle", 100, l -> {
            final AnvilOracle.Summary s = new AnvilOracle(l, msg -> LOG.info(TAG + msg)).run();
            LOG.info(TAG + "anvil cases={} match={} divergent={} skipped={} deliberate={}",
                    s.cases(), s.match(), s.divergent(), s.skipped(), s.deliberate());
            check("anvil oracle", s.divergent() == 0 && s.cases() >= 5000, "cases " + s.cases() + ", divergent " + s.divergent());
            return true;
        }));
    }

    /** Phase 1: the rules that differ from the anvil on purpose, case by case. */
    private void rules() {
        steps.add(new Step("rules cases", 100, l -> {
            new RulesCheck(l, this::check).run();
            return true;
        }));
    }

    // ---------------------------------------------------------------- Phase 1 colony scenarios

    private IBuilding smithHut;
    private ICitizenData smith;
    private ColonyScenarios.Snapshot before;

    private BlockPos smithPos() {
        return center.offset(28, 0, 0);
    }

    /** A colony with a level-1 Runesmith and its worker; the book-level cap off unless asked for. */
    private void runesmithColony(final boolean levelCap) {
        world();
        steps.add(new Step("paste the Runesmith (level 1)", 20, l -> paste(l, "Runesmith", "runesmith/runesmith1.blueprint", smithPos())));
        steps.add(new Step("Runesmith pasted", 6000, l -> pasted(l, smithPos())));
        steps.add(new Step("register the Runesmith", 200, l -> (smithHut = register(l, smithPos(), "runesmith")) != null));
        steps.add(new Step("hire a Runesmith", 200, l -> {
            smith = hire(l, smithHut);
            return jobIs(smith, "JobRunesmith");
        }));
        steps.add(new Step("settings (level cap " + (levelCap ? "on" : "off") + ")", 20, l -> {
            ColonyScenarios.set(smithHut, RunesmithSettings.LEVEL_CAP, levelCap);
            return true;
        }));
    }

    private void stockAndSnapshot(final String what, final Function<ServerLevel, ItemStack[]> items) {
        steps.add(new Step("stock: " + what, 20, l -> {
            final boolean ok = ColonyScenarios.stock(smithHut, items.apply(l));
            before = ColonyScenarios.snapshot(smithHut, smith);
            check("stocked", ok, what + " -> " + before);
            return true;
        }));
    }

    /** Lets the worker work for a while: many chances to act, or to act wrongly. */
    private void settle(final int ticks) {
        steps.add(new Step("let the worker work " + ticks + " ticks", ticks + 100, l -> tick - stepStart >= ticks));
    }

    private void waitFor(final String what, final int timeout, final Predicate<ServerLevel> done) {
        steps.add(new Step(what, timeout, l -> tick % 20 == 0 && done.test(l)));
    }

    /** The item-conservation invariant against the snapshot taken right after stocking. */
    private void invariant(final String scenario) {
        final ColonyScenarios.Snapshot now = ColonyScenarios.snapshot(smithHut, smith);
        final List<String> applied = ColonyScenarios.modLines("applied ");
        final int lapis = ColonyScenarios.lapisLogged(applied);
        final boolean ok = now.gear() == before.gear() && before.books() - now.books() == applied.size()
                && before.lapis() - now.lapis() == lapis;
        check(scenario + " item conservation", ok, "gear " + before.gear() + "->" + now.gear() + ", books " + before.books() + "->"
                + now.books() + " with " + applied.size() + " applied line(s), lapis " + before.lapis() + "->" + now.lapis() + " with "
                + lapis + " logged");
    }

    private int books() {
        return ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isBook);
    }

    private int lapis() {
        return ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isLapis);
    }

    private boolean anyWith(final ServerLevel l, final Item item, final ResourceKey<Enchantment> key, final int lvl) {
        return ColonyScenarios.all(smithHut, smith, item).stream().anyMatch(s -> ColonyScenarios.level(l, s, key) == lvl);
    }

    /** A: a sword and a Sharpness III book: the sword gets Sharpness III, the book and 3 lapis are spent. */
    private void scenarioA() {
        runesmithColony(false);
        stockAndSnapshot("iron sword, Sharpness III book, 16 lapis", l -> new ItemStack[] {
                new ItemStack(Items.IRON_SWORD), ColonyScenarios.book(l, Enchantments.SHARPNESS, 3), new ItemStack(Items.LAPIS_LAZULI, 16)});
        waitFor("the sword gets Sharpness III", 6000, l -> anyWith(l, Items.IRON_SWORD, Enchantments.SHARPNESS, 3));
        settle(400);
        steps.add(new Step("checks", 20, l -> {
            final List<String> applied = ColonyScenarios.modLines("applied ");
            check("A sword has Sharpness III", anyWith(l, Items.IRON_SWORD, Enchantments.SHARPNESS, 3), "");
            check("A book used", books() == 0, "books left " + books());
            check("A one applied line", applied.size() == 1, String.join(" | ", applied));
            check("A 3 lapis spent", lapis() == 13, "lapis left " + lapis());
            invariant("A");
            return true;
        }));
    }

    /** B: a pickaxe and a Sharpness book: nothing changes, the book stays, one NOT_SUPPORTED line. */
    private void scenarioB() {
        runesmithColony(false);
        stockAndSnapshot("iron pickaxe, Sharpness III book, 16 lapis", l -> new ItemStack[] {
                new ItemStack(Items.IRON_PICKAXE), ColonyScenarios.book(l, Enchantments.SHARPNESS, 3), new ItemStack(Items.LAPIS_LAZULI, 16)});
        settle(1500);
        steps.add(new Step("checks", 20, l -> {
            final List<String> skipped = ColonyScenarios.modLines("skipped ");
            final long notSupported = skipped.stream().filter(x -> x.endsWith("NOT_SUPPORTED")).count();
            check("B pickaxe unchanged", ColonyScenarios.all(smithHut, smith, Items.IRON_PICKAXE).stream().noneMatch(ItemStack::isEnchanted), "");
            check("B book kept", books() == 1, "books " + books());
            check("B skipped NOT_SUPPORTED once", notSupported == 1, String.join(" | ", skipped));
            check("B nothing applied", ColonyScenarios.modLines("applied ").isEmpty(), "");
            invariant("B");
            return true;
        }));
    }

    /** C: books but no gear: the worker idles, asks for nothing and logs nothing. */
    private void scenarioC() {
        runesmithColony(false);
        stockAndSnapshot("Sharpness III book, 16 lapis, no gear", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.SHARPNESS, 3), new ItemStack(Items.LAPIS_LAZULI, 16)});
        settle(1500);
        steps.add(new Step("checks", 20, l -> {
            check("C no mod lines", ColonyScenarios.modLines("applied ").isEmpty() && ColonyScenarios.modLines("skipped ").isEmpty()
                    && ColonyScenarios.modLines("requested ").isEmpty(), "");
            check("C book kept", books() == 1, "books " + books());
            check("C worker idle", String.valueOf(aiState(smith)).matches("IDLE|START_WORKING"), "AI " + aiState(smith));
            invariant("C");
            return true;
        }));
    }

    /** D: gear but no book anywhere: one request for enchanted books, open, and the worker idles. */
    private void scenarioD() {
        runesmithColony(false);
        stockAndSnapshot("iron sword, 16 lapis, no books", l -> new ItemStack[] {
                new ItemStack(Items.IRON_SWORD), new ItemStack(Items.LAPIS_LAZULI, 16)});
        settle(1500);
        steps.add(new Step("checks", 20, l -> {
            final List<String> requested = ColonyScenarios.modLines("requested ");
            check("D one request line", requested.size() == 1 && requested.get(0).contains("enchanted book"), String.join(" | ", requested));
            check("D request for enchanted books open", ColonyScenarios.booksRequested(smithHut, smith), "");
            check("D nothing applied", ColonyScenarios.modLines("applied ").isEmpty(), "");
            check("D worker idle", String.valueOf(aiState(smith)).matches("IDLE|START_WORKING"), "AI " + aiState(smith));
            invariant("D");
            return true;
        }));
    }

    /** F: Protection on a helmet that already has Fire Protection: CONFLICT once, the book stays. */
    private void scenarioF() {
        runesmithColony(false);
        stockAndSnapshot("diamond helmet with Fire Protection I, Protection I book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.gear(l, Items.DIAMOND_HELMET, Enchantments.FIRE_PROTECTION, 1),
                ColonyScenarios.book(l, Enchantments.PROTECTION, 1), new ItemStack(Items.LAPIS_LAZULI, 16)});
        settle(1500);
        steps.add(new Step("checks", 20, l -> {
            final List<String> skipped = ColonyScenarios.modLines("skipped ");
            check("F skipped CONFLICT once", skipped.stream().filter(x -> x.endsWith("CONFLICT")).count() == 1, String.join(" | ", skipped));
            check("F book kept", books() == 1, "books " + books());
            check("F helmet unchanged", ColonyScenarios.all(smithHut, smith, Items.DIAMOND_HELMET).stream()
                    .allMatch(s -> ColonyScenarios.level(l, s, Enchantments.FIRE_PROTECTION) == 1
                            && ColonyScenarios.level(l, s, Enchantments.PROTECTION) == 0), "");
            check("F nothing applied", ColonyScenarios.modLines("applied ").isEmpty(), "");
            invariant("F");
            return true;
        }));
    }

    /** G: II + II makes III with one book; then the I-book is NO_CHANGE and stays. */
    private void scenarioG() {
        runesmithColony(false);
        stockAndSnapshot("iron sword with Sharpness II, Sharpness II book, Sharpness I book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.gear(l, Items.IRON_SWORD, Enchantments.SHARPNESS, 2), ColonyScenarios.book(l, Enchantments.SHARPNESS, 2),
                ColonyScenarios.book(l, Enchantments.SHARPNESS, 1), new ItemStack(Items.LAPIS_LAZULI, 16)});
        waitFor("the sword gets Sharpness III", 6000, l -> anyWith(l, Items.IRON_SWORD, Enchantments.SHARPNESS, 3));
        settle(800);
        steps.add(new Step("checks", 20, l -> {
            final List<String> applied = ColonyScenarios.modLines("applied ");
            final List<String> skipped = ColonyScenarios.modLines("skipped ");
            check("G one applied line", applied.size() == 1, String.join(" | ", applied));
            check("G the I-book stays", ColonyScenarios.bookWithLevel(l, smithHut, smith, Enchantments.SHARPNESS, 1) && books() == 1,
                    "books " + books());
            check("G the I-book is NO_CHANGE", skipped.stream().anyMatch(x -> x.endsWith("NO_CHANGE")), String.join(" | ", skipped));
            check("G 2 lapis spent", lapis() == 14, "lapis left " + lapis());
            invariant("G");
            return true;
        }));
    }

    /** N: a usable pair but no lapis: lapis is requested once, nothing is spent. */
    private void scenarioN() {
        runesmithColony(false);
        stockAndSnapshot("iron sword, Sharpness III book, no lapis", l -> new ItemStack[] {
                new ItemStack(Items.IRON_SWORD), ColonyScenarios.book(l, Enchantments.SHARPNESS, 3)});
        settle(1500);
        steps.add(new Step("checks", 20, l -> {
            final List<String> requested = ColonyScenarios.modLines("requested ");
            check("N one lapis request line", requested.size() == 1 && requested.get(0).contains("lapis"), String.join(" | ", requested));
            check("N lapis request open", ColonyScenarios.lapisRequested(smithHut, smith), "");
            check("N nothing applied", ColonyScenarios.modLines("applied ").isEmpty(), "");
            check("N book kept", books() == 1, "books " + books());
            invariant("N");
            return true;
        }));
    }

    private IBuilding warehouse;
    private IBuilding courierHut;
    private ICitizenData courier;

    private BlockPos warehousePos() {
        return center.offset(0, 0, 30);
    }

    private BlockPos courierPos() {
        return center.offset(-28, 0, 0);
    }

    /**
     * E: gear in the racks, the books in a warehouse with a courier: the hut asks, the courier
     * brings a book, the worker uses it. Nothing is handed over by the harness.
     */
    private void scenarioE() {
        runesmithColony(false);
        steps.add(new Step("paste a warehouse and a courier's hut", 20, l -> paste(l, "Medieval Oak", "craftsmanship/storage/warehouse1.blueprint", warehousePos())
                && paste(l, "Medieval Oak", "craftsmanship/storage/deliveryman1.blueprint", courierPos())));
        steps.add(new Step("both pasted", 8000, l -> pasted(l, warehousePos()) && pasted(l, courierPos())));
        steps.add(new Step("register both", 200, l -> (warehouse = register(l, warehousePos(), "warehouse")) != null
                && (courierHut = register(l, courierPos(), "courier hut")) != null));
        steps.add(new Step("hire a courier", 200, l -> {
            courier = hire(l, courierHut);
            final com.minecolonies.core.colony.buildings.modules.CourierAssignmentModule couriers =
                    warehouse.getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.CourierAssignmentModule.class);
            final boolean assigned = couriers != null && (couriers.hasAssignedCitizen(courier) || couriers.assignCitizen(courier));
            check("courier works for the warehouse", assigned, courier.getName());
            return jobIs(courier, "JobDeliveryman");
        }));
        steps.add(new Step("books in the warehouse", 20, l -> {
            final boolean ok = ColonyScenarios.stock(warehouse, ColonyScenarios.book(l, Enchantments.SHARPNESS, 3),
                    ColonyScenarios.book(l, Enchantments.UNBREAKING, 2));
            check("warehouse stocked", ok, "Sharpness III and Unbreaking II books, "
                    + ColonyScenarios.count(warehouse, null, BuildingRunesmith::isBook) + " book(s) in the warehouse");
            return true;
        }));
        stockAndSnapshot("iron sword and 16 lapis in the Runesmith, no books", l -> new ItemStack[] {
                new ItemStack(Items.IRON_SWORD), new ItemStack(Items.LAPIS_LAZULI, 16)});
        steps.add(new Step("the hut asks for books", 3000, l -> tick % 20 == 0 && ColonyScenarios.booksRequested(smithHut, smith)));
        steps.add(new Step("a courier brings a book and the sword is enchanted", 24000, l -> {
            if (tick % 400 == 0) {
                LOG.info(TAG + "waiting: warehouse books {}, hut books {}, courier {} AI {}, smith AI {}",
                        ColonyScenarios.count(warehouse, null, BuildingRunesmith::isBook), books(), who(courier), aiState(courier), aiState(smith));
            }
            return tick % 20 == 0 && ColonyScenarios.all(smithHut, smith, Items.IRON_SWORD).stream().anyMatch(ItemStack::isEnchanted);
        }));
        settle(400);
        steps.add(new Step("checks", 20, l -> {
            final List<String> applied = ColonyScenarios.modLines("applied ");
            final int inWarehouse = ColonyScenarios.count(warehouse, null, BuildingRunesmith::isBook);
            final int withCourier = ColonyScenarios.count(courierHut, courier, BuildingRunesmith::isBook);
            check("E sword enchanted from a delivered book", !applied.isEmpty() && inWarehouse < 2,
                    applied.size() + " applied, warehouse books " + inWarehouse + " | " + String.join(" | ", applied));
            final ColonyScenarios.Snapshot now = ColonyScenarios.snapshot(smithHut, smith);
            final int total = inWarehouse + withCourier + now.books() + applied.size();
            final boolean ok = now.gear() == before.gear() && total == 2 && before.lapis() - now.lapis() == ColonyScenarios.lapisLogged(applied);
            check("E item conservation (warehouse, courier, hut)", ok, "gear " + before.gear() + "->" + now.gear() + "; books: warehouse "
                    + inWarehouse + " + courier " + withCourier + " + hut " + now.books() + " + applied " + applied.size() + " = " + total
                    + " of 2; lapis " + before.lapis() + "->" + now.lapis());
            return true;
        }));
    }

    // ---------------------------------------------------------------- Phase 2 colonist scenarios

    private ICitizenData visitee;
    private IBuilding otherHut;

    private BlockPos otherPos() {
        return center.offset(0, 0, -30);
    }

    /** A citizen with no job, standing on open ground near the huts. */
    private void unemployed(final String what) {
        steps.add(new Step("spawn a colonist without a job (" + what + ")", 100, l -> {
            visitee = colony.getCitizenManager().createAndRegisterCivilianData();
            colony.getCitizenManager().spawnOrCreateCitizen(visitee, l, spawnPoint(l).offset(4, 0, 0));
            LOG.info(TAG + "colonist {} ({})", visitee.getName(), who(visitee));
            return visitee.getEntity().isPresent();
        }));
    }

    private void wear(final java.util.function.Function<ServerLevel, ItemStack> piece, final net.minecraft.world.entity.EquipmentSlot slot) {
        steps.add(new Step("dress the colonist (" + slot + ")", 40, l -> {
            final ItemStack s = piece.apply(l);
            visitee.getInventory().forceArmorStackToSlot(slot, s.copy());
            check("colonist wears it", ItemStack.matches(visitee.getInventory().getArmorInSlot(slot), s), who(visitee));
            return true;
        }));
    }

    private void hold(final java.util.function.Function<ServerLevel, ItemStack> piece) {
        steps.add(new Step("put a tool in the colonist's hand", 40, l -> {
            final ItemStack s = piece.apply(l);
            final com.minecolonies.api.inventory.InventoryCitizen inv = visitee.getInventory();
            int slot = -1;
            for (int i = 0; i < inv.getSlots() && slot < 0; i++) {
                if (inv.getStackInSlot(i).isEmpty()) {
                    slot = i;
                }
            }
            inv.setStackInSlot(slot, s.copy());
            inv.setHeldItem(net.minecraft.world.InteractionHand.MAIN_HAND, slot);
            check("colonist holds it", ItemStack.matches(inv.getHeldItem(net.minecraft.world.InteractionHand.MAIN_HAND), s), who(visitee));
            return true;
        }));
    }

    private ItemStack worn(final net.minecraft.world.entity.EquipmentSlot slot) {
        return visitee.getInventory().getArmorInSlot(slot);
    }

    private ItemStack held() {
        return visitee.getInventory().getHeldItem(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** The entity shows the same piece at that slot as the citizen's inventory. */
    private boolean entityAgrees(final net.minecraft.world.entity.EquipmentSlot slot, final ItemStack expected) {
        return visitee.getEntity().map(e -> ItemStack.matches(e.getItemBySlot(slot), expected)).orElse(false);
    }

    /** J1: a guard wearing a leather helmet, a Protection I book in stock: the helmet is enchanted where he stands. */
    private void scenarioJ1() {
        runesmithColony(false);
        steps.add(new Step("paste a guard tower", 20, l -> paste(l, "Medieval Oak", "military/guardtower1.blueprint", otherPos())));
        steps.add(new Step("guard tower pasted", 6000, l -> pasted(l, otherPos())));
        steps.add(new Step("register the guard tower", 200, l -> (otherHut = register(l, otherPos(), "guard tower")) != null));
        steps.add(new Step("hire a guard", 200, l -> {
            visitee = hire(l, otherHut);
            check("hired a guard", visitee.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard<?>,
                    visitee.getName() + " -> " + (visitee.getJob() == null ? "none" : visitee.getJob().getClass().getSimpleName()));
            return true;
        }));
        wear(l -> new ItemStack(Items.LEATHER_HELMET), net.minecraft.world.entity.EquipmentSlot.HEAD);
        stockAndSnapshot("Protection I book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.PROTECTION, 1), new ItemStack(Items.LAPIS_LAZULI, 16)});
        waitFor("the guard's helmet gets Protection I", 12000,
                l -> ColonyScenarios.level(l, worn(net.minecraft.world.entity.EquipmentSlot.HEAD), Enchantments.PROTECTION) == 1);
        settle(200);
        steps.add(new Step("checks", 20, l -> {
            final ItemStack helmet = worn(net.minecraft.world.entity.EquipmentSlot.HEAD);
            final List<String> applied = ColonyScenarios.modLines("applied ");
            check("J1 helmet has Protection I", helmet.is(Items.LEATHER_HELMET)
                    && ColonyScenarios.level(l, helmet, Enchantments.PROTECTION) == 1, "");
            check("J1 inventory and entity agree", entityAgrees(net.minecraft.world.entity.EquipmentSlot.HEAD, helmet), who(visitee));
            check("J1 book used", books() == 0, "books " + books());
            check("J1 one applied line, at the citizen", applied.size() == 1 && applied.get(0).contains("(citizen "), String.join(" | ", applied));
            invariant("J1");
            return true;
        }));
    }

    /** J2: a colonist holding a pickaxe, an Efficiency book: the pickaxe in his hand is enchanted. */
    private void scenarioJ2() {
        runesmithColony(false);
        unemployed("holds a pickaxe");
        hold(l -> new ItemStack(Items.IRON_PICKAXE));
        stockAndSnapshot("Efficiency II book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.EFFICIENCY, 2), new ItemStack(Items.LAPIS_LAZULI, 16)});
        waitFor("the pickaxe gets Efficiency II", 12000, l -> ColonyScenarios.level(l, held(), Enchantments.EFFICIENCY) == 2);
        settle(200);
        steps.add(new Step("checks", 20, l -> {
            final List<String> applied = ColonyScenarios.modLines("applied ");
            check("J2 pickaxe in hand has Efficiency II", held().is(Items.IRON_PICKAXE)
                    && ColonyScenarios.level(l, held(), Enchantments.EFFICIENCY) == 2, "");
            check("J2 inventory and entity agree", entityAgrees(net.minecraft.world.entity.EquipmentSlot.MAINHAND, held()), who(visitee));
            check("J2 one applied line, at the citizen", applied.size() == 1 && applied.get(0).contains("(citizen "), String.join(" | ", applied));
            check("J2 2 lapis spent", lapis() == 14, "lapis " + lapis());
            invariant("J2");
            return true;
        }));
    }

    /** J3: the colonist is gone in the middle of the visit: nothing is lost, nothing duplicated, nothing thrown. */
    private void scenarioJ3() {
        runesmithColony(false);
        unemployed("will vanish");
        wear(l -> new ItemStack(Items.LEATHER_HELMET), net.minecraft.world.entity.EquipmentSlot.HEAD);
        stockAndSnapshot("Protection I book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.PROTECTION, 1), new ItemStack(Items.LAPIS_LAZULI, 16)});
        steps.add(new Step("the colonist vanishes while the Runesmith channels", 12000, l -> {
            if (!"CHANNEL".equals(aiState(smith))) {
                return false;
            }
            visitee.getEntity().ifPresent(net.minecraft.world.entity.Entity::discard);
            LOG.info(TAG + "{} discarded mid-visit (Runesmith AI {})", visitee.getName(), aiState(smith));
            return true;
        }));
        settle(400);
        steps.add(new Step("checks", 20, l -> {
            final ItemStack helmet = worn(net.minecraft.world.entity.EquipmentSlot.HEAD);
            check("J3 nothing applied", ColonyScenarios.modLines("applied ").isEmpty(), "");
            check("J3 the helmet is still there, unenchanted", helmet.is(Items.LEATHER_HELMET) && !helmet.isEnchanted(), "");
            check("J3 book and lapis untouched", books() == 1 && lapis() == 16, "books " + books() + ", lapis " + lapis());
            invariant("J3");
            return true;
        }));
    }

    /** J4: the colonists setting off: nobody's gear is touched, nothing is asked for. */
    private void scenarioJ4() {
        runesmithColony(false);
        steps.add(new Step("colonists off", 20, l -> {
            ColonyScenarios.set(smithHut, RunesmithSettings.COLONISTS, false);
            return true;
        }));
        unemployed("wears a helmet");
        wear(l -> new ItemStack(Items.LEATHER_HELMET), net.minecraft.world.entity.EquipmentSlot.HEAD);
        stockAndSnapshot("Protection I book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.PROTECTION, 1), new ItemStack(Items.LAPIS_LAZULI, 16)});
        settle(1500);
        steps.add(new Step("checks", 20, l -> {
            check("J4 helmet untouched", !worn(net.minecraft.world.entity.EquipmentSlot.HEAD).isEnchanted(), "");
            check("J4 nothing applied or asked for", ColonyScenarios.modLines("applied ").isEmpty()
                    && ColonyScenarios.modLines("requested ").isEmpty(), "");
            check("J4 book kept", books() == 1, "books " + books());
            invariant("J4");
            return true;
        }));
    }

    /**
     * J5: a builder of a level 1 hut holds a stone pickaxe. Efficiency II would make it too good for
     * his hut (stone 1 + 1 > 1): skipped ABOVE_WORKER_LEVEL. Efficiency I is fine and is applied.
     */
    private void scenarioJ5() {
        runesmithColony(false);
        steps.add(new Step("paste a builder's hut", 20, l -> paste(l, "Medieval Oak", "fundamentals/builder1.blueprint", otherPos())));
        steps.add(new Step("builder's hut pasted", 6000, l -> pasted(l, otherPos())));
        steps.add(new Step("register the builder's hut", 200, l -> (otherHut = register(l, otherPos(), "builder")) != null));
        steps.add(new Step("hire a builder", 200, l -> {
            visitee = hire(l, otherHut);
            return jobIs(visitee, "JobBuilder");
        }));
        hold(l -> new ItemStack(Items.STONE_PICKAXE));
        stockAndSnapshot("Efficiency II book, Efficiency I book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.EFFICIENCY, 2), ColonyScenarios.book(l, Enchantments.EFFICIENCY, 1),
                new ItemStack(Items.LAPIS_LAZULI, 16)});
        waitFor("the stone pickaxe gets Efficiency I", 12000, l -> {
            for (int i = 0; i < visitee.getInventory().getSlots(); i++) {
                final ItemStack s = visitee.getInventory().getStackInSlot(i);
                if (s.is(Items.STONE_PICKAXE) && ColonyScenarios.level(l, s, Enchantments.EFFICIENCY) == 1) {
                    return true;
                }
            }
            return false;
        });
        settle(300);
        steps.add(new Step("checks", 20, l -> {
            final List<String> skipped = ColonyScenarios.modLines("skipped ");
            check("J5 Efficiency II skipped as too good for the hut", skipped.stream().anyMatch(x -> x.endsWith("ABOVE_WORKER_LEVEL")),
                    String.join(" | ", skipped));
            check("J5 the Efficiency II book stays", ColonyScenarios.bookWithLevel(l, smithHut, smith, Enchantments.EFFICIENCY, 2) && books() == 1,
                    "books " + books());
            invariant("J5");
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
        // the racks: a paste into an already registered building would have made them its containers
        int racks = 0;
        final var corners = building.getCorners();
        if (corners != null) {
            for (final BlockPos p : BlockPos.betweenClosed(corners.getA(), corners.getB())) {
                if (level.getBlockEntity(p) instanceof com.minecolonies.api.tileentities.AbstractTileEntityRack
                        && !building.getContainers().contains(p)) {
                    building.addContainerPosition(p.immutable());
                    racks++;
                }
            }
        }
        check("register " + what, building.getBuildingLevel() >= 1, building.getClass().getSimpleName() + " level "
                + building.getBuildingLevel() + " schematic " + building.getSchematicName() + " at " + pos + ", " + racks + " rack(s)");
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
