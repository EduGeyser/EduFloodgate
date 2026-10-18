[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

# EduFloodgate

EduFloodgate is a fork of [Floodgate](https://github.com/GeyserMC/Floodgate) that lets Minecraft: Education Edition players join online-mode Java servers through [EduGeyser](https://github.com/EduGeyser/EduGeyser). It replaces standard Floodgate and keeps everything Floodgate does for Bedrock Edition players.

Website and documentation: [edugeyser.org](https://edugeyser.org)

## Features

- **Education player identity.** Every Education player gets a stable Java UUID. The format is set in `uuid/scheme.yml` in the EduFloodgate data folder, which is created with the default on first start. `modern`, the default, derives the UUID from the identity verified by Microsoft. `legacy` derives it from the tenant and username only; it is insecure and should only be used if you have a specific reason to. The setting must be the same in EduGeyser and in every EduFloodgate instance, or a player gets different UUIDs on different servers.
- **Education usernames.** Education players get their own prefix, `+` by default, set with `education-prefix` in the config. Education display names often collide, since the default Entra format is the first name plus the last initial, so a second player with the same name online gets a numbered suffix: `+Mark`, then `+Mark_2`.
- **Whitelisting.** `fwhitelist add <name>` works for Bedrock and Education players alike. The name can be given with or without the prefix, and an optional `bedrock` or `edu` argument after it limits the entry to that player type. A player who is not online yet is stored in a pending whitelist and whitelisted when they next join.
- **API.** `FloodgatePlayer` gains `isEducationPlayer()`, `getTenantId()`, and `getAdRole()` for other plugins. The Education fields travel through the normal Floodgate handshake data.
- **Global linking stays Bedrock-only.** Education players have no Xbox identity, so the global Xbox account linking never applies to them.

## Platforms

EduFloodgate builds for Spigot, BungeeCord, and Velocity. For Fabric and NeoForge servers use [EduFloodgate-Modded](https://github.com/EduGeyser/EduFloodgate-Modded).

EduFloodgate requires EduGeyser. Use the EduFloodgate release that was published alongside your EduGeyser release.

## Downloads

Get the latest jars from the [download page](https://edugeyser.org/download) or from [GitHub Releases](https://github.com/EduGeyser/EduFloodgate/releases).

## Setting Up

Install and configure EduFloodgate exactly like standard Floodgate, then apply the Education-specific settings:

- [Floodgate Setup](https://edugeyser.org/wiki/floodgate/setup)
- [EduFloodgate](https://edugeyser.org/wiki/geyser/education/edufloodgate): usernames and UUIDs for Education players
- [Education Setup](https://edugeyser.org/wiki/geyser/education/setup) for the EduGeyser side

The rest of the [Floodgate wiki](https://edugeyser.org/wiki/floodgate/) covers what EduFloodgate shares with upstream.

## Support

- Discord: https://edugeyser.org/discord
- Bug reports and feature requests: [GitHub Issues](https://github.com/EduGeyser/EduFloodgate/issues)

## Compiling

EduFloodgate compiles against EduGeyser's `common` module, which carries the Education fields and is not published anywhere public. Publish it to your local Maven repository first:

1. Clone [EduGeyser](https://github.com/EduGeyser/EduGeyser) next to this repository and run `./gradlew :common:publishToMavenLocal` there.
2. Make sure `geyserVersion` in `build-logic/src/main/kotlin/Versions.kt` matches the version in EduGeyser's `gradle.properties`.
3. Build with JDK 17: `./gradlew build` (on Windows `gradlew build`).
4. The jars are in `<platform>/build/libs/edufloodgate-<platform>.jar` for `bungee`, `spigot`, and `velocity`.

## Contributing

Contributions are welcome. Open an issue or a pull request on GitHub, or reach out on [Discord](https://edugeyser.org/discord).

## Credits

EduFloodgate is a fork of [Floodgate](https://github.com/GeyserMC/Floodgate), part of the [GeyserMC](https://geysermc.org) project.

EduFloodgate is licensed under the [MIT License](LICENSE).
