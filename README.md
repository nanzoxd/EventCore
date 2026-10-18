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

## Millionaire event
`/eventcore start millionaire` makes every gold ore (GOLD_ORE, DEEPSLATE_GOLD_ORE, NETHER_GOLD_ORE) drop extra gold on top of its normal drop:
`settings.extra-min` to `extra-max` more raw gold (or gold nuggets from nether gold ore), `settings.chance` percent per ore, `settings.blocks` lists the ores.
Silk touch gets no bonus (the ore block itself drops).

## DuoDeck
The former Elo Buff event is now called DuoDeck (`display-name`). Its internal id stays `elo_buff`, so config, scheduler and saved data keep working.

## Tab completion
`/eventcore start|force|stop|rig <tab>` suggests exactly one name per event (its display name as one word: `golden_tree`, `duodeck`, `millionaire`).
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
