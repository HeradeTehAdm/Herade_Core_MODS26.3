# Herade Core

Unified server-side Fabric mod for Herade House, targeting Minecraft **26.3**.

## Current status

This repository is being initialized from an empty repository. The first commit establishes the build, metadata, server entrypoint and CI build workflow. Gameplay and administration modules will be added incrementally after the baseline build is verified.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1 or newer
- Fabric API 0.162.0+26.3
- Java 26
- Gradle 9.8.0

Version values are maintained in `gradle.properties`.

## Build

With Java 26 and Gradle 9.7.1 installed:

```bash
gradle build
```

The remapped mod JAR is produced in `build/libs/`.

## Planned modules

- Core lifecycle and configuration
- Permission integration (no replacement permission-group system)
- Moderation and punishment persistence
- Vanish
- Player inspection
- Teleport command extensions without shadowing vanilla commands
- Audit and security logs
- Server protection and diagnostics
- Herade Security API integration with DS_Hereid
- Login approval and one-time second-factor challenges

## Security principles

- Use authenticated Minecraft UUIDs as account identifiers.
- Never collect hidden hardware fingerprints, MAC addresses or device serial numbers.
- Treat IP addresses as sensitive data; minimize retention and restrict access.
- Keep API credentials on the server, never in the mod's client-side code.
- Gate pending logins before they can interact with the world.
- Preserve existing server and bot data; use migrations rather than destructive resets.

## Integration

The Discord bot remains the control plane for staff-facing workflows. Herade Core communicates with a separately authenticated HTTPS API; it does not create a second Discord bot or duplicate the bot's admin panel.
