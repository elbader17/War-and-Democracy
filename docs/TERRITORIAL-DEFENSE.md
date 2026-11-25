# V2 territorial defense

V1 is the tagged campaign release, including the Six Nations Earth save. V2 adds physical defenses that operate while their chunks are ticking. Strategic armies still fight on the campaign board. The Defense tab controls installations placed in the world. Physical interceptors engage incoming physical missiles.

[Watch the Skyguard track and hit a Sable vehicle on the Six Nations range (6 seconds)](media/skyguard-range.mp4).

## Playtest sequence

| Iteration | What players should be able to do | Acceptance check |
| --- | --- | --- |
| Workshop | Craft four recognizable installations and their supplies with vanilla and Create ingredients. Supply rotation from below and ammunition from a neighboring inventory. | Remove the shaft, overload the network, remove ammunition and dismantle a battery support. Each stops firing and reports the cause. |
| Border patrol | Place a sentry in a claim, arm it and walk a hostile player across the border. | Friendly, protected, creative and spectator players pass safely. A hostile behind terrain stays covered. Changing ownership returns the station to hold fire. |
| Airspace | Fly a Sable sublevel through a defended claim. Scan locally or link an operating Create: Radars source. | Turning off a linked radar drops its feed. A moving vehicle remains the same contact as it crosses chunks. |
| Countermeasures | Launch a physical skyrocket at a hostile vehicle, release flares and deploy an interceptor battery. | Players can distinguish launch, target diversion, interception and impact. A resolved missile can only deal damage once. |
| Command | Open U, select Defense, choose all installations or a subset, then edit standing orders. | Orders survive restarts and chunk unloading. Only the current national owner can change orders. Recheck ownership at execution. |
| Field trials | Exercise the six-nation save on a copy and review the client at night, in daylight and at low GUI scale. | Inspect aim, recoil, reload, projectile silhouettes, map controls and costs with two human pilots before promoting the beta. |

## Workshop

| Installation | Role | Minimum shaft speed | One shot consumes |
| --- | --- | ---: | --- |
| Watchtower | Personnel inside owned airspace | 16 RPM | Vanilla arrow |
| Bastion | Vehicles | 32 RPM | Brass slug |
| Skyguard | Moving vehicles, pursuing skyrocket | 48 RPM | Skyrocket |
| Aegis | Incoming hostile skyrocket | 64 RPM | Interceptor |

Skyguard and Aegis need two Create brass casings on opposite horizontal sides of the base or its drive shaft, inside the same nation's land. A chest, barrel or accessible Create inventory on another horizontal side supplies ammunition. Use a vertical shaft on the bottom. Open the recipe book or a recipe viewer for crafting ingredients.

A station starts on hold. Armed stations recognize declared enemies and subjects on the engage list. Protection wins if lists overlap. Own nations, allies and non-aggression partners have protected passage. Orders can name a nation or an individual player or vehicle UUID. Watchtowers, Bastions and Skyguards engage intruders inside the station owner's current territory. An Aegis can also intercept a hostile skyrocket within range across the border. The effective owner follows the chunk. Unclaimed land has no active defense.

Use a survey key on an assembled vehicle in your own territory to register its national identity. Use the same key on a friendly radar, then on a turret, to assign a remote radar in the same dimension. Select Local or Radar in the command tab. Use a flare cartridge from the vehicle as a pursuing skyrocket approaches. A redstone-powered vanilla dispenser on a moving vehicle can also eject cartridges. Aegis carries its own interceptor rounds.

## First patrol

1. Put a Watchtower in a claimed chunk. Connect a vertical shaft below it and put arrows in a chest beside it, inside the same claim.
2. Open **U → Defense**. Select the Watchtower, enter a country or player under **Nation, player or UUID**, choose **Engage** and then **Arm**. A declared enemy is already eligible. Use **Protect** to give a specific visitor passage.
3. Hover over a station to inspect its dimension, height, power or ammunition problem, sensor and both lists. Select multiple rows to issue a common order, or choose **Select all installations** to reach every page and dimension. **Refresh** reads current field conditions.
4. For aircraft, replace the Watchtower with a supplied Skyguard and add brass supports. A linked radar must be loaded and running in another owned chunk or the turret’s own chunk. With **Local sensor** the turret detects eligible contacts on its own. Select **Hold fire** to suspend a battery.
5. For interception, supply a separate Aegis with interceptor rounds and arm it. To test diversion, fly a registered vehicle into the defended area and deploy a cartridge when a pursuing skyrocket is close.

## Art direction

Cast-iron feet and visible mounting bolts anchor a rotating collar. The Watchtower has a narrow sight and single sliding bolt, the Bastion a heavy sleeve and paired recoil buffers, the Skyguard twin copper launch rails and the Aegis a four-cell rack. Brass bearings, exposed drive wheels and copper bands use Create's material palette. The rotating upper assembly and recoiling carriage have separate pivots. The amber exhaust of an attacking rocket, blue interceptor trail and falling golden flares are distinct in flight.

## Verification

Both the base server and the full Create/Aeronautics/Radars/Diesel profile completed the 69-test Minecraft GameTest suite. The full profile exercised a survival visitor crossing a live sentry, an actual radar linked with a survey key, Sable vehicle damage, flare diversion, missile interception, ammunition consumption, kinetic power loss, multiblock dismantling, capture and saved orders. A separate client connected to a dedicated server, edited an individual Skyguard’s orders in the Defense tab and recorded the launch shown above. Large multiplayer battles and late-game supply costs remain field-playtest items.

## Integration profile

Run the full add-on profile with Java 21 and `tools/gametest.sh -PterritoryDefense=true`. The development profile pins Create 6.0.10, Aeronautics 1.3.3, Sable 2.0.6, Create: Radars 5.1.3, Create Big Cannons 5.11.7 and RPL 2.1.2. Add `-Ppetroleum=true` to exercise the V1 oil industry alongside the defenses. Distribute third-party mods from their projects.

[Create](https://modrinth.com/mod/create/version/UjX6dr61) · [Create Aeronautics](https://modrinth.com/mod/create-aeronautics/version/demX2roU) · [Sable](https://modrinth.com/mod/sable/version/fg9dTRz9) · [Create: Radars](https://modrinth.com/mod/create-radars/version/ayKJWhGv)
