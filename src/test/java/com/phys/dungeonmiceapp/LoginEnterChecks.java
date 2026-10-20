package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import com.phys.dungeonmiceapp.ui.WorkspaceView;
import java.nio.file.Files;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Native keyboard input, not a direct call to the login action. */
public final class LoginEnterChecks extends Application {
    private static Throwable failure; private WorkspaceView view;
    @Override public void start(Stage stage) throws Exception {
        System.setProperty("dungeonmice.database", Files.createTempDirectory("login-enter-").resolve("database.enc").toString());
        DatabaseManager.connect(); UserRepository.register("enter-check", "secret", "user", "", "", ""); SessionManager.logout();
        view = new WorkspaceView(stage); stage.setScene(new Scene(view, 1000, 740)); stage.setAlwaysOnTop(true); stage.show(); stage.toFront();
        TextField username = (TextField) view.lookup("#login-username"), password = (TextField) view.lookup("#login-password");
        username.setText("enter-check"); password.setText("wrong");
        TextField focused = getParameters().getRaw().contains("--username") ? username : password;
        Robot robot = new Robot();
        later(() -> { stage.requestFocus(); focused.requestFocus(); robot.mouseMove(focused.localToScreen(25, 15)); robot.mouseClick(javafx.scene.input.MouseButton.PRIMARY); later(() -> {
            check(focused.isFocused(), "Native Enter targets the login field"); enter(robot); later(() -> {
            check(!SessionManager.isLoggedIn(), "Enter rejects invalid credentials without leaving login");
            check(!((javafx.scene.control.Label) view.lookup(".validation-message")).getText().isEmpty(), "Enter invokes authentication and displays validation");
            password.setText("secret"); focused.requestFocus(); enter(robot);
            later(() -> { check(SessionManager.isLoggedIn(), "Enter logs in from " + focused.getId()); view.close(); stage.hide(); Platform.exit(); });
        }); }); });
    }
    private void enter(Robot robot) {
        robot.keyPress(KeyCode.ENTER);
        PauseTransition release = new PauseTransition(Duration.millis(70)); release.setOnFinished(event -> robot.keyRelease(KeyCode.ENTER)); release.play();
    }
    private void later(Runnable action) {
        PauseTransition delay = new PauseTransition(Duration.millis(450));
        delay.setOnFinished(event -> { try { action.run(); } catch (Throwable ex) { failure = ex; ex.printStackTrace(); Platform.exit(); } }); delay.play();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); System.out.println("PASS " + message); }
    @Override public void stop() { if (view != null) view.close(); }
    public static void main(String[] args) { launch(args); if (failure != null) System.exit(1); }
}
