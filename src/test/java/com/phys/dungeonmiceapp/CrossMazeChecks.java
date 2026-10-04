package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.ui.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javafx.application.*;
import javafx.animation.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Real source files are read-only; all database and media cache output is temporary. */
public final class CrossMazeChecks extends Application {
    private static Throwable failure;
    private static Path temporaryStorage;
    private WorkspaceView workspace;
    private List<File> sources;
    private final List<Long> sizes = new ArrayList<>(), modified = new ArrayList<>();
    private Path sandbox;
    private int index;
    private long pulse = System.nanoTime(), maximumDelay;
    private Timeline heartbeat;

    @Override public void start(Stage stage) throws Exception {
        sources = MediaFiles.videosInDirectory(getParameters().getRaw().get(0));
        if (sources.isEmpty()) throw new IllegalArgumentException("No videos in supplied directory");
        sandbox = Files.createTempDirectory("crossmaze-checks-");
        temporaryStorage = sandbox;
        System.setProperty("dungeonmice.database", sandbox.resolve("data/database.enc").toString());
        System.setProperty("dungeonmice.storage", sandbox.toString());
        for (File source : sources) { sizes.add(source.length()); modified.add(source.lastModified()); }
        DatabaseManager.connect();
        initialize(stage);
    }

    // Start through the login workflow, including actual preview and full-player navigation.
    private void initialize(Stage stage) throws Exception {
        workspace = new WorkspaceView(stage);
        Scene scene = new Scene(workspace, 1440, 900);
        scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm());
        stage.setScene(scene); stage.show();
        ((TextField) workspace.lookup("#login-username")).setText("admin");
        ((TextField) workspace.lookup("#login-password")).setText("admin123"); button("Iniciar sesi\u00f3n").fire();
        VideoGroupRepository.createGroup("CrossMaze real files", "Read-only verification");
        VideoGroup project = VideoGroupRepository.findGroupsByCurrentUser().get(0);
        VideoRepository.addReferences(project.getId(), sources);
        @SuppressWarnings("unchecked") ListView<VideoGroup> projects = (ListView<VideoGroup>) workspace.lookup("#projects-list");
        projects.getItems().setAll(VideoGroupRepository.findGroupsByCurrentUser());
        projects.getSelectionModel().selectFirst();
        @SuppressWarnings("unchecked") ListView<Video> videos = (ListView<Video>) workspace.lookup("#project-videos");
        videos.getSelectionModel().selectFirst();
        pulse = System.nanoTime();
        heartbeat = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            long now = System.nanoTime(); maximumDelay = Math.max(maximumDelay, (now - pulse) / 1000000); pulse = now;
        })); heartbeat.setCycleCount(Timeline.INDEFINITE); heartbeat.play();
        waitFor(() -> paused(player()), () -> { button("Video").fire(); checkVideo(); });
    }

    private void checkVideo() {
        long started = System.nanoTime();
        waitFor(() -> paused(player()), () -> {
            PlaybackPane pane = player();
            if (pane.getPlayer().getTotalDuration().toSeconds() < 300) throw new AssertionError("Full video was truncated");
            WritableImage pixels = pane.getViewport().snapshot(null, null);
            int colored = 0;
            for (int y = 0; y < pixels.getHeight(); y += 12) for (int x = 0; x < pixels.getWidth(); x += 12) {
                javafx.scene.paint.Color color = pixels.getPixelReader().getColor(x, y);
                if (Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue())) - Math.min(color.getRed(), Math.min(color.getGreen(), color.getBlue())) > .08) colored++;
            }
            if (colored < 100) throw new AssertionError("Blank decoded frame");
            System.out.println("PASS decoded full CrossMaze video " + (++index) + "/" + sources.size() + " readyMs=" + (System.nanoTime() - started) / 1000000);
            pane.seek(60); pane.play();
            waitFor(() -> pane.getPlayer().getCurrentTime().toSeconds() > 60.2, () -> {
                pane.pause();
                if (index == 1 && sources.size() > 2) coldSwitch();
                else if (index < sources.size()) { choices().getSelectionModel().select(index); checkVideo(); }
                else rapidSwitch();
            });
        });
    }

    private void coldSwitch() {
        Timeline switching = new Timeline();
        for (int i = 1; i < sources.size(); i++) {
            final int selection = i;
            switching.getKeyFrames().add(new KeyFrame(Duration.millis(180 * i), e -> choices().getSelectionModel().select(selection)));
        }
        switching.setOnFinished(e -> { choices().getSelectionModel().select(1); checkVideo(); });
        switching.play();
    }

    private void rapidSwitch() {
        for (int i = 0; i < 15; i++) choices().getSelectionModel().select(i % sources.size());
        choices().getSelectionModel().select(sources.size() - 1);
        waitFor(() -> paused(player()), () -> {
            try {
                heartbeat.stop();
                if (maximumDelay > 2000) throw new AssertionError("UI blocked for " + maximumDelay + " ms");
                for (int i = 0; i < sources.size(); i++) {
                    if (sources.get(i).length() != sizes.get(i) || sources.get(i).lastModified() != modified.get(i)) throw new AssertionError("Original changed");
                }
                if (Files.exists(sandbox.resolve("videos"))) throw new AssertionError("Project video copies created");
                try (java.util.stream.Stream<Path> cached = Files.list(sandbox.resolve("data/playback"))) {
                    if (cached.anyMatch(file -> file.getFileName().toString().length() != 40)) throw new AssertionError("Partial preparation file left behind");
                }
                System.out.println("PASS rapid switching, UI maxPulseGapMs=" + maximumDelay + ", originals unchanged");
                workspace.close(); Platform.exit();
            } catch (Throwable ex) { fail(ex); }
        });
    }

    @SuppressWarnings("unchecked") private ComboBox<Video> choices() { return (ComboBox<Video>) workspace.lookup("#video-choice"); }
    private static boolean paused(PlaybackPane pane) { return pane != null && pane.getPlayer() != null && pane.getPlayer().getStatus() == MediaPlayer.Status.PAUSED; }
    private PlaybackPane player() { return findPlayer(workspace); }
    private PlaybackPane findPlayer(Parent root) {
        for (Node node : root.getChildrenUnmodifiable()) {
            if (node instanceof PlaybackPane) return (PlaybackPane) node;
            if (node instanceof Parent) { PlaybackPane found = findPlayer((Parent) node); if (found != null) return found; }
        } return null;
    }
    private Button button(String text) { return findButton(workspace, text); }
    private Button findButton(Parent root, String text) {
        for (Node node : root.getChildrenUnmodifiable()) {
            if (node instanceof Button && ((Button) node).getText().equals(text)) return (Button) node;
            if (node instanceof Parent) { Button found = findButton((Parent) node, text); if (found != null) return found; }
        } return null;
    }
    private void waitFor(BooleanSupplier condition, Runnable next) {
        long start = System.nanoTime(); Timeline timer = new Timeline(); timer.setCycleCount(Timeline.INDEFINITE);
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(100), e -> {
            try {
                if (condition.getAsBoolean()) { timer.stop(); next.run(); }
                else if ((System.nanoTime() - start) / 1000000000 > 60) { timer.stop(); throw new AssertionError("Timed out at video index " + index); }
            } catch (Throwable ex) { timer.stop(); fail(ex); }
        })); timer.play();
    }
    private void fail(Throwable ex) { failure = ex; ex.printStackTrace(); if (workspace != null) workspace.close(); Platform.exit(); }
    public static void main(String[] args) {
        launch(args);
        try {
            Path temp = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if (temporaryStorage != null && temporaryStorage.toAbsolutePath().normalize().getParent().equals(temp)
                    && temporaryStorage.getFileName().toString().startsWith("crossmaze-checks-")) {
                try (java.util.stream.Stream<Path> files = Files.walk(temporaryStorage)) {
                    Iterator<Path> entries = files.sorted(Comparator.reverseOrder()).iterator();
                    while (entries.hasNext()) Files.deleteIfExists(entries.next());
                }
            }
        } catch (Exception ex) { System.err.println("Temporary test cache retained: " + temporaryStorage); }
        if (failure != null) System.exit(1);
    }
}
