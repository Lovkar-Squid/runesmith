package me.lovkar.runesmith.colony;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.modules.SettingsModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.moduleviews.SettingsModuleView;
import com.minecolonies.core.colony.buildings.moduleviews.WorkerBuildingModuleView;
import me.lovkar.runesmith.Runesmith;

/** Building modules of the Runesmith's hut. Module ids are global in MineColonies: all start with runesmith_. */
public final class RunesmithModules {

    /** One Runesmith to a hut, who comes to work from home. Knowledge first, Mana second. */
    public static final BuildingEntry.ModuleProducer<WorkerBuildingModule, WorkerBuildingModuleView> WORK =
            new BuildingEntry.ModuleProducer<>("runesmith_work",
                    () -> new WorkerBuildingModule(Runesmith.JOB.get(), Skill.Knowledge, Skill.Mana, false, b -> 1),
                    () -> WorkerBuildingModuleView::new);

    /** Every setting of the hut, in this one module. */
    public static final BuildingEntry.ModuleProducer<SettingsModule, SettingsModuleView> SETTINGS =
            new BuildingEntry.ModuleProducer<>("runesmith_settings",
                    () -> (SettingsModule) new SettingsModule()
                            .with(RunesmithSettings.ARMOR, new BoolSetting(true))
                            .with(RunesmithSettings.WEAPONS, new BoolSetting(true))
                            .with(RunesmithSettings.TOOLS, new BoolSetting(true))
                            .with(RunesmithSettings.LEVEL_CAP, new BoolSetting(true))
                            .with(RunesmithSettings.ONLY_UNENCHANTED, new BoolSetting(false))
                            .with(RunesmithSettings.LAPIS, new BoolSetting(true))
                            .with(RunesmithSettings.COLONISTS, new BoolSetting(true))
                            // off by default: the warehouse holds what the player is keeping
                            .with(RunesmithSettings.WAREHOUSE, new BoolSetting(false)),
                    () -> SettingsModuleView::new);

    private RunesmithModules() {
    }
}
