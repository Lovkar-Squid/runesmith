package me.lovkar.runesmith.colony;

import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.modules.settings.SettingKey;
import me.lovkar.runesmith.Runesmith;

/**
 * The hut's settings, all in one settings module (MineColonies consults only the first one on a
 * building). Saved by key: a setting added in a later version takes its default in older worlds.
 */
public final class RunesmithSettings {
    /** Armor may be enchanted (default on). */
    public static final ISettingKey<BoolSetting> ARMOR = key("armor");
    /** Weapons may be enchanted (default on). */
    public static final ISettingKey<BoolSetting> WEAPONS = key("weapons");
    /** Tools may be enchanted (default on). */
    public static final ISettingKey<BoolSetting> TOOLS = key("tools");
    /** A level N Runesmith uses only books whose enchantments are all at most level N (default on). */
    public static final ISettingKey<BoolSetting> LEVEL_CAP = key("levelcap");
    /** Gear that already carries an enchantment is left alone (default off). */
    public static final ISettingKey<BoolSetting> ONLY_UNENCHANTED = key("onlyunenchanted");
    /** Each book also costs lapis lazuli, one per level of the book (default on). */
    public static final ISettingKey<BoolSetting> LAPIS = key("lapis");

    private RunesmithSettings() {
    }

    private static ISettingKey<BoolSetting> key(final String path) {
        return new SettingKey<>(BoolSetting.class, Runesmith.id(path));
    }
}
