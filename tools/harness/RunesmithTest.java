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
            case "h" -> scenarioH();
            case "i" -> scenarioI();
            case "l" -> scenarioL();
            case "w" -> scenarioW();
            case "k" -> scenarioK();
            case "p" -> scenarioP();
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
        steps.add(new Step("paste the Runesmith (Forge Hall, level 1)", 20, l -> paste(l, "Runesmith", "runesmith/forgehall1.blueprint", smithPos())));
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

    /** A level 1 guard tower and its guard (a knight). */
    private void guard() {
        steps.add(new Step("paste a guard tower", 20, l -> paste(l, "Medieval Oak", "military/guardtower1.blueprint", otherPos())));
        steps.add(new Step("guard tower pasted", 6000, l -> pasted(l, otherPos())));
        steps.add(new Step("register the guard tower", 200, l -> (otherHut = register(l, otherPos(), "guard tower")) != null));
        steps.add(new Step("hire a guard", 200, l -> {
            visitee = hire(l, otherHut);
            check("hired a guard", visitee.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard<?>,
                    visitee.getName() + " -> " + (visitee.getJob() == null ? "none" : visitee.getJob().getClass().getSimpleName()));
            return true;
        }));
    }

    /**
     * J2: a guard holding a stone sword, a Sharpness I book: the sword in his hand is enchanted.
     * (A guard keeps his weapon in hand; a colonist with no job puts a tool away, to eat for one.)
     */
    private void scenarioJ2() {
        runesmithColony(false);
        guard();
        hold(l -> new ItemStack(Items.STONE_SWORD));
        stockAndSnapshot("Sharpness I book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.SHARPNESS, 1), new ItemStack(Items.LAPIS_LAZULI, 16)});
        steps.add(new Step("the sword in his hand gets Sharpness I", 12000, l -> {
            if (tick % 600 == 0) {
                LOG.info(TAG + "waiting: Runesmith AI {} ({}), guard AI {} ({}), holds {}, entity hand {}", aiState(smith), who(smith),
                        aiState(visitee), who(visitee), held(), visitee.getEntity().map(e -> String.valueOf(e.getMainHandItem())).orElse("-"));
            }
            if (tick % 20 == 0 && String.valueOf(aiState(smith)).matches("WALK_TO_CITIZEN|CHANNEL")) {
                LOG.info(TAG + "nav: {}; guard AI {} at {}, entity hand {}", navInfo(smith), aiState(visitee),
                        visitee.getEntity().map(e -> e.blockPosition().toShortString()).orElse("-"),
                        visitee.getEntity().map(e -> String.valueOf(e.getMainHandItem())).orElse("-"));
            }
            return tick % 20 == 0 && ColonyScenarios.level(l, held(), Enchantments.SHARPNESS) == 1;
        }));
        settle(200);
        steps.add(new Step("checks", 20, l -> {
            final List<String> applied = ColonyScenarios.modLines("applied ");
            check("J2 sword in hand has Sharpness I", held().is(Items.STONE_SWORD)
                    && ColonyScenarios.level(l, held(), Enchantments.SHARPNESS) == 1, "");
            // a guard shows his sword only while his AI has it equipped; what he must never show is the old, unenchanted copy
            final ItemStack shown = visitee.getEntity().map(e -> e.getMainHandItem()).orElse(ItemStack.EMPTY);
            check("J2 the entity shows no old copy", !(shown.is(Items.STONE_SWORD) && ColonyScenarios.level(l, shown, Enchantments.SHARPNESS) != 1),
                    "entity hand " + shown + " " + shown.getComponents() + ", " + who(visitee));
            check("J2 one applied line, at the citizen", applied.size() == 1 && applied.get(0).contains("(citizen "), String.join(" | ", applied));
            check("J2 1 lapis spent", lapis() == 15, "lapis " + lapis());
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
     * J5: a guard of a level 1 tower holds a stone sword. Sharpness II would make it too good for his
     * tower (stone 1 + (2 - 1) > 1), so he would put it down: skipped ABOVE_WORKER_LEVEL, nothing spent.
     */
    private void scenarioJ5() {
        runesmithColony(false);
        guard();
        hold(l -> new ItemStack(Items.STONE_SWORD));
        stockAndSnapshot("Sharpness II book, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.SHARPNESS, 2), new ItemStack(Items.LAPIS_LAZULI, 16)});
        settle(2400);
        steps.add(new Step("checks", 20, l -> {
            final List<String> skipped = ColonyScenarios.modLines("skipped ");
            check("J5 Sharpness II skipped as too good for the tower", skipped.stream().anyMatch(x -> x.endsWith("ABOVE_WORKER_LEVEL")),
                    String.join(" | ", skipped));
            check("J5 nothing applied, the book stays", ColonyScenarios.modLines("applied ").isEmpty() && books() == 1, "books " + books());
            check("J5 the sword is untouched", InventoryUtils.getItemCountInItemHandler(visitee.getInventory(), s -> s.is(Items.STONE_SWORD) && s.isEnchanted()) == 0, "");
            invariant("J5");
            return true;
        }));
    }

    // ---------------------------------------------------------------- Phase 3 scenarios

    /** H: the level cap on, a level 1 hut: a III book is ABOVE_LEVEL_CAP; once the hut is level 3 it is applied. */
    private void scenarioH() {
        runesmithColony(true);
        stockAndSnapshot("iron sword, Sharpness III book, 16 lapis", l -> new ItemStack[] {
                new ItemStack(Items.IRON_SWORD), ColonyScenarios.book(l, Enchantments.SHARPNESS, 3), new ItemStack(Items.LAPIS_LAZULI, 16)});
        settle(1200);
        steps.add(new Step("checks at level 1, then the hut grows to level 3", 20, l -> {
            final List<String> skipped = ColonyScenarios.modLines("skipped ");
            check("H level 1: ABOVE_LEVEL_CAP once", skipped.stream().filter(x -> x.endsWith("ABOVE_LEVEL_CAP")).count() == 1, String.join(" | ", skipped));
            check("H level 1: nothing applied, book kept", ColonyScenarios.modLines("applied ").isEmpty() && books() == 1, "books " + books());
            smithHut.setBuildingLevel(3);
            check("H the hut is level 3", smithHut.getBuildingLevel() == 3, "level " + smithHut.getBuildingLevel());
            return true;
        }));
        waitFor("at level 3 the III book is applied", 6000, l -> anyWith(l, Items.IRON_SWORD, Enchantments.SHARPNESS, 3));
        settle(200);
        steps.add(new Step("checks at level 3", 20, l -> {
            check("H level 3: one applied line", ColonyScenarios.modLines("applied ").size() == 1, "");
            invariant("H");
            return true;
        }));
    }

    private static final Object[][] SETTINGS_CHANGED = {
            {RunesmithSettings.ARMOR, false}, {RunesmithSettings.WEAPONS, false}, {RunesmithSettings.TOOLS, true},
            {RunesmithSettings.LEVEL_CAP, false}, {RunesmithSettings.ONLY_UNENCHANTED, true}, {RunesmithSettings.LAPIS, false},
            {RunesmithSettings.COLONISTS, false}, {RunesmithSettings.WAREHOUSE, true}};

    /**
     * I: two runs on one world. "i": every setting moved off its default, a saved world. "i reload":
     * the building, its worker and every setting are as they were.
     */
    @SuppressWarnings("unchecked")
    private void scenarioI() {
        if (mode.contains("reload")) {
            steps.add(new Step("the colony and the Runesmith come back", 2400, l -> {
                if (tick % 20 != 0) {
                    return false;
                }
                colony = IColonyManager.getInstance().getColonyByWorld(1, l);
                if (colony == null) {
                    return false;
                }
                for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                    if (b instanceof BuildingRunesmith) {
                        smithHut = b;
                        return true;
                    }
                }
                return false;
            }));
            steps.add(new Step("checks after the restart", 20, l -> {
                for (final Object[] s : SETTINGS_CHANGED) {
                    final boolean value = smithHut.getSetting((com.minecolonies.api.colony.buildings.modules.settings.ISettingKey<
                            com.minecolonies.core.colony.buildings.modules.settings.BoolSetting>) s[0]).getValue();
                    check("I setting " + ((com.minecolonies.api.colony.buildings.modules.settings.ISettingKey<?>) s[0]).getUniqueId() + " kept",
                            value == (Boolean) s[1], "is " + value + ", was set to " + s[1]);
                }
                final WorkerBuildingModule work = smithHut.getFirstModuleOccurance(WorkerBuildingModule.class);
                final List<ICitizenData> workers = work == null ? List.of() : work.getAssignedCitizen();
                check("I the worker is still hired", workers.size() == 1 && workers.get(0).getJob() != null
                        && workers.get(0).getJob().getClass().getSimpleName().equals("JobRunesmith"),
                        workers.isEmpty() ? "nobody" : workers.get(0).getName() + " -> " + workers.get(0).getJob());
                check("I the building kept its level", smithHut.getBuildingLevel() == 1, "level " + smithHut.getBuildingLevel());
                return true;
            }));
            return;
        }
        runesmithColony(false);
        steps.add(new Step("move every setting off its default", 20, l -> {
            final StringBuilder sb = new StringBuilder();
            for (final Object[] s : SETTINGS_CHANGED) {
                final com.minecolonies.api.colony.buildings.modules.settings.ISettingKey<com.minecolonies.core.colony.buildings.modules.settings.BoolSetting> key =
                        (com.minecolonies.api.colony.buildings.modules.settings.ISettingKey<com.minecolonies.core.colony.buildings.modules.settings.BoolSetting>) s[0];
                ColonyScenarios.set(smithHut, key, (Boolean) s[1]);
                sb.append(key.getUniqueId().getPath()).append('=').append(smithHut.getSetting(key).getValue()).append(' ');
            }
            smithHut.markDirty();
            check("I settings changed", true, sb.toString().trim());
            return true;
        }));
        settle(100);
        steps.add(new Step("save-all flush", 40, l -> {
            l.getServer().saveEverything(false, true, true);
            check("I world saved", true, "");
            return true;
        }));
    }

    /** L: only unenchanted gear: of an enchanted and a plain sword only the plain one is enchanted. */
    private void scenarioL() {
        runesmithColony(false);
        steps.add(new Step("only unenchanted gear on", 20, l -> {
            ColonyScenarios.set(smithHut, RunesmithSettings.ONLY_UNENCHANTED, true);
            return true;
        }));
        stockAndSnapshot("a sword with Unbreaking I, a plain sword, two Sharpness III books, 16 lapis", l -> new ItemStack[] {
                ColonyScenarios.gear(l, Items.IRON_SWORD, Enchantments.UNBREAKING, 1), new ItemStack(Items.IRON_SWORD),
                ColonyScenarios.book(l, Enchantments.SHARPNESS, 3), ColonyScenarios.book(l, Enchantments.SHARPNESS, 3),
                new ItemStack(Items.LAPIS_LAZULI, 16)});
        waitFor("the plain sword gets Sharpness III", 6000, l -> anyWith(l, Items.IRON_SWORD, Enchantments.SHARPNESS, 3));
        settle(1000);
        steps.add(new Step("checks", 20, l -> {
            final List<ItemStack> swords = ColonyScenarios.all(smithHut, smith, Items.IRON_SWORD);
            final long withUnbreakingOnly = swords.stream().filter(s -> ColonyScenarios.level(l, s, Enchantments.UNBREAKING) == 1
                    && ColonyScenarios.level(l, s, Enchantments.SHARPNESS) == 0).count();
            final long sharpOnly = swords.stream().filter(s -> ColonyScenarios.level(l, s, Enchantments.SHARPNESS) == 3
                    && ColonyScenarios.level(l, s, Enchantments.UNBREAKING) == 0).count();
            check("L the enchanted sword is left alone", withUnbreakingOnly == 1, "");
            check("L the plain sword got Sharpness III", sharpOnly == 1, "");
            check("L one book used, one kept", ColonyScenarios.modLines("applied ").size() == 1 && books() == 1, "books " + books());
            check("L ALREADY_ENCHANTED reported", ColonyScenarios.modLines("skipped ").stream().anyMatch(x -> x.endsWith("ALREADY_ENCHANTED")), "");
            invariant("L");
            return true;
        }));
    }

    /** W: the warehouse source: a sword is borrowed, enchanted at the anvil and put back into its own slot. */
    private void scenarioW() {
        runesmithColony(false);
        steps.add(new Step("paste a warehouse", 20, l -> paste(l, "Medieval Oak", "craftsmanship/storage/warehouse1.blueprint", warehousePos())));
        steps.add(new Step("warehouse pasted", 8000, l -> pasted(l, warehousePos())));
        steps.add(new Step("register the warehouse", 200, l -> (warehouse = register(l, warehousePos(), "warehouse")) != null));
        steps.add(new Step("DIAG the warehouse doors and floor", 20, l -> {
            // offsets from the Medieval Oak warehouse1 hut block: east door, stair step, hall floor, the moat south of it
            final int[][] at = {{7, 0, 0}, {7, 1, 0}, {-1, 0, 5}, {-1, 1, 5}, {8, -1, -1}, {8, -1, 0}, {8, -1, 1}, {6, -1, 0}, {6, 0, 0},
                    {6, -1, -3}, {6, 0, -3}, {6, 1, -3}, {8, -1, -4}, {8, -2, -4}, {7, -1, -8}, {7, 0, -7}, {7, -1, -6}, {7, -2, -6}};
            final StringBuilder sb = new StringBuilder();
            for (final int[] o : at) {
                final BlockPos p = warehousePos().offset(o[0], o[1], o[2]);
                sb.append(p.toShortString()).append('=').append(l.getBlockState(p)).append("; ");
            }
            LOG.info(TAG + "DIAG warehouse blocks: {}", sb);
            LOG.info(TAG + "DIAG warehouse racks: {}", warehouse.getContainers());
            // the world as it stands after the paste and the fill, four layers around the warehouse
            for (int y = -2; y <= 1; y++) {
                final StringBuilder map = new StringBuilder();
                for (int z = 20; z <= 46; z++) {
                    map.append(String.format("%n%4d ", z));
                    for (int x = -6; x <= 20; x++) {
                        map.append(mapChar(l.getBlockState(warehousePos().offset(x, y, z - 30))));
                    }
                }
                LOG.info(TAG + "DIAG map y={} (columns x -6..20):{}", y, map);
            }
            return true;
        }));
        steps.add(new Step("a plain sword in the warehouse, the warehouse source on", 20, l -> {
            final boolean ok = ColonyScenarios.stock(warehouse, new ItemStack(Items.IRON_SWORD));
            ColonyScenarios.set(smithHut, RunesmithSettings.WAREHOUSE, true);
            check("warehouse stocked", ok, ColonyScenarios.count(warehouse, null, BuildingRunesmith::isGear) + " gear in the warehouse");
            return true;
        }));
        stockAndSnapshot("Sharpness III book, 16 lapis in the Runesmith", l -> new ItemStack[] {
                ColonyScenarios.book(l, Enchantments.SHARPNESS, 3), new ItemStack(Items.LAPIS_LAZULI, 16)});
        steps.add(new Step("the sword is back in the warehouse with Sharpness III", 12000, l -> {
            if (tick % 600 == 0) {
                LOG.info(TAG + "waiting: Runesmith AI {} ({}), swords: warehouse {}, Runesmith {}", aiState(smith), who(smith),
                        ColonyScenarios.count(warehouse, null, s -> s.is(Items.IRON_SWORD)), ColonyScenarios.count(smithHut, smith, s -> s.is(Items.IRON_SWORD)));
            }
            if (tick % 20 == 0 && String.valueOf(aiState(smith)).startsWith("LOAN_")) {
                LOG.info(TAG + "nav: {}", navInfo(smith));
            }
            return tick % 20 == 0 && ColonyScenarios.all(warehouse, smith, Items.IRON_SWORD)
                    .stream().anyMatch(s -> ColonyScenarios.level(l, s, Enchantments.SHARPNESS) == 3)
                    && ColonyScenarios.count(warehouse, null, s -> s.is(Items.IRON_SWORD)) == 1;
        }));
        settle(300);
        steps.add(new Step("checks", 20, l -> {
            check("W borrowed and returned", ColonyScenarios.modLines("borrowed ").size() == 1 && ColonyScenarios.modLines("returned ").size() == 1,
                    String.join(" | ", ColonyScenarios.modLines("borrowed ")) + " | " + String.join(" | ", ColonyScenarios.modLines("returned ")));
            check("W one applied line, marked warehouse", ColonyScenarios.modLines("applied ").size() == 1
                    && ColonyScenarios.modLines("applied ").get(0).contains("(warehouse)"), String.join(" | ", ColonyScenarios.modLines("applied ")));
            check("W the sword is in the warehouse, not with the Runesmith", ColonyScenarios.count(warehouse, null, s -> s.is(Items.IRON_SWORD)) == 1
                    && ColonyScenarios.count(smithHut, smith, s -> s.is(Items.IRON_SWORD)) == 0, "");
            check("W no loan left open", smith.getJob() instanceof me.lovkar.runesmith.colony.JobRunesmith j && j.loans().isEmpty(), "");
            invariant("W");
            return true;
        }));
    }

    /** The three looks of the Runesmith's building, as the structure pack names their blueprints. */
    private static final String[] LOOKS = {"forgehall", "runetower", "crystalheart"};
    private final List<IBuilding> packHuts = new ArrayList<>();
    private final List<ICitizenData> packSmiths = new ArrayList<>();
    private final List<String> packNames = new ArrayList<>();

    /** Five huts in a row per look, north and south of the town hall, inside the force-loaded chunks. */
    private BlockPos packPos(final int look, final int level) {
        return center.offset(-80 + 32 * (level - 1), 0, new int[] {-60, 40, 90}[look]);
    }

    /**
     * P: the structure pack. Every level of every look is pasted the usual way (the hut block one
     * above the grass), registered and staffed. Each hut gets a sword, a Sharpness I book and lapis,
     * and every one of the fifteen Runesmiths must walk to his anvil and enchant the sword there. Also
     * checks the level each blueprint gives its building, and that every rack became a container.
     */
    private void scenarioP() {
        world();
        for (int look = 0; look < LOOKS.length; look++) {
            for (int level = 1; level <= 5; level++) {
                final int lk = look;
                final int lv = level;
                final String path = "runesmith/" + LOOKS[lk] + lv + ".blueprint";
                steps.add(new Step("paste " + path, 20, l -> paste(l, "Runesmith", path, packPos(lk, lv))));
                steps.add(new Step(path + " pasted", 6000, l -> pasted(l, packPos(lk, lv))));
                steps.add(new Step("register " + path, 200, l -> {
                    final IBuilding b = register(l, packPos(lk, lv), LOOKS[lk] + lv);
                    if (b == null) {
                        return false;
                    }
                    final int racks = (int) b.getContainers().stream().filter(c -> !c.equals(b.getPosition())).count();
                    check(LOOKS[lk] + lv + " level", b.getBuildingLevel() == lv, "level " + b.getBuildingLevel() + ", " + racks + " rack(s)");
                    if (b.getBuildingLevel() != lv) {
                        b.setBuildingLevel(lv);
                    }
                    packHuts.add(b);
                    packNames.add(LOOKS[lk] + lv);
                    return true;
                }));
            }
        }
        steps.add(new Step("hire fifteen Runesmiths", 400, l -> {
            for (final IBuilding b : packHuts) {
                packSmiths.add(hire(l, b));
            }
            check("fifteen Runesmiths hired", packSmiths.stream().allMatch(c -> c != null && c.getJob() instanceof me.lovkar.runesmith.colony.JobRunesmith),
                    packSmiths.size() + " hired");
            return true;
        }));
        steps.add(new Step("stock: a sword, a Sharpness I book and 4 lapis in every hut", 40, l -> {
            boolean ok = true;
            for (final IBuilding b : packHuts) {
                ok &= ColonyScenarios.stock(b, new ItemStack(Items.IRON_SWORD), ColonyScenarios.book(l, Enchantments.SHARPNESS, 1),
                        new ItemStack(Items.LAPIS_LAZULI, 4));
            }
            check("every hut stocked", ok, "");
            return true;
        }));
        steps.add(new Step("every Runesmith enchants his sword at his anvil", 15000, l -> {
            final List<String> waiting = new ArrayList<>();
            for (int i = 0; i < packHuts.size(); i++) {
                final IBuilding b = packHuts.get(i);
                final boolean done = ColonyScenarios.all(b, packSmiths.get(i), Items.IRON_SWORD).stream()
                        .anyMatch(st -> ColonyScenarios.level(l, st, Enchantments.SHARPNESS) == 1);
                if (!done) {
                    waiting.add(packNames.get(i) + " (" + aiState(packSmiths.get(i)) + ", " + who(packSmiths.get(i)) + ")");
                }
            }
            if (tick % 1200 == 0) {
                LOG.info(TAG + "waiting for {} of {}: {}", waiting.size(), packHuts.size(), waiting);
            }
            return tick % 20 == 0 && waiting.isEmpty();
        }));
        settle(100);
        steps.add(new Step("checks", 20, l -> {
            final List<String> applied = ColonyScenarios.modLines("applied ");
            check("P one applied line per hut", applied.size() == packHuts.size(), applied.size() + " applied lines for " + packHuts.size() + " huts");
            for (int i = 0; i < packHuts.size(); i++) {
                final IBuilding b = packHuts.get(i);
                final String at = "hut=" + b.getPosition().toShortString() + " ";
                check("P " + packNames.get(i) + " enchanted at its anvil", applied.stream().filter(a -> a.contains(at)).count() == 1
                        && ColonyScenarios.count(b, packSmiths.get(i), BuildingRunesmith::isBook) == 0,
                        "level " + b.getBuildingLevel() + ", work tag " + b.getLocationsFromTag(BuildingRunesmith.TAG_WORK));
            }
            return true;
        }));
    }

    /**
     * K: fuzz. All three sources on, a level 5 hut, random gear and random books (curses
     * included) in rounds, until at least 200 books have been applied. The invariant is checked over
     * the whole run, across the racks, the pack, the warehouse and the colonists.
     */
    private void scenarioK() {
        runesmithColony(false);
        steps.add(new Step("paste a warehouse", 20, l -> paste(l, "Medieval Oak", "craftsmanship/storage/warehouse1.blueprint", warehousePos())));
        steps.add(new Step("warehouse pasted", 8000, l -> pasted(l, warehousePos())));
        steps.add(new Step("register the warehouse", 200, l -> (warehouse = register(l, warehousePos(), "warehouse")) != null));
        steps.add(new Step("all sources on, a level 5 hut, three dressed colonists", 100, l -> {
            ColonyScenarios.set(smithHut, RunesmithSettings.WAREHOUSE, true);
            smithHut.setBuildingLevel(5);
            fuzz = new Fuzz(l);
            for (int i = 0; i < 3; i++) {
                final ICitizenData c = colony.getCitizenManager().createAndRegisterCivilianData();
                colony.getCitizenManager().spawnOrCreateCitizen(c, l, spawnPoint(l).offset(2 + 2 * i, 0, 2));
                c.getInventory().forceArmorStackToSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, fuzz.gear(Items.IRON_HELMET));
                c.getInventory().forceArmorStackToSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, fuzz.gear(Items.IRON_CHESTPLATE));
                fuzz.colonists.add(c);
                fuzz.gearIn += 2;
            }
            final int whBefore = ColonyScenarios.count(warehouse, null, BuildingRunesmith::isGear);
            for (int i = 0; i < 6; i++) {
                ColonyScenarios.stock(warehouse, fuzz.randomGear());
            }
            fuzz.gearIn += ColonyScenarios.count(warehouse, null, BuildingRunesmith::isGear) - whBefore;
            check("fuzz set up", true, "level " + smithHut.getBuildingLevel() + ", " + fuzz.gearIn + " gear pieces out in the colony");
            return true;
        }));
        steps.add(new Step("fuzz until 200 books are applied", 120000, l -> {
            if (tick % 100 != 0) {
                return false;
            }
            final int applied = ColonyScenarios.modLines("applied ").size();
            if (applied >= 200) {
                return true;
            }
            if (tick - fuzz.lastRound >= 1200 || ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isBook) == 0) {
                fuzz.round(l);
                LOG.info(TAG + "fuzz round {}: {} applied so far, books in {}, gear in {}", fuzz.rounds, applied, fuzz.booksIn, fuzz.gearIn);
            }
            return false;
        }));
        settle(600);
        steps.add(new Step("checks", 20, l -> {
            final List<String> applied = ColonyScenarios.modLines("applied ");
            final int lapisLogged = ColonyScenarios.lapisLogged(applied);
            final int gearNow = ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isGear)
                    + ColonyScenarios.count(warehouse, null, BuildingRunesmith::isGear) + fuzz.colonistGear() + fuzz.gearTakenOut;
            final int booksNow = ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isBook);
            final int lapisNow = ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isLapis);
            check("K at least 200 applied", applied.size() >= 200, applied.size() + " applied in " + fuzz.rounds + " rounds");
            check("K gear conserved", gearNow == fuzz.gearIn, "in " + fuzz.gearIn + ", now " + gearNow + " (incl. " + fuzz.gearTakenOut + " taken out)");
            check("K books conserved", booksNow + applied.size() + fuzz.booksTakenOut == fuzz.booksIn, "in " + fuzz.booksIn + ", left " + booksNow
                    + ", applied " + applied.size() + ", taken out " + fuzz.booksTakenOut);
            check("K lapis conserved", lapisNow + lapisLogged == fuzz.lapisIn, "in " + fuzz.lapisIn + ", left " + lapisNow + ", logged " + lapisLogged);
            LOG.info(TAG + "K sources: rack {}, citizen {}, warehouse {}", applied.stream().filter(x -> x.contains("(rack)")).count(),
                    applied.stream().filter(x -> x.contains("(citizen ")).count(), applied.stream().filter(x -> x.contains("(warehouse)")).count());
            return true;
        }));
    }

    private Fuzz fuzz;

    /** The fuzz scenario's bookkeeping and its random stock. */
    private final class Fuzz {
        final java.util.Random random = new java.util.Random(20261003L);
        final List<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>> enchantments = new ArrayList<>();
        final List<Item> gearItems = new ArrayList<>();
        final List<ICitizenData> colonists = new ArrayList<>();
        int gearIn;
        int booksIn;
        int lapisIn;
        int gearTakenOut;
        int booksTakenOut;
        int rounds;
        int lastRound;

        Fuzz(final ServerLevel level) {
            level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).holders().forEach(enchantments::add);
            for (final Item item : BuiltInRegistries.ITEM) {
                if (BuildingRunesmith.isGear(new ItemStack(item))) {
                    gearItems.add(item);
                }
            }
        }

        ItemStack gear(final Item item) {
            return new ItemStack(item);
        }

        ItemStack randomGear() {
            return new ItemStack(gearItems.get(random.nextInt(gearItems.size())));
        }

        ItemStack randomBook() {
            final var e = enchantments.get(random.nextInt(enchantments.size()));
            final ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            m.set(e, 1 + random.nextInt(e.value().getMaxLevel()));
            if (random.nextInt(6) == 0) { // now and then a book with two enchantments
                final var f = enchantments.get(random.nextInt(enchantments.size()));
                m.set(f, 1 + random.nextInt(f.value().getMaxLevel()));
            }
            final ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
            b.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
            return b;
        }

        int colonistGear() {
            int n = 0;
            for (final ICitizenData c : colonists) {
                for (final net.minecraft.world.entity.EquipmentSlot s : new net.minecraft.world.entity.EquipmentSlot[] {
                        net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST}) {
                    n += BuildingRunesmith.isGear(c.getInventory().getArmorInSlot(s)) ? 1 : 0;
                }
            }
            return n;
        }

        /**
         * Takes worked gear (and, when the racks fill up, some of the rest and some books) out of the
         * racks, and brings new gear, books and lapis. Everything is counted by what actually moved.
         */
        void round(final ServerLevel level) {
            rounds++;
            lastRound = tick;
            final IItemHandler racks = smithHut.getItemHandlerCap((net.minecraft.core.Direction) null);
            final int gearHeld = ColonyScenarios.count(smithHut, null, BuildingRunesmith::isGear);
            final int booksHeld = ColonyScenarios.count(smithHut, null, BuildingRunesmith::isBook);
            for (int i = 0; i < racks.getSlots(); i++) {
                final ItemStack s = racks.getStackInSlot(i);
                if (BuildingRunesmith.isGear(s) && (s.isEnchanted() && random.nextBoolean() || gearHeld > 20 && random.nextInt(3) == 0)) {
                    gearTakenOut += racks.extractItem(i, 1, false).getCount();
                } else if (BuildingRunesmith.isBook(s) && booksHeld > 30 && random.nextInt(3) == 0) {
                    booksTakenOut += racks.extractItem(i, 1, false).getCount();
                }
            }
            int before = ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isGear);
            for (int i = 0; i < 6; i++) {
                ColonyScenarios.stock(smithHut, randomGear());
            }
            gearIn += ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isGear) - before;
            before = ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isBook);
            for (int i = 0; i < 14; i++) {
                ColonyScenarios.stock(smithHut, randomBook());
            }
            booksIn += ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isBook) - before;
            before = ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isLapis);
            if (before < 64) {
                ColonyScenarios.stock(smithHut, new ItemStack(Items.LAPIS_LAZULI, 64));
            }
            lapisIn += ColonyScenarios.count(smithHut, smith, BuildingRunesmith::isLapis) - before;
        }
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
        // The raw paste leaves Structurize's placeholder blocks in the world, which a builder never places:
        // "solid substitution" (fill with ground) and "substitution" (keep the terrain). Pathfinding does not
        // stand on them, and they walled the warehouse hall off from its door. They become what a builder
        // would leave on this flat world: ground below the hut's level, air from it up.
        int filled = 0;
        final net.minecraft.world.level.block.Block solidFill = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("structurize:blocksolidsubstitution"));
        final net.minecraft.world.level.block.Block keep = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("structurize:blocksubstitution"));
        if (corners != null) {
            for (final BlockPos p : BlockPos.betweenClosed(corners.getA(), corners.getB())) {
                final net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
                if (s.is(solidFill) || s.is(keep)) {
                    final boolean ground = s.is(solidFill) || p.getY() < pos.getY();
                    level.setBlock(p, ground ? net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()
                            : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    filled++;
                }
            }
        }
        check("register " + what, building.getBuildingLevel() >= 1, building.getClass().getSimpleName() + " level "
                + building.getBuildingLevel() + " schematic " + building.getSchematicName() + " at " + pos + ", " + racks + " rack(s), "
                + filled + " placeholder(s) replaced");
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

    /** One character per block, for the DIAG maps. */
    private static char mapChar(final net.minecraft.world.level.block.state.BlockState s) {
        if (s.isAir()) {
            return '.';
        }
        final String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (id.contains("door")) {
            return 'D';
        }
        if (id.contains("rack")) {
            return 'R';
        }
        if (id.startsWith("blockhut")) {
            return 'H';
        }
        if (id.contains("stairs")) {
            return 's';
        }
        if (id.contains("slab")) {
            return '_';
        }
        if (id.contains("fence") || id.contains("wall")) {
            return 'f';
        }
        if (id.contains("substitution")) {
            return '?';
        }
        if (id.contains("dirt") || id.contains("grass") || id.contains("path")) {
            return ',';
        }
        return s.blocksMotion() ? '#' : '~';
    }

    /** Where a worker is and what its navigation is doing, for diagnosing walks that never arrive. */
    private static String navInfo(final ICitizenData data) {
        final Optional<AbstractEntityCitizen> e = data.getEntity();
        if (e.isEmpty()) {
            return "no entity";
        }
        final StringBuilder sb = new StringBuilder(aiState(data)).append(" at ").append(e.get().blockPosition().toShortString());
        if (data.getJob() instanceof me.lovkar.runesmith.colony.JobRunesmith j && !j.loans().isEmpty()) {
            final BlockPos rack = j.loans().get(0).rack();
            sb.append(", loan rack ").append(rack.toShortString()).append(" dist ")
                    .append(String.format("%.1f", Math.sqrt(e.get().blockPosition().distSqr(rack))));
        }
        if (e.get().getNavigation() instanceof com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate nav) {
            final com.minecolonies.core.entity.pathfinding.pathresults.PathResult<?> pr = nav.getPathResult();
            sb.append(", nav done ").append(nav.isDone());
            if (pr == null) {
                sb.append(", no path result");
            } else {
                sb.append(", status ").append(pr.getStatus()).append(", reaches ").append(pr.isPathReachingDestination())
                        .append(", len ").append(pr.getPathLength());
                final Object job = pr.getJob();
                sb.append(", job ").append(job == null ? "none" : job.getClass().getSimpleName());
                if (job instanceof com.minecolonies.core.entity.pathfinding.pathjobs.IDestinationPathJob d) {
                    sb.append(" -> ").append(d.getDestination().toShortString());
                }
            }
        }
        return sb.toString();
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
