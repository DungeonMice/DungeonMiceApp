package com.phys.dungeonmiceapp.ui;

import com.google.gson.JsonArray;
import com.phys.dungeonmiceapp.api.PythonFrameClient;
import com.phys.dungeonmiceapp.model.Video;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;

/** Sequential analysis retaining only event snapshots on disk. */
public final class FrameBatchPane extends BorderPane implements AutoCloseable {
    private final List<Video> videos;
    private final Map<String, JsonArray> regions = new HashMap<>();
    private final URI address;
    private final String token;
    private final BiConsumer<Video, ActivityLog.Entry> log;
    private final java.util.function.Consumer<Video> currentVideo;
    private final DoubleProperty progress = new SimpleDoubleProperty();
    private final StringProperty message = new SimpleStringProperty();
    private final Runnable completed;
    private FramePlaybackPane current;
    private CompletableFuture<Void> released = CompletableFuture.completedFuture(null);
    private boolean closed;
    private int index, succeeded, failed;

    public FrameBatchPane(List<Video> videos, Map<String, JsonArray> regions, URI address, String token,
                          BiConsumer<Video, ActivityLog.Entry> log, java.util.function.Consumer<Video> currentVideo, Runnable completed) {
        if (videos.isEmpty()) throw new IllegalArgumentException("El proyecto no tiene videos");
        this.videos = new ArrayList<>(videos);
        for (Video video : videos) this.regions.put(video.getId(), Objects.requireNonNull(regions.get(video.getId())).deepCopy());
        this.address = address; this.token = token; this.log = log; this.currentVideo = currentVideo; this.completed = completed;
        setId("frame-batch"); setMinSize(0, 0);
    }
    public ReadOnlyDoubleProperty progressProperty() { return progress; }
    public ReadOnlyStringProperty messageProperty() { return message; }
    public CompletableFuture<Void> cleanupFuture() { return released; }
    public void start() { next(); }
    private void next() {
        if (closed) return;
        if (index == videos.size()) {
            progress.set(1); message.set("Lote terminado: " + succeeded + "/" + videos.size() + " completados, " + failed + " fallidos.");
            Label summary = new Label(message.get()); summary.setWrapText(true); setCenter(summary); completed.run(); return;
        }
        Video video = videos.get(index);
        currentVideo.accept(video);
        message.set((index + 1) + "/" + videos.size() + " - " + video.getTitle());
        try {
            current = new FramePlaybackPane(new PythonFrameClient(address, token), MediaFiles.resolve(video.getFilePath()).toPath(),
                    regions.get(video.getId()), text -> log.accept(video, text), video.getOwnerId(), video.getId());
            current.progressProperty().addListener((obs, old, fraction) -> {
                if (!closed) {
                    double value = Math.max(0, Math.min(1, fraction.doubleValue()));
                    progress.set((index + value) / videos.size());
                    message.set((index + 1) + "/" + videos.size() + " - " + video.getTitle() + " (" + (int) (value * 100) + "%)");
                }
            });
            current.setOnFinished(error -> finishedVideo(video, error)); setCenter(current); current.play();
        } catch (RuntimeException error) { log.accept(video, new ActivityLog.Entry(0, 0, "Error: " + error.getMessage(), true)); finishedVideo(video, error); }
    }
    private void finishedVideo(Video video, Throwable error) {
        if (closed) return;
        if (error == null) succeeded++; else failed++;
        index++; progress.set((double) index / videos.size());
        message.set("Liberando analizador... " + index + "/" + videos.size());
        released = current == null ? CompletableFuture.completedFuture(null) : current.closeAsync(); current = null;
        released.whenComplete((ignored, cleanupError) -> Platform.runLater(() -> {
            if (closed) return;
            if (cleanupError != null) {
                message.set("Lote interrumpido: no se pudo liberar el analizador. " + succeeded + " completados.");
                closed = true; completed.run(); return;
            }
            next();
        }));
    }
    public void setZoom(double zoom) { if (current != null) current.setZoom(zoom); }
    public boolean reviewFrame(String videoId, int frame) { return current != null && index < videos.size() && videos.get(index).getId().equals(videoId) && current.reviewFrame(frame); }
    public void pause() { if (current != null) current.pause(); }
    public CompletableFuture<Void> closeAsync() {
        if (!closed) {
            closed = true;
            message.set("Lote cancelado: " + succeeded + " completados, " + failed + " fallidos de " + videos.size() + ".");
            if (current != null) { released = current.closeAsync(); current = null; }
        }
        return released;
    }
    @Override public void close() { closeAsync(); }
}
