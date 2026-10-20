package com.phys.dungeonmiceapp.ui;

import java.io.File;
import java.util.function.Consumer;
import java.util.*;
import com.phys.dungeonmiceapp.model.VideoRegion;
import javafx.geometry.Point2D;
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
    private final javafx.scene.image.ImageView view = new javafx.scene.image.ImageView();
    private final Region viewport;
    private final Canvas regions = new Canvas();
    private List<VideoRegion> regionData = Collections.emptyList();
    private List<Point2D> draft = Collections.emptyList();
    private Consumer<Point2D> regionClick;
    private Consumer<Point2D> regionMove;
    private List<Point2D> radiusGuide = Collections.emptyList();
    private String highlighted;
    private final javafx.beans.property.BooleanProperty videoReady = new javafx.beans.property.SimpleBooleanProperty(false);
    private final Label status = new Label("Selecciona un video");
    private final LoadingAnimation loading = new LoadingAnimation(160);
    private final Label clock = new Label("00:00:00.000");
    private final Slider timeline = new Slider();
    private final Slider volume = new Slider(0, 1, .5);
    private final FlowPane playbackControls = new FlowPane(6, 6);
    private final Consumer<String> log;
    private DirectPlayer player;
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
            { getChildren().addAll(view, regions, loading, status); status.setManaged(false); loading.setManaged(false); regions.setManaged(false); regions.setMouseTransparent(true); }
            @Override protected void layoutChildren() {
                double width = Math.max(0, getWidth() - 2), height = Math.max(0, getHeight() - 2);
                double aspect = player != null && player.getMedia().getHeight() > 0
                        ? (double) player.getMedia().getWidth() / player.getMedia().getHeight() : 16.0 / 9;
                double fitWidth = Math.min(width, height * aspect), fitHeight = fitWidth / aspect;
                view.setFitWidth(fitWidth); view.setFitHeight(fitHeight);
                view.relocate((getWidth() - fitWidth) / 2, (getHeight() - fitHeight) / 2);
                regions.setWidth(getWidth()); regions.setHeight(getHeight()); drawRegions();
                status.autosize(); status.relocate((getWidth() - status.getWidth()) / 2, (getHeight() - status.getHeight()) / 2);
                double loaderHeight = Math.min(74, getHeight() * .3), loaderWidth = loaderHeight * 320 / 148;
                loading.resizeRelocate((getWidth() - loaderWidth) / 2,
                        Math.max(4, (getHeight() - status.getHeight()) / 2 - loaderHeight - 10), loaderWidth, loaderHeight);
            }
        };
        viewport.setId("playback-viewport");
        viewport.getStyleClass().add("playback-viewport");
        viewport.setMinSize(0, compact ? 100 : 180); viewport.setPrefSize(748, compact ? 210 : 420);
        Rectangle clip = new Rectangle(); clip.widthProperty().bind(viewport.widthProperty()); clip.heightProperty().bind(viewport.heightProperty());
        clip.setArcWidth(16); clip.setArcHeight(16); viewport.setClip(clip);
        status.getStyleClass().add("playback-status"); status.setWrapText(true); status.setMaxWidth(400);
        status.setId("playback-status");
        loading.setId("video-loading"); loading.setVisible(false); loading.setMouseTransparent(true);
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
        FlowPane buttons = playbackControls;
        buttons.setDisable(true);
        buttons.setAlignment(Pos.CENTER_LEFT); buttons.getStyleClass().add("playback-toolbar");
        buttons.getChildren().addAll(control("back", "Retroceder 5 segundos", () -> skip(-5)),
                control("first", "Ir al inicio", () -> seek(0)),
                control("previous", "Fotograma anterior (1/30 s)", () -> step(-1)),
                control("play", "Reproducir", this::play), control("pause", "Pausar", this::pause),
                control("stop", "Detener", () -> { if (player != null) { player.stop(); log.accept("Reproduccion detenida"); } }),
                control("next", "Fotograma siguiente (1/30 s)", () -> step(1)),
                control("last", "Ir al final", () -> { if (player != null) seek(player.getTotalDuration().toSeconds()); }));
        HBox audio = new HBox(6, control("volume", "Silenciar / activar sonido", () -> { if (player != null) player.setMute(!player.isMute()); }), volume); audio.setAlignment(Pos.CENTER_LEFT);
        buttons.getChildren().add(audio);
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
            if (regionMove != null) { Point2D point = sceneToVideo(e.getSceneX(), e.getSceneY()); if (point != null) regionMove.accept(point); return; }
            if (regionClick != null || zoom <= 1) return;
            panX += e.getSceneX() - mouseX; panY += e.getSceneY() - mouseY;
            mouseX = e.getSceneX(); mouseY = e.getSceneY(); clampPan();
        });
        viewport.setOnMouseMoved(e -> {
            if (regionMove != null) { Point2D point = sceneToVideo(e.getSceneX(), e.getSceneY()); if (point != null) regionMove.accept(point); }
        });
        viewport.setOnMouseClicked(e -> {
            if (regionClick == null || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            Point2D point = sceneToVideo(e.getSceneX(), e.getSceneY());
            if (point != null) regionClick.accept(point);
            e.consume();
        });
    }

    public void open(File file) {
        openDirect(file);
    }

    public void prepare() { close(); loading.setVisible(true); status.setText("Preparando video..."); }
    public void preparationMessage(String message) { if (player == null) status.setText(message); }
    public void showError(String message) { close(); fail(message); }

    private void openDirect(File file) {
        close(); resetZoom();
        status.setText("Cargando video..."); status.setVisible(true);
        loading.setVisible(true);
        if (!file.isFile()) { fail("No se encontro el archivo: " + file.getName()); return; }
        final long token = ++generation;
        try {
            player = new DirectPlayer(file, view); final DirectPlayer loaded = player;
            loaded.setVolume(volume.getValue());
            loaded.setOnError(() -> {
                if (token != generation) return;
                RuntimeException error = loaded.getError(); close();
                fail(error == null ? "No se pudo abrir el video." : error.getMessage());
            });
            loaded.setOnReady(() -> {
                if (token != generation) return;
                videoReady.set(true);
                loading.setVisible(false);
                status.setVisible(false); timeline.setDisable(false); playbackControls.setDisable(false);
                timeline.setMax(Math.max(.001, loaded.getTotalDuration().toSeconds()));
                loaded.seek(Duration.ZERO); viewport.requestLayout();
                clock.setText(format(Duration.ZERO) + " / " + format(loaded.getTotalDuration()));
                log.accept("Video listo: " + file.getName());
                priming = true; loaded.setMute(true); loaded.play();
                javafx.animation.PauseTransition firstFrame = new javafx.animation.PauseTransition(Duration.millis(180));
                firstFrame.setOnFinished(e -> {
                    if (token == generation && priming) { priming = false; loaded.pause(); loaded.setMute(false); if (Double.isFinite(reviewTime)) { seek(reviewTime); reviewTime = Double.NaN; } }
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
        } catch (RuntimeException e) { close(); fail(e.getMessage()); }
    }

    private void fail(String text) { loading.setVisible(false); status.setText(text); status.setVisible(true); timeline.setDisable(true); playbackControls.setDisable(true); log.accept(text); }
    public void play() { if (player != null) { if (priming) player.setMute(false); priming = false; player.play(); log.accept("Reproduciendo"); } }
    public void pause() { if (player != null) { if (priming) player.setMute(false); priming = false; player.pause(); log.accept("Pausado"); } }
    public void seek(double seconds) {
        if (player != null && Double.isFinite(seconds)) player.seek(Duration.seconds(Math.max(0, Math.min(seconds, timeline.getMax()))));
    }
    private void skip(double seconds) { if (player != null) seek(player.getCurrentTime().toSeconds() + seconds); }
    private double reviewTime = Double.NaN;
    public void reviewAt(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return;
        if (!videoReady.get() || priming) reviewTime = seconds;
        else { pause(); seek(seconds); }
    }
    private void step(int direction) { pause(); skip(direction / 30.0); }
    public void resetZoom() { zoom = 1; panX = panY = 0; applyTransform(); }
    public void setZoom(double scale) { zoom = Math.max(1, Math.min(5, scale)); clampPan(); }
    private void clampPan() {
        double maxX = Math.max(0, (view.getFitWidth() * zoom - viewport.getWidth()) / 2);
        double maxY = Math.max(0, (view.getFitHeight() * zoom - viewport.getHeight()) / 2);
        panX = Math.max(-maxX, Math.min(maxX, panX)); panY = Math.max(-maxY, Math.min(maxY, panY)); applyTransform();
    }
    private void applyTransform() { view.setScaleX(zoom); view.setScaleY(zoom); view.setTranslateX(panX); view.setTranslateY(panY); drawRegions(); }
    public DirectPlayer getPlayer() { return player; }
    public Region getViewport() { return viewport; }
    public javafx.beans.property.ReadOnlyBooleanProperty videoReadyProperty() { return videoReady; }
    public void setRegionClickHandler(Consumer<Point2D> handler) {
        regionClick = handler; viewport.setCursor(handler == null ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.CROSSHAIR);
    }
    public void setRegionMoveHandler(Consumer<Point2D> handler) { regionMove = handler; }
    public void setRadiusGuide(List<Point2D> points) { radiusGuide = new ArrayList<>(points); drawRegions(); }
    public void setRegionOverlay(List<VideoRegion> data, List<Point2D> points, String selected) {
        regionData = new ArrayList<>(data); draft = new ArrayList<>(points); highlighted = selected; drawRegions();
    }
    public Point2D sceneToVideo(double sceneX, double sceneY) {
        if (!videoReady.get() || view.getFitWidth() <= 0 || view.getFitHeight() <= 0) return null;
        Point2D viewportPoint = viewport.sceneToLocal(sceneX, sceneY);
        if (!viewport.contains(viewportPoint)) return null;
        Point2D local = view.sceneToLocal(sceneX, sceneY);
        if (local.getX() < 0 || local.getY() < 0 || local.getX() >= view.getFitWidth() || local.getY() >= view.getFitHeight()) return null;
        return new Point2D(Math.min(player.getMedia().getWidth() - 1, local.getX() / view.getFitWidth() * player.getMedia().getWidth()),
                Math.min(player.getMedia().getHeight() - 1, local.getY() / view.getFitHeight() * player.getMedia().getHeight()));
    }
    public Point2D videoToScene(Point2D point) {
        return view.localToScene(point.getX() / player.getMedia().getWidth() * view.getFitWidth(), point.getY() / player.getMedia().getHeight() * view.getFitHeight());
    }
    private Point2D overlayPoint(Point2D point) {
        return view.localToParent(point.getX() / player.getMedia().getWidth() * view.getFitWidth(), point.getY() / player.getMedia().getHeight() * view.getFitHeight());
    }
    private void drawRegions() {
        GraphicsContext g = regions.getGraphicsContext2D(); g.clearRect(0, 0, regions.getWidth(), regions.getHeight());
        if (player == null || !videoReady.get()) return;
        for (VideoRegion region : regionData) {
            if (region.getWidth() != player.getMedia().getWidth() || region.getHeight() != player.getMedia().getHeight()) continue;
            if (region.isMorris()) {
                Point2D center = overlayPoint(region.getCenter()), edge = overlayPoint(region.getCenter().add(region.getRadius(), 0)); double radius = edge.getX() - center.getX();
                g.setStroke(Color.web(region.getColor(), .6)); g.setLineWidth(1); g.setLineDashes(5, 4); g.strokeOval(center.getX() - radius, center.getY() - radius, radius * 2, radius * 2); g.setLineDashes();
                g.strokeLine(center.getX() - 4, center.getY(), center.getX() + 4, center.getY()); g.strokeLine(center.getX(), center.getY() - 4, center.getX(), center.getY() + 4);
            }
            int count = region.getPoints().size(); double[] x = new double[count], y = new double[count];
            for (int i = 0; i < count; i++) { Point2D p = overlayPoint(region.getPoints().get(i)); x[i] = p.getX(); y[i] = p.getY(); }
            Color color = Color.web(region.getColor()); g.setFill(new Color(color.getRed(), color.getGreen(), color.getBlue(), .12));
            g.fillPolygon(x, y, count); g.setStroke(color); g.setLineWidth(region.getName().equals(highlighted) ? 3 : 1.5); g.strokePolygon(x, y, count);
            g.setFill(Color.BLACK); g.fillText(region.getName(), x[0] + 5, y[0] - 5); g.setFill(color); g.fillText(region.getName(), x[0] + 4, y[0] - 6);
        }
        if (radiusGuide.size() == 2) {
            Point2D center = overlayPoint(radiusGuide.get(0)), edge = overlayPoint(radiusGuide.get(1));
            g.setStroke(Color.BLACK); g.setLineWidth(5); g.strokeLine(center.getX(), center.getY(), edge.getX(), edge.getY());
            g.setStroke(Color.WHITE); g.setLineWidth(2); g.strokeLine(center.getX(), center.getY(), edge.getX(), edge.getY());
            for (Point2D handle : Arrays.asList(center, edge)) { g.setFill(Color.BLACK); g.fillOval(handle.getX() - 5, handle.getY() - 5, 10, 10); g.setFill(Color.WHITE); g.fillOval(handle.getX() - 3, handle.getY() - 3, 6, 6); }
            String text = String.format(java.util.Locale.ROOT, "r = %.1f px", radiusGuide.get(0).distance(radiusGuide.get(1)));
            double x = (center.getX() + edge.getX()) / 2 + 7, y = (center.getY() + edge.getY()) / 2 - 7;
            g.setStroke(Color.BLACK); g.setLineWidth(3); g.strokeText(text, x, y); g.setFill(Color.WHITE); g.fillText(text, x, y);
        }
        g.setStroke(Color.WHITE); g.setFill(Color.WHITE); g.setLineWidth(2);
        Point2D previous = null;
        for (Point2D point : draft) {
            Point2D p = overlayPoint(point); g.fillOval(p.getX() - 3, p.getY() - 3, 6, 6);
            if (previous != null) g.strokeLine(previous.getX(), previous.getY(), p.getX(), p.getY()); previous = p;
        }
    }

    @Override public void close() {
        regionMove = null; regionClick = null; radiusGuide = Collections.emptyList();
        generation++; priming = false; reviewTime = Double.NaN;
        if (pendingOpen != null) { pendingOpen.stop(); pendingOpen = null; }
        DirectPlayer previous = player; player = null;
        videoReady.set(false); view.setImage(null); view.setViewport(null); drawRegions();
        loading.setVisible(false);
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
