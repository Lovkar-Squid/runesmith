package me.lovkar.runesmith.compat;

import com.minecolonies.api.sounds.EventType;
import com.minecolonies.api.sounds.ModSoundEvents;
import com.minecolonies.api.util.Tuple;
import me.lovkar.runesmith.Runesmith;
import net.minecraft.sounds.SoundEvent;

import java.util.List;
import java.util.Map;

/**
 * MineColonies looks citizen voice lines up by job path and throws a NullPointerException on the
 * first line of a job that has no entry. The Runesmith borrows the Enchanter's voice.
 */
public final class Voices {
    private Voices() {
    }

    public static void lend() {
        final Map<String, Map<EventType, List<Tuple<SoundEvent, SoundEvent>>>> voices = ModSoundEvents.CITIZEN_SOUND_EVENTS;
        Map<EventType, List<Tuple<SoundEvent, SoundEvent>>> lines = voices.get("enchanter");
        if (lines == null) {
            lines = voices.get("unemployed");
        }
        if (lines == null) {
            Runesmith.LOGGER.warn("[Runesmith] no citizen voice lines found to lend to the Runesmith");
            return;
        }
        voices.put(Runesmith.JOB_ID.getPath(), lines);
        Runesmith.LOGGER.info("[Runesmith] the Runesmith speaks with the Enchanter's voice lines");
    }
}
