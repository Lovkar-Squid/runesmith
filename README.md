# Runesmith

*A MineColonies profession that puts the Enchanter's books to work.*

> **Work in progress.** Nothing here is released yet.

MineColonies' Enchanter turns Ancient Tomes into enchanted books, but nothing in the colony ever
uses them: you have to carry each book to an anvil yourself. Runesmith adds one building and one
worker who does that job for you. The worker takes enchanted books from the colony's stock and
applies them to armor, weapons and tools by the vanilla anvil rules, at no XP cost.

Made by **Lovkar & Claude** for NeoForge 1.21.1 and MineColonies.

## How to use it

1. Build the **Runesmith** hut (crafted from planks, a build tool and an anvil) and hire a worker.
2. Put the armor, weapons and tools you want enchanted into the hut's racks, plus some lapis
   lazuli.
3. The Runesmith asks the colony for enchanted books, and couriers bring them from the warehouse.
   You can also put books into the racks yourself.
4. When the anvil would accept a book for a piece, the Runesmith applies it. The enchanted piece
   stays in the same rack slot until you take it.

## Rules

- A book goes only on gear the vanilla anvil would accept it for. The item type must be right, and
  no enchantment may conflict with one already on the piece.
- Levels follow the anvil: two equal levels make one level higher, otherwise the higher level
  wins, never above the enchantment's maximum.
- A book is used whole or not at all. If one of its enchantments cannot go on, the book stays.
  (The anvil would apply the part that fits.)
- A book is spent only if the piece actually changes. A book lower than what the piece already
  has stays in the colony's stock.
- Curses are never applied.
- The price is the book plus lapis lazuli, one per level of the book. There is no XP cost, no
  rising repair cost, no renaming and no repairing.
- Runesmith does not post NeoForge's `AnvilUpdateEvent`. It follows the plain vanilla rules even
  where another mod changes what an anvil does. This is deliberate: it keeps the colony's
  behaviour predictable.
- Every action is written to the log with a `[Runesmith]` prefix.

## Settings (in the hut)

| Setting | Default | What it does |
| --- | --- | --- |
| Enchant armor / weapons / tools | on | Which kinds of gear the Runesmith may work on. An axe counts as a weapon and as a tool. |
| Book level limited by building level | on | A level N Runesmith uses only books of level N or lower. Stronger books stay in stock until the building grows. Two level II books can still make level III. |
| Only unenchanted gear | off | Gear that already carries any enchantment is left alone. |
| Lapis lazuli per book level | on | Each book also costs lapis lazuli, one per level. |

Keep the books you want to save in your own chests, not in the warehouse: the Runesmith may use
any enchanted book the colony has.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
