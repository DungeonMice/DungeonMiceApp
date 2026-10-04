package com.phys.dungeonmiceapp.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Path handling only: videos are never copied, remuxed or converted. */
public final class MediaFiles {
    private MediaFiles() {}

    public static Path storage() {
        return Paths.get(System.getProperty("dungeonmice.storage", ".")).toAbsolutePath().normalize();
    }

    public static File resolve(String path) {
        Path file = Paths.get(path);
        Path resolved = (file.isAbsolute() ? file : storage().resolve(file)).normalize();
        if (Files.isRegularFile(resolved)) return resolved.toFile();
        Path normalized = Paths.get(java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC));
        Path fallback = (normalized.isAbsolute() ? normalized : storage().resolve(normalized)).normalize();
        if (!fallback.equals(resolved) && Files.isRegularFile(fallback)) return fallback.toFile();
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

    public static File playable(File source) throws IOException {
        if (!source.isFile()) throw new IOException("Archivo no encontrado: " + source);
        return source;
    }

    public static File preview(File source) throws IOException { return playable(source); }

    public static String copyPhoto(File source) throws IOException {
        if (source == null) return "";
        Path folder = storage().resolve("data/profiles"); Files.createDirectories(folder);
        String name = source.getName();
        String extension = name.lastIndexOf('.') < 0 ? ".png" : name.substring(name.lastIndexOf('.'));
        Path destination = folder.resolve(UUID.randomUUID() + extension);
        Files.copy(source.toPath(), destination);
        return destination.toString();
    }
}
