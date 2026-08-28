# Rainbow Inventory Tags

A small companion plugin for RuneLite's built-in **Inventory Tags** plugin.

Inventory Tags lets you color-code items (shift + right-click an item -> Examine -> Inventory
tag). The [Rainbow Rave](https://github.com/geheur/rainbow-rave) plugin hub plugin can make
Inventory Tags items rainbow too, but its "Inventory tags" setting is all-or-nothing: "Same"
makes *every* tagged item rainbow regardless of which color you gave it, which stomps on any
color-coding you've set up (e.g. blue = keep, red = sell).

Rainbow Inventory Tags instead lets you choose, per tag color, whether that group should be rainbow
or keep its normal static color. Your blue and red groups can stay exactly as they are while only
the group(s) you pick cycle through the rainbow.

## Usage

1. Enable RuneLite's built-in **Inventory Tags** plugin (search for it in the plugin list) and
   tag some items as usual: shift + right-click an item in your inventory -> Examine -> Inventory
   tag -> pick a color (or "Pick" for a custom one).
2. Enable **Rainbow Inventory Tags**.
3. Shift + right-click a tagged item -> Examine again. You'll now also see a **Rainbow tags**
   submenu next to the vanilla "Inventory tag" one. Open it.
4. Click **Rainbow tag** (shown in that item's tag color). Every item tagged with that exact
   color - one item or a whole group - now cycles through the rainbow. Click **Remove rainbow**
   the same way to turn it back into a static color.

Untagged items, and tagged items you haven't marked as rainbow, are left completely alone.

### The "Rainbow tags" submenu

- **Tag + rainbow this item** - only shown on untagged items. Assigns a fresh color and marks it
  rainbow in one click, instead of tagging it first and toggling rainbow on separately.
- **Rainbow tag / Remove rainbow** - shown on already-tagged items. Toggles the rainbow effect
  for every item sharing that item's exact tag color.
- **Set speed for this group** - only shown once a group is rainbow. Opens a chatbox prompt to
  give that specific color group its own cycle speed (in ms), overriding the global default.
- **Reset speed for this group** - only shown once a group has a custom speed. Removes the
  override so it goes back to using the global default.
- **Rainbow all tag groups** / **Remove rainbow from all groups** - bulk actions that apply to
  every tag color you've ever used, regardless of which item you opened the menu on.

### Settings

- **Color speed (ms)** - the default cycle speed for rainbow groups that don't have their own
  override set via "Set speed for this group".
- **Sync all rainbow groups** - off by default, so each rainbow-enabled color group cycles out
  of phase with the others; turn on to make every rainbow group flash in lockstep instead.
- **Animation style** - `RAINBOW` (default) cycles full-saturation, full-brightness hues.
  `PASTEL_PULSE` cycles softer, lower-saturation colors with a gentle brightness pulse instead of
  a hard color-wheel spin.
- **Show tags in bank** - on by default. Inventory Tags itself never draws tag colors on items
  sitting in bank storage, static or rainbow. With this on, this plugin shows both there too, so
  the bank matches how your tagged items look everywhere else. Turn it off for a plain bank with
  no tag colors at all (rainbow or static) - the rainbow effect will still show normally in your
  inventory and equipment either way, this setting only affects the bank.
- **Shuffle animation** - off by default. Gives every item in a rainbow group its own slightly
  randomized color offset instead of the whole group pulsing in the same neat phase-locked
  pattern - more chaotic and festive, less like a synchronized light show. Purely cosmetic; it
  doesn't change which items are rainbow.

The outline/fill/underline style and fill opacity used for tags (rainbow or not) come from
Inventory Tags' own settings, so both plugins always look consistent with each other.

## How it works

This plugin doesn't reimplement tagging - it depends on and reads the same data RuneLite's
Inventory Tags plugin already stores per item ID, and draws on top of its overlay only for the
color groups you've flagged as rainbow. Nothing is stored about *which items* are rainbow,
only which *tag colors* are - so the effect automatically follows the group as you tag or
untag items with that color.

## Installing

This isn't published on the official Plugin Hub. To run it yourself:

```
git clone <this repo>
cd rainbow-inventory-tags
./gradlew run
```

That launches a development copy of the RuneLite client with the plugin already loaded (you'll
need a Jagex account linked for the dev client - see
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)).

## License

BSD 2-Clause, see [LICENSE](LICENSE). Not affiliated with or endorsed by RuneLite or Jagex.

Some render/caching logic and the tag-color storage format are structurally based on RuneLite's
built-in Inventory Tags plugin and on [Rainbow Rave](https://github.com/geheur/rainbow-rave),
both BSD 2-Clause - their original copyright notices are retained in the affected source files
(`RainbowInventoryTagsOverlay.java`, `RainbowInventoryTagsPlugin.java`).

## Changelog

- Fixed: the fill-color cache didn't account for Inventory Tags' "Fill opacity" setting, so
  changing that slider could leave already-cached items showing the old opacity for a while.
- Fixed: rainbow speeds faster than ~1.2s/cycle didn't actually speed up past that floor, due to
  a rounding edge case in the color-step math.
- Cached the rainbow on/off and effective-speed lookups per tag color, instead of re-reading
  config on every render frame for every rainbow-tagged item.
- Caches now clear on plugin startup instead of only invalidating reactively, closing a narrow
  staleness window across disable/re-enable cycles.
- Old rainbow/speed settings for tag colors that are no longer in use get cleaned up
  automatically, instead of accumulating forever and potentially resurfacing if that exact color
  is ever reused.
- Added shuffle animation mode - see Settings above.
- Fixed: rainbow speeds that weren't an exact multiple of ~1.2s could quietly run shorter than
  requested (e.g. 2000ms actually cycling at 1200ms), because the cycle length was reconstructed
  from a rounded step count instead of timed against the exact requested speed. Timing and the
  number of distinct colors per cycle are now computed independently, so every speed runs at
  exactly the length you set.
- Lowered the number of distinct colors per rainbow cycle (60 -> 30) to reduce pressure on
  RuneLite's shared, size-limited item outline cache - "Shuffle animation" in particular can have
  several copies of the same item showing different colors at once, which multiplies how many
  outline entries are needed simultaneously.
- Removed the loot-pickup flash/chat alert added in a previous version - it wasn't reliable
  enough to be worth keeping.
