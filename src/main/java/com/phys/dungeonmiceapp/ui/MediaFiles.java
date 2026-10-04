package com.phys.dungeonmiceapp.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonParser;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** FFmpeg file work only; callers persist repository changes on the JavaFX thread. */
public final class MediaFiles {
    private MediaFiles() {}
    private static final Map<Path, ReentrantLock> preparationLocks = new ConcurrentHashMap<>();

    public static Path storage() {
        return Paths.get(System.getProperty("dungeonmice.storage", ".")).toAbsolutePath().normalize();
    }

    public static File resolve(String path) {
        Path file = Paths.get(path);
        Path resolved = (file.isAbsolute() ? file : storage().resolve(file)).normalize();
        if (Files.isRegularFile(resolved)) return resolved.toFile();
        // Windows can store visually identical names with different Unicode sequences.
        // Preserve the exact stored path; normalization is only a legacy fallback.
        Path normalized = Paths.get(java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC));
        Path fallback = (normalized.isAbsolute() ? normalized : storage().resolve(normalized)).normalize();
        if (!fallback.equals(resolved) && Files.isRegularFile(fallback)) return fallback.toFile();
        // Old databases may contain absolute paths from a different checkout/drive.
        String portable = path.replace('\\', '/');
        int videos = portable.toLowerCase(Locale.ROOT).indexOf("/videos/");
        if (videos >= 0) {
            Path relocated = storage().resolve(portable.substring(videos + 1)).normalize();
            if (relocated.startsWith(storage().resolve("videos")) && Files.isRegularFile(relocated)) return relocated.toFile();
        }
        return resolved.toFile();
    }

    public static List<File> videosInDirectory(String directory) throws IOException {
        if (directory == null || directory.trim().isEmpty()) return Collections.emptyList();
        Path folder = Paths.get(directory.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) throw new IOException("El directorio no existe: " + directory);
        List<File> videos = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            files.filter(Files::isRegularFile).filter(path -> isVideoFile(path.toFile()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> videos.add(path.toFile()));
        }
        return videos;
    }

    public static boolean isVideoFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && Arrays.asList(".mp4", ".m4v", ".mov", ".avi", ".mkv", ".wmv", ".webm", ".mpg", ".mpeg").contains(name.substring(dot));
    }

    public static List<File> references(Collection<File> sources) throws IOException {
        Map<Path, File> unique = new LinkedHashMap<>();
        for (File source : sources) {
            File file = source.getCanonicalFile();
            if (!file.isFile() || !isVideoFile(file)) throw new IOException("Video no disponible: " + source);
            unique.putIfAbsent(file.toPath(), file);
        }
        return new ArrayList<>(unique.values());
    }

    public static File playable(File source) throws Exception {
        return playable(source, text -> {});
    }

    public static File playable(File source, Consumer<String> progress) throws Exception {
        return prepareMedia(source, false, progress);
    }

    public static File preview(File source) throws Exception {
        return preview(source, text -> {});
    }

    public static File preview(File source, Consumer<String> progress) throws Exception {
        return prepareMedia(source, true, progress);
    }

    private static File prepareMedia(File source, boolean preview, Consumer<String> progress) throws Exception {
        progress.accept("Comprobando video...");
        boolean compatible = compatibleStreams(source);
        String name = source.getName().toLowerCase(Locale.ROOT);
        if (compatible && (name.endsWith(".mp4") || name.endsWith(".m4v"))) return source;
        Path output = cache(source, preview ? "previews" : "playback", ".mp4");
        ReentrantLock lock = preparationLocks.computeIfAbsent(output, path -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            if (Files.isRegularFile(output) && Files.size(output) > 0) return output.toFile();
            progress.accept(compatible ? "Adaptando contenedor, sin recodificar..." : "Convirtiendo video a formato compatible...");
            Path temporary = Files.createTempFile(output.getParent(), output.getFileName().toString() + "-", ".mp4");
            try {
                if (compatible) remux(source, temporary, preview);
                else convert(source, temporary, preview);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (Files.size(temporary) == 0) throw new IOException("El archivo preparado esta vacio.");
                try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
                return output.toFile();
            } finally { Files.deleteIfExists(temporary); }
        } finally { lock.unlock(); }
    }

    public static File thumbnail(File source) throws Exception {
        if (!source.isFile()) throw new IOException("Archivo no encontrado: " + source.getName());
        Path file = cache(source, "thumbnails", ".jpg");
        if (!Files.isRegularFile(file)) {
            try { run(Arrays.asList(ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", source.getAbsolutePath(),
                    "-frames:v", "1", "-vf", "scale=240:136:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=240:136:(ow-iw)/2:(oh-ih)/2", file.toString()), 60);
            } catch (Exception e) { Files.deleteIfExists(file); throw e; }
        }
        return file.toFile();
    }

    public static String copyPhoto(File source) throws IOException {
        if (source == null) return "";
        Path folder = storage().resolve("data/profiles"); Files.createDirectories(folder);
        String name = source.getName();
        String extension = name.lastIndexOf('.') < 0 ? ".png" : name.substring(name.lastIndexOf('.'));
        Path destination = folder.resolve(UUID.randomUUID() + extension);
        Files.copy(source.toPath(), destination);
        return destination.toString();
    }

    private static Path cache(File input, String folder, String suffix) throws IOException {
        Path directory = storage().resolve("data").resolve(folder); Files.createDirectories(directory);
        String key = "atomic-v2:" + input.getCanonicalPath() + ":" + input.lastModified() + ":" + input.length();
        return directory.resolve(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + suffix);
    }

    private static boolean compatibleStreams(File input) throws Exception {
        if (!input.isFile()) throw new IOException("Archivo no encontrado: " + input.getName());
        String probe;
        try {
            probe = run(Arrays.asList(System.getProperty("dungeonmice.ffprobe", "ffprobe"), "-v", "error", "-show_streams", "-of", "json", input.getAbsolutePath()), 20);
        } catch (IOException e) { return false; }
        boolean hasVideo = false;
        for (com.google.gson.JsonElement element : JsonParser.parseString(probe).getAsJsonObject().getAsJsonArray("streams")) {
            com.google.gson.JsonObject stream = element.getAsJsonObject();
            String type = stream.get("codec_type").getAsString();
            String codec = stream.has("codec_name") ? stream.get("codec_name").getAsString() : "";
            if (type.equals("video")) {
                if (!codec.equals("h264") || !stream.has("pix_fmt") || !stream.get("pix_fmt").getAsString().equals("yuv420p")) return false;
                hasVideo = true;
            }
            if (type.equals("audio") && !codec.equals("aac") && !codec.equals("mp3")) return false;
        }
        return hasVideo;
    }

    private static void convert(File input, Path output, boolean preview) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", input.getAbsolutePath()));
        if (preview) args.addAll(Arrays.asList("-t", "15"));
        args.addAll(Arrays.asList("-map", "0:v:0", "-map", "0:a:0?", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-threads", "2", "-preset", "fast", "-crf", "23", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-movflags", "+faststart", output.toString()));
        try { run(args, preview ? 120 : 3600); }
        catch (Exception e) { Files.deleteIfExists(output); throw e; }
    }

    private static void remux(File input, Path output, boolean preview) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", input.getAbsolutePath()));
        if (preview) args.addAll(Arrays.asList("-t", "15"));
        args.addAll(Arrays.asList("-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart", output.toString()));
        run(args, 180);
    }

    private static String ffmpeg() { return System.getProperty("dungeonmice.ffmpeg", "ffmpeg"); }

    private static String run(List<String> command, int timeout) throws Exception {
        Path log = Files.createTempFile("dungeonmice-ffmpeg-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("El procesamiento del video excedio el tiempo disponible.");
            }
            String output = Files.readString(log);
            if (process.exitValue() != 0) throw new IOException("No se pudo procesar el video. " + output.substring(0, Math.min(500, output.length())));
            return output;
        } catch (InterruptedException e) {
            if (process != null) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            Thread.currentThread().interrupt(); throw e;
        } finally { Files.deleteIfExists(log); }
    }
}
