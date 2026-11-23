# Free Range Pets

Let your Player-Owned House (POH) Menagerie pets roam freely across Gielinor instead of staying cooped up in your house.

## Features
- **Automatic POH Menagerie sync:** Visit your POH Menagerie once and your pets are released across different regions of Gielinor so they immediately spread out instead of clumping together.
- **Full-world Golem-grade roaming:** Powered by a tile-accurate world walkability mesh, live scene collision harvesting, and OSRS's doors, ladders, staircases, stiles, bridges, caves, and shortcuts (gated by your account's real skills, quests, and varbits).
- **Anti-crowding dispersion:** Each pet prefers unoccupied regions and keeps personal space from other pets when nearby. A **Scatter** button in the sidebar also lets you redistribute pets across Gielinor at any time.
- **Follower aware:** Whenever you take a pet out to follow you, its roaming copy automatically pauses until you dismiss or store it again.
- **Sidebar tracker:** View every roaming pet, its live whereabouts (including upstairs and underground places) and distance, star favourites, rename pets inline, and click **Find** to point a hint arrow (and optional Shortest Path route) straight to it.
- **Map & scene overlays:** Optional world map markers, minimap dots, nameplates, and right-click Examine / Shift+Set Name entries.

## Usage
1. Enable **Free Range Pets** in RuneLite.
2. Enter your **POH** and walk near your **Menagerie** so the plugin can observe your pets.
3. Leave your POH and watch your pets explore Gielinor! Open the **Free Range Pets** sidebar tab to see where each pet is at any time.

## Credits & Licensing
Licensed under the BSD 2-Clause License.
- World walkability mesh, transport table format, and three-tier roaming architecture derived from **Golems Don't Die** by Varzeki (BSD 2-Clause).
- Transport dataset derived from **Shortest Path** by Skretzo (BSD 2-Clause).
- Curated region names derived from **Location Display** by trinhc2 (BSD 2-Clause).
- Client-side `RuneLiteObjectController` rendering approach inspired by **Creator's Kit** by ScreteMonge.

## Development
```bash
./gradlew test
./gradlew run
```
