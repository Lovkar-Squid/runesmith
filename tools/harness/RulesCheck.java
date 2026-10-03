package me.lovkar.runesmithtest;

import me.lovkar.runesmith.logic.EnchantApplier;
import me.lovkar.runesmith.logic.EnchantApplier.Reason;
import me.lovkar.runesmith.logic.RunesmithPolicy;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Hand-written cases for the rules the anvil oracle does not compare, because they differ from the
 * anvil on purpose: multi-enchantment books (all or nothing), curses, the level cap, categories,
 * "only unenchanted", the lapis price, and the inputs staying untouched.
 */
final class RulesCheck {
    interface Checker {
        void check(String name, boolean ok, String detail);
    }

    private final ServerLevel level;
    private final Checker checker;

    RulesCheck(final ServerLevel level, final Checker checker) {
        this.level = level;
        this.checker = checker;
    }

    private Holder<Enchantment> e(final ResourceKey<Enchantment> key) {
        return level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key);
    }

    private ItemStack book(final Object... pairs) {
        final ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            final ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) pairs[i];
            m.set(e(key), (Integer) pairs[i + 1]);
        }
        final ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
        b.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
        return b;
    }

    private ItemStack gear(final net.minecraft.world.item.Item item, final Object... pairs) {
        final ItemStack s = new ItemStack(item);
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            final ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) pairs[i];
            s.enchant(e(key), (Integer) pairs[i + 1]);
        }
        return s;
    }

    private void expect(final String name, final ItemStack gear, final ItemStack book, final RunesmithPolicy policy, final Reason expected) {
        final ItemStack gearCopy = gear.copy();
        final ItemStack bookCopy = book.copy();
        final EnchantApplier.Result r = EnchantApplier.apply(gear, book, policy);
        final boolean untouched = ItemStack.matches(gear, gearCopy) && ItemStack.matches(book, bookCopy);
        checker.check("rules " + name, r.reason() == expected && untouched,
                "expected " + expected + ", got " + r.reason() + " " + EnchantApplier.describe(r.after())
                        + (untouched ? "" : " - INPUT MODIFIED"));
    }

    void run() {
        final RunesmithPolicy open = RunesmithPolicy.OPEN;
        final RunesmithPolicy noWeapons = new RunesmithPolicy(true, false, true, 0, false, 0);
        final RunesmithPolicy capTwo = new RunesmithPolicy(true, true, true, 2, false, 0);
        final RunesmithPolicy fresh = new RunesmithPolicy(true, true, true, 0, true, 0);
        final RunesmithPolicy lapis = new RunesmithPolicy(true, true, true, 0, false, 1);

        expect("plain book is not a book", gear(Items.IRON_SWORD), new ItemStack(Items.BOOK), open, Reason.NOT_A_BOOK);
        expect("empty enchanted book", gear(Items.IRON_SWORD), new ItemStack(Items.ENCHANTED_BOOK), open, Reason.EMPTY_BOOK);
        expect("stick is not gear", new ItemStack(Items.STICK), book(Enchantments.SHARPNESS, 1), open, Reason.NOT_GEAR);
        expect("elytra is not gear in 1.0", new ItemStack(Items.ELYTRA), book(Enchantments.MENDING, 1), open, Reason.NOT_GEAR);
        expect("two swords in one stack", new ItemStack(Items.IRON_SWORD, 2), book(Enchantments.SHARPNESS, 1), open, Reason.NOT_GEAR);
        expect("weapons switched off", gear(Items.IRON_SWORD), book(Enchantments.SHARPNESS, 1), noWeapons, Reason.CATEGORY_DISABLED);
        expect("an axe is still a tool", gear(Items.IRON_AXE), book(Enchantments.EFFICIENCY, 1), noWeapons, Reason.OK);
        expect("only unenchanted: enchanted sword", gear(Items.IRON_SWORD, Enchantments.UNBREAKING, 1), book(Enchantments.SHARPNESS, 1), fresh,
                Reason.ALREADY_ENCHANTED);
        expect("only unenchanted: plain sword", gear(Items.IRON_SWORD), book(Enchantments.SHARPNESS, 1), fresh, Reason.OK);
        expect("curse is never used", gear(Items.IRON_SWORD), book(Enchantments.VANISHING_CURSE, 1), open, Reason.CURSE);
        expect("level cap 2 refuses a III book", gear(Items.IRON_SWORD), book(Enchantments.SHARPNESS, 3), capTwo, Reason.ABOVE_LEVEL_CAP);
        expect("level cap 2 takes a II book", gear(Items.IRON_SWORD), book(Enchantments.SHARPNESS, 2), capTwo, Reason.OK);
        expect("cap is on the book: II + II under cap 2", gear(Items.IRON_SWORD, Enchantments.SHARPNESS, 2), book(Enchantments.SHARPNESS, 2),
                capTwo, Reason.OK);
        expect("multi book, one part conflicts: whole book refused", gear(Items.IRON_SWORD),
                book(Enchantments.SHARPNESS, 2, Enchantments.SMITE, 1), open, Reason.CONFLICT);
        expect("multi book, one part unsupported: whole book refused", gear(Items.IRON_SWORD),
                book(Enchantments.SHARPNESS, 1, Enchantments.EFFICIENCY, 1), open, Reason.NOT_SUPPORTED);
        expect("multi book, all parts fit", gear(Items.IRON_AXE), book(Enchantments.SHARPNESS, 1, Enchantments.EFFICIENCY, 1), open, Reason.OK);
        expect("lower book than the gear has", gear(Items.IRON_SWORD, Enchantments.SHARPNESS, 5), book(Enchantments.SHARPNESS, 3), open,
                Reason.NO_CHANGE);
        expect("V + V stays V", gear(Items.IRON_SWORD, Enchantments.SHARPNESS, 5), book(Enchantments.SHARPNESS, 5), open, Reason.NO_CHANGE);

        final EnchantApplier.Result priced = EnchantApplier.apply(gear(Items.IRON_SWORD), book(Enchantments.SHARPNESS, 3), lapis);
        checker.check("rules lapis: III book costs 3", priced.ok() && priced.lapis() == 3, priced.reason() + " lapis " + priced.lapis());
        final EnchantApplier.Result priced2 = EnchantApplier.apply(gear(Items.IRON_SWORD),
                book(Enchantments.SHARPNESS, 2, Enchantments.UNBREAKING, 3), lapis);
        checker.check("rules lapis: II + III book costs 5", priced2.ok() && priced2.lapis() == 5, priced2.reason() + " lapis " + priced2.lapis());
        final EnchantApplier.Result free = EnchantApplier.apply(gear(Items.IRON_SWORD), book(Enchantments.SHARPNESS, 3), open);
        checker.check("rules lapis off costs 0", free.ok() && free.lapis() == 0, free.reason() + " lapis " + free.lapis());
        final EnchantApplier.Result combined = EnchantApplier.apply(gear(Items.IRON_SWORD, Enchantments.SHARPNESS, 2), book(Enchantments.SHARPNESS, 2), open);
        checker.check("rules II + II = III", combined.ok()
                        && EnchantmentHelper.getEnchantmentsForCrafting(combined.gear()).getLevel(e(Enchantments.SHARPNESS)) == 3,
                EnchantApplier.describe(combined.after()));
    }
}
