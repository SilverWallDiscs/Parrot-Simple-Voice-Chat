# Parrot + Simple Voice Chat

A Fabric 1.21.1 mod that lets Smart Parrots learn and imitate nearby players through Simple Voice Chat. Its features run on the server; installing the mod on clients is optional.

## Current prototype

- `/parrotdebug summon` — spawns a vanilla parrot marked as a Smart Parrot (permission level 2).
- `/parrotvoice consent` — explicitly opts the executing player into temporary voice learning, or disables it.
- `/parrotvoice status` — displays the Minecraft client language, speech-model status and consent state.
- `/parrotdebug repeat` — asks the nearest Smart Parrot to repeat its latest learned phrase (permission level 2).
- `/parrotdebug words` — lists words learned by the nearest Smart Parrot.
- `/parrotdebug repeatword <word>` — asks the nearest Smart Parrot to repeat one learned word.
- `/parrotdebug info` — reports received packets, active captures, RAM phrases, word clips, consenting players and speech-model status.
- Decodes incoming Simple Voice Chat Opus microphone frames to 48 kHz mono PCM.
- Uses the language sent in vanilla Minecraft client options. Spanish locales (`es_*`) use the Spanish model. English recognition is planned but is not included yet; other languages are not incorrectly fed to the Spanish model.
- Uses Vosk offline speech recognition with word timestamps to identify individual spoken Spanish words.
- Converts the 48 kHz microphone audio to 16 kHz for Vosk, then cuts word clips from the original higher-quality audio using the recognized start/end times.
- Adds small silence gaps inferred from incoming voice-packet timing so speech recognition can estimate word boundaries more accurately.
- Automatically downloads the official small Spanish model on the first server start (about 39 MB) into `config/parrot-svc/models`. It is loaded asynchronously; server ticks are not blocked. The model is stored on disk, but player audio and learned clips stay in RAM.
- Keeps phrase and word clips only in server RAM, with bounded memory and clip counts. Disabling consent, disconnecting, or stopping the server erases that player's active audio and learned clips.
- Replays phrases and individual words from the parrot's entity position through a Simple Voice Chat entity audio channel. Playback uses the parrot-like pitch and DSP chain.

## First Spanish test

1. Start the Fabric 1.21.1 server with Fabric API, Simple Voice Chat and this mod.
2. The server downloads and loads the Spanish Vosk model on first startup. Check the console for `Spanish speech recognition is ready`.
3. Set the Minecraft client language to Spanish in Options > Language, then join the server. No client-side copy of this mod is required.
4. Spawn a Smart Parrot with `/parrotdebug summon`.
5. Enable learning with `/parrotvoice consent`.
6. Stand within 12 blocks of the parrot and say a short Spanish phrase, for example: "tengo mucha hambre".
7. Use `/parrotdebug words` to inspect recognized words, then `/parrotdebug repeatword tengo` to hear one word repeated by the parrot.

The model status is available through `/parrotvoice status` and `/parrotdebug info`. If the model is still downloading/loading, wait for the ready message before expecting words to be learned.

## Speech recognition and languages

The first language is Spanish because it is the project's initial test language. The mod reads the locale Minecraft already sends to the server, so player language selection works even if they have not installed this mod client-side. Spanish locale variants currently share the small Spanish model `vosk-model-small-es-0.42`.

English is designed as the next model to add. Players with an English client locale are detected, but English transcription remains disabled until its model is integrated.

The Spanish model is the lightweight 39 MB Vosk model. The official model list reports a 16.02% word error rate on the Common Voice test and 16.72% on the MTEDX test for this small model; noisy game voice chat may perform differently. A larger Spanish model is available but requires significantly more disk and RAM.

## Privacy and retention

Voice learning is opt-in per player and starts disabled. Speech recognition runs locally on the server; player audio is not sent to an external service. The language model is downloaded separately and stored under the server's config directory. Voice audio is not written to disk. Disabling consent or disconnecting erases that player's active capture and learned clips. Stopping the server clears all voice memory.

## Limitations

This milestone recognizes and stores individual word audio clips. It does not yet automatically compose new sentences from learned words. Word timestamps depend on speech-recognition accuracy and clear voice packets; false or missed words are possible. The next step is to improve word-learning confidence and then build the phrase-composition system.

## Build and dependencies

Requires Java 21, Gradle 8.12+, Minecraft 1.21.1 and Fabric. Install Parrot + Simple Voice Chat, Fabric API and Simple Voice Chat on the server. Players need Simple Voice Chat on their clients to send and hear voice. This mod is server-side in functionality and client optional.

Vosk Java and JNA are included as nested dependencies. The model itself is downloaded from the official Vosk models host on first server startup. Vosk's Java API is Apache-2.0; the Spanish small model is also listed as Apache-2.0.
