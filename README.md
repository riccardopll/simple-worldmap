<img src="src/main/resources/assets/simple-worldmap/icon.png" width="128">

# Simple World Map

A small client-side world map mod for Fabric on Minecraft 26.3.

It records the terrain you explore and shows it on a full-screen map with waypoints. It runs only on the client: no server mod, no new blocks or items. It works in single player, on vanilla servers and on Paper servers.

## Features

- Full-screen map of explored terrain, with grass, leaves and water colored by biome
- Zoom from block level out to thousands of blocks across
- Waypoints with a name and color, stored per world and per dimension
- A waypoint where you last died
- Player heads for you and nearby players
- Nether support: the map shows the first floor below the bedrock roof

## Controls

| Input | Action |
| --- | --- |
| `M` | Open or close the map |
| Left-drag | Pan the map |
| Scroll wheel, `+` / `-` | Zoom in or out |
| Right-click on the map | Add a waypoint there |
| Right-click on a waypoint | Edit or delete it |
| `Space` | Center the map on yourself |

`M`, `+` and `-` can be rebound under Options → Controls → Key Binds → Simple World Map.

## Downloads

Download the latest jar from [GitHub Releases](https://github.com/riccardopll/simple-worldmap/releases). Every push to `main` publishes a new release.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
3. Put `simple-worldmap-<version>.jar` in your `mods` folder.

The server needs nothing installed.

## Storage

Map data is stored in `.minecraft/simple-worldmap/`, separate from world saves. To reset the map for a world, delete its folder while the game is closed.

## Reporting issues

Report bugs and feature requests on the [issue tracker](https://github.com/riccardopll/simple-worldmap/issues).

## Building

Requires JDK 25.

```sh
./gradlew build             # writes the jar to build/libs/
./gradlew runClient         # starts a development client
./gradlew runClientGameTest # runs the automated client tests
```

## License

MIT. See [LICENSE](LICENSE).
