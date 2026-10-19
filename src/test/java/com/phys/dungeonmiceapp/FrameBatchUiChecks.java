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
import javafx.scene.image.ImageView;
import javafx.stage.*;
import javafx.util.Duration;

/** Real native Python sessions against disposable short fixtures and an isolated DB. */
public final class FrameBatchUiChecks extends Application {
    private static Throwable failure;
    private WorkspaceView workspace;
    private ManagedPythonBridge bridge;
    private Path temporary;
    private Timeline waiting;
    private String project;
    private List<Video> videos;
    private List<VideoRegion> template;
    private double lastProgress;
    private boolean observedSecond;
    private void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    private void fire(String id) { ((Button) workspace.lookup("#" + id)).fire(); }
    private Label status() { return (Label) workspace.lookup("#processing-status"); }
    @Override public void start(Stage stage) {
        try {
            temporary = Files.createTempDirectory("dungeonmice-frame-batch-");
            System.setProperty("dungeonmice.database", temporary.resolve("data/database.enc").toString());
            System.setProperty("dungeonmice.events.directory", temporary.resolve("events").toString());
            System.setProperty("dungeonmice.storage", temporary.resolve("app").toString()); Files.createDirectories(temporary.resolve("app"));
            DatabaseManager.connect(); SessionManager.setCurrentUser(UserRepository.login("admin", "admin123"));
            project = VideoGroupRepository.saveProject(null, "Frame batch", "", "", "", "", "").getId();
            Path folder = Paths.get(getParameters().getRaw().get(0));
            VideoRepository.addReferences(project, Arrays.asList(folder.resolve("first.avi").toFile(), folder.resolve("broken.avi").toFile(), folder.resolve("second.avi").toFile()));
            videos = VideoRepository.findByGroup(project); template = new ArrayList<>();
            for (JsonElement element : JsonParser.parseString(Files.readString(Paths.get(getParameters().getRaw().get(1)))).getAsJsonArray()) {
                JsonObject region = element.getAsJsonObject(); if (!region.has("color")) region.addProperty("color", "#00874b"); template.add(VideoRegion.fromJson(region));
            }
            VideoRepository.saveRegions(videos.get(0).getId(), template);
            JsonObject special = template.get(0).toJson(); special.addProperty("id", "Especial");
            VideoRepository.saveRegions(videos.get(2).getId(), Collections.singletonList(VideoRegion.fromJson(special)));
            workspace = new WorkspaceView(stage); Scene scene = new Scene(workspace, 1440, 900);
            scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm()); stage.setScene(scene); stage.show();
            ((TextField) workspace.lookup("#login-username")).setText("admin"); ((PasswordField) workspace.lookup("#login-password")).setText("admin123");
            for (Node node : workspace.lookupAll(".button")) if (node instanceof Button && ((Button) node).getText().equals("Iniciar sesi\u00f3n")) ((Button) node).fire();
            bridge = new ManagedPythonBridge(System.err::println);
            bridge.start().thenAccept(uri -> Platform.runLater(() -> safe(() -> {
                workspace.pythonConnected(uri.toString(), bridge.frameToken()); fire("nav-video"); await(this::originalReady, this::cancelConfirmation);
            }))).exceptionally(error -> { Platform.runLater(() -> fail(error)); return null; });
        } catch (Throwable error) { fail(error); }
    }
    private boolean originalReady() {
        Node viewport = workspace.lookup("#playback-viewport");
        return viewport != null && viewport.getParent() instanceof PlaybackPane && ((PlaybackPane) viewport.getParent()).videoReadyProperty().get();
    }
    private void confirmBatch(ButtonType answer) {
        Platform.runLater(() -> safe(() -> {
            for (Window window : new ArrayList<>(Window.getWindows())) if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane) {
                DialogPane pane = (DialogPane) window.getScene().getRoot(); ((Button) pane.lookupButton(answer)).fire(); return;
            }
            throw new AssertionError("Batch confirmation not shown");
        }));
        fire("python-process-all");
    }
    private void cancelConfirmation() {
        confirmBatch(ButtonType.CANCEL);
        check(VideoRepository.findRegions(videos.get(1).getId()).isEmpty() && VideoRepository.findRegions(videos.get(2).getId()).get(0).getName().equals("Especial") && workspace.lookup("#frame-batch") == null, "Cancelling confirmation leaves existing special-case regions untouched");
        confirmBatch(ButtonType.OK);
        check(((ProgressBar) workspace.lookup("#processing-progress")).isVisible(), "Batch progress bar visible");
        for (Video video : videos) {
            List<VideoRegion> saved = VideoRepository.findRegions(video.getId());
            check(saved.size() == template.size(), "Current regions applied to " + video.getTitle());
            for (int i = 0; i < saved.size(); i++) check(saved.get(i).toJson().equals(template.get(i).toJson()), "Copied geometry and parameters match template");
        }
        await(() -> workspace.lookup("#analysis-frame") != null && ((ImageView) workspace.lookup("#analysis-frame")).getImage() != null, () -> {
            check(((Button) workspace.lookup("#region-add")).isDisabled(), "Region edits locked during batch");
            check(((TabPane) workspace.lookup("#video-inspector")).getTabs().get(1).isDisabled(), "Event logs locked throughout batch analysis");
            snapshot();
            fire("frame-pause"); delay(400, () -> {
                double paused = ((ProgressBar) workspace.lookup("#processing-progress")).getProgress();
                delay(400, () -> {
                    check(((ProgressBar) workspace.lookup("#processing-progress")).getProgress() == paused, "Pausing stops batch frame requests");
                    TabPane tabs = (TabPane) workspace.lookup("#frame-views"); tabs.getSelectionModel().select(2);
                    check(((ImageView) workspace.lookup("#analysis-frame")).getImage() != null, "Batch supports trajectory view");
                    fire("python-cancel"); check(status().getText().startsWith("Lote cancelado"), "Cancel reports completed and failed counts");
                    await(this::originalReady, this::runToCompletion);
                });
            });
        });
    }
    private void runToCompletion() {
        confirmBatch(ButtonType.OK); lastProgress = 0;
        await(() -> {
            double value = ((ProgressBar) workspace.lookup("#processing-progress")).getProgress();
            checkProgress(value);
            if (status().getText().startsWith("3/3")) observedSecond = true;
            return status().getText().startsWith("Lote terminado");
        }, () -> {
            check(observedSecond, "Batch continues into second valid session after an invalid video");
            check(status().getText().contains("2/3 completados, 1 fallidos"), "Invalid video fails independently; valid videos complete");
            check(ProcessedVideoRepository.findByProject(project).isEmpty(), "Batch never registers an MP4 export");
            check(!((Button) workspace.lookup("#python-process-all")).isDisabled(), "Batch action unlocked after completion");
            check(((ProgressBar) workspace.lookup("#processing-progress")).getProgress() == 1, "Global progress reaches one");
            for (Video video : Arrays.asList(videos.get(0), videos.get(2))) {
                ActivityLog.Entry complete = null;
                for (JsonElement entry : VideoRepository.findActivity(video.getId())) {
                    ActivityLog.Entry saved = ActivityLog.Entry.fromJson(entry.getAsJsonObject());
                    if (saved.text.startsWith("Analisis por frames completado")) complete = saved;
                }
                check(complete != null && complete.frame == 60 && Math.abs(complete.seconds - 2.0) < .001, "Batch persists the native frame and time separately for " + video.getTitle());
                try { check(complete.archive != null && EventFrameArchive.load(video.getOwnerId(), video.getId(), complete).metadata.get("index").getAsInt() == 60, "Batch retains a persistent event snapshot for " + video.getTitle()); }
                catch (java.io.IOException error) { throw new RuntimeException(error); }
            }
            await(this::originalReady, this::checkIndividualCompletion);
        });
    }
    private void checkIndividualCompletion() {
        fire("python-process");
        await(() -> status().getText().startsWith("Completado"), () -> {
            check(((ProgressBar) workspace.lookup("#processing-progress")).getProgress() == 1, "Individual progress reaches 100 percent at EOF");
            check(status().getText().contains("100%"), "Individual completion label shows final percentage");
            check(!((TabPane) workspace.lookup("#video-inspector")).getTabs().get(1).isDisabled(), "Logs unlock after individual analysis");
            check(!status().textProperty().isBound(), "Individual progress bindings released on completion");
            await(this::originalReady, () -> {
                confirmBatch(ButtonType.OK);
                await(() -> workspace.lookup("#analysis-frame") != null && ((ImageView) workspace.lookup("#analysis-frame")).getImage() != null, () -> {
                    fire("nav-data"); check(workspace.lookup("#frame-batch") == null, "Navigation cancels the frame queue");
                    delay(600, () -> { fire("nav-video"); await(this::originalReady, () -> { check(!((Button) workspace.lookup("#python-process-all")).isDisabled(), "Navigation restores usable processing controls"); Platform.exit(); }); });
                });
            });
        });
    }
    private void checkProgress(double value) { if (value < lastProgress || value > 1) throw new AssertionError("Global progress moved backwards/out of range"); lastProgress = value; }
    private void snapshot() {
        try {
            workspace.applyCss(); workspace.layout(); javafx.scene.image.WritableImage shot = workspace.snapshot(null, null);
            java.awt.image.BufferedImage png = new java.awt.image.BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Files.createDirectories(Paths.get("target/ui-snapshots")); javax.imageio.ImageIO.write(png, "png", Paths.get("target/ui-snapshots/frame-batch.png").toFile());
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void delay(int millis, Runnable action) { PauseTransition timer = new PauseTransition(Duration.millis(millis)); timer.setOnFinished(event -> Platform.runLater(() -> safe(action))); timer.play(); }
    private void safe(Runnable action) { try { action.run(); } catch (Throwable error) { fail(error); } }
    private void await(BooleanSupplier ready, Runnable action) {
        if (waiting != null) waiting.stop(); int[] count = {0};
        waiting = new Timeline(new KeyFrame(Duration.millis(100), event -> safe(() -> { if (ready.getAsBoolean()) { waiting.stop(); Platform.runLater(() -> safe(action)); } else if (++count[0] > 600) throw new AssertionError("Batch UI timeout: " + status().getText()); })));
        waiting.setCycleCount(Animation.INDEFINITE); waiting.play();
    }
    private void fail(Throwable error) { failure = error; error.printStackTrace(); if (waiting != null) waiting.stop(); Platform.exit(); }
    @Override public void stop() throws Exception {
        if (workspace != null) workspace.close(); if (bridge != null) bridge.close(); SessionManager.logout();
        if (temporary != null) try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
    }
    public static void main(String[] args) { launch(args); if (failure != null) throw new AssertionError("Frame batch UI checks failed", failure); }
}
