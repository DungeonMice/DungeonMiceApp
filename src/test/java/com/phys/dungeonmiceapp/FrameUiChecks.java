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
    private void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    @Override public void start(Stage stage) {
        this.stage = stage;
        try {
            temporary = Files.createTempDirectory("dungeonmice-frame-ui-");
            System.setProperty("dungeonmice.database", temporary.resolve("data/database.enc").toString()); System.setProperty("dungeonmice.storage", temporary.resolve("app").toString());
            Files.createDirectories(temporary.resolve("app")); DatabaseManager.connect(); SessionManager.setCurrentUser(UserRepository.login("admin", "admin123"));
            VideoGroup group = VideoGroupRepository.saveProject(null, "Frames", "", "", "", "", "");
            VideoRepository.addReferences(group.getId(), Collections.singletonList(FrameApiChecks.source(getParameters().getRaw().get(0)).toFile()));
            Video video = VideoRepository.findByGroup(group.getId()).get(0); List<VideoRegion> regions = new ArrayList<>();
            for (JsonElement item : JsonParser.parseString(Files.readString(Paths.get(getParameters().getRaw().get(1)))).getAsJsonArray()) {
                JsonObject region = item.getAsJsonObject(); if (!region.has("color")) region.addProperty("color", "#00874b"); regions.add(VideoRegion.fromJson(region));
            } VideoRepository.saveRegions(video.getId(), regions);
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
        fire("python-frames");
        await(() -> image() != null && image().getImage() != null, () -> {
            check(image().getImage().getWidth() == 1280 && image().getImage().getHeight() == 720, "Python frame rendered at original dimensions in main UI");
            check(((Button) workspace.lookup("#python-process")).isDisabled() && ((Button) workspace.lookup("#region-add")).isDisabled(), "Conflicting processing and region edits locked during frame session");
            snapshot("frames-1440x900.png"); fire("frame-play");
            await(() -> player().progressProperty().get() > 3.0 / 10000, () -> {
                fire("frame-pause"); delay(700, () -> {
                    double paused = player().progressProperty().get(); delay(500, () -> {
                        check(player().progressProperty().get() == paused, "Pausing main UI stops frame analysis requests");
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
                                fire("nav-data"); check(workspace.lookup("#frame-player") == null, "Navigation disposes live frame player"); fire("nav-video");
                                await(() -> workspace.lookup("#playback-viewport") != null && ((PlaybackPane) workspace.lookup("#playback-viewport").getParent()).videoReadyProperty().get(), () -> {
                                    check(!((Button) workspace.lookup("#python-frames")).isDisabled(), "Original playback and frame action restored after navigation"); Platform.exit();
                                });
                            });
                        });
                    });
                });
            });
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
