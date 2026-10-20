package com.phys.dungeonmiceapp.ui;

import com.google.gson.*;
import com.phys.dungeonmiceapp.model.Video;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Owned analysis artifacts survive the Python session and application restarts. */
public final class AnalysisReports {
    private AnalysisReports() { }
    public static final class Report {
        public final Path directory;
        public final JsonObject metadata;
        private Report(Path directory) throws IOException {
            this.directory = directory;
            Path file = directory.resolve("report.json");
            if (Files.size(file) > 8 * 1024 * 1024) throw new IOException("Metadatos del reporte demasiado grandes");
            try {
                metadata = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                String workbook = metadata.get("workbook").getAsString();
                if (!workbook.matches("[^/\\\\:]+\\.xlsx") || !Files.isRegularFile(directory.resolve(workbook), LinkOption.NOFOLLOW_LINKS)
                        || !metadata.has("sheets") || metadata.getAsJsonArray("sheets").size() == 0) throw new IOException("Reporte Excel incompleto");
            } catch (RuntimeException ex) { throw new IOException("Reporte danado", ex); }
        }
        public Path workbook() { return directory.resolve(metadata.get("workbook").getAsString()); }
        @Override public String toString() { return directory.getParent().getFileName().toString(); }
    }
    public static void store(Path destination, byte[] packet) throws IOException {
        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(destination.getParent(), ".reports-");
        try {
            int total = 0, count = 0;
            Set<String> names = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packet))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (++count > 32 || entry.isDirectory() || !name.matches("[^/\\\\:]+\\.(xlsx|png|json)") || name.equals("..") || !names.add(name))
                        throw new IOException("Archivo de reporte invalido");
                    try (OutputStream out = Files.newOutputStream(staging.resolve(name), StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[16384]; int length;
                        while ((length = zip.read(buffer)) != -1) {
                            total += length; if (total > 64 * 1024 * 1024) throw new IOException("Reporte demasiado grande");
                            out.write(buffer, 0, length);
                        }
                    }
                }
            }
            new Report(staging);
            Files.move(staging, destination);
        } finally {
            if (Files.exists(staging)) try (java.util.stream.Stream<Path> files = Files.walk(staging)) {
                for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(file);
            }
        }
    }
    public static List<Report> find(Video video) throws IOException {
        Path parent = EventFrameArchive.location(video.getOwnerId(), video.getId(), "probe").getParent();
        List<Report> reports = new ArrayList<>();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return reports;
        try (DirectoryStream<Path> runs = Files.newDirectoryStream(parent)) {
            for (Path run : runs) {
                if (!Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS) || !run.getFileName().toString().matches("[A-Za-z0-9_-]{1,80}")) continue;
                Path marker = run.resolve("markers.json"), folder = run.resolve("reports");
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    JsonObject saved = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
                    if (video.getOwnerId().equals(saved.get("owner").getAsString()) && video.getId().equals(saved.get("video").getAsString())
                            && "completed".equals(saved.get("state").getAsString())) reports.add(new Report(folder));
                } catch (RuntimeException | IOException ex) { System.err.println("Reporte no disponible: " + ex.getMessage()); }
            }
        }
        reports.sort(Comparator.comparingLong((Report report) -> report.directory.toFile().lastModified()).reversed());
        return reports;
    }
}
