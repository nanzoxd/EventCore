# EventCore

Paper plugin (Java 21, Paper API 1.21.x, Adventure/MiniMessage, no Skript). Folia-safe scheduling.

## Build
    mvn clean package
The jar is `target/EventCore-1.0.0.jar`. Drop it into `plugins/` and start the server.
It compiles against `paper-api 1.21.8`; the locator bar event needs a server that has that gamerule (1.21.6+),
everything else works on earlier 1.21.x servers that have the same API.

## Config files (plugins/EventCore/)
config.yml (palette, style, timezone, limits), messages.yml, events.yml (all events, scheduler, airdrop),
rituals.yml (Ritual Creator + particle layers), loot.yml (crate loot), gui.yml (GUI layouts).
Text supports MiniMessage, `{placeholders}` and `<g>` (palette gradient). Small caps are applied automatically
to template text (switch: `style.small-caps`). Missing keys are added from the defaults on start.

## Commands
/eventcore start <event|count> [minutes] | force <event> [minutes] | rig <event|clear> [minutes] | stop [event]
| list | reload | gui | schedule | airdrop spawn <tier>      (permission eventcore.admin or eventcore.<sub>)
/ritualcreator (/rcreator, /creator)                         (eventcore.ritual)
eventcore.bypass skips blocked commands during Locator Bar and shows real names to admins during Anonymous.

## Discord notifications
In config.yml set `discord.enabled: true` and paste your webhook URL under `discord.webhook-url`.
Sent for: event start/end, scheduled start warnings, lootdrop landed/opened, ritual start/complete/stop.
Each toggle, colour, the ping (`discord.mention`) and the server name are configurable.

## Loot editing
`/eventcore airdrop loot [tier]` opens the loot editor. Admins (eventcore.gui) can also **sneak + click a crate**, falling
or landed, to open the editor for that crate's tier. The editor shows the loot as real items in a locked chest. Left click raises an entry's chance by 5, right click lowers it by 5, shift-left / shift-right change the amount by 1, Q removes the entry. Add an item by clicking an empty slot with it on your cursor or shift-clicking it in your inventory. The bottom row goes back, saves, or adds a command reward. Closing the editor saves too.

## Elo Buff event
`/eventcore start elobuff` multiplies every Elo gain (kills and playtime) by `settings.multiplier` (default 1.5) while it runs.
It needs the patched ReliqueOnlineAPI (ReliqueSmpStats-patched.zip: adds `getStatsManager()` and `setEloGainMultiplier`).
Losses are not changed. The multiplier goes back to normal when the event ends or the server stops.

## Gold Frenzy event
(formerly Millionaire; the aliases `millionaire` and `mrbeast` still work) `/eventcore start gold_frenzy` makes every gold ore (GOLD_ORE, DEEPSLATE_GOLD_ORE, NETHER_GOLD_ORE) drop extra gold on top of its normal drop:
`settings.extra-min` to `extra-max` more raw gold (or gold nuggets from nether gold ore), `settings.chance` percent per ore, `settings.blocks` lists the ores.
Silk touch gets no bonus (the ore block itself drops).

## DuoDeck
The former Elo Buff event is now called DuoDeck (`display-name`). Its internal id stays `elo_buff`, so config, scheduler and saved data keep working.

## Tab completion
`/eventcore start|force|stop|rig <tab>` suggests exactly one name per event (its display name as one word: `golden_tree`, `duodeck`, `gold_frenzy`).
Ids and aliases still work when typed, they are just not suggested.

## Event display
`event-display` in config.yml (or the Events page in `/eventcore gui`) sets how running events show: BOSSBAR, ACTIONBAR or BOTH.
Actionbar and bossbar read "Golden Tree : 01:00" in small caps. Each event has its own bossbar colour, style and gradient in events.yml.

## Colours
Everything uses only the two palettes in config.yml: peach (#fffbf0 #fff1e0 #ffe4d5 #ffd7ca #ffcfca) and gold (#d8b04a #e8c670 #f1d58f #f7e2ad #fff0cc).
Titles, chat, bossbars, holograms and the GUIs follow `palette`, `theme`, `bossbar-gradient` and the per event gradients in events.yml.
The `[EventCore]` label (chat prefix and /eventcore gui title) is the grey gradient #808080 #C4C4C4 #808080 (`eventcore-gradient`, messages.yml `prefix`).
Chat, titles and messages use only the palette (config.yml `palette`, `style.text-color`). Any red/green/gray colour tag in a
message is flattened to `style.text-color` (`style.flatten-colors: false` turns that off). Bossbars use `bossbar-gradient`.

## Notes on behaviour
* `rig` queues an event: the next `/eventcore start <count>` roll (or random pick) takes rigged events first.
* The airdrop code is displayed (bossbar, actionbar, title, chat) and on the crate hologram; it is not an input.
* Crates are invulnerable at all times; hits are counted as virtual HP, so explosions can never break them.
* Ritual: the sigil, GUI and bossbar follow RitualCreator.sk: a circle (radius 9) with a hexagram (two triangles, radius 4.5) and ambient particles falling above it,
  drawn at `particles.height` above the ritual spot (2.325 like the script, lower it to put it on the ground). The nether star in the ritual menu cycles `particles.cycle`
  (gold, peach); add more palettes under `particles.palettes`. The bossbar shows "item Ritual | time [x, y, z]" and drains like the script.
  Finishing shows "<item> ritual completed" as the title. Adventure bossbars have no id.
* Reloading while a ritual runs keeps it running and warns admins, like the original script.
* `/eventcore gui` lists every key of every file with a widget for its type, so every setting is editable in-game.

## Updating
Delete the old `plugins/EventCore/` config files (config, events, messages, gui, rituals) once so the new defaults are written.

## Known limits
* Anonymous skin uses Paper's profile API with the texture link in events.yml (settings.skin.url); check that skin loads on your server.
* Scoreboard teams (glow colours, hidden nametags) are limited on Folia; failures are logged once.
* This source was written without access to a compiler or Paper API in the authoring environment: it has not been
  built or run. Expect to fix a few compile errors on the first `mvn package`.

## Lootdrop flow
Start -> `Lootdrop Incoming | 30 Seconds` (`spawn.incoming-seconds`) -> `Lootdrop Falling | 25 Minutes` (`fall.duration-minutes`),
spawned at least 100 blocks above the surface (`fall.min-height`), smooth descent, explosion on landing, low-pitched elytra sound next to the landed crate
(`land.ambient-sound`). The event runs at least incoming + fall + 5 minutes. Hitting a crate announces each new hitter in chat; when it opens the top 3 hitters are listed (`hit.top-count`).

## Late joiners
Players who join while an event runs get its bossbar and a one-time chat explanation (`events.<id>.messages.join`, header in messages.yml `event-join`). Nothing restarts.

## Ritual palettes
gold, peach, fire, pink, blue, red, white (nether star in /ritualcreator cycles through every palette in rituals.yml).

## Altars (custom crafting)

`altars.yml` + `/altar` (permission `eventcore.altar`, players need `eventcore.altar.use`, default true).

- `/altar recipes` opens the recipe GUI: **new recipe** -> put the ingredients in the 3x3 grid, the result in the output slot,
  set the ritual minutes (clock, default 30) and press **save**. Left-click a recipe to edit, shift + right-click to delete.
- `/altar spawn <preset>` builds an altar (presets and their block layout are in `altars.yml` -> `presets`). Right-click the
  centre block to craft: ingredients in the 3x3 grid, click the output to start the ritual. The output drops on the altar when it ends.
- `/altar spawn <recipe id>` builds an altar that crafts only that recipe (one altar per output, e.g. `/altar spawn nether_sword`);
  recipe ids tab-complete. `/altar spawn [preset]` still places the all-recipes altar. `/altar spawn` prints what the altar crafts.
- The pedestal shows the recipe's output item rotating above it (no glow), before the ritual too (`display.idle`). The hologram shows
  the output name (its own gradient/colours, bold if it has no custom name) with a line under it (`hologram.underline`), then one
  ingredient per line as `[4] Netherite Ingot`.
- Recipe editor (`/altar recipes`, `/altar recipe edit <id>`): put the ingredients in the 3x3 grid and the result in the right slot,
  click an item to take it out, stack size = amount, +/- buttons for minutes. Placed items are returned when you close it.
- `/altar bind <preset> <recipe|none>` makes a preset craft one recipe; the hologram above the altar then shows that recipe.
- `/altar remove [id]`, `/altar list`, `/altar stop [id|all]`, `/altar reload`.
- **Coordinate reveal:** the ritual bossbar/chat/title show the altar's coordinates one number at a time:
  X when it starts, Y after 10 min, Z after 15 min (`ritual.reveal-seconds`, scaled for other ritual lengths).
  Hidden numbers are obfuscated filler digits, the real value is never sent early.
- Altar blocks are protected from breaking and explosions; running rituals survive restarts.

## Altar crafting (hologram style)
Right-click the altar (the centre block, a structure block in LOAD mode; existing altars need /altar remove + /altar spawn to change) with the ingredients in your inventory. The hologram above it lists every recipe
(`altars.yml` preset `recipe:` binds a preset to one recipe, empty = all). The first recipe you have all items for starts a
ritual: the ingredients are taken, the output floats above the pedestal, the boss bar counts down and the coordinates are
revealed one number at a time (`ritual.reveal-seconds`). When the ritual ends the item drops above the pedestal and the altar
vanishes after `ritual.remove-after-seconds` (`ritual.remove-altar: false` keeps it).
Recipes live in `plugins/EventCore/altar-recipes.yml` (any input / output items, custom names, model data, enchants) or in game:
`/altar recipe create <id> [minutes]`, `/altar recipe output <id>` (item in hand), `/altar recipe input <id> [amount]` (exact item in hand)
or `/altar recipe input <id> <MATERIAL> <amount>`, `/altar recipe clear|minutes|delete|list`. `/altar reload` re-reads the file.

## Player Hunt
`/playerhunt <player1> <player2> ...` (alias `/phunt`, permission `eventcore.playerhunt`) hunts any number of online players at once.
Every target gets a bossbar with the name and live coordinates (`hunt.bossbar`), and one chat update with everybody's coordinates goes out every
`playerhunt.broadcast-seconds` (30). `/playerhunt stop [player|all]`, `/playerhunt list`. Targets who log out show "offline" and resume on rejoin.

## Bounty Hunt
`/bountyadd <player>` (alias `/bounty`, permission `eventcore.bounty`) opens the GUI: put ANY item in the middle slot (enchanted, custom named, shulkers, books...
it is stored exactly as placed), set the survival timer with the clock (left +, right -, shift x5) and press start.
* Bossbar: target, reward, live coordinates and time left. Coordinates in chat every `bounty.broadcast-seconds` (30).
* Target survives the timer: the target gets the reward. Another player kills the target: the killer gets it. Victory / defeat titles go to both.
* Target offline longer than `bounty.disconnect.grace-seconds` (120): the bounty moves to a random eligible online player (not creative/spectator,
  not `eventcore.bounty.exempt`); with no one eligible, or `action: CANCEL`, the reward goes back to its creator.
* Rewards leave a bounty through one guarded step (atomic done flag, one file write), then sit in a pending list in `bounties.yml` until they are in the winner's
  inventory, so they are never paid twice and never lost to an offline player or a restart. A full inventory drops the item at the winner's feet, owned by them, never despawning.
* Active bounties and pending rewards are saved in `plugins/EventCore/bounties.yml` (on every change, every 10 s and on shutdown).
* `/bountystop [player|all]` and `/bountylist`. Settings: `bounty:` in config.yml, texts: `bounty:` in messages.yml, GUI: `bounty-gui:` in gui.yml.
* Discord: no new webhook. `discord.notify.hunt-start` / `bounty-start` (off by default) would reuse your webhook and the existing `event` thumbnail.

## Admin GUI
`/eventcore gui` now has: Events, Airdrop, Loot tables, Ritual creator, Ritual settings, Altar recipes, Config editor, Player hunt, Bounty, Reload.
The Config editor lists every key of every yml file with a widget for its type (toggle, steppers, type in chat, list add/remove/YAML).
Every tooltip shows controls as mouse icons: a lit half of `▆ ▆` is the pressed button (colours in config.yml `lore.mouse`).

## Ritual scheduling and IST
All EventCore times use `timezone` in config.yml, now `Asia/Kolkata` (IST). On first start the plugin switches an existing config to IST once, so event schedules in events.yml are read as IST from then on.
`/ritual schedule` opens the scheduler: date, hour and minute (IST), the ritual item in the middle slot, ritual length, and a button that sets the location to where you stand.
`/ritual list` and `/ritual cancel <id>` (returns the item). A schedule waits if another ritual is running at its time. Saved in `data/ritual-schedule.yml`, so it survives restarts. `/ritual` alone opens the Ritual Creator.
