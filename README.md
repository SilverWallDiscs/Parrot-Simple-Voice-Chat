# Parrot + Simple Voice Chat

A server-side Fabric 1.21.1 mod that lets Smart Parrots learn and imitate consenting players through Simple Voice Chat. Clients do not need this mod installed; they do need Simple Voice Chat to use voice chat.

## Consent and privacy

- On first join, a player with no saved choice sees clickable green **[Sí]** and red **[No]** options in chat.
- The three persisted states are: undecided, accepted and declined. Players who have already decided are not prompted again.
- `/parrotvoice consent` toggles consent. `/parrotvoice accept` enables it, `/parrotvoice decline` declines, `/parrotvoice revoke` withdraws consent and deletes that player's stored voice clips and every phrase containing their voice, and `/parrotvoice status` shows the saved state.
- Consent is per player and persists across reconnects and server restarts. Disconnecting only stops live capture; it does not delete already learned audio, so a parrot can still repeat its stored owner's voice while that owner is offline.
- Voice clips and phrase recordings are stored on the server in compressed per-parrot files under `config/parrot-svc/parrots/`. The consent choices are stored in `config/parrot-svc/player-consent.properties`. Back up these files with the server.
- Audio is reduced from 48 kHz / 16-bit to 16 kHz / 8-bit mono and compressed before saving. This lowers disk use and gives playback a rougher, more parrot-like sound. This storage format is unchanged.
- Speech recognition runs offline on the server using Vosk. Player audio is not sent to a cloud speech service.
- Only a player who has accepted is captured. Revoking or declining deletes that player's stored contributions from every parrot memory file, including mixed phrases that used their voice.

## Smart Parrot memory

- Each parrot has its own independent memory keyed by its Minecraft entity UUID. Its memory survives owner disconnects and server restarts.
- Each parrot remembers up to 10,000 distinct words by default. The limit is configurable in `config/parrot-svc/voice-memory.properties` with `max-words-per-parrot=10000` (allowed range: 1–10000). Existing installations with the previous generated default of `150` are upgraded to `10000`; other custom values are preserved. Restart after editing.
- A word can retain up to three speaker recordings. When composing or repeating words, the parrot prefers its tamed owner’s recording when available; otherwise it uses the newest available speaker recording.
- Words are stored lowercase without acute accents or umlauts, while preserving `ñ`. Thus `dormiré`, `dormire` and `dórmire` match the same word, but `año` and `ano` remain different.
- Natural multi-word utterances are stored as their own phrase recordings. The newest clean owner recording has priority over a phrase assembled from fragments or one containing visitor audio.
- The mod also combines recent short utterances into phrase candidates (up to 4 fragments and 8 words). The suffix `(3)` means the phrase was built from three separate utterances; `(2)` means two. `E` marks a phrase where at least one recording came from someone other than the parrot's owner.
- If the owner later says the whole phrase fluently, that one-piece owner recording replaces the lower-priority assembled/visitor version. A later clean owner sequence can also replace an `E` candidate.
- Learned phrases have no age-based expiration. The store has a defensive ceiling of 20,000 distinct phrases and 40 MB of phrase audio per parrot; when a ceiling is reached, existing phrases are kept and new distinct phrase entries are not added.

## Commands

- `/parrotdebug summon` — spawns a test Smart Parrot (permission level 2).
- `/parrotdebug words` — lists words learned by the nearest Smart Parrot.
- `/parrotdebug phrases` — lists up to 40 recently updated phrases and their fragment / visitor markers.
- `/parrotdebug suggestions` — displays 83 phrase ideas grouped as a teaching prompt list. They are not pre-recorded voice lines; the parrot must hear a consenting player say them.
- `/parrotdebug repeat` — plays the nearest Smart Parrot's latest saved phrase.
- `/parrotdebug repeatword <word>` — plays a saved word, preferring the tamed owner's voice.
- `/parrotdebug tryphrase <phrase>` — plays an exact saved phrase if one exists; otherwise composes it from the parrot's saved word recordings. Diacritics are ignored.
- `/parrotdebug info` — shows nearby parrot memory counts, captures, consent count and speech model status.
- `/parrotvoice status` — shows your language, recognition status and consent state.

## Speech recognition and learning

1. Install Fabric API, Simple Voice Chat and this mod on the server. Players use the Simple Voice Chat client mod as usual.
2. Set Minecraft's client language to Spanish. Spanish locales (`es_*`) use the small Vosk model `vosk-model-small-es-0.42`.
3. On first server start, the model downloads to `config/parrot-svc/models` and loads in the background. Wait for `Spanish speech recognition is ready`.
4. Tame a parrot and keep it nearby (within 12 blocks), or use `/parrotdebug summon` for a test parrot.
5. Accept the clickable chat prompt. You can change your decision later with `/parrotvoice revoke` or `/parrotvoice consent`.
6. Speak naturally. Recognized words are clipped from the original voice recording, saved to that parrot and used to build phrase recordings.
7. Try examples such as `/parrotdebug tryphrase tengo mucha hambre`, `/parrotdebug tryphrase hola mi dueño` or `/parrotdebug tryphrase te extrañé`.

Playback raises the voice pitch by 1.8× while preserving the original phrase duration; it does not make the parrot speak faster. The existing storage format and playback tempo are unchanged.\n\nThe recognition model can mishear words, especially with background noise, overlapping voices or fast speech. The built-in example phrases are goals to teach, not synthetic voice lines: the mod needs consented recordings before it can reproduce a phrase in a player's voice.

## Parrot voice tone filters

The playback DSP now has a speech-preserving parrot-tone chain. It keeps the learned human recording as the intelligibility reference and blends it with a filtered version. It does not overlay separate squawks or replace speech with birdsong, and it is not a trained parrot-voice model.

- The original learned samples are never rewritten. Pitch and timbre processing are applied in memory only when the parrot plays a phrase.
- Pitch remains 1.8× with the original duration preserved.
- On first playback, the mod creates `config/parrot-svc/voice-filters.properties`. Edit the values below and restart the server to apply them:

```properties
mix-percent=50
nasal=45
brightness=25
roughness=12
flutter=8
```

- `mix-percent`: dry/wet blend. 0 is the clean pitch-shifted player voice; 100 is the full parrot filter.
- `nasal`: broad vocal resonances that add a nasal/beak-like quality.
- `brightness`: upper-mid presence.
- `roughness`: subtle quantized grain.
- `flutter`: slight 5.5 Hz amplitude irregularity.

All five controls accept values from 0 to 100. The defaults deliberately stay gentle to protect speech intelligibility. The `.psvc` file format, learned recordings and stored audio are unchanged.

### Audacity 4 preview effect

The repository includes `audacity/ParrotVoiceTone.ny`, a Nyquist effect with the same control names and defaults so you can tune the tone before changing the server config.

1. In Audacity, open **Tools → Nyquist Plugin Installer**.
2. Browse to `audacity/ParrotVoiceTone.ny`, install it, then restart Audacity. Enable it in Plugin Manager if needed.
3. Work on a duplicate of your voice recording. To match the mod's pitch, apply **Effect → Pitch and Tempo → Change Pitch** with **+80%** (1.8×) while preserving duration.
4. Apply **Parrot Voice Tone (PSVC)** and adjust its mix, nasal, brightness, roughness and flutter controls.

Nyquist effects process the selected audio offline; they are not live microphone filters. Audacity's Nyquist EQ uses its own filter implementation, so the preview is intended to match the mod's controls and general character rather than guarantee bit-identical output.

## Audacity 4 real-time voice effect

For live adjustment while playing a track, use the native VST3 version in `audacity-vst3/`. The Nyquist script in `audacity/ParrotVoiceTone.ny` is an offline effect and does not appear in Audacity's real-time effects list.

The Windows x64 VST3 is built by the **Build Audacity VST3** GitHub Actions workflow. Download the `ParrotVoiceTone-win-x64` artifact from a successful workflow run, extract it, and copy the complete `ParrotVoiceTone.vst3` folder to:

```text
C:\\Program Files\\Common Files\\VST3
```

Restart Audacity 4, then open a track's **Effects** panel (or press `E`), choose **Add effect**, and select **Parrot Voice Tone**. Adjust the five controls while the track plays. The track's original waveform remains unchanged.

The VST3 preview applies a real-time 1.8× pitch shift and the parrot-timbre filter chain while the track plays. It accepts mono or stereo tracks and both 32-bit and 64-bit audio buffers. Its starting preset is intentionally stronger so the change is clearly audible: mix 85, nasal 75, brightness 60, roughness 38 and flutter 28. The Minecraft mod still has its own default values in `voice-filters.properties`; the controls have the same meanings, but the two implementations are not yet bit-identical.

Audacity's real-time track effects process playback of a recorded track. They do not act as a live microphone monitor while recording.

## Build

Requires Java 21, Gradle 8.12+, Minecraft 1.21.1 and Fabric. Vosk Java and JNA are included as nested dependencies. The model itself is downloaded from the official Vosk model host on first startup.
