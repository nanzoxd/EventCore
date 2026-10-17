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

## Notes on behaviour
* `rig` queues an event: the next `/eventcore start <count>` roll (or random pick) takes rigged events first.
* The airdrop code is displayed (bossbar, actionbar, title, chat) and on the crate hologram; it is not an input.
* Crates are invulnerable at all times; hits are counted as virtual HP, so explosions can never break them.
* Ritual: the bossbar colour "gold" of the original maps to YELLOW (Adventure has no gold). Adventure bossbars have no id.
* Reloading while a ritual runs keeps it running and warns admins, like the original script.
* `/eventcore gui` lists every key of every file with a widget for its type, so every setting is editable in-game.

## Known limits
* Anonymous skin hiding is not implemented (needs a packet library).
* Scoreboard teams (glow colours, hidden nametags) are limited on Folia; failures are logged once.
* This source was written without access to a compiler or Paper API in the authoring environment: it has not been
  built or run. Expect to fix a few compile errors on the first `mvn package`.
