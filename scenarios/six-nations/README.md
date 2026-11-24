# Six Nations Earth

This V1 starting save contains 62,500 pregenerated chunks inside a 4,000 × 4,000 block border on Minecraft Java 1.21.1. The seven continents use a square Miller projection with compressed relief. The countries, borders and politics are fictional. Country names are the original in-world proper names.

With the server stopped, copy the world directory into the server directory under a new name and set level-name in server.properties to that name. Install the V1 War & Democracy jar, NeoForge 21.1.266, Create 6.0.10 and Create Diesel Generators. Copy modserver-server.toml into config on a new installation. For an existing configuration, merge mapSyncRadiusChunks=250, mapMaxMarkers=10000 and maxChunksPerNation=4000. Back up any existing world before switching.

Dev owns Liga Austral in the demo. Before first launch run `python3 assign_league.py path/to/world YOUR-PLAYER-UUID` to give your account the country. The other five owners are cartographer envoys. Start in Córdoba with a Boreal enclave to the east already at war with you. The Sahel is your ally. Each capital holds a reserve chest, all three army branches, 120 conventional missiles, eight nuclear missiles and an oil concession. Antarctica is neutral.

Use M to open JourneyMap, K for the council and U for campaign orders. To reveal the whole world immediately, install JourneyMap 6.0.9, join once and close the client. Copy the contents of journeymap/overworld here into the client's journeymap/data/mp/<server>/overworld folder. For single player use sp in place of mp.

| Nation | Capital region | Block X / Y / Z |
| --- | --- | --- |
| Liga Austral | Córdoba | -712 / 89 / 488 |
| Federación Boreal | Great Lakes | -1000 / 86 / -696 |
| Concordia Europea | Bohemia | 168 / 90 / -824 |
| Unión del Sahel | Kano | 88 / 93 / -184 |
| Coalición de Jade | Xi’an | 1208 / 103 / -536 |
| Pacto del Coral | Darling Downs | 1688 / 93 / 424 |

See the [world atlas](world-atlas.png). Terrain data comes from [Natural Earth](https://www.naturalearthdata.com/downloads/50m-physical-vectors/), [GMT Earth Relief](https://www.generic-mapping-tools.org/remote-datasets/earth-relief.html), [SRTM15+](https://doi.org/10.1029/2019EA000658) and the [Köppen–Geiger climate classification](https://koeppen-geiger.vu-wien.ac.at/present.htm).
