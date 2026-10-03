package me.lovkar.runesmith;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.items.ItemBlockHut;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import com.minecolonies.core.colony.buildings.views.EmptyView;
import com.minecolonies.core.colony.jobs.views.DefaultJobView;
import me.lovkar.runesmith.block.BlockHutRunesmith;
import me.lovkar.runesmith.block.RunesmithTileEntity;
import me.lovkar.runesmith.colony.BuildingRunesmith;
import me.lovkar.runesmith.colony.JobRunesmith;
import me.lovkar.runesmith.colony.RunesmithModules;
import me.lovkar.runesmith.compat.Voices;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runesmith - a MineColonies profession that puts the Enchanter's books to work.
 *
 * <p>One building (registry name {@code runesmith:runesmith}) with one worker who applies
 * enchanted books from the colony's stock to the armor, weapons and tools in the hut's racks, by
 * the vanilla anvil's rules, without the XP. Registered through MineColonies' own registries, with
 * a block-entity type of our own for the hut (MineColonies' type only accepts its own hut blocks).</p>
 */
@Mod(Runesmith.MODID)
public final class Runesmith {
    public static final String MODID = "runesmith";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public static final String HUT_NAME = "blockhutrunesmith";
    public static final ResourceLocation JOB_ID = id("runesmith");
    public static final ResourceLocation BUILDING_ID = id("runesmith");

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<JobEntry> JOBS = DeferredRegister.create(CommonMinecoloniesAPIImpl.JOBS, MODID);
    public static final DeferredRegister<BuildingEntry> BUILDINGS = DeferredRegister.create(CommonMinecoloniesAPIImpl.BUILDINGS, MODID);

    public static final DeferredBlock<BlockHutRunesmith> BLOCK_HUT = BLOCKS.register(HUT_NAME, BlockHutRunesmith::new);
    public static final DeferredItem<Item> ITEM_HUT = ITEMS.register(HUT_NAME, () -> new ItemBlockHut(BLOCK_HUT.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RunesmithTileEntity>> BUILDING_BE =
            BLOCK_ENTITIES.register("colonybuilding", () -> BlockEntityType.Builder.of(RunesmithTileEntity::new, BLOCK_HUT.get()).build(null));

    public static final DeferredHolder<JobEntry, JobEntry> JOB = JOBS.register(JOB_ID.getPath(), () -> new JobEntry.Builder()
            .setJobProducer(JobRunesmith::new)
            .setJobViewProducer(() -> DefaultJobView::new)
            .setRegistryName(JOB_ID)
            .createJobEntry());

    public static final DeferredHolder<BuildingEntry, BuildingEntry> BUILDING = BUILDINGS.register(BUILDING_ID.getPath(), () -> new BuildingEntry.Builder()
            .setBuildingBlock(BLOCK_HUT.get())
            .setBuildingProducer(BuildingRunesmith::new)
            .setBuildingViewProducer(() -> EmptyView::new)
            .setRegistryName(BUILDING_ID)
            .addBuildingModuleProducer(RunesmithModules.WORK)
            .addBuildingModuleProducer(RunesmithModules.SETTINGS)
            .createBuildingEntry());

    /** MineColonies' creative tab that lists every hut block. */
    private static final ResourceKey<CreativeModeTab> HUTS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("minecolonies", "mchuts"));

    public Runesmith(final IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        JOBS.register(modBus);
        BUILDINGS.register(modBus);
        modBus.addListener(EventPriority.HIGH, Runesmith::registerCapabilities);
        modBus.addListener(Runesmith::addToCreativeTab);
        modBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(Voices::lend));
        // the version from the mod's own metadata, never a number typed in here
        final String version = ModList.get().getModContainerById(MODID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        LOGGER.info("[Runesmith] Runesmith {} loaded", version);
    }

    public static ResourceLocation id(final String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    /** The racks inside the hut: MineColonies reads the hut's inventory through this capability. */
    private static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BUILDING_BE.get(), (be, side) -> be.getItemHandlerCap(side));
    }

    private static void addToCreativeTab(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(HUTS_TAB)) {
            event.accept(ITEM_HUT.get());
        }
    }
}
