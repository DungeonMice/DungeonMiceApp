package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.ManagedPythonBridge;
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
import javafx.scene.image.*;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Native analysis followed by event review with Python stopped and a rebuilt workspace. */
public final class FlagEventUiChecks extends Application {
    private static Throwable failure;
    private WorkspaceView workspace;
    private ManagedPythonBridge bridge;
    private Stage stage;
    private Path temporary;
    private Timeline waiting;
    private Video video;
    private ActivityLog.Entry marker;
    private final Map<String, Image> expected = new LinkedHashMap<>();
    private void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    private void fire(String id) { ((Button) workspace.lookup("#" + id)).fire(); }
    @Override public void start(Stage stage) {
        this.stage = stage;
        safe(() -> {
            temporary = Files.createTempDirectory("dungeonmice-flag-ui-");
            System.setProperty("dungeonmice.database", temporary.resolve("data/database.enc").toString());
            System.setProperty("dungeonmice.storage", temporary.resolve("app").toString()); Files.createDirectories(temporary.resolve("app"));
            System.setProperty("dungeonmice.events.directory", temporary.resolve("events").toString());
            DatabaseManager.connect(); SessionManager.setCurrentUser(UserRepository.login("admin", "admin123"));
            String project = VideoGroupRepository.saveProject(null, "Flag events", "", "", "", "", "").getId();
            VideoRepository.addReferences(project, Collections.singletonList(Paths.get(getParameters().getRaw().get(0)).resolve("first.avi").toFile()));
            video = VideoRepository.findByGroup(project).get(0); List<VideoRegion> regions = new ArrayList<>();
            for (JsonElement item : JsonParser.parseString(Files.readString(Paths.get(getParameters().getRaw().get(1)))).getAsJsonArray()) {
                JsonObject region = item.getAsJsonObject(); if (!region.has("color")) region.addProperty("color", "#00874b"); regions.add(VideoRegion.fromJson(region));
            }
            VideoRepository.saveRegions(video.getId(), regions); buildWorkspace();
            bridge = new ManagedPythonBridge(System.err::println);
            bridge.start().thenAccept(uri -> Platform.runLater(() -> safe(() -> {
                workspace.pythonConnected(uri.toString(), bridge.frameToken()); fire("nav-video");
                await(this::originalReady, () -> {
                    ((TabPane) workspace.lookup("#video-inspector")).getSelectionModel().select(1);
                    fire("python-process");
                    await(() -> workspace.lookup("#analysis-frame") != null && ((ImageView) workspace.lookup("#analysis-frame")).getImage() != null, this::duringAnalysis);
                });
            }))).exceptionally(error -> { Platform.runLater(() -> fail(error)); return null; });
        });
    }
    private void buildWorkspace() {
        workspace = new WorkspaceView(stage); Scene scene = new Scene(workspace, 1440, 900);
        scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm()); stage.setScene(scene); stage.show();
        ((TextField) workspace.lookup("#login-username")).setText("admin"); ((PasswordField) workspace.lookup("#login-password")).setText("admin123");
        for (Node node : workspace.lookupAll(".button")) if (node instanceof Button && ((Button) node).getText().equals("Iniciar sesi\u00f3n")) ((Button) node).fire();
    }
    private boolean originalReady() {
        Node viewport = workspace.lookup("#playback-viewport");
        return viewport != null && viewport.getParent() instanceof PlaybackPane && ((PlaybackPane) viewport.getParent()).videoReadyProperty().get();
    }
    private void duringAnalysis() {
        TabPane inspector = (TabPane) workspace.lookup("#video-inspector");
        check(inspector.getTabs().get(1).isDisabled() && inspector.getSelectionModel().getSelectedIndex() == 0, "Starting analysis hides and locks logs");
        fire("frame-pause");
        delay(400, () -> {
            check(inspector.getTabs().get(1).isDisabled(), "Pausing analysis does not unlock logs");
            fire("frame-play");
            await(() -> workspace.lookup("#frame-player") == null && ((Label) workspace.lookup("#processing-status")).getText().startsWith("Completado"), this::afterAnalysis);
        });
    }
    private void afterAnalysis() throws Exception {
        check(!((TabPane) workspace.lookup("#video-inspector")).getTabs().get(1).isDisabled(), "Logs become available after final snapshot is saved");
        check(((ProgressBar) workspace.lookup("#processing-progress")).getProgress() == 1, "Completion keeps 100 percent progress visible");
        for (JsonElement item : VideoRepository.findActivity(video.getId())) {
            ActivityLog.Entry entry = ActivityLog.Entry.fromJson(item.getAsJsonObject());
            if (entry.text.startsWith("Analisis por frames completado")) marker = entry;
        }
        check(marker != null && marker.archive != null && marker.frame == 60 && marker.seconds == 2.0, "Persisted final log contains an exact frame marker and archive reference");
        com.phys.dungeonmiceapp.api.PythonFrameClient.Frame frame = EventFrameArchive.load(video.getOwnerId(), video.getId(), marker);
        for (Map.Entry<String, byte[]> view : frame.views.entrySet()) expected.put(view.getKey(), new Image(new java.io.ByteArrayInputStream(view.getValue())));
        long pid = bridge.pid(); bridge.close();
        check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Event review is tested with native Python stopped");
        workspace.close(); buildWorkspace(); fire("nav-video");
        await(this::originalReady, () -> {
            TabPane inspector = (TabPane) workspace.lookup("#video-inspector"); inspector.getSelectionModel().select(1);
            workspace.applyCss(); workspace.layout();
            @SuppressWarnings("unchecked") ListView<ActivityLog.Entry> logs = (ListView<ActivityLog.Entry>) workspace.lookup("#activity-log");
            ActivityLog.Entry persisted = logs.getItems().stream().filter(entry -> marker.archive.equals(entry.archive) && entry.text.startsWith("Analisis por frames completado")).findFirst().orElseThrow();
            logs.scrollTo(persisted); workspace.applyCss(); workspace.layout();
            delay(200, () -> {
                ListCell<?> cell = logs.lookupAll(".list-cell").stream().filter(node -> node instanceof ListCell && ((ListCell<?>) node).getItem() == persisted).map(node -> (ListCell<?>) node).findFirst().orElseThrow();
                javafx.event.Event.fireEvent(cell, new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 4, 4, 4, 4, javafx.scene.input.MouseButton.PRIMARY, 2, false, false, false, false, false, false, false, false, false, true, null));
                await(() -> workspace.lookup("#event-frame") != null && ((ImageView) workspace.lookup("#event-frame")).getImage() != null, this::checkReview);
            });
        });
    }
    private void checkReview() throws Exception {
        check(workspace.lookup("#frame-player") == null && workspace.lookup("#log-review") == null, "Double-click opens saved event rather than another analysis or video decoder");
        TabPane views = (TabPane) workspace.lookup("#event-views"); check(views.getTabs().size() == 3, "All synchronized analysis views survive session cleanup and workspace restart");
        for (Tab tab : views.getTabs()) {
            views.getSelectionModel().select(tab);
            Image actual = ((ImageView) workspace.lookup("#event-frame")).getImage(), wanted = expected.get(tab.getUserData().toString());
            check(actual.getWidth() == wanted.getWidth() && actual.getHeight() == wanted.getHeight(), "Saved view keeps source resolution: " + tab.getText());
            for (int y = 0; y < actual.getHeight(); y += 17) for (int x = 0; x < actual.getWidth(); x += 19)
                if (actual.getPixelReader().getArgb(x, y) != wanted.getPixelReader().getArgb(x, y)) throw new AssertionError("Wrong event pixels");
        }
        views.getSelectionModel().select(0); screenshot("flag-event-1440x900.png");
        stage.setWidth(1000); stage.setHeight(740);
        delay(300, () -> {
            screenshot("flag-event-1000x740.png");
            Node frame = workspace.lookup("#event-frame"); check(frame.localToScene(frame.getBoundsInLocal()).getMaxY() < stage.getScene().getHeight(), "Saved event fits compact viewport");
            check(((ListView<?>) workspace.lookup("#activity-log")).getHeight() >= 120, "Event log retains usable height in compact review");
            fire("close-log-review"); check(workspace.lookup("#event-review") == null, "Back action restores video view");
            try (java.util.stream.Stream<Path> files = Files.walk(temporary)) { check(files.noneMatch(path -> path.toString().endsWith(".mp4")), "Flag workflow does not generate or retain a video copy"); }
            Platform.exit();
        });
    }
    private void screenshot(String name) throws Exception {
        workspace.applyCss(); workspace.layout(); WritableImage shot = workspace.snapshot(null, null);
        java.awt.image.BufferedImage png = new java.awt.image.BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
        Files.createDirectories(Paths.get("target/ui-snapshots")); javax.imageio.ImageIO.write(png, "png", Paths.get("target/ui-snapshots", name).toFile());
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private void safe(Action action) { try { action.run(); } catch (Throwable error) { fail(error); } }
    private void delay(int millis, Action action) { PauseTransition timer = new PauseTransition(Duration.millis(millis)); timer.setOnFinished(event -> safe(action)); timer.play(); }
    private void await(BooleanSupplier ready, Action action) {
        if (waiting != null) waiting.stop(); int[] count = {0};
        waiting = new Timeline(new KeyFrame(Duration.millis(100), event -> safe(() -> { if (ready.getAsBoolean()) { waiting.stop(); action.run(); } else if (++count[0] > 450) throw new AssertionError("Flag UI timeout"); })));
        waiting.setCycleCount(Animation.INDEFINITE); waiting.play();
    }
    private void fail(Throwable error) { failure = error; error.printStackTrace(); if (waiting != null) waiting.stop(); Platform.exit(); }
    @Override public void stop() throws Exception {
        if (workspace != null) workspace.close(); if (bridge != null) bridge.close(); SessionManager.logout();
        if (temporary != null) try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
    }
    public static void main(String[] args) { launch(args); if (failure != null) throw new AssertionError("Flag event UI checks failed", failure); }
}
