package me.lovkar.runesmith.compat;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What a colonist wears and holds, read and replaced through MineColonies' own citizen inventory
 * (armor lives in its own armor inventory, the held tool in a slot of the main one), and which
 * colonists the Runesmith may visit at all.
 */
public final class Citizens {

    /** A piece a colonist wears or holds. */
    public enum Place {
        HEAD(EquipmentSlot.HEAD),
        CHEST(EquipmentSlot.CHEST),
        LEGS(EquipmentSlot.LEGS),
        FEET(EquipmentSlot.FEET),
        HAND(EquipmentSlot.MAINHAND);

        public final EquipmentSlot slot;

        Place(final EquipmentSlot slot) {
            this.slot = slot;
        }
    }

    private Citizens() {
    }

    /**
     * Colonists the Runesmith may visit: with a loaded, living entity, not children, not the
     * Runesmith himself. Guards first, then the nearest.
     */
    public static List<ICitizenData> visitable(final IColony colony, final ICitizenData self, final BlockPos from) {
        final List<ICitizenData> out = new ArrayList<>();
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            if (c.getId() == self.getId() || c.isChild()) {
                continue;
            }
            final Optional<AbstractEntityCitizen> e = c.getEntity();
            if (e.isPresent() && e.get().isAlive()) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing((ICitizenData c) -> !(c.getJob() instanceof AbstractJobGuard<?>))
                .thenComparingDouble(c -> c.getEntity().map(e -> e.blockPosition().distSqr(from)).orElse(Double.MAX_VALUE)));
        return out;
    }

    public static ICitizenData byId(final IColony colony, final int id) {
        return colony.getCitizenManager().getCivilian(id);
    }

    /** The piece at that place, as the citizen's inventory holds it. */
    public static ItemStack piece(final ICitizenData citizen, final Place place) {
        final InventoryCitizen inv = citizen.getInventory();
        return place == Place.HAND ? inv.getHeldItem(InteractionHand.MAIN_HAND) : inv.getArmorInSlot(place.slot);
    }

    /** The citizen's inventory and the citizen's entity both show this piece at that place. */
    public static boolean shows(final ICitizenData citizen, final Place place, final ItemStack expected) {
        if (!ItemStack.matches(piece(citizen, place), expected)) {
            return false;
        }
        final Optional<AbstractEntityCitizen> e = citizen.getEntity();
        return e.isEmpty() || ItemStack.matches(e.get().getItemBySlot(place.slot), expected);
    }

    /**
     * Puts the enchanted piece where the old one is, if the old one is still there. Armor is
     * cleared and set again, so the citizen takes off the old piece's attributes and puts on the
     * new one's. Returns whether inventory and entity both show the enchanted piece afterwards.
     */
    public static boolean replace(final ICitizenData citizen, final Place place, final ItemStack old, final ItemStack enchanted) {
        final InventoryCitizen inv = citizen.getInventory();
        if (place == Place.HAND) {
            final int slot = inv.getHeldItemSlot(InteractionHand.MAIN_HAND);
            if (slot < 0 || slot >= inv.getSlots() || !ItemStack.matches(inv.getStackInSlot(slot), old)) {
                return false;
            }
            inv.setStackInSlot(slot, enchanted.copy());
        } else {
            if (!ItemStack.matches(inv.getArmorInSlot(place.slot), old)) {
                return false;
            }
            inv.forceClearArmorInSlot(place.slot, old);
            inv.forceArmorStackToSlot(place.slot, enchanted.copy());
        }
        return shows(citizen, place, enchanted);
    }
}
