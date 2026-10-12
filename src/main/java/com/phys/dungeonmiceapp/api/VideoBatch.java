package com.phys.dungeonmiceapp.api;

import com.google.gson.JsonArray;
import com.phys.dungeonmiceapp.model.Video;
import com.phys.dungeonmiceapp.ui.MediaFiles;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Sequential processing keeps memory and the single Python worker bounded. */
public final class VideoBatch {
    public static final class Item {
        private final Video video;
        private final Path source, destination;
        private final JsonArray regions;
        private Item(Video video, Path source, Path destination, JsonArray regions) {
            this.video = video; this.source = source; this.destination = destination; this.regions = regions.deepCopy();
        }
        public Video getVideo() { return video; }
        public Path getSource() { return source; }
        public Path getDestination() { return destination; }
        public JsonArray getRegions() { return regions.deepCopy(); }
    }
    @FunctionalInterface public interface Processor { Path process(Item item, Consumer<String> progress) throws Exception; }
    @FunctionalInterface public interface Completed { void accept(Item item, Path result) throws Exception; }
    @FunctionalInterface public interface Failed { void accept(Item item, Exception error); }

    public static List<Item> plan(List<Video> videos, Map<String, JsonArray> regions, Path directory, Path application) throws IOException {
        Path folder = directory.toRealPath();
        if (!Files.isDirectory(folder) || folder.startsWith(application.toRealPath())) throw new IOException("Selecciona una carpeta fuera de la aplicacion.");
        List<Item> items = new ArrayList<>(); Set<Path> reserved = new HashSet<>();
        for (Video video : videos) {
            JsonArray payload = regions.get(video.getId());
            try { com.phys.dungeonmiceapp.model.VideoRegion.analysisType(payload); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException(video.getTitle() + ": " + error.getMessage()); }
            Path source = MediaFiles.resolve(video.getFilePath()).toPath();
            String name = source.getFileName().toString(); int dot = name.lastIndexOf('.');
            String stem = dot < 0 ? name : name.substring(0, dot);
            if (stem.length() > 100) stem = stem.substring(0, 100);
            Path destination = folder.resolve(stem + "-procesado.mp4"); int suffix = 2;
            while (Files.exists(destination) || reserved.contains(destination)) destination = folder.resolve(stem + "-procesado-" + suffix++ + ".mp4");
            reserved.add(destination);
            items.add(new Item(video, source, destination, payload));
        }
        return Collections.unmodifiableList(items);
    }

    public static int run(List<Item> items, Processor processor, Completed completed, Failed failed, Consumer<String> progress) throws Exception {
        int successes = 0;
        for (int i = 0; i < items.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Item item = items.get(i); String prefix = (i + 1) + "/" + items.size() + " videos - " + item.video.getTitle() + "\n";
            progress.accept(prefix + "Iniciando");
            try {
                Path result = processor.process(item, text -> progress.accept(prefix + text));
                completed.accept(item, result); successes++;
            } catch (Exception error) {
                if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                failed.accept(item, error);
            }
        }
        return successes;
    }
    private VideoBatch() { }
}
