# EduFloodgate

A [Floodgate](https://github.com/GeyserMC/Floodgate) fork that adds **Minecraft Education Edition** player support for online mode Java servers. Companion plugin for [EduGeyser](https://github.com/SendableMetatype/EduGeyser).

## Features

- **Education player identity** - Stable UUID derived from the MESS-verified Entra Object ID, cryptographically tied to the student's M365 account
- **Education usernames** - Prefixed with `+` by default (e.g. `+Mark`), with `_N` suffix for collision resolution when two players share the same display name (e.g. `+Mark_2`)
- **FloodgatePlayer API** - `isEducationPlayer()`, `getTenantId()`, and `getAdRole()` for downstream plugins
- **Xbox Live linking bypass** - Education clients are automatically excluded from player linking (no Xbox account to link)
- **BedrockData protocol extension** - Education fields (isEdu, tenantId, adRole) passed through the Floodgate data pipeline

## Downloads

Pre-built jars are available on the [Releases](https://github.com/SendableMetatype/EduFloodgate/releases) page.

Requires [EduGeyser](https://github.com/SendableMetatype/EduGeyser).

## Documentation

- **[Setup Guide](https://codeberg.org/SendableMetatype/EduGeyser-Docs/src/branch/master/SETUP-GUIDE.md)** - How to install and configure EduGeyser + EduFloodgate

For technical details, see the **[Master Documentation](https://codeberg.org/SendableMetatype/EduGeyser-Docs/src/branch/master/edugeyser-master-documentation.md)**.

---

# Floodgate

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Build Status](https://github.com/GeyserMC/Floodgate/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/GeyserMC/Floodgate/actions/workflows/build.yml?query=branch%3Amaster)
[![Discord](https://img.shields.io/discord/613163671870242838.svg?color=%237289da&label=discord)](http://discord.geysermc.org/)
[![Hits](https://hitcount.dev/p/GeyserMC/Floodgate.svg)](https://hitcount.dev/p/GeyserMC/Floodgate)

[Download](https://geysermc.org/download/?project=floodgate)

Hybrid mode plugin to allow for connections from [Geyser](https://github.com/GeyserMC/Geyser) to join online mode servers.

Geyser is an open collaboration project by [CubeCraft Games](https://cubecraft.net).

See the [Floodgate](https://geysermc.org/wiki/floodgate/) section in the GeyserMC Wiki for more info about what Floodgate is, how you setup Floodgate and known issues/caveats. Additionally, it includes a more in-depth look into how Floodgate works and the Floodgate API.
