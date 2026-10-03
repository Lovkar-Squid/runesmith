package me.lovkar.runesmith.logic;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Set;

/**
 * The anvil, without its price: what a vanilla anvil would make of one piece of gear and one
 * enchanted book, as a pure function.
 *
 * <p>The enchantment step mirrors {@code AnvilMenu.createResult} of NeoForge 21.1 line for line,
 * through the same calls: {@code EnchantmentHelper.getEnchantmentsForCrafting},
 * {@code ItemStack.supportsEnchantment}, {@code Enchantment.areCompatible},
 * {@code Enchantment.getMaxLevel}, {@code ItemStack.isBookEnchantable} and
 * {@code ItemEnchantments.Mutable}. Its creative-player shortcut is gone. Everything else the anvil
 * does is left out on purpose: no XP cost, no repair-cost growth, no rename and no repair.</p>
 *
 * <p>Two differences from the anvil are deliberate. A book is used whole or not at all: if any of
 * its enchantments cannot go on, nothing is applied, where the anvil applies the subset that fits.
 * And a book that would leave the gear exactly as it was is {@link Reason#NO_CHANGE}, where the
 * anvil happily charges for it.</p>
 *
 * <p>No MineColonies types, no side effects; the inputs are never modified.</p>
 */
public final class EnchantApplier {

    public enum Reason {
        OK,
        NOT_GEAR,
        NOT_A_BOOK,
        EMPTY_BOOK,
        CATEGORY_DISABLED,
        ALREADY_ENCHANTED,
        CURSE,
        ABOVE_LEVEL_CAP,
        NOT_SUPPORTED,
        CONFLICT,
        NO_CHANGE
    }

    /**
     * The verdict. {@code gear} is a new stack when the reason is {@link Reason#OK}, otherwise
     * {@link ItemStack#EMPTY}. {@code lapis} is the lapis lazuli the application costs under the
     * policy (0 unless OK).
     */
    public record Result(Reason reason, ItemStack gear, ItemEnchantments before, ItemEnchantments after, int lapis) {
        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    private EnchantApplier() {
    }

    public static Result apply(final ItemStack gear, final ItemStack book, final RunesmithPolicy policy) {
        final ItemEnchantments before = gear.isEmpty() ? ItemEnchantments.EMPTY : EnchantmentHelper.getEnchantmentsForCrafting(gear);
        // cheapest checks first
        final Set<GearCategory> categories = GearCategory.of(gear);
        if (gear.isEmpty() || gear.getCount() != 1 || categories.isEmpty() || gear.is(Items.ENCHANTED_BOOK)
                || !EnchantmentHelper.canStoreEnchantments(gear)) {
            return reject(Reason.NOT_GEAR, before);
        }
        if (!book.is(Items.ENCHANTED_BOOK) || !book.has(DataComponents.STORED_ENCHANTMENTS)) {
            return reject(Reason.NOT_A_BOOK, before);
        }
        final ItemEnchantments stored = EnchantmentHelper.getEnchantmentsForCrafting(book);
        if (stored.isEmpty()) {
            return reject(Reason.EMPTY_BOOK, before);
        }
        if (categories.stream().noneMatch(policy::allows)) {
            return reject(Reason.CATEGORY_DISABLED, before);
        }
        if (policy.onlyUnenchanted() && !before.isEmpty()) {
            return reject(Reason.ALREADY_ENCHANTED, before);
        }
        int bookLevels = 0;
        for (final Object2IntMap.Entry<Holder<Enchantment>> e : stored.entrySet()) {
            if (e.getKey().is(EnchantmentTags.CURSE)) {
                return reject(Reason.CURSE, before);
            }
            if (policy.levelCap() > 0 && e.getIntValue() > policy.levelCap()) {
                return reject(Reason.ABOVE_LEVEL_CAP, before);
            }
            bookLevels += e.getIntValue();
        }
        if (!gear.isBookEnchantable(book)) {
            return reject(Reason.NOT_SUPPORTED, before);
        }

        // AnvilMenu.createResult, the enchantment loop
        final ItemEnchantments.Mutable current = new ItemEnchantments.Mutable(before);
        for (final Object2IntMap.Entry<Holder<Enchantment>> e : stored.entrySet()) {
            final Holder<Enchantment> holder = e.getKey();
            final int have = current.getLevel(holder);
            final int offered = e.getIntValue();
            int level = have == offered ? offered + 1 : Math.max(offered, have);
            if (!gear.supportsEnchantment(holder)) {
                return reject(Reason.NOT_SUPPORTED, before);
            }
            for (final Holder<Enchantment> other : current.keySet()) {
                if (!other.equals(holder) && !Enchantment.areCompatible(holder, other)) {
                    return reject(Reason.CONFLICT, before);
                }
            }
            final int max = holder.value().getMaxLevel();
            if (level > max) {
                level = max;
            }
            current.set(holder, level);
        }
        final ItemEnchantments after = current.toImmutable();
        if (sameEnchantments(before, after)) {
            return new Result(Reason.NO_CHANGE, ItemStack.EMPTY, before, after, 0);
        }
        final ItemStack out = gear.copy();
        EnchantmentHelper.setEnchantments(out, after);
        return new Result(Reason.OK, out, before, after, bookLevels * Math.max(0, policy.lapisPerLevel()));
    }

    /** Same enchantments at the same levels (tooltip visibility is not an enchantment). */
    public static boolean sameEnchantments(final ItemEnchantments a, final ItemEnchantments b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (final Object2IntMap.Entry<Holder<Enchantment>> e : a.entrySet()) {
            if (b.getLevel(e.getKey()) != e.getIntValue()) {
                return false;
            }
        }
        return true;
    }

    /** "minecraft:sharpness 3, minecraft:unbreaking 2" - for logs. */
    public static String describe(final ItemEnchantments enchantments) {
        final StringBuilder sb = new StringBuilder();
        for (final Object2IntMap.Entry<Holder<Enchantment>> e : enchantments.entrySet()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(e.getKey().getRegisteredName()).append(' ').append(e.getIntValue());
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static Result reject(final Reason reason, final ItemEnchantments before) {
        return new Result(reason, ItemStack.EMPTY, before, before, 0);
    }
}
