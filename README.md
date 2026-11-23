# Free Range Pets

Let your Player-Owned House (POH) Menagerie pets roam freely across Gielinor instead of staying cooped up in your house.

The pets are purely cosmetic and client-side: only you can see them, and nothing is sent to the game.

## Features
- **POH Menagerie sync:** visit your own Menagerie once and each pet there is released somewhere different in Gielinor.
- **World-wide roaming:** pets walk on real collision and use doors, gates, stiles, ladders, stairs, stepping stones and shortcuts. Requirement-gated shortcuts are only used if your account meets the skill, quest and varbit requirements.
- **Spreading out:** pets prefer regions no other pet is in, and give each other some personal space when nearby. The **Scatter** button in the sidebar spreads them out again at any time.
- **Follower aware:** while a pet is following you, its roaming copy pauses until you dismiss or store it again.
- **Sidebar tracker:** see every roaming pet and where it is (including upstairs and underground), star favourites, rename pets, and click **Find** to point a hint arrow at one. If the Shortest Path plugin is installed, a route can be drawn too.
- **Map and scene extras:** optional world map markers, minimap dots, nameplates, and right-click Examine / Shift + right-click Set Name.

## Usage
1. Enable **Free Range Pets** in RuneLite.
2. Enter your own house using the portal's **Home** or **Build mode** option (or any house teleport) and walk near your Menagerie so the plugin can see your pets. Entering through **Friend's house** never adds pets.
3. Leave your house and go find your pets. Open the **Free Range Pets** sidebar tab to see where each one is.

## Credits and licensing
Licensed under the BSD 2-Clause License (see `LICENSE`).

- The roaming engine (world mesh, transport network, pathfinding, off-screen itineraries and the dead-end handling) is adapted from [Golems Don't Die](https://github.com/Varzeki/golems-dont-die) by Varzeki, BSD 2-Clause. Files derived from it carry both copyright lines.
- The bundled data files `world-mesh.gz`, `transports.gz` and `places.gz` are copied unmodified from Golems Don't Die. Per that project:
  - `world-mesh.gz` is walkability built from the game's own cache collision data;
  - `transports.gz` is derived from the transport table of [Shortest Path](https://github.com/Skretzo/shortest-path) by Skretzo, BSD 2-Clause;
  - `places.gz` is derived from the region names in Location Display by trinhc2, BSD 2-Clause.
- The client-side `RuneLiteObjectController` rendering approach is inspired by Creator's Kit by ScreteMonge.

## Development
Build with JDK 11 to 21 (the Gradle 8.8 wrapper does not run on JDK 25):

```bash
./gradlew test
./gradlew run
```
