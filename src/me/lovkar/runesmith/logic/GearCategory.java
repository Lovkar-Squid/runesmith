package me.lovkar.runesmith.logic;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * The gear classes a Runesmith may work on, by the vanilla enchantable item tags, so other mods'
 * gear joins in through the same datapack tags vanilla uses. An axe is both a weapon and a tool.
 * Anything else (elytra, shield, flint and steel, brush, pumpkins, heads, ...) is not gear here.
 */
public enum GearCategory {
    ARMOR,
    WEAPON,
    TOOL;

    /** The categories this stack belongs to; empty when it is not gear at all. */
    public static Set<GearCategory> of(final ItemStack stack) {
        final Set<GearCategory> out = EnumSet.noneOf(GearCategory.class);
        if (stack.isEmpty()) {
            return out;
        }
        if (stack.is(ItemTags.ARMOR_ENCHANTABLE)) {
            out.add(ARMOR);
        }
        if (stack.is(ItemTags.WEAPON_ENCHANTABLE) || stack.is(ItemTags.BOW_ENCHANTABLE)
                || stack.is(ItemTags.CROSSBOW_ENCHANTABLE) || stack.is(ItemTags.TRIDENT_ENCHANTABLE)) {
            out.add(WEAPON);
        }
        if (stack.is(ItemTags.MINING_ENCHANTABLE) || stack.is(ItemTags.FISHING_ENCHANTABLE)) {
            out.add(TOOL);
        }
        return out;
    }
}
