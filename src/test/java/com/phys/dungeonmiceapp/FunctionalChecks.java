package com.phys.dungeonmiceapp;

import com.google.gson.JsonObject;
import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import com.phys.dungeonmiceapp.ui.*;
import java.io.File;
import java.nio.file.*;
import java.util.function.BooleanSupplier;
import javafx.application.*;
import javafx.animation.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Run with a disposable database and a generated fixture, never against user data. */
public final class FunctionalChecks extends Application {
    private static Throwable failure;
    private WorkspaceView workspace;
    private PlaybackPane playback;
    private Stage stage;
    private File imported;
    private Path sandbox;
    private User user;
    private VideoGroup project;
    private int reopenChecks;

    @Override public void start(Stage stage) {
        this.stage = stage;
        try {
            sandbox = Files.createTempDirectory("dungeonmice-checks-");
            System.setProperty("dungeonmice.database", sandbox.resolve("data/database.enc").toString());
            System.setProperty("dungeonmice.storage", sandbox.toString());
            DatabaseManager.connect();
            check(UserRepository.login("admin", "admin123") != null, "Legacy admin login");
            check(UserRepository.login("admin", "wrong") == null, "Wrong password rejected");
            user = UserRepository.register("check-user", "check-password", "user", "Test Person", "ABC", "");
            check(!user.getPassword().equals("check-password"), "Password encoded");
            expect(() -> UserRepository.register("check-user", "other", "user", "", "", ""));
            SessionManager.setCurrentUser(user);
            project = VideoGroupRepository.saveProject(null, "Project One", "Existing description", "Category", "Responsible", "A, B", "");
            project = VideoGroupRepository.saveProject(project.getId(), "Project Renamed", "Updated description", "Category 2", "Responsible 2", "A", "");
            check(project.getDescription().equals("Updated description") && project.getCategory().equals("Category 2"), "Project update");
            JsonObject legacy = new JsonObject(); legacy.addProperty("name", "Legacy project"); legacy.addProperty("description", "Old description"); legacy.addProperty("ownerId", user.getId());
            JsonObject stored = DatabaseManager.insertOne("videoGroups", legacy);
            check(VideoGroupRepository.findById(stored.get("_id").getAsString()).getCategory().isEmpty(), "Legacy project compatibility");
            expect(() -> UserRepository.updateProfile(user, "check-user", "", "", "", "wrong", "new-password"));
            user = UserRepository.updateProfile(user, "renamed-user", "Full Name", "ID42", "", "check-password", "new-password");
            check(UserRepository.login("renamed-user", "new-password") != null, "Password and username update");
            check(UserRepository.login("renamed-user", "check-password") == null, "Old password rejected");
            check(DatabaseManager.login("renamed-user", "new-password"), "Legacy login API supports encoded passwords");
            SessionManager.setCurrentUser(user);
            User another = UserRepository.register("other-user", "password", "user", "", "", ""); SessionManager.setCurrentUser(another);
            check(VideoGroupRepository.findGroupsByCurrentUser().isEmpty(), "Project isolation");
            expect(() -> VideoGroupRepository.saveProject(project.getId(), "Hijack", "", "", "", "", ""));
            SessionManager.setCurrentUser(user);
            File fixture = new File(getParameters().getRaw().get(0));
            imported = MediaFiles.importVideo(fixture, user.getId(), project.getId());
            check(imported.isFile() && fixture.isFile(), "Imported copy and original exist");
            File thumb = MediaFiles.thumbnail(imported); check(thumb.length() > 0, "Real thumbnail extracted");
            check(MediaFiles.preview(imported).isFile(), "Native preview");
            File avi = new File(getParameters().getRaw().get(1));
            check(MediaFiles.preview(avi).isFile(), "AVI preview conversion");
            File converted = MediaFiles.importVideo(avi, user.getId(), project.getId());
            check(converted.isFile(), "AVI import conversion");
            VideoRepository.addVideo(project.getId(), "Fixture", imported.getAbsolutePath());
            VideoRepository.addVideo(project.getId(), "Converted AVI", converted.getAbsolutePath());
            check(VideoRepository.findByGroup(project.getId()).size() == 2, "Videos saved");
            DatabaseManager.connect();
            check(UserRepository.login("renamed-user", "new-password").getIdentification().equals("ID42"), "Account persisted after reload");
            check(VideoGroupRepository.findById(project.getId()).getName().equals("Project Renamed"), "Project persisted after reload");
            SessionManager.logout();
            workspace = new WorkspaceView(stage); Scene scene = new Scene(workspace, 1440, 900);
            scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm()); stage.setScene(scene); stage.show();
            css(); text("login-username", "renamed-user"); text("login-password", "new-password"); fire("Iniciar sesi\u00f3n"); css();
            check(SessionManager.isLoggedIn(), "UI authentication");
            check(((ListView<?>) workspace.lookup("#projects-list")).getItems().size() == 2, "UI real projects");
            @SuppressWarnings("unchecked") ListView<Video> videos = (ListView<Video>) workspace.lookup("#project-videos");
            videos.getSelectionModel().selectFirst(); css();
            PlaybackPane preview = findPlayer(workspace);
            waitFor(() -> preview.getPlayer() != null && preview.getPlayer().getStatus() == MediaPlayer.Status.PAUSED, () -> {
                snapshot(workspace, "projects-functional.png");
                fire("Video"); css(); check(workspace.lookup("#video-choice") != null, "UI video selector");
                playback = findPlayer(workspace);
                waitFor(() -> playback.getPlayer() != null && playback.getPlayer().getStatus() == MediaPlayer.Status.PAUSED, this::checkPlayback);
            });
        } catch (Throwable e) { fail(e); }
    }

    private void checkPlayback() {
        check(playback.getPlayer().getMedia().getWidth() == 640, "Media dimensions loaded");
        WritableImage image = playback.getViewport().snapshot(null, null);
        int colored = 0;
        for (int y = 0; y < image.getHeight(); y += 8) for (int x = 0; x < image.getWidth(); x += 8) {
            javafx.scene.paint.Color color = image.getPixelReader().getColor(x, y);
            if (Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue())) - Math.min(color.getRed(), Math.min(color.getGreen(), color.getBlue())) > .15) colored++;
        }
        check(colored > 100, "Decoded video pixels visible");
        if (reopenChecks++ < 3) {
            MediaPlayer previous = playback.getPlayer(); playback.open(imported);
            check(previous.getStatus() == MediaPlayer.Status.DISPOSED, "Previous decoder disposed on reopening");
            waitFor(() -> playback.getPlayer() != null && playback.getPlayer().getStatus() == MediaPlayer.Status.PAUSED, this::checkPlayback);
            return;
        }
        double width = playback.getViewport().getWidth(), height = playback.getViewport().getHeight();
        playback.setZoom(3); for (int i = 0; i < 15; i++) { playback.requestLayout(); workspace.requestLayout(); workspace.layout(); }
        check(Math.abs(width - playback.getViewport().getWidth()) < .01 && Math.abs(height - playback.getViewport().getHeight()) < .01, "Zoom does not stretch layout");
        playback.resetZoom(); playback.seek(1); playback.play();
        waitFor(() -> playback.getPlayer().getCurrentTime().toSeconds() > 1.3, () -> {
            playback.pause();
            waitFor(() -> playback.getPlayer().getStatus() == MediaPlayer.Status.PAUSED, this::afterPaused);
        });
    }

    private void afterPaused() {
            Slider volume = (Slider) playback.lookup("#playback-volume"); volume.setValue(.27);
            check(Math.abs(playback.getPlayer().getVolume() - .27) < .001, "Volume changes applied");
            snapshot(workspace, "video-functional.png");
            MediaPlayer old = playback.getPlayer(); fire("Datos"); css(); check(old.getStatus() == MediaPlayer.Status.DISPOSED, "Player disposed on navigation");
            fire("Usuario"); css(); check(((TextField) workspace.lookup("#profile-name")).getText().equals("Full Name"), "UI profile restored");
            text("profile-name", "Updated Through UI"); fire("Guardar cambios"); css();
            check(UserRepository.findByUsername("renamed-user").getFullName().equals("Updated Through UI"), "UI profile saved");
            snapshot(workspace, "profile-saved.png");
            fire("Crear proyecto"); css(); text("project-name", "UI Project"); text("project-description", "Created in the interface"); fire("Crear proyecto"); css();
            check(VideoGroupRepository.findGroupsByCurrentUser().size() == 3, "UI project creation");
            fire("Editar proyecto"); css(); text("project-name", "UI Project Updated"); fire("Guardar cambios"); css();
            check(VideoGroupRepository.findGroupsByCurrentUser().stream().anyMatch(p -> p.getName().equals("UI Project Updated")), "UI project edit");
            logout(workspace); css(); check(!SessionManager.isLoggedIn() && workspace.lookup("#login-password") != null, "UI logout");
            text("login-username", "renamed-user"); text("login-password", "wrong"); fire("Iniciar sesi\u00f3n"); css(); check(!SessionManager.isLoggedIn(), "UI invalid login rejected");
            fire("Crear cuenta"); css(); text("profile-username", "registered-in-ui"); text("new-password", "ui-password"); text("repeat-password", "ui-password"); fire("Crear cuenta"); css();
            check(UserRepository.login("registered-in-ui", "ui-password") != null, "UI account registration");
            workspace.close();
            SessionManager.setCurrentUser(user);
            VideoRepository.deleteByGroup(project.getId()); VideoGroupRepository.deleteById(project.getId());
            check(VideoRepository.findByGroup(project.getId()).isEmpty(), "Project video associations deleted");
            System.out.println("PASS: authentication, legacy data, account/profile, project CRUD and isolation, persistence, imports, AVI conversion, thumbnails, UI previews, decoded pixels, play/pause/seek, zoom sizing, disposal.");
            System.out.println("Disposable test data: " + sandbox);
            Platform.exit();
    }

    private void css() { workspace.applyCss(); workspace.layout(); }
    private void text(String id, String value) { ((TextField) workspace.lookup("#" + id)).setText(value); }
    private void fire(String title) { Button button = button(workspace, title); check(button != null, "Button exists: " + title); button.fire(); }
    private Button button(Parent root, String title) {
        for (Node node : root.getChildrenUnmodifiable()) { if (node instanceof Button && ((Button) node).getText().equals(title)) return (Button) node;
            if (node instanceof Parent) { Button found = button((Parent) node, title); if (found != null) return found; } } return null;
    }
    private PlaybackPane findPlayer(Parent root) {
        for (Node node : root.getChildrenUnmodifiable()) { if (node instanceof PlaybackPane) return (PlaybackPane) node;
            if (node instanceof Parent) { PlaybackPane found = findPlayer((Parent) node); if (found != null) return found; } } return null;
    }
    private void logout(Parent root) {
        for (Node node : root.getChildrenUnmodifiable()) {
            if (node instanceof Hyperlink && ((Hyperlink) node).getText().equals("Cerrar sesi\u00f3n")) { ((Hyperlink) node).fire(); return; }
            if (node instanceof Parent) { logout((Parent) node); if (!SessionManager.isLoggedIn()) return; }
        }
    }
    private void waitFor(BooleanSupplier condition, Runnable next) {
        long start = System.nanoTime(); Timeline timer = new Timeline(); timer.setCycleCount(Timeline.INDEFINITE);
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(100), event -> {
            try { if (condition.getAsBoolean()) { timer.stop(); next.run(); }
                else if ((System.nanoTime() - start) / 1000000000 > 20) { timer.stop(); throw new AssertionError("Timed out waiting for media"); } }
            catch (Throwable e) { timer.stop(); fail(e); }
        })); timer.play();
    }
    private void snapshot(Node node, String name) {
        try {
            Path folder = Paths.get("target/functional-snapshots"); Files.createDirectories(folder);
            WritableImage shot = node.snapshot(null, null); java.awt.image.BufferedImage image = new java.awt.image.BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            javax.imageio.ImageIO.write(image, "png", folder.resolve(name).toFile());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void expect(Runnable operation) { try { operation.run(); throw new AssertionError("Expected validation failure"); } catch (IllegalArgumentException expected) {} }
    private void fail(Throwable e) {
        failure = e; e.printStackTrace();
        if (playback != null && playback.getPlayer() != null) System.err.println("Player status: " + playback.getPlayer().getStatus() + ", time: " + playback.getPlayer().getCurrentTime() + ", error: " + playback.getPlayer().getError());
        if (workspace != null) { snapshot(workspace, "failure.png"); workspace.close(); }
        if (playback != null) playback.close(); Platform.exit();
    }
    public static void main(String[] args) { launch(args); if (failure != null) throw new IllegalStateException("Functional checks failed", failure); }
}
