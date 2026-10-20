package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.model.Video;
import com.phys.dungeonmiceapp.ui.ReportPane;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Real persisted native workbook data rendered in compact and desktop layouts. */
public final class ReportUiChecks extends Application {
    private static Throwable failure; private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ReportPane view; private Stage stage; private int pass;
    @Override public void start(Stage stage) {
        this.stage = stage; System.setProperty("dungeonmice.events.directory", Paths.get(getParameters().getRaw().get(0), "events").toString());
        Video video = new Video("report-video", "Escopolamina 1.avi", "unused", "project", "report-owner", new Date());
        view = new ReportPane(stage, Collections.singletonList(video), worker); view.getStyleClass().add("workspace"); Scene scene = new Scene(view, 1200, 740);
        scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm()); stage.setScene(scene); stage.show();
        Timeline wait = new Timeline(); wait.getKeyFrames().add(new KeyFrame(Duration.millis(200), event -> {
            try {
                TableView<?> table = (TableView<?>) view.lookup("#report-preview");
                if (table.getItems().isEmpty()) { if (++pass > 60) throw new AssertionError("Persisted reports never displayed"); return; }
                wait.stop(); check(!view.lookup("#report-excel").isDisabled() && !view.lookup("#report-pdf").isDisabled(), "Excel and PDF available without Python connection");
                check(table.getItems().toString().contains("MorrisPool") || table.getItems().toString().contains("CrossMaze"), "Preview displays native workbook results, not only file paths");
                snapshot("reports-desktop.png"); stage.setWidth(900); stage.setHeight(650);
                PauseTransition compact = new PauseTransition(Duration.millis(250)); compact.setOnFinished(done -> {
                    try {
                        snapshot("reports-compact.png");
                        check(view.lookup("#report-pdf").localToScene(view.lookup("#report-pdf").getBoundsInLocal()).getMaxX() < scene.getWidth(), "Export buttons fit compact report view");
                        worker.shutdownNow(); stage.hide(); Platform.exit();
                    } catch (Throwable ex) { fail(ex); }
                }); compact.play();
            } catch (Throwable ex) { wait.stop(); fail(ex); }
        })); wait.setCycleCount(Timeline.INDEFINITE); wait.play();
    }
    private void snapshot(String name) throws Exception {
        view.applyCss(); view.layout(); javafx.scene.image.WritableImage image = view.snapshot(null, null);
        java.awt.image.BufferedImage output = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(output, "png", Paths.get(getParameters().getRaw().get(0), name).toFile());
    }
    private static void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    private void fail(Throwable ex) { failure = ex; ex.printStackTrace(); worker.shutdownNow(); Platform.exit(); }
    @Override public void stop() { worker.shutdownNow(); }
    public static void main(String[] args) { launch(args); if (failure != null) System.exit(1); }
}
