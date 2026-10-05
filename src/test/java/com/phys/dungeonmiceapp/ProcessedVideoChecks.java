package com.phys.dungeonmiceapp;

import com.google.gson.JsonArray;
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
import javafx.stage.Stage;
import javafx.util.Duration;

public final class ProcessedVideoChecks extends Application {
    private static Throwable failure;
    private Path sandbox, first, second;
    private WorkspaceView workspace;
    private Stage stage;
    private Video source;
    private ProcessedVideo older, latest;
    private Timeline wait;
    private int loops;

    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); System.out.println("PASS " + text); }
    private static void rejected(Runnable action, String text) { try { action.run(); } catch (IllegalArgumentException expected) { check(true, text); return; } throw new AssertionError(text); }
    @Override public void start(Stage stage) {
        this.stage = stage;
        try {
            sandbox = Files.createTempDirectory("dungeonmice-processed-check-"); System.setProperty("dungeonmice.database", sandbox.resolve("data/database.enc").toString()); System.setProperty("dungeonmice.storage", sandbox.resolve("app").toString());
            Files.createDirectories(sandbox.resolve("app")); DatabaseManager.connect(); User admin = UserRepository.login("admin", "admin123"); SessionManager.setCurrentUser(admin);
            VideoGroup group = VideoGroupRepository.saveProject(null, "Processed results", "", "", "", "", "");
            Path fixture = Paths.get(getParameters().getRaw().get(0)).toRealPath(); VideoRepository.addReferences(group.getId(), Collections.singletonList(fixture.toFile())); source = VideoRepository.findByGroup(group.getId()).get(0);
            first = sandbox.resolve("processed-one.mp4"); second = sandbox.resolve("processed-two.mp4"); Files.copy(fixture, first); Files.copy(fixture, second);
            JsonArray regions = new JsonArray(); com.google.gson.JsonObject region = new com.google.gson.JsonObject(); region.addProperty("id", "norte"); regions.add(region);
            older = ProcessedVideoRepository.record(group.getId(), source.getId(), first.toFile(), regions); Thread.sleep(10); latest = ProcessedVideoRepository.record(group.getId(), source.getId(), second.toFile(), regions);
            check(ProcessedVideoRepository.record(group.getId(), source.getId(), second.toFile(), regions).getId().equals(latest.getId()), "Duplicate result paths not added twice");
            check(ProcessedVideoRepository.findByProject(group.getId()).size() == 2, "External result paths indexed per project");
            check(!Files.exists(sandbox.resolve("app/videos")) && !Files.exists(sandbox.resolve("app/processed")), "Library creates no video copies");
            regions.get(0).getAsJsonObject().addProperty("id", "changed"); check(latest.getRegions().get(0).getAsJsonObject().get("id").getAsString().equals("norte"), "Analysis regions are an immutable snapshot");
            User other = UserRepository.register("other-results", "password", "user", "", "", ""); SessionManager.setCurrentUser(other);
            rejected(() -> ProcessedVideoRepository.findByProject(group.getId()), "Other user cannot read result library");
            rejected(() -> ProcessedVideoRepository.remove(group.getId(), latest.getId()), "Other user cannot remove results"); SessionManager.setCurrentUser(admin);
            DatabaseManager.connect(); check(ProcessedVideoRepository.findByProject(group.getId()).get(0).getId().equals(latest.getId()), "Results survive encrypted database reload in newest-first order");
            workspace = new WorkspaceView(stage); Scene scene = new Scene(workspace, 1440, 900); scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm()); stage.setScene(scene); stage.show();
            ((TextField) workspace.lookup("#login-username")).setText("admin"); ((PasswordField) workspace.lookup("#login-password")).setText("admin123"); fireText("Iniciar sesi\u00f3n"); css(); fire("nav-processed");
            awaitReady(this::checkLibrary);
        } catch (Throwable error) { fail(error); }
    }

    @SuppressWarnings("unchecked") private ListView<ProcessedVideo> results() { return (ListView<ProcessedVideo>) workspace.lookup("#processed-list"); }
    private void checkLibrary() {
        check(results().getItems().size() == 2 && results().getSelectionModel().getSelectedItem().getId().equals(latest.getId()), "Processed page selects latest result");
        check(!((ProgressIndicator) player().lookup("#video-loading")).isVisible(), "Video loading indicator hides when ready"); snapshot("processed-1440x900.png");
        results().getSelectionModel().select(1); awaitReady(this::navigateBack);
    }
    private void navigateBack() {
        check(results().getSelectionModel().getSelectedItem().getId().equals(older.getId()), "Older result can be selected and played");
        DirectPlayer old = player().getPlayer(); fire("nav-data"); fire("nav-processed"); check(old.getStatus() == javafx.scene.media.MediaPlayer.Status.DISPOSED, "Result decoder disposed on navigation");
        awaitReady(() -> {
            check(results().getSelectionModel().getSelectedItem().getId().equals(older.getId()), "Selected result restored after switching tabs");
            if (loops++ < 2) { navigateBack(); return; }
            ((TextField) workspace.lookup("#processed-search")).setText("two"); css(); check(results().getItems().size() == 1, "Processed library search filters results");
            ((TextField) workspace.lookup("#processed-search")).clear(); fire("processed-original"); awaitReady(this::checkVideoPanel);
        });
    }
    private void checkVideoPanel() {
        check(!((Button) workspace.lookup("#python-result")).isDisabled(), "View result restored from database on original video page");
        TabPane tabs = (TabPane) workspace.lookup("#video-inspector"); check(tabs.getTabs().size() == 2, "Regions and logs have separate inspector tabs"); tabs.getSelectionModel().select(1); css();
        @SuppressWarnings("unchecked") ListView<ActivityLog.Entry> logs = (ListView<ActivityLog.Entry>) workspace.lookup("#activity-log");
        check(logs != null && !logs.getItems().isEmpty(), "Structured playback logs visible");
        int count = logs.getItems().size(); fire("nav-data"); fire("nav-video"); awaitReady(() -> {
            ((TabPane) workspace.lookup("#video-inspector")).getSelectionModel().select(1); css();
            ListView<?> restored = (ListView<?>) workspace.lookup("#activity-log"); check(restored.getItems().size() >= count, "Logs retained when switching views");
            ((TextField) workspace.lookup("#log-search")).setText("never-matches-any-record"); check(restored.getItems().isEmpty(), "Log search works"); ((TextField) workspace.lookup("#log-search")).clear();
            snapshot("logs-1440x900.png"); stage.setWidth(1000); stage.setHeight(740);
            await(() -> stage.getScene().getWidth() <= 1000, () -> {
                css(); Node process = workspace.lookup("#python-process"); javafx.geometry.Bounds bounds = process.localToScene(process.getBoundsInLocal());
                check(bounds.getMinY() >= 0 && bounds.getMaxY() <= stage.getScene().getHeight(), "Processing controls remain visible in compact window"); snapshot("logs-1000x740.png");
                fire("python-result"); awaitReady(this::checkMissingAndDeletion);
            });
        });
    }
    private void checkMissingAndDeletion() {
        try {
            check(results().getSelectionModel().getSelectedItem().getId().equals(latest.getId()), "View result reopens the latest stored output"); snapshot("processed-1000x740.png");
            fire("nav-data");
            await(() -> { try { Files.deleteIfExists(second); return true; } catch (java.io.IOException busy) { return false; } }, this::finishDeleted);
        } catch (Throwable error) { fail(error); }
    }
    private void finishDeleted() {
        try {
            fire("nav-processed");
            check(((Label) workspace.lookup("#processed-state")).getText().contains("movido o eliminado"), "Moved or missing result produces a clear message");
            ProcessedVideoRepository.remove(source.getGroupId(), older.getId()); check(Files.isRegularFile(first), "Removing history does not delete output video");
            ProcessedVideoRepository.removeByProject(source.getGroupId()); check(ProcessedVideoRepository.findByProject(source.getGroupId()).isEmpty() && Files.isRegularFile(first), "Project cleanup removes only references");
            check(Files.isRegularFile(Paths.get(source.getFilePath())), "Original preserved throughout library operations"); workspace.close(); Platform.exit();
        } catch (Throwable error) { fail(error); }
    }
    private PlaybackPane player() { PlaybackPane player = findPlayer(workspace); if (player == null) throw new AssertionError("No player"); return player; }
    private static PlaybackPane findPlayer(Node node) { if (node instanceof PlaybackPane) return (PlaybackPane) node; if (node instanceof Parent) for (Node child : ((Parent) node).getChildrenUnmodifiable()) { PlaybackPane found = findPlayer(child); if (found != null) return found; } return null; }
    private void awaitReady(Runnable action) { await(() -> player().getPlayer() != null && player().getPlayer().getStatus() == javafx.scene.media.MediaPlayer.Status.PAUSED, action); }
    private void await(BooleanSupplier condition, Runnable action) {
        long end = System.nanoTime() + 30_000_000_000L;
        wait = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            try { css(); if (condition.getAsBoolean()) { wait.stop(); action.run(); } else if (System.nanoTime() > end) throw new AssertionError("UI wait timed out"); } catch (Throwable error) { fail(error); }
        })); wait.setCycleCount(Timeline.INDEFINITE); wait.play();
    }
    private void fire(String id) { css(); ((Button) workspace.lookup("#" + id)).fire(); css(); }
    private void fireText(String text) { css(); for (Node node : workspace.lookupAll(".button")) if (node instanceof Button && ((Button) node).getText().equals(text)) { ((Button) node).fire(); return; } throw new AssertionError("Button not found: " + text); }
    private void css() { workspace.applyCss(); workspace.layout(); }
    private void snapshot(String name) {
        try {
            css(); var snapshot = workspace.snapshot(null, null); java.awt.image.BufferedImage image = new java.awt.image.BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            Path path = Paths.get("target/ui-snapshots/" + name); Files.createDirectories(path.getParent()); javax.imageio.ImageIO.write(image, "png", path.toFile());
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void fail(Throwable error) { failure = error; error.printStackTrace(); if (wait != null) wait.stop(); if (workspace != null) workspace.close(); Platform.exit(); }
    @Override public void stop() throws Exception {
        if (workspace != null) workspace.close();
        if (sandbox != null) try (var paths = Files.walk(sandbox)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                for (int i = 0; ; i++) { try { Files.deleteIfExists(path); break; } catch (java.io.IOException busy) { if (i >= 40) throw busy; Thread.sleep(50); } }
            }
        }
    }
    public static void main(String[] args) { launch(args); if (failure != null) throw new AssertionError("Processed library checks failed", failure); }
}
