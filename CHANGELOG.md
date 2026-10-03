# Changelog

## [0.1.0-beta.1]

### Added
- **The Runesmith**: a MineColonies hut with one worker who applies enchanted books to armor,
  weapons and tools by the vanilla anvil rules, without the XP.
  - A book goes only where the anvil would accept it, and it is used whole or not at all.
  - A book that would change nothing stays in stock.
  - Curses are never applied.
- **Price**: the book plus lapis lazuli, one per level of the book. A setting turns the lapis off.
- **Gear from three places**:
  - the hut's racks;
  - the colonists, guards first: the armor they wear and the weapon or tool they hold. A tool is
    left alone when the enchantment would make it too good for the worker's hut, so he never puts
    it down;
  - the warehouse (off by default): one piece at a time, borrowed and put back where it was.
- **Books** come from the colony: the hut asks the warehouse for the books its gear can take (one
  enchantment each, as the Enchanter makes them), and couriers bring them. Empty or useless books are
  never asked for.
- **Finished gear goes to the warehouse** (setting, on by default): a piece the Runesmith has
  enchanted in his racks, which no book in stock has improved for two minutes, is carried to the
  warehouse by a courier, where colonists can ask for it.
- **Settings in the hut**:
  - armor, weapons, tools;
  - book level limited by the building's level;
  - only unenchanted gear;
  - lapis;
  - colonists;
  - warehouse;
  - send finished gear to the warehouse.
- **The building in three looks**: Forge Hall, Rune Tower and Crystal Heart, five levels each, as
  alternatives in the bundled *Runesmith* structure pack. Every level of a look keeps one footprint.
- **The hut block**: a small anvil with an enchanted book on a plinth of glowing runes, with an
  amethyst cluster. Drawn from vanilla textures.
- **Idle**: with nothing to do, the Runesmith potters about his hut instead of standing still.
- **Logging**: every action is one `[Runesmith]` line in the log. A pair of gear and book that cannot
  go together is logged once, and again only when a setting or the building's level changes.
