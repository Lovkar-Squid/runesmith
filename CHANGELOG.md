# Changelog

## [Unreleased]

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
- **Books** come from the colony: the hut asks the warehouse for enchanted books, and couriers bring
  them.
- **Settings in the hut**:
  - armor, weapons, tools;
  - book level limited by the building's level;
  - only unenchanted gear;
  - lapis;
  - colonists;
  - warehouse.
- **The building in three looks**: Forge Hall, Rune Tower and Crystal Heart, five levels each, as
  alternatives in the bundled *Runesmith* structure pack. Every level of a look keeps one footprint.
- **The hut block**: a small anvil with an enchanted book on a plinth of glowing runes, with an
  amethyst cluster. Drawn from vanilla textures.
- **Logging**: every action is one `[Runesmith]` line in the log. A pair of gear and book that cannot
  go together is logged once, and again only when a setting or the building's level changes.
