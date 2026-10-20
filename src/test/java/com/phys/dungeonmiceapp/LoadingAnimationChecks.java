package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.ui.LoadingAnimation;
import com.phys.dungeonmiceapp.ui.PlaybackPane;
import java.nio.file.*;
import java.util.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Verify the packaged GIF actually moves and loops, and fits loading layouts. */
public final class LoadingAnimationChecks extends Application {
    private static Throwable failure;
    private PlaybackPane player;
    private final Set<Integer> frames = new HashSet<>(), lateFrames = new HashSet<>();
    private int count;
    @Override public void start(Stage stage) {
        player = new PlaybackPane(false, text -> { }); player.prepare();
        LoadingAnimation small = new LoadingAnimation(76), corner = new LoadingAnimation(112);
        HBox examples = new HBox(12, small, corner); examples.setPadding(new javafx.geometry.Insets(12));
        BorderPane root = new BorderPane(player); root.setTop(examples); root.getStyleClass().add("workspace");
        Scene scene = new Scene(root, 1000, 740); scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm());
        stage.setScene(scene); stage.show();
        Timeline sampling = new Timeline();
        sampling.getKeyFrames().add(new KeyFrame(Duration.millis(100), event -> {
            try {
                int signature = signature(small.snapshot(null, null)); frames.add(signature); if (count > 9) lateFrames.add(signature);
                if (++count < 20) return;
                sampling.stop();
                check(frames.size() >= 2 && lateFrames.size() >= 2, "Loading GIF changes frames and continues looping beyond its first cycle");
                javafx.scene.Node loading = player.lookup("#video-loading"), status = player.lookup("#playback-status");
                check(loading instanceof LoadingAnimation && loading.isVisible(), "Video preparation uses the mouse animation");
                check(loading.getBoundsInParent().getMaxY() + 4 <= status.getBoundsInParent().getMinY(), "Loading animation does not overlap its status text");
                save(root.snapshot(null, null), "loading-desktop.png");
                stage.setWidth(800); stage.setHeight(520);
                PauseTransition compact = new PauseTransition(Duration.millis(250)); compact.setOnFinished(done -> {
                    try {
                        save(root.snapshot(null, null), "loading-compact.png");
                        check(loading.getBoundsInParent().getMaxY() <= status.getBoundsInParent().getMinY(), "Animation fits the compact viewport without covering text");
                        player.close(); check(!loading.isVisible(), "Closing playback hides the animated loader"); stage.hide(); Platform.exit();
                    } catch (Throwable ex) { fail(ex); }
                }); compact.play();
            } catch (Throwable ex) { sampling.stop(); fail(ex); }
        })); sampling.setCycleCount(Timeline.INDEFINITE); sampling.play();
    }
    private static int signature(WritableImage image) {
        int hash = 1;
        for (int y = 0; y < image.getHeight(); y += 2) for (int x = 0; x < image.getWidth(); x += 2) hash = 31 * hash + image.getPixelReader().getArgb(x, y);
        return hash;
    }
    private static void save(WritableImage image, String name) throws Exception {
        Path output = Paths.get("target/loading-check"); Files.createDirectories(output);
        java.awt.image.BufferedImage pixels = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) pixels.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(pixels, "png", output.resolve(name).toFile());
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); System.out.println("PASS " + text); }
    private void fail(Throwable ex) { failure = ex; ex.printStackTrace(); Platform.exit(); }
    @Override public void stop() { if (player != null) player.close(); }
    public static void main(String[] args) { launch(args); if (failure != null) System.exit(1); }
}
