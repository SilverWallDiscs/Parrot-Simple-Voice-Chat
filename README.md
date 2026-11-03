# Parrot + Simple Voice Chat

A Fabric 1.21.1 experiment that lets marked Smart Parrots learn and repeat nearby players through Simple Voice Chat. The mod is server-side in functionality, but safe to install on clients too (client optional).

## Current prototype

- /parrotdebug summon — spawns a vanilla parrot marked as a Smart Parrot (permission level 2).
- /parrotvoice consent — explicitly opts the executing player into temporary voice learning, or disables it.
- /parrotdebug repeat — asks the nearest Smart Parrot to repeat its latest learned clip (permission level 2).
- /parrotdebug info — reports received packets, active captures, phrases held in RAM and consenting players.
- Decodes incoming Opus microphone frames to 48 kHz PCM with the Simple Voice Chat API decoder.
- Ends a phrase after a short gap in incoming voice packets, then assigns it to the nearest Smart Parrot within 12 blocks.
- Stores short audio clips in server RAM only (maximum 6 seconds per phrase, a global memory cap, and bounded clip count).
- Replays the clip from the parrot's entity position through a Simple Voice Chat entity audio channel, with a raised pitch and slight vibrato.

## Privacy and retention

Voice learning is opt-in per player and starts disabled. Audio is not written to disk and is never sent to an external service. Disabling consent or disconnecting erases that player's active capture and learned clips. Stopping the server clears all voice memory. Only nearby Smart Parrots receive phrases.

## Important limitations

This milestone learns and replays complete phrases. It does not yet recognize speech or isolate individual words. Word-level recombination still needs a speech-to-text/alignment stage and word-boundary segmentation. Pitch raising is currently simple resampling, so it also makes playback shorter/faster.

## Build and dependencies

Requires Java 21, Gradle 8.12+, Minecraft 1.21.1 and Fabric. Install Parrot + Simple Voice Chat, Fabric API and Simple Voice Chat on the server. Players need Simple Voice Chat on their clients to hear the parrot. Parrot + Simple Voice Chat can also be installed on clients, but it is not required; no client-side copy is needed for the server features.

Simple Voice Chat API 2.6.25 is loaded through the official voicechat entrypoint.
