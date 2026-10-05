package com.phys.dungeonmiceapp;

import com.google.gson.JsonArray;
import com.phys.dungeonmiceapp.api.PythonVideoClient;
import com.phys.dungeonmiceapp.ui.PlaybackPane;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import javafx.animation.*;
import javafx.application.*;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Real Python analysis, receive the annotated file, then decode it with the Java player. */
public final class DungeonMiceBridgeChecks extends Application {
    private static Throwable failure;
    private PlaybackPane player;
    private Timeline wait;
    private Path source, result;
    private long started;

    @Override public void start(Stage stage) {
        try {
            source = Paths.get(getParameters().getRaw().get(1));
            result = Paths.get(getParameters().getRaw().get(2));
            URI api = URI.create(getParameters().getRaw().get(0));
            player = new PlaybackPane(false, System.out::println);
            Scene scene = new Scene(player, 1000, 740);
            scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm());
            stage.setScene(scene); stage.show();
            Thread transfer = new Thread(() -> {
                try {
                    new PythonVideoClient(api).process(source, result, "bridge-video", "bridge-project", new JsonArray(), System.out::println);
                    if (Arrays.equals(hash(source), hash(result))) throw new AssertionError("Python returned an echo rather than analysis");
                    Platform.runLater(() -> { player.open(result.toFile()); started = System.nanoTime(); awaitPlayback(); });
                } catch (Throwable error) { Platform.runLater(() -> fail(error)); }
            }, "dungeonmice-bridge-check"); transfer.setDaemon(true); transfer.start();
        } catch (Throwable error) { fail(error); }
    }

    private void awaitPlayback() {
        wait = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            try {
                if (System.nanoTime() - started > 30_000_000_000L) throw new AssertionError("Processed video never became ready");
                if (player.getPlayer() == null || player.getPlayer().getMedia().getWidth() == 0) return;
                if (player.getPlayer().getStatus() != javafx.scene.media.MediaPlayer.Status.PAUSED) return;
                // VLC aligns MPEG-4 callback buffers to 32 rows (720 -> 736).
                // The script separately verifies the actual encoded size is still 1280x720.
                if (player.getPlayer().getMedia().getWidth() != 1280 || player.getPlayer().getMedia().getHeight() < 720 || player.getPlayer().getMedia().getHeight() > 752)
                    throw new AssertionError("Processed video dimensions: " + player.getPlayer().getMedia().getWidth() + "x" + player.getPlayer().getMedia().getHeight());
                if (player.getPlayer().getTotalDuration().toSeconds() < 34) throw new AssertionError("Processed clip truncated");
                javafx.scene.image.WritableImage image = player.getViewport().snapshot(null, null);
                int green = 0;
                for (int y = 0; y < image.getHeight(); y += 2) for (int x = 0; x < image.getWidth(); x += 2) {
                    javafx.scene.paint.Color color = image.getPixelReader().getColor(x, y);
                    if (color.getGreen() > .5 && color.getGreen() > color.getRed() + .2 && color.getGreen() > color.getBlue() + .2) green++;
                }
                if (green < 100) throw new AssertionError("No annotated region pixels visible in Java");
                System.out.println("PASS Python region annotations visible in Java");
                wait.stop(); player.seek(28); player.play();
                wait = new Timeline(new KeyFrame(Duration.millis(100), e -> {
                    try {
                        if (System.nanoTime() - started > 40_000_000_000L) throw new AssertionError("Processed video did not play/seek: " + player.getPlayer().getStatus() + " time=" + player.getPlayer().getCurrentTime() + " duration=" + player.getPlayer().getTotalDuration());
                        if (player.getPlayer().getCurrentTime().toSeconds() > 28.2) {
                            System.out.println("PASS real DungeonMice result decoded, seeked and played in Java");
                            wait.stop(); player.close(); Platform.exit();
                        }
                    } catch (Throwable error) { fail(error); }
                })); wait.setCycleCount(Timeline.INDEFINITE); wait.play();
            } catch (Throwable error) { fail(error); }
        })); wait.setCycleCount(Timeline.INDEFINITE); wait.play();
    }

    private static byte[] hash(Path path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count);
        }
        return hash.digest();
    }

    private void fail(Throwable error) { failure = error; error.printStackTrace(); if (wait != null) wait.stop(); if (player != null) player.close(); Platform.exit(); }
    public static void main(String[] args) { launch(args); if (failure != null) throw new AssertionError("Bridge integration failed", failure); }
}
