# Parrot + Simple Voice Chat

A Fabric 1.21.1 experiment for intelligent parrots that learn speech fragments from nearby players through Simple Voice Chat.

## Current prototype

- `/parrotdebug summon` — spawns a vanilla parrot marked as a Smart Parrot (permission level 2).
- `/parrotdebug info` — reports how many non-empty microphone packets the Simple Voice Chat API listener has received since server startup.
- Simple Voice Chat API 2.6.25 integration is registered through the official `voicechat` entrypoint.

This is an early integration milestone, not the complete learning system yet. The current listener only counts incoming Opus microphone packets; it does not persist, transcribe, or replay voice. Word-level learning requires decoding and segmenting audio plus a speech recognizer. Audio is not written to disk in this prototype.

## Build

Requires Java 21 and Gradle 8.12+.

```bash
gradle build
```

The mod currently targets Fabric Loader and Minecraft 1.21.1. Simple Voice Chat must be installed on both client and server, with an API version compatible with 2.6.25.

## Debug commands

```text
/parrotdebug summon
/parrotdebug info
```
