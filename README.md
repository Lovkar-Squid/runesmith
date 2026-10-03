# Runesmith

*A MineColonies profession that puts the Enchanter's books to work.*

> **Work in progress.** Nothing here is released yet.

MineColonies' Enchanter turns Ancient Tomes into enchanted books, but nothing in the colony ever
uses them: you have to carry each book to an anvil yourself. Runesmith adds one building and one
worker who does that job for you. The worker takes enchanted books from the colony's stock and
applies them to armor, weapons and tools by the vanilla anvil rules, at no XP cost.

Made by **Lovkar & Claude** for NeoForge 1.21.1 and MineColonies.

## Rules

- A book goes only on gear the vanilla anvil would accept it for. The item type must be right, and
  no enchantment may conflict with one already on the piece.
- Levels follow the anvil: two equal levels make one level higher, otherwise the higher level
  wins, never above the enchantment's maximum.
- A book is spent only if the piece actually changed. Anything else is left alone, and the book
  stays.
- Every action is written to the log with a `[Runesmith]` prefix.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
