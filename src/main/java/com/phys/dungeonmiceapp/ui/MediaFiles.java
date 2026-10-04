package com.phys.dungeonmiceapp.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonParser;

/** FFmpeg file work only; callers persist repository changes on the JavaFX thread. */
public final class MediaFiles {
    private MediaFiles() {}

    public static Path storage() {
        return Paths.get(System.getProperty("dungeonmice.storage", ".")).toAbsolutePath().normalize();
    }

    public static File resolve(String path) {
        Path file = Paths.get(java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC));
        Path resolved = (file.isAbsolute() ? file : storage().resolve(file)).normalize();
        if (Files.isRegularFile(resolved)) return resolved.toFile();
        // Old databases may contain absolute paths from a different checkout/drive.
        String portable = path.replace('\\', '/');
        int videos = portable.toLowerCase(Locale.ROOT).indexOf("/videos/");
        if (videos >= 0) {
            Path relocated = storage().resolve(portable.substring(videos + 1)).normalize();
            if (relocated.startsWith(storage().resolve("videos")) && Files.isRegularFile(relocated)) return relocated.toFile();
        }
        return resolved.toFile();
    }

    public static File importVideo(File input, String userId, String groupId) throws Exception {
        Path directory = storage().resolve("videos").resolve(safeId(userId)).resolve(safeId(groupId));
        Files.createDirectories(directory);
        Path output = directory.resolve(UUID.randomUUID() + ".mp4");
        try {
            if (isNative(input)) Files.copy(input.toPath(), output);
            else convert(input, output, false);
            return output.toFile();
        } catch (Exception e) { Files.deleteIfExists(output); throw e; }
    }

    public static File preview(File source) throws Exception {
        if (isNative(source)) return source;
        Path file = cache(source, "previews", ".mp4");
        if (!Files.isRegularFile(file)) convert(source, file, true);
        return file.toFile();
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

    private static String safeId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Identificador de proyecto no valido.");
        return id;
    }

    private static Path cache(File input, String folder, String suffix) throws IOException {
        Path directory = storage().resolve("data").resolve(folder); Files.createDirectories(directory);
        String key = input.getCanonicalPath() + ":" + input.lastModified() + ":" + input.length();
        return directory.resolve(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + suffix);
    }

    private static boolean isNative(File input) throws Exception {
        if (!input.isFile()) throw new IOException("Archivo no encontrado: " + input.getName());
        if (!input.getName().toLowerCase(Locale.ROOT).endsWith(".mp4") && !input.getName().toLowerCase(Locale.ROOT).endsWith(".m4v")) return false;
        String probe;
        try {
            probe = run(Arrays.asList(System.getProperty("dungeonmice.ffprobe", "ffprobe"), "-v", "error", "-show_streams", "-of", "json", input.getAbsolutePath()), 20);
        } catch (IOException e) { return false; }
        boolean hasVideo = false;
        for (com.google.gson.JsonElement element : JsonParser.parseString(probe).getAsJsonObject().getAsJsonArray("streams")) {
            com.google.gson.JsonObject stream = element.getAsJsonObject();
            String type = stream.get("codec_type").getAsString();
            String codec = stream.has("codec_name") ? stream.get("codec_name").getAsString() : "";
            if (type.equals("video")) { if (!codec.equals("h264")) return false; hasVideo = true; }
            if (type.equals("audio") && !codec.equals("aac") && !codec.equals("mp3")) return false;
        }
        return hasVideo;
    }

    private static void convert(File input, Path output, boolean preview) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", input.getAbsolutePath()));
        if (preview) args.addAll(Arrays.asList("-t", "15"));
        args.addAll(Arrays.asList("-map", "0:v:0", "-map", "0:a:0?", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-preset", "fast", "-crf", "23", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-movflags", "+faststart", output.toString()));
        try { run(args, preview ? 120 : 3600); }
        catch (Exception e) { Files.deleteIfExists(output); throw e; }
    }

    private static String ffmpeg() { return System.getProperty("dungeonmice.ffmpeg", "ffmpeg"); }

    private static String run(List<String> command, int timeout) throws Exception {
        Path log = Files.createTempFile("dungeonmice-ffmpeg-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IOException("El procesamiento del video excedio el tiempo disponible.");
            }
            String output = Files.readString(log);
            if (process.exitValue() != 0) throw new IOException("No se pudo procesar el video. " + output.substring(0, Math.min(500, output.length())));
            return output;
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt(); throw e;
        } finally { Files.deleteIfExists(log); }
    }
}
