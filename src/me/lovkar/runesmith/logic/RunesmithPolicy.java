package me.lovkar.runesmith.logic;

/**
 * What the colony allows the Runesmith to do, read once per decision from the hut's settings.
 *
 * @param armor           armor may be enchanted
 * @param weapons         weapons may be enchanted (swords, axes, mace, bow, crossbow, trident)
 * @param tools           tools may be enchanted (axes, pickaxes, shovels, hoes, shears, fishing rod)
 * @param levelCap        0 = no cap; otherwise the highest enchantment level a usable book may carry
 * @param onlyUnenchanted gear that already carries any enchantment is left alone
 * @param lapisPerLevel   lapis lazuli spent per level on the book (0 = no lapis)
 */
public record RunesmithPolicy(boolean armor, boolean weapons, boolean tools, int levelCap, boolean onlyUnenchanted, int lapisPerLevel) {

    /** Everything allowed, no cap, no lapis: the anvil-oracle policy. */
    public static final RunesmithPolicy OPEN = new RunesmithPolicy(true, true, true, 0, false, 0);

    public boolean allows(final GearCategory category) {
        return switch (category) {
            case ARMOR -> armor;
            case WEAPON -> weapons;
            case TOOL -> tools;
        };
    }
}
