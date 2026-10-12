package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import com.phys.dungeonmiceapp.ui.*;
import java.nio.file.*;
import java.util.*;
import javafx.application.*;
import javafx.animation.*;
import javafx.geometry.Point2D;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class RegionEditorChecks extends Application {
    private static Throwable failure;
    private Path sandbox;
    private PlaybackPane player;
    private Timeline wait;
    private RegionEditor editor;
    private Scene scene;
    private Video video;

    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); System.out.println("PASS " + name); }
    private static void rejected(Runnable action, String name) { try { action.run(); } catch (IllegalArgumentException expected) { check(true, name); return; } throw new AssertionError(name); }
    @Override public void start(Stage stage) {
        try {
            sandbox = Files.createTempDirectory("dungeonmice-regions-check-");
            System.setProperty("dungeonmice.database", sandbox.resolve("data/database.enc").toString());
            DatabaseManager.connect(); SessionManager.setCurrentUser(UserRepository.login("admin", "admin123"));
            VideoGroup project = VideoGroupRepository.saveProject(null, "Regions", "", "", "", "", "");
            Path source = Paths.get(getParameters().getRaw().get(0)).toRealPath();
            VideoRepository.addReferences(project.getId(), Collections.singletonList(source.toFile()));
            video = VideoRepository.findByGroup(project.getId()).get(0);
            player = new PlaybackPane(false, System.out::println); editor = new RegionEditor(player); editor.setVideo(video);
            BorderPane root = new BorderPane(player); ScrollPane scroll = new ScrollPane(editor); scroll.setFitToWidth(true); scroll.setPrefWidth(240); root.setRight(scroll);
            scene = new Scene(root, 1000, 740); scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm());
            stage.setScene(scene); stage.show(); player.open(source.toFile());
            long deadline = System.nanoTime() + 30_000_000_000L;
            wait = new Timeline(new KeyFrame(Duration.millis(100), event -> {
                try {
                    if (System.nanoTime() > deadline) throw new AssertionError("Video was not ready");
                    if (!player.videoReadyProperty().get() || player.getPlayer().getStatus() != javafx.scene.media.MediaPlayer.Status.PAUSED) return;
                    wait.stop(); runChecks(stage);
                } catch (Throwable error) { fail(error); }
            })); wait.setCycleCount(Timeline.INDEFINITE); wait.play();
        } catch (Throwable error) { fail(error); }
    }

    private void runChecks(Stage stage) throws Exception {
        int width = player.getPlayer().getMedia().getWidth(), height = player.getPlayer().getMedia().getHeight();
        check(width > 0 && height > 0, "Decoded original frame dimensions available");
        if (width == 1280) check(height == 720, "VLC padding is excluded from region coordinates");
        rejected(editor::requestRegions, "Empty regions blocked before upload");
        Button add = (Button) scene.lookup("#region-add"); add.fire();
        Point2D[] north = rectangle(width * .42, height * .02, width * .50, height * .43);
        for (Point2D point : north) click(point);
        ((TextField) scene.lookup("#region-name")).setText("norte-ui"); ((Button) scene.lookup("#region-save")).fire();
        check(VideoRepository.findRegions(video.getId()).size() == 1, "Drawn polygon persisted per video");
        rejected(editor::requestRegions, "CrossMaze minimum two regions enforced");
        player.setZoom(2); scene.getRoot().applyCss(); scene.getRoot().layout();
        Point2D known = new Point2D(width * .48, height * .65);
        Point2D transformed = player.videoToScene(known), restored = player.sceneToVideo(transformed.getX(), transformed.getY());
        check(restored != null && restored.distance(known) < .01, "Zoom mapping preserves original pixel coordinates");
        player.getViewport().getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, transformed));
        player.getViewport().getOnMouseDragged().handle(mouse(MouseEvent.MOUSE_DRAGGED, transformed.add(30, -20)));
        Point2D panned = player.videoToScene(known), unpanned = player.sceneToVideo(panned.getX(), panned.getY());
        check(unpanned != null && unpanned.distance(known) < .01, "Pan mapping preserves original pixel coordinates");
        player.resetZoom(); add.fire();
        Point2D[] south = rectangle(width * .40, height * .50, width * .52, height * .90);
        for (Point2D point : south) click(point);
        ((Button) scene.lookup("#region-undo")).fire(); click(south[3]);
        ((TextField) scene.lookup("#region-name")).setText("sur-ui"); ((Button) scene.lookup("#region-save")).fire();
        JsonArray payload = editor.requestRegions();
        check(payload.size() == 2 && payload.get(0).getAsJsonObject().get("type").getAsString().equals("PolygonRegion"), "UI produces native Python polygon payload");
        JsonArray pair = payload.get(0).getAsJsonObject().getAsJsonArray("points").get(0).getAsJsonArray();
        check(Math.abs(pair.get(0).getAsDouble() - north[0].getX()) < .01 && Math.abs(pair.get(1).getAsDouble() - north[0].getY()) < .01, "Drawn points sent in original resolution");
        DatabaseManager.connect(); editor.setVideo(video);
        check(editor.requestRegions().equals(payload), "Regions survive encrypted database reload");
        User admin = SessionManager.getCurrentUser();
        SessionManager.setCurrentUser(UserRepository.register("other-regions-user", "password", "user", "", "", ""));
        rejected(() -> VideoRepository.findRegions(video.getId()), "Other user cannot read video regions");
        rejected(() -> VideoRepository.saveRegions(video.getId(), Collections.emptyList()), "Other user cannot overwrite video regions");
        SessionManager.setCurrentUser(admin);
        @SuppressWarnings("unchecked") ListView<VideoRegion> list = (ListView<VideoRegion>) scene.lookup("#region-list");
        list.getSelectionModel().select(0); ((Button) scene.lookup("#region-edit")).fire();
        ((TextField) scene.lookup("#region-name")).setText("sur-ui"); ((Button) scene.lookup("#region-save")).fire();
        check(!((Label) scene.lookup("#region-message")).getText().isEmpty() && VideoRepository.findRegions(video.getId()).size() == 2, "Duplicate names rejected without losing data");
        ((Button) scene.lookup("#region-cancel")).fire();
        list.getSelectionModel().select(0); ((Button) scene.lookup("#region-edit")).fire();
        ((TextField) scene.lookup("#region-name")).setText("este-ui"); ((Button) scene.lookup("#region-save")).fire();
        check(VideoRepository.findRegions(video.getId()).get(0).getName().equals("este-ui"), "Existing region can be edited");
        list.getSelectionModel().select(1); ((Button) scene.lookup("#region-delete")).fire();
        check(VideoRepository.findRegions(video.getId()).size() == 1, "Region deletion persisted");
        VideoRepository.saveRegions(video.getId(), Arrays.asList(VideoRegion.fromJson(payload.get(0).getAsJsonObject()), VideoRegion.fromJson(payload.get(1).getAsJsonObject()))); editor.setVideo(video);
        editor.setLocked(true); check(add.isDisabled(), "Editing disabled during processing"); editor.setLocked(false);
        VideoRepository.addVideo(video.getGroupId(), "Other", video.getFilePath());
        Video other = VideoRepository.findByGroup(video.getGroupId()).get(1); editor.setVideo(other);
        check(list.getItems().isEmpty(), "Switching videos isolates regions"); editor.setVideo(video);
        rejected(() -> new VideoRegion("bad", "#00874b", Arrays.asList(new Point2D(1, 1), new Point2D(2, 2), new Point2D(3, 3)), .8, width, height), "Zero-area polygon rejected");
        rejected(() -> new VideoRegion("bad", "#00874b", Arrays.asList(new Point2D(1, 1), new Point2D(50, 40), new Point2D(1, 50), new Point2D(30, 1)), .8, width, height), "Crossed polygon rejected");
        stage.setWidth(1440); stage.setHeight(900); scene.getRoot().applyCss(); scene.getRoot().layout();
        scene.getRoot().resize(1440, 900); scene.getRoot().layout();
        Point2D resized = player.videoToScene(north[0]), original = player.sceneToVideo(resized.getX(), resized.getY());
        check(original != null && original.distance(north[0]) < .01, "Resize preserves region coordinates");
        if (getParameters().getRaw().size() > 1) Files.writeString(Paths.get(getParameters().getRaw().get(1)), editor.requestRegions().toString());
        Path screenshot = Paths.get("target/ui-snapshots/regions.png"); Files.createDirectories(screenshot.getParent());
        javafx.scene.image.WritableImage snapshot = scene.getRoot().snapshot(null, null);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(image, "png", screenshot.toFile());
        editor.setVideo(other);
        @SuppressWarnings("unchecked") ComboBox<String> shape = (ComboBox<String>) scene.lookup("#region-shape"); shape.getSelectionModel().select(1); add.fire();
        Point2D center = new Point2D(width / 2.0, height / 2.0); double radius = Math.min(width, height) / 4.0;
        click(center); click(center.add(radius, 0));
        ((TextField) scene.lookup("#region-name")).setText("plataforma"); ((Button) scene.lookup("#region-save")).fire();
        JsonArray pool = editor.requestRegions(); JsonObject nativePool = pool.get(0).getAsJsonObject();
        check(pool.size() == 1 && nativePool.get("type").getAsString().equals("CircularFractionRegion") && !nativePool.has("points"), "Two clicks create one native Morris quadrant without polygon approximation in API");
        check(Math.abs(nativePool.get("radius").getAsDouble() - radius) < .01 && nativePool.get("fraction").getAsDouble() == .25, "Pool radius and 90-degree sector use source pixels");
        check(VideoRegion.analysisType(pool).equals("MorrisPool"), "One Morris region passes analysis validation");
        DatabaseManager.connect(); editor.setVideo(other); check(editor.requestRegions().equals(pool), "Morris geometry survives database reload");
        list.getSelectionModel().select(0); ((Button) scene.lookup("#region-edit")).fire();
        @SuppressWarnings("unchecked") ComboBox<String> quadrant = (ComboBox<String>) scene.lookup("#pool-quadrant"); quadrant.getSelectionModel().select(1);
        scene.getRoot().applyCss(); scene.getRoot().layout();
        javafx.scene.image.WritableImage poolShot = scene.getRoot().snapshot(null, null); java.awt.image.BufferedImage poolImage = new java.awt.image.BufferedImage((int) poolShot.getWidth(), (int) poolShot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < poolImage.getHeight(); y++) for (int x = 0; x < poolImage.getWidth(); x++) poolImage.setRGB(x, y, poolShot.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(poolImage, "png", Paths.get("target/ui-snapshots/morris-editor.png").toFile());
        ((Button) scene.lookup("#region-save")).fire(); check(editor.requestRegions().get(0).getAsJsonObject().get("angle_start").getAsDouble() == 90, "Quadrant can be changed independently per video");
        check(VideoRepository.findRegions(video.getId()).size() == 2, "Morris edits leave other video's CrossMaze polygons intact");
        rejected(() -> VideoRegion.morris("bad", "#00874b", new Point2D(0, 0), 20, 0, .8, width, height), "Pool extending outside frame rejected");
        player.close(); Platform.exit();
    }

    private static Point2D[] rectangle(double x1, double y1, double x2, double y2) { return new Point2D[]{new Point2D(x1, y1), new Point2D(x2, y1), new Point2D(x2, y2), new Point2D(x1, y2)}; }
    private void click(Point2D original) {
        Point2D scenePoint = player.videoToScene(original);
        check(player.sceneToVideo(scenePoint.getX(), scenePoint.getY()) != null, "Vertex lies on visible frame");
        player.getViewport().getOnMouseClicked().handle(mouse(MouseEvent.MOUSE_CLICKED, scenePoint));
    }
    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, Point2D point) {
        return new MouseEvent(type, point.getX(), point.getY(), point.getX(), point.getY(), MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null);
    }
    private void fail(Throwable error) { failure = error; error.printStackTrace(); if (wait != null) wait.stop(); if (player != null) player.close(); Platform.exit(); }
    @Override public void stop() throws Exception {
        if (player != null) player.close();
        if (sandbox != null) try (var paths = Files.walk(sandbox)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
    public static void main(String[] args) { launch(args); if (failure != null) throw new AssertionError("Region checks failed", failure); }
}
