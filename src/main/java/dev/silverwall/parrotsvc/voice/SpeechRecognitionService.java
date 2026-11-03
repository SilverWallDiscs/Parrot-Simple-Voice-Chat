package dev.silverwall.parrotsvc.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Offline Spanish speech recognition. The language choice comes from vanilla
 * Minecraft client options, so clients do not need this mod installed.
 */
public final class SpeechRecognitionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("parrot_svc/speech");
    private static final String MODEL_NAME = "vosk-model-small-es-0.42";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";
    private static final int RECOGNITION_SAMPLE_RATE = 16_000;
    private static final int SOURCE_SAMPLE_RATE = 48_000;
    private static final long MAX_MODEL_DOWNLOAD_BYTES = 90L * 1024L * 1024L;
    private static final long MAX_MODEL_EXTRACTED_BYTES = 240L * 1024L * 1024L;
    private static final int MAX_PENDING_RECOGNITIONS = 12;

    private static final AtomicInteger PENDING_RECOGNITIONS = new AtomicInteger();
    private static final Set<String> WARNED_UNSUPPORTED_LANGUAGES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static volatile ExecutorService executor = newExecutor();
    private static volatile Model spanishModel;
    private static volatile String status = "Not initialized";
    private static volatile boolean stopping;

    private SpeechRecognitionService() {
    }

    private static ExecutorService newExecutor() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "Parrot-Speech-Recognition");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    public static boolean supportsLanguage(String clientLanguage) {
        return clientLanguage != null
                && clientLanguage.toLowerCase(Locale.ROOT).replace('-', '_').startsWith("es");
    }

    public static String getStatus() {
        return status;
    }

    public static int getPendingRecognitionCount() {
        return PENDING_RECOGNITIONS.get();
    }

    /** Downloads and loads the small Spanish model off the server tick thread. */
    public static synchronized void initialize(Path configDirectory) {
        if ("Loading Spanish speech model".equals(status)
                || "Downloading Spanish speech model".equals(status)
                || spanishModel != null) {
            return;
        }

        stopping = false;
        status = "Downloading Spanish speech model";
        ExecutorService worker = executor;
        worker.execute(() -> {
            try {
                Path modelsDirectory = configDirectory.resolve("parrot-svc").resolve("models");
                Path modelDirectory = modelsDirectory.resolve(MODEL_NAME);
                Files.createDirectories(modelsDirectory);

                if (!isValidModelDirectory(modelDirectory)) {
                    LOGGER.info("Spanish voice model is missing. Downloading {} (about 39 MB) to {}",
                            MODEL_NAME, modelDirectory);
                    Path archive = modelsDirectory.resolve(MODEL_NAME + ".zip.part");
                    Path extractionDirectory = modelsDirectory.resolve(MODEL_NAME + ".extracting");
                    Files.deleteIfExists(archive);
                    deleteRecursively(extractionDirectory);
                    downloadModel(archive);
                    extractModel(archive, extractionDirectory);

                    Path extractedModel = extractionDirectory.resolve(MODEL_NAME);
                    if (!Files.isDirectory(extractedModel)) {
                        extractedModel = extractionDirectory;
                    }
                    if (!isValidModelDirectory(extractedModel)) {
                        throw new IOException("The downloaded archive does not contain a valid Vosk Spanish model");
                    }

                    deleteRecursively(modelDirectory);
                    Files.move(extractedModel, modelDirectory, StandardCopyOption.REPLACE_EXISTING);
                    deleteRecursively(extractionDirectory);
                    Files.deleteIfExists(archive);
                }

                if (stopping) {
                    return;
                }
                status = "Loading Spanish speech model";
                Model loadedModel = new Model(modelDirectory.toAbsolutePath().toString());
                if (stopping) {
                    loadedModel.close();
                    return;
                }
                spanishModel = loadedModel;
                status = "Ready (Spanish)";
                LOGGER.info("Spanish speech recognition is ready. Client language es_* will use {}.", MODEL_NAME);
            } catch (Exception exception) {
                if (!stopping) {
                    status = "Unavailable: " + safeMessage(exception);
                    LOGGER.error("Could not initialize Spanish speech recognition: {}", safeMessage(exception), exception);
                }
            }
        });
    }

    /**
     * Queues phrase recognition. Returns false if the phrase cannot be queued.
     * Callback is executed by the recognition worker.
     */
    public static boolean recognizeSpanishAsync(
            String clientLanguage,
            short[] sourceAudio,
            Consumer<List<WordSegment>> callback
    ) {
        if (!supportsLanguage(clientLanguage) || sourceAudio == null || sourceAudio.length < SOURCE_SAMPLE_RATE / 5) {
            return false;
        }

        int pending = PENDING_RECOGNITIONS.incrementAndGet();
        if (pending > MAX_PENDING_RECOGNITIONS) {
            PENDING_RECOGNITIONS.decrementAndGet();
            LOGGER.warn("Speech recognition queue is full; skipping one phrase to protect server performance.");
            return false;
        }

        short[] audioCopy = sourceAudio.clone();
        ExecutorService worker = executor;
        try {
            worker.execute(() -> {
                List<WordSegment> recognized = List.of();
                try {
                    Model model = spanishModel;
                    if (model != null && !stopping) {
                        recognized = recognize(model, audioCopy);
                    }
                } catch (Exception exception) {
                    LOGGER.warn("Spanish speech recognition failed for one phrase: {}", safeMessage(exception));
                }

                try {
                    callback.accept(recognized);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not deliver recognized words back to the server thread: {}", safeMessage(exception));
                } finally {
                    PENDING_RECOGNITIONS.decrementAndGet();
                    java.util.Arrays.fill(audioCopy, (short) 0);
                }
            });
            return true;
        } catch (RuntimeException exception) {
            PENDING_RECOGNITIONS.decrementAndGet();
            java.util.Arrays.fill(audioCopy, (short) 0);
            LOGGER.warn("Could not queue speech recognition: {}", safeMessage(exception));
            return false;
        }
    }

    private static List<WordSegment> recognize(Model model, short[] sourceAudio) {
        short[] audio16k = downsample48kTo16k(sourceAudio);
        List<WordSegment> words = new ArrayList<>();
        try (Recognizer recognizer = new Recognizer(model, RECOGNITION_SAMPLE_RATE)) {
            recognizer.setWords(true);
            recognizer.acceptWaveForm(audio16k, audio16k.length);
            parseWordResults(recognizer.getFinalResult(), words);
        } finally {
            java.util.Arrays.fill(audio16k, (short) 0);
        }
        return List.copyOf(words);
    }

    private static void parseWordResults(String json, List<WordSegment> output) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray result = root.getAsJsonArray("result");
            if (result == null) {
                return;
            }

            for (JsonElement element : result) {
                JsonObject wordObject = element.getAsJsonObject();
                String word = wordObject.get("word").getAsString()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
                double start = wordObject.get("start").getAsDouble();
                double end = wordObject.get("end").getAsDouble();
                double confidence = wordObject.has("conf") ? wordObject.get("conf").getAsDouble() : 0.0;

                if (word.isBlank() || !Double.isFinite(start) || !Double.isFinite(end)
                        || end <= start || confidence < 0.40) {
                    continue;
                }
                output.add(new WordSegment(word, start, end, confidence));
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("Vosk returned a result that could not be parsed: {}", safeMessage(exception));
        }
    }

    private static short[] downsample48kTo16k(short[] input) {
        int outputLength = input.length / 3;
        short[] output = new short[outputLength];
        for (int i = 0; i < outputLength; i++) {
            int source = i * 3;
            int sum = input[source] + input[source + 1] + input[source + 2];
            output[i] = (short) (sum / 3);
        }
        return output;
    }

    private static boolean isValidModelDirectory(Path directory) {
        return Files.isDirectory(directory)
                && Files.isRegularFile(directory.resolve("am").resolve("final.mdl"))
                && Files.isRegularFile(directory.resolve("conf").resolve("mfcc.conf"));
    }

    private static void downloadModel(Path destination) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(25))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(MODEL_URL))
                .timeout(Duration.ofMinutes(8))
                .header("User-Agent", "Parrot-Simple-Voice-Chat/0.2.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
                throw new IOException("Model download returned HTTP " + response.statusCode());
            }
        }

        try (InputStream input = new BufferedInputStream(response.body());
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_MODEL_DOWNLOAD_BYTES) {
                    throw new IOException("Spanish model archive exceeded the 90 MB download limit");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(destination);
            throw exception;
        }
    }

    private static void extractModel(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        long totalExtracted = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                Path output = destination.resolve(entry.getName()).normalize();
                if (!output.startsWith(destination.normalize())) {
                    throw new IOException("Rejected an unsafe path in the Spanish model archive");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(output))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            totalExtracted += read;
                            if (totalExtracted > MAX_MODEL_EXTRACTED_BYTES) {
                                throw new IOException("Spanish model archive exceeded the 240 MB extraction limit");
                            }
                            file.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException exception) {
            deleteRecursively(destination);
            throw exception;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    public static synchronized void shutdown() {
        stopping = true;
        ExecutorService oldExecutor = executor;
        oldExecutor.shutdownNow();
        executor = newExecutor();

        Model oldModel = spanishModel;
        spanishModel = null;
        if (oldModel != null) {
            try {
                oldModel.close();
            } catch (Exception exception) {
                LOGGER.debug("Could not close Spanish speech model cleanly", exception);
            }
        }
        PENDING_RECOGNITIONS.set(0);
        status = "Not initialized";
    }

    public record WordSegment(String word, double startSeconds, double endSeconds, double confidence) {
    }
}
