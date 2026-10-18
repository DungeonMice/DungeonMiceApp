package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import com.phys.dungeonmiceapp.ui.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javafx.application.*;
import javafx.animation.*;
import javafx.geometry.Point2D;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import javafx.util.Duration;

public final class ProjectCategoryChecks extends Application {
    private static Throwable failure;
    private WorkspaceView workspace;
    private Path temporary, fixture;
    private Timeline waiting;
    private VideoGroup morris, cross;
    private Video morrisVideo;
    private String savedRegion;
    private Stage stage;
    private void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    private void fire(String id) { ((Button) workspace.lookup("#" + id)).fire(); }
    private void click(String text) {
        for (Node node : workspace.lookupAll(".button")) if (node instanceof Button && text.equals(((Button) node).getText())) { ((Button) node).fire(); return; }
        throw new AssertionError("Button not found: " + text);
    }
    @SuppressWarnings("unchecked") private ComboBox<ExperimentType> category() { return (ComboBox<ExperimentType>) workspace.lookup("#project-category"); }
    @SuppressWarnings("unchecked") private ComboBox<String> shape() { return (ComboBox<String>) workspace.lookup("#region-shape"); }
    @Override public void start(Stage stage) {
        this.stage = stage;
        try {
            temporary = Files.createTempDirectory("dungeonmice-category-ui-"); fixture = Paths.get(getParameters().getRaw().get(0)).toAbsolutePath();
            System.setProperty("dungeonmice.database", temporary.resolve("data/database.enc").toString()); System.setProperty("dungeonmice.storage", temporary.toString());
            DatabaseManager.connect(); SessionManager.setCurrentUser(UserRepository.login("admin", "admin123"));
            workspace = new WorkspaceView(stage); Scene scene = new Scene(workspace, 1440, 900); scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm()); stage.setScene(scene); stage.show();
            ((TextField) workspace.lookup("#login-username")).setText("admin"); ((PasswordField) workspace.lookup("#login-password")).setText("admin123"); click("Iniciar sesi\u00f3n");
            click("Crear proyecto");
            check(category().getItems().size() == 2 && !category().isEditable(), "Project category is a closed dropdown with two experiment types");
            check(category().getItems().get(1).toString().equals("Piscina de Morris"), "Morris has a readable Spanish label");
            ((TextField) workspace.lookup("#project-name")).setText("Pool");
            answerDialog(ButtonType.OK, "Selecciona Piscina"); click("Crear proyecto");
            check(VideoGroupRepository.findGroupsByCurrentUser().isEmpty(), "Missing category prevents project creation");
            category().setValue(ExperimentType.MORRIS_POOL); snapshot("project-category-1440.png");
            stage.setWidth(1000); stage.setHeight(740);
            delay(250, () -> {
                snapshot("project-category-1000.png"); Node control = category();
                check(control.localToScene(control.getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth(), "Dropdown fits compact project form");
                click("Crear proyecto"); morris = named("Pool"); check(morris.getCategory().equals("MorrisPool"), "Canonical Morris type persisted");
                morrisVideo = associate(morris);
                VideoRepository.saveRegions(morrisVideo.getId(), Collections.singletonList(VideoRegion.morris("Plataforma", "#00874b", new Point2D(160, 120), 70, 270, .8, 320, 240)));
                savedRegion = VideoRepository.findRegions(morrisVideo.getId()).get(0).toJson().toString();
                fire("nav-video"); await(this::ready, this::checkMorris);
            });
        } catch (Throwable error) { fail(error); }
    }
    private VideoGroup named(String name) { return VideoGroupRepository.findGroupsByCurrentUser().stream().filter(group -> name.equals(group.getName())).findFirst().orElseThrow(); }
    private Video associate(VideoGroup group) {
        try { VideoRepository.addReferences(group.getId(), Collections.singletonList(fixture.toFile())); return VideoRepository.findByGroup(group.getId()).get(0); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private boolean ready() { Node viewport = workspace.lookup("#playback-viewport"); return viewport != null && viewport.getParent() instanceof PlaybackPane && ((PlaybackPane) viewport.getParent()).videoReadyProperty().get(); }
    private RegionEditor editor() { return (RegionEditor) workspace.lookup("#region-editor"); }
    private void checkMorris() {
        check(shape().getItems().size() == 1 && shape().getValue().equals("Cuadrante (Morris)"), "Morris project only offers the circular quadrant editor");
        ExperimentType.MORRIS_POOL.validateRegions(editor().requestRegions());
        fire("region-add"); check(shape().getValue().equals("Cuadrante (Morris)") && !workspace.lookup("#pool-angle-slider").isDisabled(), "Morris add opens the saved quadrant controls"); fire("region-cancel");
        fire("nav-projects"); click("Editar proyecto"); check(category().getValue() == ExperimentType.MORRIS_POOL, "Editing restores the saved category");
        category().setValue(ExperimentType.CROSS_MAZE); answerDialog(ButtonType.CANCEL, "Hay regiones"); click("Guardar cambios");
        check(named("Pool").getCategory().equals("MorrisPool"), "Cancelling type change preserves project type");
        answerDialog(ButtonType.OK, "Hay regiones"); click("Guardar cambios");
        check(named("Pool").getCategory().equals("CrossMaze"), "Confirmed type change saved");
        check(VideoRepository.findRegions(morrisVideo.getId()).get(0).toJson().toString().equals(savedRegion), "Type change never deletes or converts saved geometry");
        DatabaseManager.connect(); check(named("Pool").getCategory().equals("CrossMaze"), "Category survives encrypted database reload");
        fire("nav-video"); await(this::ready, () -> {
            check(shape().getItems().size() == 1 && shape().getValue().equals("Poligono (CrossMaze)"), "Changed category controls the editor, not legacy stored shapes");
            try { editor().requestRegions(); throw new AssertionError("Mismatched regions accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("no corresponden"), "Wrong experiment regions blocked before Python processing"); }
            check(VideoRepository.findRegions(morrisVideo.getId()).get(0).toJson().toString().equals(savedRegion), "Validation preserves incompatible saved regions");
            createCross();
        });
    }
    private void createCross() {
        fire("nav-projects"); click("Crear proyecto"); check(category().getValue() == null, "New project does not inherit the previous category");
        ((TextField) workspace.lookup("#project-name")).setText("Cross"); category().setValue(ExperimentType.CROSS_MAZE); click("Crear proyecto"); cross = named("Cross");
        Video video = associate(cross);
        VideoRepository.saveRegions(video.getId(), Arrays.asList(polygon("Centro", 20), polygon("Brazo", 80)));
        fire("nav-video"); await(this::ready, () -> {
            check(shape().getItems().size() == 1 && shape().getValue().equals("Poligono (CrossMaze)"), "New CrossMaze project only offers polygons");
            ExperimentType.CROSS_MAZE.validateRegions(editor().requestRegions());
            fire("region-add"); check(shape().getValue().equals("Poligono (CrossMaze)") && ((TextField) workspace.lookup("#region-name")).getText().equals("Region 3"), "CrossMaze add opens polygon creation"); fire("region-cancel");
            check(VideoRepository.findRegions(morrisVideo.getId()).get(0).toJson().toString().equals(savedRegion), "Different projects retain independent regions");
            VideoGroup legacy = VideoGroupRepository.saveProject(null, "Legacy", "", "Categoria antigua", "", "", "");
            fire("nav-projects");
            @SuppressWarnings("unchecked") ListView<VideoGroup> projects = (ListView<VideoGroup>) workspace.lookup("#projects-list");
            projects.getSelectionModel().select(projects.getItems().stream().filter(group -> legacy.getId().equals(group.getId())).findFirst().orElseThrow());
            click("Editar proyecto"); check(category().getValue() == null && named("Legacy").getCategory().equals("Categoria antigua"), "Legacy categories are not silently converted");
            click("Cancelar"); check(ExperimentType.fromCategory("Piscina de Morris") == ExperimentType.MORRIS_POOL, "Existing human-readable category recognized"); Platform.exit();
        });
    }
    private VideoRegion polygon(String name, int x) { return new VideoRegion(name, "#00874b", Arrays.asList(new Point2D(x, 20), new Point2D(x + 30, 20), new Point2D(x + 30, 50), new Point2D(x, 50)), .8, 320, 240); }
    private void answerDialog(ButtonType answer, String expected) {
        Platform.runLater(() -> safe(() -> {
            for (Window window : new ArrayList<>(Window.getWindows())) if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane) {
                DialogPane pane = (DialogPane) window.getScene().getRoot(); check(pane.getContentText().contains(expected), "Expected category validation/confirmation dialog"); ((Button) pane.lookupButton(answer)).fire(); return;
            }
            throw new AssertionError("Expected project dialog missing");
        }));
    }
    private void snapshot(String name) {
        try {
            workspace.applyCss(); workspace.layout(); javafx.scene.image.WritableImage shot = workspace.snapshot(null, null);
            java.awt.image.BufferedImage png = new java.awt.image.BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Files.createDirectories(Paths.get("target/ui-snapshots")); javax.imageio.ImageIO.write(png, "png", Paths.get("target/ui-snapshots", name).toFile());
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void delay(int millis, Runnable action) { PauseTransition timer = new PauseTransition(Duration.millis(millis)); timer.setOnFinished(event -> Platform.runLater(() -> safe(action))); timer.play(); }
    private void safe(Runnable action) { try { action.run(); } catch (Throwable error) { fail(error); } }
    private void await(BooleanSupplier ready, Runnable action) {
        if (waiting != null) waiting.stop(); int[] count = {0}; waiting = new Timeline(new KeyFrame(Duration.millis(100), event -> safe(() -> { if (ready.getAsBoolean()) { waiting.stop(); Platform.runLater(() -> safe(action)); } else if (++count[0] > 300) throw new AssertionError("Project category UI timeout"); })));
        waiting.setCycleCount(Animation.INDEFINITE); waiting.play();
    }
    private void fail(Throwable error) { failure = error; error.printStackTrace(); if (waiting != null) waiting.stop(); Platform.exit(); }
    @Override public void stop() throws Exception {
        if (workspace != null) workspace.close(); SessionManager.logout();
        if (temporary != null) try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
    }
    public static void main(String[] args) { launch(args); if (failure != null) throw new AssertionError("Project category checks failed", failure); }
}
