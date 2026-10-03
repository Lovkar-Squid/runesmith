package me.lovkar.runesmithtest;

import me.lovkar.runesmith.logic.EnchantApplier;
import me.lovkar.runesmith.logic.GearCategory;
import me.lovkar.runesmith.logic.RunesmithPolicy;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The anvil oracle: a real {@link AnvilMenu} (survival fake player, no world position) is the judge
 * of {@link EnchantApplier}. Single-enchantment books only; the deliberate differences (gear outside
 * the categories, curses) are checked against their own expected reason and counted apart.
 */
final class AnvilOracle {
    static final long SEED = 20261003L;
    private static final int MAX_DIVERGENCES_LOGGED = 60;

    record Summary(int cases, int match, int divergent, int skipped, int deliberate) {}

    private final ServerLevel level;
    private final Consumer<String> log;
    private final AnvilMenu menu;
    private final Random random = new Random(SEED);
    private int cases;
    private int match;
    private int divergent;
    private int skipped;
    private int deliberate;

    AnvilOracle(final ServerLevel level, final Consumer<String> log) {
        this.level = level;
        this.log = log;
        final FakePlayer player = FakePlayerFactory.getMinecraft(level);
        this.menu = new AnvilMenu(0, player.getInventory(), ContainerLevelAccess.NULL);
    }

    Summary run() {
        final Registry<Enchantment> registry = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        final List<Holder<Enchantment>> enchantments = new ArrayList<>();
        registry.holders().forEach(enchantments::add);
        final List<ItemStack> books = new ArrayList<>();
        for (final Holder<Enchantment> e : enchantments) {
            for (int l = 1; l <= e.value().getMaxLevel() + 1; l++) {
                books.add(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(e, l)));
            }
        }
        final List<Item> gear = gearItems();
        log.accept("anvil oracle: seed " + SEED + ", " + enchantments.size() + " enchantments, " + books.size()
                + " books, " + gear.size() + " gear items (+ stick, stone, enchanted book)");

        for (final Item item : gear) {
            final ItemStack clean = new ItemStack(item);
            // 1. clean gear against every book
            for (final ItemStack book : books) {
                compare(clean, book);
            }
            // 2. each enchantment the anvil accepts, alone at level 1 and at its maximum, against 30 books
            for (final Holder<Enchantment> e : enchantments) {
                for (final int l : new int[] {1, e.value().getMaxLevel()}) {
                    final ItemStack start = feed(clean, EnchantedBookItem.createForEnchantment(new EnchantmentInstance(e, l)));
                    if (start != null) {
                        for (int k = 0; k < 30; k++) {
                            compare(start, books.get(random.nextInt(books.size())));
                        }
                    }
                }
            }
            // 3. seeded random pairs and triples, built by the anvil itself, against 40 books
            for (int s = 0; s < 6; s++) {
                ItemStack start = clean;
                final int wanted = 2 + random.nextInt(2);
                for (int tries = 0; tries < 40 && EnchantmentHelper.getEnchantmentsForCrafting(start).size() < wanted; tries++) {
                    final ItemStack next = feed(start, books.get(random.nextInt(books.size())));
                    if (next != null) {
                        start = next;
                    }
                }
                for (int k = 0; k < 40; k++) {
                    compare(start, books.get(random.nextInt(books.size())));
                }
            }
        }
        // not gear: Runesmith must refuse them all, whatever the anvil does
        for (final ItemStack notGear : List.of(new ItemStack(Items.STICK), new ItemStack(Items.STONE), books.get(0).copy())) {
            for (final ItemStack book : books) {
                compare(notGear, book);
            }
        }
        return new Summary(cases, match, divergent, skipped, deliberate);
    }

    /** The anvil's result for gear + book, with repair cost 0 so the 40-level limit bites as rarely as possible. */
    private ItemStack anvil(final ItemStack gear, final ItemStack book) {
        menu.getSlot(0).set(gear.copy());
        menu.getSlot(1).set(book.copy());
        menu.setItemName(gear.getHoverName().getString());
        menu.createResult();
        return menu.getSlot(2).getItem().copy();
    }

    /** A legal starting item: what the anvil makes of it, repair cost reset; null if the anvil refuses. */
    private ItemStack feed(final ItemStack gear, final ItemStack book) {
        final ItemStack out = anvil(gear, book);
        if (out.isEmpty() || EnchantApplier.sameEnchantments(
                EnchantmentHelper.getEnchantmentsForCrafting(out), EnchantmentHelper.getEnchantmentsForCrafting(gear))) {
            return null;
        }
        out.remove(DataComponents.REPAIR_COST);
        return out;
    }

    private void compare(final ItemStack gear, final ItemStack book) {
        cases++;
        final ItemStack fromAnvil = anvil(gear, book);
        final int cost = menu.getCost();
        final EnchantApplier.Result ours = EnchantApplier.apply(gear, book, RunesmithPolicy.OPEN);
        final boolean curse = EnchantmentHelper.getEnchantmentsForCrafting(book).keySet().stream().anyMatch(h -> h.is(EnchantmentTags.CURSE));
        final boolean notGear = GearCategory.of(gear).isEmpty() || gear.is(Items.ENCHANTED_BOOK);
        if (notGear || curse) {
            deliberate++;
            final EnchantApplier.Reason expected = notGear ? EnchantApplier.Reason.NOT_GEAR : EnchantApplier.Reason.CURSE;
            if (ours.reason() != expected) {
                diverge(gear, book, "deliberate rule: expected " + expected, fromAnvil, ours);
            }
            return;
        }
        final ItemEnchantments before = EnchantmentHelper.getEnchantmentsForCrafting(gear);
        if (fromAnvil.isEmpty()) {
            if (cost >= 40) {
                skipped++;
                return;
            }
            if (ours.ok() || ours.reason() == EnchantApplier.Reason.NO_CHANGE) {
                diverge(gear, book, "anvil refused", fromAnvil, ours);
            } else {
                match++;
            }
            return;
        }
        final ItemEnchantments after = EnchantmentHelper.getEnchantmentsForCrafting(fromAnvil);
        if (EnchantApplier.sameEnchantments(before, after)) {
            if (ours.reason() == EnchantApplier.Reason.NO_CHANGE) {
                match++;
            } else {
                diverge(gear, book, "anvil left it unchanged", fromAnvil, ours);
            }
            return;
        }
        if (ours.ok() && EnchantApplier.sameEnchantments(ours.after(), after)
                && EnchantApplier.sameEnchantments(EnchantmentHelper.getEnchantmentsForCrafting(ours.gear()), after)) {
            match++;
        } else {
            diverge(gear, book, "anvil changed it", fromAnvil, ours);
        }
    }

    private void diverge(final ItemStack gear, final ItemStack book, final String what, final ItemStack fromAnvil, final EnchantApplier.Result ours) {
        divergent++;
        if (divergent <= MAX_DIVERGENCES_LOGGED) {
            log.accept("DIVERGENCE " + what + ": " + describe(gear) + " + " + describe(book) + " -> anvil "
                    + (fromAnvil.isEmpty() ? "nothing" : describe(fromAnvil)) + " (cost " + menu.getCost() + "), ours "
                    + ours.reason() + " " + EnchantApplier.describe(ours.after()));
        }
    }

    static String describe(final ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath() + "[" + EnchantApplier.describe(EnchantmentHelper.getEnchantmentsForCrafting(s)) + "]";
    }

    /** Every item in the category tags of brief section 5.5, in registry order. */
    private static List<Item> gearItems() {
        final List<TagKey<Item>> tags = List.of(ItemTags.ARMOR_ENCHANTABLE, ItemTags.WEAPON_ENCHANTABLE, ItemTags.BOW_ENCHANTABLE,
                ItemTags.CROSSBOW_ENCHANTABLE, ItemTags.TRIDENT_ENCHANTABLE, ItemTags.MINING_ENCHANTABLE, ItemTags.FISHING_ENCHANTABLE);
        final Set<Item> out = new LinkedHashSet<>();
        for (final Item item : BuiltInRegistries.ITEM) {
            final ItemStack s = new ItemStack(item);
            if (tags.stream().anyMatch(s::is)) {
                out.add(item);
            }
        }
        return new ArrayList<>(out);
    }
}
