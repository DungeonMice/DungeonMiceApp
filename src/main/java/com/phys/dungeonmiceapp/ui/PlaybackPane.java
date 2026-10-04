package com.phys.dungeonmiceapp.ui;

import java.io.File;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.*;
import javafx.scene.paint.Color;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public final class PlaybackPane extends VBox implements AutoCloseable {
    private final MediaView view = new MediaView();
    private final Region viewport;
    private final Label status = new Label("Selecciona un video");
    private final Label clock = new Label("00:00:00.000");
    private final Slider timeline = new Slider();
    private final Slider volume = new Slider(0, 1, .5);
    private final HBox playbackControls = new HBox(6);
    private final Consumer<String> log;
    private MediaPlayer player;
    private long generation;
    private double zoom = 1;
    private double panX, panY, mouseX, mouseY;
    private boolean updating;
    private boolean priming;
    private javafx.animation.PauseTransition pendingOpen;

    public PlaybackPane(boolean compact, Consumer<String> log) {
        this.log = log;
        view.setManaged(false); view.setPreserveRatio(true);
        viewport = new Region() {
            { getChildren().addAll(view, status); status.setManaged(false); }
            @Override protected void layoutChildren() {
                double width = Math.max(0, getWidth() - 2), height = Math.max(0, getHeight() - 2);
                double aspect = player != null && player.getMedia().getHeight() > 0
                        ? (double) player.getMedia().getWidth() / player.getMedia().getHeight() : 16.0 / 9;
                double fitWidth = Math.min(width, height * aspect), fitHeight = fitWidth / aspect;
                view.setFitWidth(fitWidth); view.setFitHeight(fitHeight);
                view.relocate((getWidth() - fitWidth) / 2, (getHeight() - fitHeight) / 2);
                status.autosize(); status.relocate((getWidth() - status.getWidth()) / 2, (getHeight() - status.getHeight()) / 2);
            }
        };
        viewport.setId("playback-viewport");
        viewport.getStyleClass().add("playback-viewport");
        viewport.setMinSize(0, compact ? 100 : 180); viewport.setPrefSize(748, compact ? 210 : 420);
        Rectangle clip = new Rectangle(); clip.widthProperty().bind(viewport.widthProperty()); clip.heightProperty().bind(viewport.heightProperty());
        clip.setArcWidth(16); clip.setArcHeight(16); viewport.setClip(clip);
        status.getStyleClass().add("playback-status"); status.setWrapText(true); status.setMaxWidth(400);
        timeline.setMax(1); timeline.setDisable(true); timeline.setId("playback-seek");
        timeline.valueProperty().addListener((obs, old, value) -> {
            if (!updating && player != null && timeline.isValueChanging()) player.seek(Duration.seconds(value.doubleValue()));
        });
        timeline.setOnMouseReleased(e -> seek(timeline.getValue()));
        timeline.setOnKeyReleased(e -> seek(timeline.getValue()));
        volume.setPrefWidth(compact ? 70 : 100);
        volume.setId("playback-volume");
        volume.setTooltip(new Tooltip("Volumen"));
        volume.valueProperty().addListener((obs, old, value) -> { if (player != null) player.setVolume(value.doubleValue()); });
        HBox buttons = playbackControls;
        buttons.setDisable(true);
        buttons.setAlignment(Pos.CENTER_LEFT); buttons.getStyleClass().add("playback-toolbar");
        buttons.getChildren().addAll(control("back", "Retroceder 5 segundos", () -> skip(-5)),
                control("first", "Ir al inicio", () -> seek(0)),
                control("previous", "Fotograma anterior (1/30 s)", () -> step(-1)),
                control("play", "Reproducir", this::play), control("pause", "Pausar", this::pause),
                control("stop", "Detener", () -> { if (player != null) { player.stop(); log.accept("Reproduccion detenida"); } }),
                control("next", "Fotograma siguiente (1/30 s)", () -> step(1)),
                control("last", "Ir al final", () -> { if (player != null) seek(player.getTotalDuration().toSeconds()); }));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        buttons.getChildren().addAll(spacer, control("volume", "Silenciar / activar sonido", () -> { if (player != null) player.setMute(!player.isMute()); }), volume);
        if (compact) {
            buttons.getChildren().remove(2); // Frame stepping remains in the full player.
            buttons.getChildren().remove(5);
        }
        clock.getStyleClass().add("timecode");
        setSpacing(8); setMinSize(0, 0); getChildren().addAll(viewport, clock, timeline, buttons);
        VBox.setVgrow(viewport, Priority.ALWAYS);
        viewport.setOnScroll(e -> {
            if (player == null) return;
            setZoom(zoom * (e.getDeltaY() > 0 ? 1.12 : 1 / 1.12)); e.consume();
        });
        viewport.setOnMousePressed(e -> { mouseX = e.getSceneX(); mouseY = e.getSceneY(); });
        viewport.setOnMouseDragged(e -> {
            if (zoom <= 1) return;
            panX += e.getSceneX() - mouseX; panY += e.getSceneY() - mouseY;
            mouseX = e.getSceneX(); mouseY = e.getSceneY(); clampPan();
        });
    }

    public void open(File file) {
        open(file, 0);
    }

    public void prepare() { close(); status.setText("Preparando video..."); }
    public void preparationMessage(String message) { if (player == null) status.setText(message); }
    public void showError(String message) { close(); fail(message); }

    private void open(File file, int attempt) {
        close(); resetZoom();
        status.setText("Cargando video..."); status.setVisible(true);
        if (!file.isFile()) { fail("No se encontro el archivo: " + file.getName()); return; }
        final long token = ++generation;
        try {
            Media media = new Media(file.toURI().toString());
            player = new MediaPlayer(media); final MediaPlayer loaded = player;
            view.setMediaPlayer(loaded); loaded.setVolume(volume.getValue());
            media.setOnError(() -> handleMediaError(file, attempt, token, media.getError()));
            loaded.setOnError(() -> handleMediaError(file, attempt, token, loaded.getError()));
            loaded.setOnReady(() -> {
                if (token != generation) return;
                status.setVisible(false); timeline.setDisable(false); playbackControls.setDisable(false);
                timeline.setMax(Math.max(.001, loaded.getTotalDuration().toSeconds()));
                loaded.seek(Duration.ZERO); viewport.requestLayout();
                clock.setText(format(Duration.ZERO) + " / " + format(loaded.getTotalDuration()));
                log.accept("Video listo: " + file.getName());
                priming = true; loaded.setMute(true); loaded.play();
                javafx.animation.PauseTransition firstFrame = new javafx.animation.PauseTransition(Duration.millis(180));
                firstFrame.setOnFinished(e -> {
                    if (token == generation && priming) { priming = false; loaded.pause(); loaded.setMute(false); }
                });
                firstFrame.play();
            });
            loaded.currentTimeProperty().addListener((obs, old, time) -> {
                if (token != generation) return;
                updating = true;
                if (!timeline.isValueChanging()) timeline.setValue(time.toSeconds());
                updating = false;
                clock.setText(format(time) + " / " + format(loaded.getTotalDuration()));
            });
            loaded.setOnEndOfMedia(() -> { if (token == generation) { loaded.pause(); log.accept("Fin del video"); } });
        } catch (MediaException e) { handleMediaError(file, attempt, token, e); }
    }

    private void handleMediaError(File file, int attempt, long token, MediaException error) {
        if (token != generation) return;
        // Windows' native decoder can still be releasing the previous preview of this URI.
        if (attempt < 3 && error != null && error.toString().contains("ERROR_MEDIA_INVALID")) {
            close(); status.setText("Cargando video...");
            long retryToken = generation;
            pendingOpen = new javafx.animation.PauseTransition(Duration.millis(400 * (attempt + 1)));
            pendingOpen.setOnFinished(e -> { if (retryToken == generation) open(file, attempt + 1); });
            pendingOpen.play();
        } else {
            close();
            fail("No se pudo abrir el video. Comprueba que el archivo este disponible y use un formato compatible."
                    + (error == null ? "" : "\n" + error.getMessage()));
        }
    }

    private void fail(String text) { status.setText(text); status.setVisible(true); timeline.setDisable(true); playbackControls.setDisable(true); log.accept(text); }
    public void play() { if (player != null) { if (priming) player.setMute(false); priming = false; player.play(); log.accept("Reproduciendo"); } }
    public void pause() { if (player != null) { if (priming) player.setMute(false); priming = false; player.pause(); log.accept("Pausado"); } }
    public void seek(double seconds) {
        if (player != null && Double.isFinite(seconds)) player.seek(Duration.seconds(Math.max(0, Math.min(seconds, timeline.getMax()))));
    }
    private void skip(double seconds) { if (player != null) seek(player.getCurrentTime().toSeconds() + seconds); }
    private void step(int direction) { pause(); skip(direction / 30.0); }
    public void resetZoom() { zoom = 1; panX = panY = 0; applyTransform(); }
    public void setZoom(double scale) { zoom = Math.max(1, Math.min(5, scale)); clampPan(); }
    private void clampPan() {
        double maxX = Math.max(0, (view.getFitWidth() * zoom - viewport.getWidth()) / 2);
        double maxY = Math.max(0, (view.getFitHeight() * zoom - viewport.getHeight()) / 2);
        panX = Math.max(-maxX, Math.min(maxX, panX)); panY = Math.max(-maxY, Math.min(maxY, panY)); applyTransform();
    }
    private void applyTransform() { view.setScaleX(zoom); view.setScaleY(zoom); view.setTranslateX(panX); view.setTranslateY(panY); }
    public MediaPlayer getPlayer() { return player; }
    public Region getViewport() { return viewport; }

    @Override public void close() {
        generation++; priming = false;
        if (pendingOpen != null) { pendingOpen.stop(); pendingOpen = null; }
        MediaPlayer previous = player; player = null;
        view.setMediaPlayer(null);
        // Disposing already stops playback; stop() can block a failing native decoder.
        if (previous != null) previous.dispose();
        timeline.setDisable(true); timeline.setValue(0); playbackControls.setDisable(true);
        status.setText("Selecciona un video"); status.setVisible(true); clock.setText("00:00:00.000");
    }

    public static String format(Duration duration) {
        if (duration == null || duration.isUnknown() || duration.isIndefinite()) return "00:00:00.000";
        long millis = Math.max(0, (long) duration.toMillis());
        return String.format("%02d:%02d:%02d.%03d", millis / 3600000, millis / 60000 % 60, millis / 1000 % 60, millis % 1000);
    }

    private Button control(String kind, String text, Runnable action) {
        Canvas canvas = new Canvas(16, 16); GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#202020")); g.setStroke(Color.web("#202020")); g.setLineWidth(1.6);
        if (kind.equals("pause")) { g.fillRect(4, 2, 3, 12); g.fillRect(10, 2, 3, 12); }
        else if (kind.equals("stop")) g.fillRect(3, 3, 10, 10);
        else if (kind.equals("volume")) { g.fillPolygon(new double[]{2, 6, 10, 10, 6, 2}, new double[]{6, 6, 3, 13, 10, 10}, 6); g.strokeArc(7, 3, 7, 10, -60, 120, javafx.scene.shape.ArcType.OPEN); }
        else {
            boolean backwards = kind.equals("back") || kind.equals("first") || kind.equals("previous");
            g.fillPolygon(backwards ? new double[]{11, 4, 11} : new double[]{4, 11, 4}, new double[]{2, 8, 14}, 3);
            if (kind.equals("first") || kind.equals("previous")) g.strokeLine(2, 2, 2, 14);
            if (kind.equals("last") || kind.equals("next")) g.strokeLine(14, 2, 14, 14);
        }
        Button button = new Button(); button.setGraphic(canvas); button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(text)); button.setAccessibleText(text); button.setOnAction(e -> action.run());
        return button;
    }
}
