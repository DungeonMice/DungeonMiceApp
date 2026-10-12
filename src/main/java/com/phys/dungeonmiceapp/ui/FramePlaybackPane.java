package com.phys.dungeonmiceapp.ui;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.PythonFrameClient;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/** Demand-paced playback. At most one frame request/image decode is in flight. */
public final class FramePlaybackPane extends VBox implements AutoCloseable {
    private final PythonFrameClient client;
    private final Consumer<String> log;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread thread = new Thread(r, "dungeonmice-frames"); thread.setDaemon(true); return thread; });
    private final ImageView image = new ImageView();
    private final Label status = new Label("Conectando analisis por frames...");
    private final Label clock = new Label();
    private final Slider timeline = new Slider();
    private final ProgressIndicator loading = new ProgressIndicator();
    private final HBox controls = new HBox(6);
    private final StackPane viewport = new StackPane(image, loading, status);
    private final javafx.beans.property.DoubleProperty progress = new javafx.beans.property.SimpleDoubleProperty(0);
    private volatile boolean closed;
    private boolean playing, pending, ready;
    private int shown, processed, firstCached = 1, total;
    private double fps = 30, zoom = 1;
    private double mouseX, mouseY;
    private JsonObject previousStates;
    private Future<?> request;

    public FramePlaybackPane(PythonFrameClient client, Path source, JsonArray regions, Consumer<String> log) {
        this.client = client; this.log = log;
        setId("frame-player"); setSpacing(8); setMinSize(0, 0); setPadding(new Insets(12, 16, 16, 16));
        image.setPreserveRatio(true); image.setSmooth(true); image.setMouseTransparent(true); image.setId("analysis-frame");
        image.fitWidthProperty().bind(viewport.widthProperty().subtract(2)); image.fitHeightProperty().bind(viewport.heightProperty().subtract(2));
        viewport.getStyleClass().add("playback-viewport"); viewport.setMinSize(0, 180); VBox.setVgrow(viewport, Priority.ALWAYS);
        Rectangle clip = new Rectangle(); clip.widthProperty().bind(viewport.widthProperty()); clip.heightProperty().bind(viewport.heightProperty()); clip.setArcWidth(16); clip.setArcHeight(16); viewport.setClip(clip);
        loading.setMaxSize(32, 32); loading.setId("frame-loading"); status.getStyleClass().add("playback-status"); status.setWrapText(true); status.setMaxWidth(400); status.setId("frame-status");
        viewport.setOnScroll(event -> { setZoom(zoom * (event.getDeltaY() > 0 ? 1.12 : 1 / 1.12)); event.consume(); });
        viewport.setOnMousePressed(event -> { mouseX = event.getSceneX(); mouseY = event.getSceneY(); });
        viewport.setOnMouseDragged(event -> {
            if (zoom <= 1) return;
            image.setTranslateX(image.getTranslateX() + event.getSceneX() - mouseX); image.setTranslateY(image.getTranslateY() + event.getSceneY() - mouseY);
            mouseX = event.getSceneX(); mouseY = event.getSceneY(); clampPan();
        });
        timeline.setId("frame-seek"); timeline.setDisable(true); timeline.setMin(1);
        timeline.setOnMouseReleased(event -> seek((int) Math.round(timeline.getValue()))); timeline.setOnKeyReleased(event -> seek((int) Math.round(timeline.getValue())));
        controls.setAlignment(Pos.CENTER_LEFT); controls.getStyleClass().add("playback-toolbar"); controls.setDisable(true);
        controls.getChildren().addAll(button("|\u25c0", "frame-first", "Primer frame disponible", () -> seek(firstCached)),
            button("\u25c0", "frame-previous", "Frame anterior", () -> seek(shown - 1)),
            button("\u25b6", "frame-play", "Reproducir analisis", this::play),
            button("\u2161", "frame-pause", "Pausar analisis", this::pause),
            button("\u25b6|", "frame-next", "Frame siguiente", this::next));
        clock.getStyleClass().add("timecode"); getChildren().addAll(viewport, clock, timeline, controls);
        request = worker.submit(() -> {
            try {
                JsonObject info = client.open(source, regions);
                int count = info.get("total").getAsInt(); double rate = info.get("fps").getAsDouble();
                if (count <= 0 || !Double.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("Metadatos de video invalidos");
                Platform.runLater(() -> { if (closed) return; total = count; fps = rate; ready = true; controls.setDisable(false); status.setVisible(false); log.accept("Analisis por frames conectado"); next(); });
            } catch (Exception error) { failed(error); }
        });
    }
    public void play() { if (closed || !ready) return; playing = true; if (!pending) next(); }
    public void pause() { playing = false; }
    public void next() { if (!ready || pending || closed) return; fetch(shown < processed ? shown + 1 : -1); }
    public void seek(int index) {
        pause(); if (!ready || pending || closed || index == shown) return;
        if (index < firstCached || index > processed) { log.accept("Frame fuera de la ventana de cache: " + firstCached + "-" + processed); timeline.setValue(shown); return; }
        fetch(index);
    }
    private void fetch(int index) {
        long started = System.nanoTime();
        pending = true; loading.setVisible(true);
        request = worker.submit(() -> {
            try {
                PythonFrameClient.Frame frame = client.frame(index);
                Image decoded = frame == null ? null : new Image(new ByteArrayInputStream(frame.image));
                if (decoded != null && decoded.isError()) throw new IllegalArgumentException("Imagen recibida invalida");
                Platform.runLater(() -> {
                    if (closed) return;
                    pending = false; loading.setVisible(false);
                    if (frame == null) { playing = false; status.setText("Analisis completado"); status.setVisible(true); log.accept("Analisis por frames completado: " + processed + " frames"); return; }
                    shown = frame.metadata.get("index").getAsInt();
                    processed = Math.max(processed, shown);
                    progress.set((double) processed / total);
                    // Server exposes its exact byte/count-limited cache floor in each packet.
                    firstCached = frame.metadata.has("firstCached") ? frame.metadata.get("firstCached").getAsInt() : Math.max(1, processed - 119);
                    image.setImage(decoded); status.setVisible(false); timeline.setDisable(false); timeline.setMin(firstCached); timeline.setMax(Math.max(firstCached, processed)); timeline.setValue(shown);
                    clock.setText(PlaybackPane.format(Duration.seconds(frame.metadata.get("time").getAsDouble())) + "  |  Frame " + shown + "/" + total + "  |  Cache " + firstCached + "-" + processed);
                    JsonObject states = frame.metadata.getAsJsonObject("regions");
                    if (index < 0) {
                        for (String name : states.keySet()) {
                            boolean inside = states.getAsJsonObject(name).get("inside").getAsBoolean();
                            if (previousStates != null && previousStates.has(name) && previousStates.getAsJsonObject(name).get("inside").getAsBoolean() != inside) log.accept("Frame " + shown + " - " + name + ": " + (inside ? "entrada" : "salida"));
                        } previousStates = states.deepCopy();
                    }
                    if (playing) {
                        long delay = Math.max(0, (long) (1000 / fps) - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        request = worker.schedule(() -> Platform.runLater(() -> { if (!closed && playing) next(); }), delay, TimeUnit.MILLISECONDS);
                    }
                });
            } catch (Exception error) { failed(error); }
        });
    }
    private void failed(Exception error) {
        Platform.runLater(() -> { if (closed) return; pending = false; playing = false; loading.setVisible(false); status.setText(error.getMessage()); status.setVisible(true); log.accept("Error de analisis por frames: " + error.getMessage()); });
    }
    private static Button button(String text, String id, String tooltip, Runnable action) {
        Button button = new Button(text); button.setId(id); button.getStyleClass().add("icon-button"); button.setTooltip(new Tooltip(tooltip)); button.setAccessibleText(tooltip); button.setOnAction(event -> action.run()); return button;
    }
    public javafx.beans.property.ReadOnlyDoubleProperty progressProperty() { return progress; }
    public void setZoom(double value) {
        zoom = Math.max(1, Math.min(5, value)); image.setScaleX(zoom); image.setScaleY(zoom);
        if (zoom == 1) { image.setTranslateX(0); image.setTranslateY(0); } else clampPan();
    }
    private void clampPan() {
        double x = Math.max(0, (image.getBoundsInLocal().getWidth() * zoom - viewport.getWidth()) / 2);
        double y = Math.max(0, (image.getBoundsInLocal().getHeight() * zoom - viewport.getHeight()) / 2);
        image.setTranslateX(Math.max(-x, Math.min(x, image.getTranslateX()))); image.setTranslateY(Math.max(-y, Math.min(y, image.getTranslateY())));
    }
    @Override public void close() {
        if (closed) return; closed = true; playing = false;
        if (request != null) request.cancel(true); worker.shutdownNow(); image.setImage(null);
        Thread cleanup = new Thread(client::close, "dungeonmice-frame-close"); cleanup.setDaemon(true); cleanup.start();
    }
}
