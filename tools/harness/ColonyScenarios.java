package me.lovkar.runesmithtest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import me.lovkar.runesmith.colony.BuildingRunesmith;
import me.lovkar.runesmith.compat.Requests;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.items.IItemHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for the colony scenarios (A-G, N): stock the hut, find what is in it, read the mod's own
 * log lines back, and the item-conservation invariant.
 */
final class ColonyScenarios {
    private static final Pattern LAPIS = Pattern.compile(" \\+ (\\d+) lapis ");

    record Snapshot(int gear, int books, int lapis) {}

    private ColonyScenarios() {
    }

    static Holder<Enchantment> ench(final ServerLevel level, final ResourceKey<Enchantment> key) {
        return level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key);
    }

    static ItemStack book(final ServerLevel level, final ResourceKey<Enchantment> key, final int lvl) {
        final ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        m.set(ench(level, key), lvl);
        final ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
        b.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
        return b;
    }

    static ItemStack gear(final ServerLevel level, final Item item, final ResourceKey<Enchantment> key, final int lvl) {
        final ItemStack s = new ItemStack(item);
        if (key != null) {
            s.enchant(ench(level, key), lvl);
        }
        return s;
    }

    static boolean stock(final IBuilding building, final ItemStack... stacks) {
        boolean ok = true;
        for (final ItemStack s : stacks) {
            ok &= InventoryUtils.addItemStackToProvider(building, s);
        }
        return ok;
    }

    static IItemHandler racks(final IBuilding building) {
        return building.getItemHandlerCap((Direction) null);
    }

    static int count(final IBuilding building, final ICitizenData worker, final Predicate<ItemStack> what) {
        int n = InventoryUtils.getItemCountInItemHandler(racks(building), what);
        if (worker != null) {
            n += InventoryUtils.getItemCountInItemHandler(worker.getInventory(), what);
        }
        return n;
    }

    static Snapshot snapshot(final IBuilding building, final ICitizenData worker) {
        return new Snapshot(count(building, worker, BuildingRunesmith::isGear), count(building, worker, BuildingRunesmith::isBook),
                count(building, worker, BuildingRunesmith::isLapis));
    }

    /** All stacks of this item in the racks and the worker's pack. */
    static List<ItemStack> all(final IBuilding building, final ICitizenData worker, final Item item) {
        final List<ItemStack> out = new ArrayList<>();
        for (final IItemHandler h : List.of(racks(building), worker.getInventory())) {
            for (int i = 0; i < h.getSlots(); i++) {
                if (h.getStackInSlot(i).is(item)) {
                    out.add(h.getStackInSlot(i));
                }
            }
        }
        return out;
    }

    static int level(final ServerLevel level, final ItemStack stack, final ResourceKey<Enchantment> key) {
        return EnchantmentHelper.getEnchantmentsForCrafting(stack).getLevel(ench(level, key));
    }

    static boolean bookWithLevel(final ServerLevel level, final IBuilding building, final ICitizenData worker,
            final ResourceKey<Enchantment> key, final int lvl) {
        return all(building, worker, Items.ENCHANTED_BOOK).stream().anyMatch(b -> level(level, b, key) == lvl);
    }

    static void set(final IBuilding building, final ISettingKey<BoolSetting> key, final boolean value) {
        final BoolSetting s = building.getSetting(key);
        if (s.getValue() != value) {
            s.trigger();
        }
    }

    static boolean booksRequested(final IBuilding building, final ICitizenData worker) {
        return Requests.pending(building, worker, Items.ENCHANTED_BOOK);
    }

    static boolean lapisRequested(final IBuilding building, final ICitizenData worker) {
        return Requests.pending(building, worker, Items.LAPIS_LAZULI);
    }

    /** The mod's own log lines so far: "[Runesmith] <what> ...". */
    static List<String> modLines(final String what) {
        try {
            final String all = new String(Files.readAllBytes(Path.of("logs", "latest.log")), StandardCharsets.UTF_8);
            final List<String> out = new ArrayList<>();
            for (final String line : all.split("\n")) {
                // the mod's own logger only: the harness quotes these lines in its CHECK details
                final int at = line.contains("[runesmith/]") ? line.indexOf("[Runesmith] " + what) : -1;
                if (at >= 0) {
                    out.add(line.substring(at).trim());
                }
            }
            return out;
        } catch (final Exception e) {
            return List.of("UNREADABLE " + e);
        }
    }

    static int lapisLogged(final List<String> applied) {
        int sum = 0;
        for (final String line : applied) {
            final Matcher m = LAPIS.matcher(line);
            if (m.find()) {
                sum += Integer.parseInt(m.group(1));
            }
        }
        return sum;
    }
}
