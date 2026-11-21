# libs/

Third party jars used to **compile** the mod (they are not shipped with it).

## journeymap-api-neoforge-2.0.0-1.21.1.jar

Public API of JourneyMap (API v2.0.0 for Minecraft 1.21.1 / NeoForge).

JourneyMap ships its API inside the mod jar as a JarJar dependency
(`META-INF/jarjar/journeymap-api-neoforge-2.0.0-1.21.1.jar`), and the mod jar
itself is resolved from the Modrinth maven:

    maven.modrinth:journeymap:1.21.1-6.0.9+neoforge

The API jar in this folder was extracted from it with:

    unzip -p journeymap-1.21.1-6.0.9+neoforge.jar \
        META-INF/jarjar/journeymap-api-neoforge-2.0.0-1.21.1.jar \
        > libs/journeymap-api-neoforge-2.0.0-1.21.1.jar

It is vendored because JourneyMap's own maven (`maven.journeymap.info`) is not
always reachable from the build machines. Replace it with the official artifact
when the network allows it.
