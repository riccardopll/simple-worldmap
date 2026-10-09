# Simple World Map

A small client-side world map mod for Fabric on Minecraft 26.3.

It records the terrain you explore and shows it on a full-screen map with waypoints. It runs only on the client: no server mod, no network payloads, no new blocks or items. It works in single player, on vanilla servers and on Paper servers.

## Features

- Full-screen map of explored terrain, colored like vanilla map items (one pixel per block, with height and water-depth shading).
- Pan by dragging, zoom with the scroll wheel or keys, coordinates under the cursor.
- Player heads: yours with a facing marker, and other players within the server's tracking range, with their names.
- Waypoints with a name and color, stored per world or server and per dimension.
- Nether support: the map shows the first floor below the bedrock roof.

## Controls

| Input | Action |
| --- | --- |
| `M` | Open or close the map |
| `B` | Add a waypoint at your position |
| Left-drag | Pan the map |
| Scroll wheel or two-finger swipe | Zoom in or out, centered on the cursor |
| `=` / `-` (in the map) | Zoom in or out, centered on the screen |
| Right-click on empty map | Add a waypoint at that spot |
| Right-click on a waypoint | Edit or delete it |
| `Space` (in the map) | Center the map on yourself |
| `Enter` (in the waypoint dialog) | Save |

You can rebind `M`, `B`, `=` and `-` under Options → Controls → Key Binds → Simple World Map.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for Minecraft 26.3.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
3. Put `simple-worldmap-<version>.jar` in your `mods` folder.

The server needs nothing installed.

## Storage

Map data lives in the game directory, separate from world saves:

```
.minecraft/simple-worldmap/
  singleplayer/<world folder>/<namespace>/<dimension>/
  multiplayer/<server address>/<namespace>/<dimension>/
  realms/<realm name>/<namespace>/<dimension>/
    r.<x>.<z>.swm     one file per 512×512 block region
    waypoints.json
```

Each region file stores one byte per block (the vanilla map color index and shade), compressed with deflate. A fully explored region usually takes a few tens of kilobytes. To reset the map for a world, delete its folder while the game is closed.

## How it works

- When the client receives a chunk, its position goes into a queue. Each client tick samples queued chunks for at most 2 ms, reading the surface heightmap and the block map colors. Chunks within two chunks of the player are re-sampled every 2 seconds, so block changes nearby show up.
- Region files are read and written on a single low-priority background thread. Changes are saved every 30 seconds, on dimension change and on disconnect.
- Region textures exist only while the map is open, and textures that are off screen for a while are freed.

## Build

Requires JDK 25. With [SDKMAN!](https://sdkman.io), `.sdkmanrc` selects it:

```sh
sdk env install
./gradlew build
```

The jar is written to `build/libs/simple-worldmap-<version>.jar`.

Other tasks:

- `./gradlew runClient` starts a development client.
- `./gradlew runClientGameTest` runs an automated client test. It creates a single-player world and a dedicated server, opens the map, adds, edits and deletes waypoints, visits the Nether, checks the files on disk and saves screenshots to `build/run/clientGameTest/screenshots/`.

## Limitations

- Colors use the vanilla map palette, so there are no biome-tinted grass or water colors.
- The Nether view uses one fixed layer: the first floor below the roof. There is no cave or layer selector.
- Servers behind a proxy (BungeeCord, Velocity) that share one address store every backend world in the same folder.
- Waypoints are not shown in the world (no beacon beams or HUD).
- Other players appear only while the server sends their position to your client, which happens within its entity tracking range (on Paper, `entity-tracking-range.players` in `spigot.yml`). Invisible players are hidden, and the server does not send spectators to players who are not spectators.

## License

MIT. See [LICENSE](LICENSE).
