# DonixTpa

A DonutSMP-inspired player-to-player teleport request plugin for Paper.

## Features

- /tpa <player> and /tpahere <player> requests.
- /tpaccept [player], /tpdeny [player], and /tpacancel [player].
- Optional Donut-style confirmation and accept inventories.
- Clickable chat Accept and Deny actions.
- /tpatoggle, /tpaheretoggle, /tpaguitoggle, and /tpauto.
- Configurable request expiration, cooldown, warmup, movement/damage cancellation, sounds, GUI items, and messages.
- Per-player preferences persist in plugins/DonixTpa/players.yml.
- Requests clear when they expire or either player disconnects.
- Async Paper teleportation to avoid synchronous chunk loading at the destination.

## Commands

| Command | Description |
|---|---|
| /tpa <player> | Request to teleport to a player. |
| /tpahere <player> | Request that a player teleport to you. |
| /tpaccept [player] | Accept the newest request, a named request, or open the request menu. |
| /tpdeny [player] | Deny the newest request or a named request. |
| /tpacancel [player] | Cancel your newest outgoing request or a named request. |
| /tpatoggle | Toggle incoming TPA requests. |
| /tpaheretoggle | Toggle incoming TPAHere requests. |
| /tpaguitoggle | Toggle confirmation and accept menus. |
| /tpauto | Automatically accept incoming TPA requests. |
| /dtpa reload | Reload config.yml (admin). |

The chat Accept button runs a quick accept action. Players can also use /tpaccept <player> to open the accept confirmation menu.

## Permissions

| Permission | Default | Description |
|---|---|---|
| donix.tpa.use | Everyone | Use teleport request commands. |
| donix.tpa.admin | OP | Reload and bypass restrictions. |
| donix.tpa.cooldown.bypass | OP | Bypass the request cooldown. |
| donix.tpa.limit.bypass | OP | Bypass request toggles and request limits. |
| donix.tpa.delay.bypass | OP | Skip the teleport countdown. |

## Build

Requires JDK 21 and Maven.

~~~bash
mvn clean package
~~~

Output: target/DonixTpa-1.0.1.jar

GitHub Actions builds the plugin on pushes to main and uploads the JAR as a workflow artifact.

## Compatibility

Compiled against Paper 1.21.11 API with Java 21 bytecode. Intended for Paper-based servers from 1.21.11 through 26.3.
