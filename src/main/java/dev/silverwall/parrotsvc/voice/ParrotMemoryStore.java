package dev.silverwall.parrotsvc.voice;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persistent memory, stored independently for every Smart Parrot entity UUID. */
public final class ParrotMemoryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("parrot_svc/parrot-memory");
    private static final int MAGIC = 0x50535643;
    private static final int FORMAT_VERSION = 1;
    private static final int SOURCE_RATE = 48_000;
    private static final int DECIMATION = 3;
    private static final int MAX_CLIPS_PER_WORD = 3;
    private static final int MAX_RECENT_FRAGMENTS = 12;
    private static final long FRAGMENT_MAX_AGE_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_PHRASE_FRAGMENTS = 4;
    private static final int MAX_PHRASE_WORDS = 8;
    private static final int MAX_PHRASE_SECONDS = 8;
    private static final int MAX_WORDS_PER_FILE = 5000;
    private static final int MAX_PHRASES_PER_FILE = 20000;
    private static final long MAX_PHRASE_AUDIO_BYTES = 40L * 1024L * 1024L;
    private static final int MAX_BLOB_BYTES = 1_000_000;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final int SEPARATOR_SAMPLES = SOURCE_RATE / 20;

    private static final Map<UUID, ParrotMemory> MEMORIES = new ConcurrentHashMap<>();
    private static final Map<UUID, ConsentState> CONSENTS = new ConcurrentHashMap<>();
    private static Path parrotsDirectory;
    private static Path consentFile;
    private static int maxWordsPerParrot = 150;

    private ParrotMemoryStore() {
    }

    public enum ConsentState { UNKNOWN, ACCEPTED, DECLINED }

    public static synchronized void initialize(Path configDirectory, int wordLimit) {
        Path root = configDirectory.resolve("parrot-svc");
        parrotsDirectory = root.resolve("parrots");
        consentFile = root.resolve("player-consent.properties");
        maxWordsPerParrot = Math.max(1, Math.min(MAX_WORDS_PER_FILE, wordLimit));
        try {
            Files.createDirectories(parrotsDirectory);
            CONSENTS.clear();
            Properties properties = new Properties();
            if (Files.isRegularFile(consentFile)) {
                try (var input = Files.newInputStream(consentFile)) {
                    properties.load(input);
                }
            }
            for (String key : properties.stringPropertyNames()) {
                try {
                    UUID playerId = UUID.fromString(key);
                    ConsentState state = ConsentState.valueOf(properties.getProperty(key, "UNKNOWN"));
                    if (state != ConsentState.UNKNOWN) {
                        CONSENTS.put(playerId, state);
                    }
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("Ignoring invalid player consent entry '{}'", key);
                }
            }
            clearCache();
            LOGGER.info("Persistent Smart Parrot memory ready; {} consent record(s) loaded", CONSENTS.size());
        } catch (IOException exception) {
            LOGGER.error("Could not initialize persistent Smart Parrot memory", exception);
        }
    }

    public static ConsentState getConsentState(UUID playerId) {
        return CONSENTS.getOrDefault(playerId, ConsentState.UNKNOWN);
    }

    public static boolean isConsenting(UUID playerId) {
        return getConsentState(playerId) == ConsentState.ACCEPTED;
    }

    public static int getConsentingPlayerCount() {
        return (int) CONSENTS.values().stream().filter(state -> state == ConsentState.ACCEPTED).count();
    }

    /** A decline or revoke deletes all persistent audio contributed by that player. */
    /** Wipes cached audio on shutdown without deleting the persistent per-parrot files. */
    public static synchronized void clearCache() {
        for (ParrotMemory memory : MEMORIES.values()) {
            for (List<WordRecording> recordings : memory.words.values()) {
                recordings.forEach(recording -> Arrays.fill(recording.audio, (byte) 0));
            }
            memory.phrases.values().forEach(phrase -> Arrays.fill(phrase.audio, (byte) 0));
            for (Fragment fragment : memory.fragments) {
                fragment.words.forEach(word -> Arrays.fill(word.audio, (byte) 0));
            }
        }
        MEMORIES.clear();
    }

    public static synchronized void setConsent(UUID playerId, ConsentState state) {
        if (state == ConsentState.UNKNOWN) {
            CONSENTS.remove(playerId);
        } else {
            CONSENTS.put(playerId, state);
        }
        saveConsents();
        if (state != ConsentState.ACCEPTED) {
            forgetPlayer(playerId);
        }
    }

    private static void saveConsents() {
        if (consentFile == null) return;
        Properties properties = new Properties();
        CONSENTS.forEach((id, state) -> properties.setProperty(id.toString(), state.name()));
        Path temp = consentFile.resolveSibling(consentFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(consentFile.getParent());
            try (var output = Files.newOutputStream(temp)) {
                properties.store(output, "Parrot + Simple Voice Chat player consent choices");
            }
            try {
                Files.move(temp, consentFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, consentFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not persist player consent choices", exception);
        }
    }

    public static synchronized void forgetPlayer(UUID playerId) {
        if (parrotsDirectory == null || !Files.isDirectory(parrotsDirectory)) return;
        try (var files = Files.list(parrotsDirectory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".psvc")).toList()) {
                UUID parrotId;
                try {
                    String name = file.getFileName().toString();
                    parrotId = UUID.fromString(name.substring(0, name.length() - 5));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                ParrotMemory memory = load(parrotId);
                boolean changed = false;

                var wordIterator = memory.words.entrySet().iterator();
                while (wordIterator.hasNext()) {
                    Map.Entry<String, List<WordRecording>> entry = wordIterator.next();
                    if (entry.getValue().removeIf(clip -> clip.speakerId.equals(playerId))) changed = true;
                    if (entry.getValue().isEmpty()) wordIterator.remove();
                }

                var phraseIterator = memory.phrases.entrySet().iterator();
                while (phraseIterator.hasNext()) {
                    PhraseRecording phrase = phraseIterator.next().getValue();
                    if (phrase.contributors.contains(playerId)) {
                        Arrays.fill(phrase.audio, (byte) 0);
                        phraseIterator.remove();
                        changed = true;
                    }
                }

                var fragmentIterator = memory.fragments.iterator();
                while (fragmentIterator.hasNext()) {
                    Fragment fragment = fragmentIterator.next();
                    if (fragment.speakerId.equals(playerId)) {
                        for (StoredWord word : fragment.words) Arrays.fill(word.audio, (byte) 0);
                        fragmentIterator.remove();
                        changed = true;
                    }
                }
                if (changed) save(parrotId, memory);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not remove revoked clips for player {}", playerId, exception);
        }
    }

    public static synchronized void learnUtterance(
            UUID parrotId, UUID speakerId, UUID entityOwnerId,
            List<String> recognizedWords, List<short[]> wordSamples, short[] fullUtterance
    ) {
        if (recognizedWords == null || wordSamples == null || recognizedWords.isEmpty()
                || recognizedWords.size() != wordSamples.size() || !isConsenting(speakerId)) return;

        ParrotMemory memory = load(parrotId);
        if (entityOwnerId != null) memory.ownerId = entityOwnerId;
        else if (memory.ownerId == null) memory.ownerId = speakerId;
        UUID ownerId = memory.ownerId;

        List<StoredWord> fragmentWords = new ArrayList<>();
        for (int i = 0; i < recognizedWords.size(); i++) {
            String word = normalizeWord(recognizedWords.get(i));
            short[] samples = wordSamples.get(i);
            if (word.isBlank() || samples == null || samples.length < SOURCE_RATE / 12
                    || samples.length > (int) (SOURCE_RATE * 1.5)) continue;

            byte[] encoded = encodeAudio(samples);
            if (encoded.length == 0) continue;
            List<WordRecording> recordings = memory.words.computeIfAbsent(word, ignored -> new ArrayList<>());
            var existingIterator = recordings.iterator();
            while (existingIterator.hasNext()) {
                WordRecording existing = existingIterator.next();
                if (existing.speakerId.equals(speakerId)) {
                    Arrays.fill(existing.audio, (byte) 0);
                    existingIterator.remove();
                }
            }
            if (recordings.size() >= MAX_CLIPS_PER_WORD) {
                int removeAt = 0;
                for (int j = 0; j < recordings.size(); j++) {
                    if (!recordings.get(j).speakerId.equals(ownerId)) {
                        removeAt = j;
                        break;
                    }
                }
                WordRecording removed = recordings.remove(removeAt);
                Arrays.fill(removed.audio, (byte) 0);
            }
            recordings.add(new WordRecording(speakerId, encoded.clone()));
            fragmentWords.add(new StoredWord(word, encoded));
        }
        if (fragmentWords.isEmpty()) return;

        long now = System.currentTimeMillis();
        var oldFragments = memory.fragments.iterator();
        while (oldFragments.hasNext()) {
            Fragment old = oldFragments.next();
            if (now - old.timestamp > FRAGMENT_MAX_AGE_MILLIS) {
                old.words.forEach(word -> Arrays.fill(word.audio, (byte) 0));
                oldFragments.remove();
            }
        }
        memory.fragments.addLast(new Fragment(speakerId, now, List.copyOf(fragmentWords)));
        while (memory.fragments.size() > MAX_RECENT_FRAGMENTS) {
            Fragment removed = memory.fragments.removeFirst();
            removed.words.forEach(word -> Arrays.fill(word.audio, (byte) 0));
        }

        if (fullUtterance != null && recognizedWords.size() >= 2 && fullUtterance.length > 0) {
            String phraseText = String.join(" ", recognizedWords.stream()
                    .map(ParrotMemoryStore::normalizeWord).filter(word -> !word.isBlank()).toList());
            if (!phraseText.isBlank()) {
                putPhrase(memory, phraseText, encodeAudio(fullUtterance), 1,
                        !speakerId.equals(ownerId), true, Set.of(speakerId), now);
            }
        }
        createFragmentPhrases(memory, ownerId, now);
        save(parrotId, memory);
    }

    private static void createFragmentPhrases(ParrotMemory memory, UUID ownerId, long now) {
        List<Fragment> all = new ArrayList<>(memory.fragments);
        for (int size = 2; size <= Math.min(MAX_PHRASE_FRAGMENTS, all.size()); size++) {
            buildFragmentPhrase(memory, all.subList(all.size() - size, all.size()), ownerId, now);
        }
        List<Fragment> ownerFragments = all.stream()
                .filter(fragment -> fragment.speakerId.equals(ownerId)).toList();
        for (int size = 2; size <= Math.min(MAX_PHRASE_FRAGMENTS, ownerFragments.size()); size++) {
            List<Fragment> candidate = ownerFragments.subList(ownerFragments.size() - size, ownerFragments.size());
            if (candidate.get(candidate.size() - 1).timestamp == all.get(all.size() - 1).timestamp) {
                buildFragmentPhrase(memory, candidate, ownerId, now);
            }
        }
    }

    private static void buildFragmentPhrase(
            ParrotMemory memory, List<Fragment> fragments, UUID ownerId, long now
    ) {
        int count = fragments.stream().mapToInt(fragment -> fragment.words.size()).sum();
        if (count < 2 || count > MAX_PHRASE_WORDS) return;

        List<String> words = new ArrayList<>();
        List<byte[]> audioParts = new ArrayList<>();
        Set<UUID> contributors = new LinkedHashSet<>();
        boolean foreign = false;
        for (Fragment fragment : fragments) {
            contributors.add(fragment.speakerId);
            foreign |= !fragment.speakerId.equals(ownerId);
            for (StoredWord word : fragment.words) {
                words.add(word.word);
                audioParts.add(word.audio);
            }
        }
        byte[] composed = composeEncoded(audioParts);
        if (composed.length > 0) {
            putPhrase(memory, String.join(" ", words), composed, fragments.size(),
                    foreign, false, contributors, now);
        }
    }

    private static void putPhrase(
            ParrotMemory memory, String phraseText, byte[] audio, int fragments,
            boolean foreign, boolean fluent, Set<UUID> contributors, long now
    ) {
        if (audio == null || audio.length == 0) return;
        String key = normalizePhrase(phraseText);
        if (key.isBlank()) return;
        PhraseRecording candidate = new PhraseRecording(
                key, audio, fragments, foreign, fluent, Set.copyOf(contributors), now);
        PhraseRecording existing = memory.phrases.get(key);
        if (existing == null && memory.phrases.size() >= MAX_PHRASES_PER_FILE) {
            Arrays.fill(audio, (byte) 0);
            LOGGER.warn("Smart Parrot phrase-count limit reached; keeping existing phrases");
            return;
        }
        boolean preferred = existing == null || score(candidate) < score(existing)
                || (score(candidate) == score(existing) && candidate.updatedAt > existing.updatedAt);
        if (preferred) {
            long currentBytes = memory.phrases.values().stream().mapToLong(phrase -> phrase.audio.length).sum();
            long previousBytes = existing == null ? 0 : existing.audio.length;
            if (currentBytes - previousBytes + audio.length > MAX_PHRASE_AUDIO_BYTES) {
                Arrays.fill(audio, (byte) 0);
                LOGGER.warn("Smart Parrot phrase-audio storage limit reached; keeping existing phrases");
                return;
            }
            if (existing != null) Arrays.fill(existing.audio, (byte) 0);
            memory.phrases.put(key, candidate);
        } else {
            Arrays.fill(audio, (byte) 0);
        }
    }

    private static int score(PhraseRecording phrase) {
        return (phrase.foreign ? 1000 : 0) + (phrase.fluent ? 0 : 100) + phrase.fragments;
    }

    public static synchronized short[] getWordAudio(UUID parrotId, String requestedWord) {
        ParrotMemory memory = load(parrotId);
        List<WordRecording> recordings = memory.words.get(normalizeWord(requestedWord));
        if (recordings == null || recordings.isEmpty()) return null;
        return decodeAudio(selectWordRecording(memory, recordings).audio);
    }

    private static WordRecording selectWordRecording(ParrotMemory memory, List<WordRecording> recordings) {
        if (memory.ownerId != null) {
            for (WordRecording recording : recordings) {
                if (recording.speakerId.equals(memory.ownerId)) return recording;
            }
        }
        return recordings.get(recordings.size() - 1);
    }

    public static synchronized PhrasePlayback getPhrase(UUID parrotId, String phrase) {
        PhraseRecording found = load(parrotId).phrases.get(normalizePhrase(phrase));
        return found == null ? null : toPlayback(found);
    }

    public static synchronized PhrasePlayback getLatestPhrase(UUID parrotId) {
        PhraseRecording latest = load(parrotId).phrases.values().stream()
                .max((a, b) -> Long.compare(a.updatedAt, b.updatedAt)).orElse(null);
        return latest == null ? null : toPlayback(latest);
    }

    private static PhrasePlayback toPlayback(PhraseRecording phrase) {
        return new PhrasePlayback(decodeAudio(phrase.audio), phrase.text,
                phrase.fragments, phrase.foreign, phrase.fluent);
    }

    public static synchronized List<String> getWords(UUID parrotId) {
        return List.copyOf(load(parrotId).words.keySet());
    }

    public static synchronized int getWordCount(UUID parrotId) {
        return load(parrotId).words.size();
    }

    public static synchronized int getPhraseCount(UUID parrotId) {
        return load(parrotId).phrases.size();
    }

    public static synchronized List<String> getPhrases(UUID parrotId) {
        return load(parrotId).phrases.values().stream()
                .sorted((a, b) -> Long.compare(b.updatedAt, a.updatedAt))
                .limit(40)
                .map(phrase -> phrase.text + " (" + (phrase.fluent ? "1" : phrase.fragments)
                        + (phrase.foreign ? ", E" : "") + ")")
                .toList();
    }

    public static synchronized short[] composeWords(UUID parrotId, List<String> requestedWords) {
        ParrotMemory memory = load(parrotId);
        List<byte[]> parts = new ArrayList<>();
        for (String word : requestedWords) {
            List<WordRecording> recordings = memory.words.get(normalizeWord(word));
            if (recordings == null || recordings.isEmpty()) return null;
            parts.add(selectWordRecording(memory, recordings).audio);
        }
        byte[] encoded = composeEncoded(parts);
        return encoded.length == 0 ? null : decodeAudio(encoded);
    }

    private static byte[] composeEncoded(List<byte[]> audioParts) {
        List<short[]> decoded = new ArrayList<>();
        long total = (long) SEPARATOR_SAMPLES * Math.max(0, audioParts.size() - 1);
        for (byte[] part : audioParts) {
            short[] samples = decodeAudio(part);
            if (samples == null || samples.length == 0) {
                decoded.forEach(array -> Arrays.fill(array, (short) 0));
                return new byte[0];
            }
            decoded.add(samples);
            total += samples.length;
        }
        if (total > SOURCE_RATE * MAX_PHRASE_SECONDS) {
            decoded.forEach(array -> Arrays.fill(array, (short) 0));
            return new byte[0];
        }
        short[] result = new short[(int) total];
        int offset = 0;
        for (int i = 0; i < decoded.size(); i++) {
            short[] samples = decoded.get(i);
            System.arraycopy(samples, 0, result, offset, samples.length);
            offset += samples.length + (i < decoded.size() - 1 ? SEPARATOR_SAMPLES : 0);
            Arrays.fill(samples, (short) 0);
        }
        byte[] encoded = encodeAudio(result);
        Arrays.fill(result, (short) 0);
        return encoded;
    }

    /** 48 kHz PCM -> 16 kHz / 8-bit mono -> DEFLATE. */
    private static byte[] encodeAudio(short[] source) {
        if (source == null || source.length == 0) return new byte[0];
        int outputLength = (source.length + DECIMATION - 1) / DECIMATION;
        byte[] pcm = new byte[outputLength];
        for (int i = 0; i < outputLength; i++) {
            int start = i * DECIMATION;
            int end = Math.min(source.length, start + DECIMATION);
            int sum = 0;
            for (int j = start; j < end; j++) sum += source[j];
            pcm[i] = (byte) ((sum / (end - start)) >> 8);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(bytes)) {
                deflater.write(pcm);
            }
            Arrays.fill(pcm, (byte) 0);
            return bytes.toByteArray();
        } catch (IOException exception) {
            Arrays.fill(pcm, (byte) 0);
            return new byte[0];
        }
    }

    /** Inflates compact audio and expands it back to 48 kHz for the existing parrot DSP. */
    private static short[] decodeAudio(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_BLOB_BYTES) return null;
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(encoded));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int maximum = SOURCE_RATE * MAX_PHRASE_SECONDS / DECIMATION;
            while ((read = inflater.read(buffer)) != -1) {
                if (bytes.size() + read > maximum) return null;
                bytes.write(buffer, 0, read);
            }
            byte[] pcm = bytes.toByteArray();
            short[] result = new short[pcm.length * DECIMATION];
            for (int i = 0; i < pcm.length; i++) {
                short sample = (short) (pcm[i] << 8);
                int offset = i * DECIMATION;
                result[offset] = sample;
                result[offset + 1] = sample;
                result[offset + 2] = sample;
            }
            Arrays.fill(pcm, (byte) 0);
            return result;
        } catch (IOException exception) {
            LOGGER.warn("Could not decode a stored Smart Parrot audio clip", exception);
            return null;
        }
    }

    private static String normalizeWord(String word) {
        if (word == null) return "";
        String lower = word.toLowerCase(java.util.Locale.ROOT).trim().replace("ñ", "\uE000");
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace("\uE000", "ñ")
                .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }

    private static String normalizePhrase(String phrase) {
        if (phrase == null) return "";
        String lower = phrase.toLowerCase(java.util.Locale.ROOT).replace("ñ", "\uE000");
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace("\uE000", "ñ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static ParrotMemory load(UUID parrotId) {
        return MEMORIES.computeIfAbsent(parrotId, ParrotMemoryStore::loadFromDisk);
    }

    private static ParrotMemory loadFromDisk(UUID parrotId) {
        ParrotMemory memory = new ParrotMemory();
        if (parrotsDirectory == null) return memory;
        Path file = fileFor(parrotId);
        if (!Files.isRegularFile(file)) return memory;
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_FILE_BYTES) throw new IOException("Memory file size is outside limits");
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(Files.newInputStream(file))))) {
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION)
                    throw new IOException("Unsupported parrot memory file format");
                memory.ownerId = readNullableUuid(input);
                int wordCount = boundedCount(input.readInt(), MAX_WORDS_PER_FILE);
                for (int i = 0; i < wordCount; i++) {
                    String word = input.readUTF();
                    int recordings = boundedCount(input.readInt(), MAX_CLIPS_PER_WORD);
                    List<WordRecording> values = new ArrayList<>();
                    for (int j = 0; j < recordings; j++) {
                        UUID speakerId = readNullableUuid(input);
                        byte[] audio = readBlob(input);
                        if (speakerId != null && audio.length > 0) values.add(new WordRecording(speakerId, audio));
                    }
                    if (!values.isEmpty()) memory.words.put(word, values);
                }

                int phraseCount = boundedCount(input.readInt(), MAX_PHRASES_PER_FILE);
                for (int i = 0; i < phraseCount; i++) {
                    String text = input.readUTF();
                    int fragments = boundedCount(input.readInt(), MAX_PHRASE_FRAGMENTS);
                    boolean foreign = input.readBoolean();
                    boolean fluent = input.readBoolean();
                    long updated = input.readLong();
                    int contributorCount = boundedCount(input.readInt(), 16);
                    Set<UUID> contributors = new LinkedHashSet<>();
                    for (int j = 0; j < contributorCount; j++) {
                        UUID id = readNullableUuid(input);
                        if (id != null) contributors.add(id);
                    }
                    byte[] audio = readBlob(input);
                    if (audio.length > 0) memory.phrases.put(text,
                            new PhraseRecording(text, audio, fragments, foreign, fluent, Set.copyOf(contributors), updated));
                }

                int fragmentCount = boundedCount(input.readInt(), MAX_RECENT_FRAGMENTS);
                for (int i = 0; i < fragmentCount; i++) {
                    UUID speakerId = readNullableUuid(input);
                    long timestamp = input.readLong();
                    int count = boundedCount(input.readInt(), MAX_PHRASE_WORDS);
                    List<StoredWord> words = new ArrayList<>();
                    for (int j = 0; j < count; j++) words.add(new StoredWord(input.readUTF(), readBlob(input)));
                    if (speakerId != null && !words.isEmpty())
                        memory.fragments.addLast(new Fragment(speakerId, timestamp, List.copyOf(words)));
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load Smart Parrot memory {}: {}", parrotId, exception.getMessage());
            return new ParrotMemory();
        }
        while (memory.words.size() > maxWordsPerParrot) {
            String oldest = memory.words.keySet().iterator().next();
            List<WordRecording> removed = memory.words.remove(oldest);
            if (removed != null) removed.forEach(recording -> Arrays.fill(recording.audio, (byte) 0));
        }
        return memory;
    }

    private static void save(UUID parrotId, ParrotMemory memory) {
        if (parrotsDirectory == null) return;
        Path file = fileFor(parrotId);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(parrotsDirectory);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new DeflaterOutputStream(Files.newOutputStream(temp))))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                writeNullableUuid(output, memory.ownerId);
                output.writeInt(memory.words.size());
                for (Map.Entry<String, List<WordRecording>> entry : memory.words.entrySet()) {
                    output.writeUTF(entry.getKey());
                    output.writeInt(entry.getValue().size());
                    for (WordRecording recording : entry.getValue()) {
                        writeNullableUuid(output, recording.speakerId);
                        writeBlob(output, recording.audio);
                    }
                }
                output.writeInt(memory.phrases.size());
                for (PhraseRecording phrase : memory.phrases.values()) {
                    output.writeUTF(phrase.text);
                    output.writeInt(phrase.fragments);
                    output.writeBoolean(phrase.foreign);
                    output.writeBoolean(phrase.fluent);
                    output.writeLong(phrase.updatedAt);
                    output.writeInt(phrase.contributors.size());
                    for (UUID speakerId : phrase.contributors) writeNullableUuid(output, speakerId);
                    writeBlob(output, phrase.audio);
                }
                output.writeInt(memory.fragments.size());
                for (Fragment fragment : memory.fragments) {
                    writeNullableUuid(output, fragment.speakerId);
                    output.writeLong(fragment.timestamp);
                    output.writeInt(fragment.words.size());
                    for (StoredWord word : fragment.words) {
                        output.writeUTF(word.word);
                        writeBlob(output, word.audio);
                    }
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save Smart Parrot memory {}", parrotId, exception);
        }
    }

    private static Path fileFor(UUID parrotId) {
        return parrotsDirectory.resolve(parrotId + ".psvc");
    }

    private static int boundedCount(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid entry count in parrot memory file");
        return value;
    }

    private static byte[] readBlob(DataInputStream input) throws IOException {
        byte[] bytes = new byte[boundedCount(input.readInt(), MAX_BLOB_BYTES)];
        input.readFully(bytes);
        return bytes;
    }

    private static void writeBlob(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static UUID readNullableUuid(DataInputStream input) throws IOException {
        return input.readBoolean() ? new UUID(input.readLong(), input.readLong()) : null;
    }

    private static void writeNullableUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeBoolean(uuid != null);
        if (uuid != null) {
            output.writeLong(uuid.getMostSignificantBits());
            output.writeLong(uuid.getLeastSignificantBits());
        }
    }

    public record PhrasePlayback(short[] samples, String text, int fragments, boolean foreign, boolean fluent) {}

    private static final class ParrotMemory {
        private UUID ownerId;
        private final Map<String, List<WordRecording>> words = new LinkedHashMap<>();
        private final Map<String, PhraseRecording> phrases = new LinkedHashMap<>();
        private final Deque<Fragment> fragments = new ArrayDeque<>();
    }
    private static final class WordRecording {
        private final UUID speakerId;
        private final byte[] audio;
        private WordRecording(UUID speakerId, byte[] audio) { this.speakerId = speakerId; this.audio = audio; }
    }
    private static final class PhraseRecording {
        private final String text;
        private final byte[] audio;
        private final int fragments;
        private final boolean foreign;
        private final boolean fluent;
        private final Set<UUID> contributors;
        private final long updatedAt;
        private PhraseRecording(String text, byte[] audio, int fragments, boolean foreign,
                                boolean fluent, Set<UUID> contributors, long updatedAt) {
            this.text = text; this.audio = audio; this.fragments = fragments;
            this.foreign = foreign; this.fluent = fluent; this.contributors = contributors; this.updatedAt = updatedAt;
        }
    }
    private static final class Fragment {
        private final UUID speakerId;
        private final long timestamp;
        private final List<StoredWord> words;
        private Fragment(UUID speakerId, long timestamp, List<StoredWord> words) {
            this.speakerId = speakerId; this.timestamp = timestamp; this.words = words;
        }
    }
    private static final class StoredWord {
        private final String word;
        private final byte[] audio;
        private StoredWord(String word, byte[] audio) { this.word = word; this.audio = audio; }
    }
}
