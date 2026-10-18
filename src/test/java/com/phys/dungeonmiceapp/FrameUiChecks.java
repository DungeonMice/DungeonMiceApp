package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.*;
import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import com.phys.dungeonmiceapp.ui.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javafx.application.*;
import javafx.animation.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class FrameUiChecks extends Application {
    private static Throwable failure;
    private WorkspaceView workspace;
    private ManagedPythonBridge bridge;
    private Stage stage;
    private Path temporary;
    private Timeline waiting;
    private int expectedWidth, expectedHeight;
    private String projectId;
    private void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    @Override public void start(Stage stage) {
        this.stage = stage;
        try {
            temporary = Files.createTempDirectory("dungeonmice-frame-ui-");
            System.setProperty("dungeonmice.database", temporary.resolve("data/database.enc").toString()); System.setProperty("dungeonmice.storage", temporary.resolve("app").toString());
            Files.createDirectories(temporary.resolve("app")); DatabaseManager.connect(); SessionManager.setCurrentUser(UserRepository.login("admin", "admin123"));
            VideoGroup group = VideoGroupRepository.saveProject(null, "Frames", "", "", "", "", "");
            projectId = group.getId();
            VideoRepository.addReferences(group.getId(), Collections.singletonList(FrameApiChecks.source(getParameters().getRaw().get(0)).toFile()));
            Video video = VideoRepository.findByGroup(group.getId()).get(0); List<VideoRegion> regions = new ArrayList<>();
            for (JsonElement item : JsonParser.parseString(Files.readString(Paths.get(getParameters().getRaw().get(1)))).getAsJsonArray()) {
                JsonObject region = item.getAsJsonObject(); if (!region.has("color")) region.addProperty("color", "#00874b"); regions.add(VideoRegion.fromJson(region));
            } VideoRepository.saveRegions(video.getId(), regions); expectedWidth = regions.get(0).getWidth(); expectedHeight = regions.get(0).getHeight();
            JsonArray priorLogs = new JsonArray(); priorLogs.add(new ActivityLog.Entry(2.0, 60, "Registro de analisis anterior", false).toJson());
            VideoRepository.saveActivity(Collections.singletonMap(video.getId(), priorLogs));
            workspace = new WorkspaceView(stage); Scene scene = new Scene(workspace, 1440, 900); scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm()); stage.setScene(scene); stage.show();
            ((TextField) workspace.lookup("#login-username")).setText("admin"); ((PasswordField) workspace.lookup("#login-password")).setText("admin123");
            for (Node node : workspace.lookupAll(".button")) if (node instanceof Button && ((Button) node).getText().equals("Iniciar sesi\u00f3n")) ((Button) node).fire();
            bridge = new ManagedPythonBridge(System.err::println); bridge.start().thenAccept(uri -> Platform.runLater(() -> {
                workspace.pythonConnected(uri.toString(), bridge.frameToken()); fire("nav-video");
                await(() -> ((PlaybackPane) workspace.lookup("#playback-viewport").getParent()).videoReadyProperty().get(), this::openFrames);
            })).exceptionally(error -> { Platform.runLater(() -> fail(error)); return null; });
        } catch (Throwable error) { fail(error); }
    }
    private void fire(String id) { ((Button) workspace.lookup("#" + id)).fire(); }
    private ImageView image() { return (ImageView) workspace.lookup("#analysis-frame"); }
    private FramePlaybackPane player() { return (FramePlaybackPane) workspace.lookup("#frame-player"); }
    private void openFrames() {
        check(workspace.lookup("#python-api-url") == null, "Automatic frame connection has no editable endpoint");
        check(((Label) workspace.lookup("#python-connection-status")).getText().equals("Listo para procesar"), "Internal connection reports processing readiness");
        check(!((TitledPane) workspace.lookup("#python-export-options")).isExpanded(), "MP4 export is not the primary analysis workflow");
        check(((Button) workspace.lookup("#python-process")).getText().equals("Procesar por frames"), "Main processing action explicitly selects the native frame engine");
        fire("python-process");
        await(() -> image() != null && image().getImage() != null, () -> {
            check(image().getImage().getWidth() == expectedWidth && image().getImage().getHeight() == expectedHeight, "Python frame rendered at original dimensions in main UI");
            ProgressBar progress = (ProgressBar) workspace.lookup("#frame-progress");
            check(progress != null && progress.isVisible() && progress.getProgress() > 0, "Individual frame player has a visible determinate progress bar");
            check(((Label) workspace.lookup("#frame-progress-label")).getText().contains("frames ("), "Individual progress displays frame count and percentage");
            check(((Button) workspace.lookup("#python-process")).isDisabled() && ((Button) workspace.lookup("#region-add")).isDisabled(), "Conflicting processing and region edits locked during frame session");
            snapshot("frames-1440x900.png");
            await(() -> ((Slider) workspace.lookup("#frame-seek")).getMax() >= 5, () -> {
                fire("frame-pause"); delay(700, () -> {
                    double paused = player().progressProperty().get(); delay(500, () -> {
                        check(player().progressProperty().get() == paused, "Pausing main UI stops frame analysis requests");
                        check(((ProgressBar) workspace.lookup("#frame-progress")).getProgress() == paused, "Visible frame progress stays fixed while paused");
                        check(((Label) workspace.lookup("#processing-status")).getText().startsWith("Pausado"), "Sidebar reports the individual analysis state");
                        ((TabPane) workspace.lookup("#video-inspector")).getSelectionModel().select(1); workspace.applyCss(); workspace.layout();
                        @SuppressWarnings("unchecked") ListView<ActivityLog.Entry> logs = (ListView<ActivityLog.Entry>) workspace.lookup("#activity-log");
                        ActivityLog.Entry pause = logs.getItems().stream().filter(entry -> entry.text.equals("Analisis pausado")).reduce((a, b) -> b).orElseThrow();
                        check(pause.frame > 0 && pause.seconds > 0 && ((Label) workspace.lookup("#frame-clock")).getText().startsWith(pause.time), "Individual frame logs carry native nonzero time and frame, not the disposed player's clock");
                        check(player().reviewFrame(Math.max(1, pause.frame - 1)), "Saved frame can be reviewed inside the bounded cache");
                        check(ProcessedVideoRepository.findByProject(projectId).isEmpty(), "Live frame analysis never registers a processed MP4");
                        try (java.util.stream.Stream<Path> files = Files.walk(temporary)) { check(files.noneMatch(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".mp4")), "Primary frame workflow produces no MP4 file"); }
                        catch (java.io.IOException error) { throw new RuntimeException(error); }
                        TabPane views = (TabPane) workspace.lookup("#frame-views");
                        check(views.getTabs().size() == 3, "Three analysis views available in main player");
                        javafx.scene.image.Image videoImage = image().getImage(); views.getSelectionModel().select(1);
                        check(image().getImage() != null && image().getImage() != videoImage, "Detection tab switches rendered image without a new frame request"); snapshot("detection-1440x900.png");
                        views.getSelectionModel().select(2); check(image().getImage() != null && player().progressProperty().get() == paused, "Trajectory tab keeps the same analysis frame and session"); snapshot("trajectory-1440x900.png");
                        fire("frame-previous"); delay(300, () -> {
                            check(player().progressProperty().get() == paused, "Previous frame uses cache without advancing analysis");
                            stage.setWidth(1000); stage.setHeight(740); delay(300, () -> {
                                snapshot("frames-1000x740.png"); Node next = workspace.lookup("#frame-next");
                                check(next.localToScene(next.getBoundsInLocal()).getMaxY() <= stage.getScene().getHeight(), "Frame controls fit compact window");
                                Node progressNode = workspace.lookup("#frame-progress"); check(progressNode.localToScene(progressNode.getBoundsInLocal()).getMaxY() <= stage.getScene().getHeight(), "Individual progress fits compact window");
                                checkInlineReview(() -> {
                                fire("nav-data"); check(workspace.lookup("#frame-player") == null, "Navigation disposes live frame player"); fire("nav-video");
                                await(() -> workspace.lookup("#playback-viewport") != null && ((PlaybackPane) workspace.lookup("#playback-viewport").getParent()).videoReadyProperty().get(), () -> {
                                    check(!((Button) workspace.lookup("#python-process")).isDisabled(), "Original playback and frame action restored after navigation"); Platform.exit();
                                });
                                });
                            });
                        });
                    });
                });
            });
        });
    }
    @SuppressWarnings("unchecked")
    private void checkInlineReview(Runnable after) {
        FramePlaybackPane analysis = player(); double progress = analysis.progressProperty().get();
        ListView<ActivityLog.Entry> logs = (ListView<ActivityLog.Entry>) workspace.lookup("#activity-log");
        logs.scrollTo(0); workspace.applyCss(); workspace.layout();
        ActivityLog.Entry previous = logs.getItems().stream().filter(entry -> entry.text.equals("Registro de analisis anterior")).findFirst().orElseThrow();
        ListCell<?> cell = logs.lookupAll(".list-cell").stream().filter(node -> node instanceof ListCell && ((ListCell<?>) node).getItem() == previous).map(node -> (ListCell<?>) node).findFirst().orElseThrow();
        long windows = javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing).count();
        javafx.event.Event.fireEvent(cell, new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 4, 4, 4, 4, javafx.scene.input.MouseButton.PRIMARY, 2, false, false, false, false, false, false, false, false, false, true, null));
        await(() -> {
            Node viewport = workspace.lookup("#log-review #playback-viewport");
            if (viewport == null) return false;
            PlaybackPane review = (PlaybackPane) viewport.getParent();
            return review.getPlayer() != null && review.getPlayer().getStatus() == javafx.scene.media.MediaPlayer.Status.PAUSED && Math.abs(review.getPlayer().getCurrentTime().toSeconds() - 2.0) < .25;
        }, () -> {
            check(javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing).count() == windows, "Uncached log opens at its timestamp inside the workspace without another window");
            check(analysis.progressProperty().get() == progress, "Inline original review does not advance or restart analysis");
            PlaybackPane review = (PlaybackPane) workspace.lookup("#log-review #playback-viewport").getParent(); DirectPlayer decoder = review.getPlayer();
            snapshot("inline-log-review-1000x740.png"); fire("close-log-review");
            check(workspace.lookup("#log-review") == null && workspace.lookup("#frame-player") == analysis, "Back action restores the same paused analysis and cached views");
            check(decoder.getStatus() == javafx.scene.media.MediaPlayer.Status.DISPOSED, "Closing inline review releases its VLC decoder");
            after.run();
        });
    }
    private void delay(int millis, Runnable action) { PauseTransition timer = new PauseTransition(Duration.millis(millis)); timer.setOnFinished(event -> safe(action)); timer.play(); }
    private void safe(Runnable action) { try { action.run(); } catch (Throwable error) { fail(error); } }
    private void await(BooleanSupplier ready, Runnable action) {
        if (waiting != null) waiting.stop(); int[] count = {0};
        waiting = new Timeline(new KeyFrame(Duration.millis(100), event -> { try { if (ready.getAsBoolean()) { waiting.stop(); action.run(); } else if (++count[0] > 300) throw new AssertionError("Frame UI timeout"); } catch (Throwable error) { fail(error); } })); waiting.setCycleCount(Animation.INDEFINITE); waiting.play();
    }
    private void snapshot(String name) {
        try { workspace.applyCss(); workspace.layout(); javafx.scene.image.WritableImage screenshot = workspace.snapshot(null, null); java.awt.image.BufferedImage png = new java.awt.image.BufferedImage((int) screenshot.getWidth(), (int) screenshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, screenshot.getPixelReader().getArgb(x, y)); Files.createDirectories(Paths.get("target/ui-snapshots")); javax.imageio.ImageIO.write(png, "png", Paths.get("target/ui-snapshots", name).toFile());
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void fail(Throwable error) { failure = error; error.printStackTrace(); if (waiting != null) waiting.stop(); Platform.exit(); }
    @Override public void stop() throws Exception {
        if (workspace != null) workspace.close(); if (bridge != null) bridge.close(); SessionManager.logout();
        if (temporary != null) try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
    }
    public static void main(String[] args) { launch(args); if (failure != null) throw new AssertionError("Frame UI checks failed", failure); }
}
