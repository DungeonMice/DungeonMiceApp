package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.database.DatabaseManager;
import com.phys.dungeonmiceapp.ui.WorkspaceView;
import java.nio.file.Files;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/** Exercises button actions against a real undecorated native window. */
public final class WindowControlsChecks extends Application {
    private static Throwable failure;
    private Stage stage;
    private WorkspaceView view;
    private Rectangle2D original;
    private boolean closing;

    @Override public void start(Stage stage) throws Exception {
        this.stage = stage;
        System.setProperty("dungeonmice.database", Files.createTempDirectory("window-checks-").resolve("data/database.enc").toString());
        DatabaseManager.connect();
        view = new WorkspaceView(stage);
        Scene scene = new Scene(view, 1000, 740);
        scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm());
        stage.initStyle(StageStyle.UNDECORATED); stage.setScene(scene); stage.show(); stage.centerOnScreen();
        later(() -> {
            original = bounds(); click("window-maximize");
            later(() -> {
                Rectangle2D available = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()).get(0).getVisualBounds();
                check(equal(bounds(), available), "Maximize fills working area: " + bounds() + " expected " + available); click("window-maximize");
                later(() -> {
                    check(equal(bounds(), original), "Restore preserves original bounds");
                    Button maximize = (Button) view.lookup("#window-maximize");
                    javafx.event.Event.fireEvent(maximize, new MouseEvent(MouseEvent.MOUSE_CLICKED, 15, 15, 15, 15,
                            MouseButton.PRIMARY, 2, false, false, false, false, false, false, false, false, false, true,
                            new PickResult(maximize, 15, 15)));
                    check(equal(bounds(), original), "Button double click does not trigger title-bar maximize");
                    view.getCenter().setDisable(true);
                    check(!view.lookup("#window-minimize").isDisabled() && !view.lookup("#window-close").isDisabled(), "Window controls remain enabled while content is busy");
                    click("window-minimize");
                    later(() -> {
                        check(stage.isIconified(), "Minimize reaches native window"); stage.setIconified(false); stage.toFront();
                        later(() -> {
                            check(equal(bounds(), original), "Minimize does not move or resize window");
                            closing = true;
                            if (getParameters().getRaw().contains("--native-close")) stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                            else click("window-close");
                            later(() -> { throw new AssertionError("Close did not exit application"); });
                        });
                    });
                });
            });
        });
    }

    private void click(String id) {
        Button button = (Button) view.lookup("#" + id);
        check(button.getWidth() == 40 && button.getHeight() == 38, "Window button fills its visual area: " + id);
        check(button.isPickOnBounds() && button.contains(0.5, 0.5) && button.contains(39.5, 0.5)
                && button.contains(0.5, 37.5) && button.contains(39.5, 37.5), "All button corners accept input: " + id);
        check(button.getGraphic().isMouseTransparent(), "Icon does not intercept button input: " + id);
        button.fire();
    }
    private Rectangle2D bounds() { return new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()); }
    private static boolean equal(Rectangle2D a, Rectangle2D b) {
        return Math.abs(a.getMinX() - b.getMinX()) < 2 && Math.abs(a.getMinY() - b.getMinY()) < 2
                && Math.abs(a.getWidth() - b.getWidth()) < 2 && Math.abs(a.getHeight() - b.getHeight()) < 2;
    }
    private void later(Runnable step) {
        PauseTransition pause = new PauseTransition(Duration.millis(700));
        pause.setOnFinished(e -> { try { step.run(); } catch (Throwable ex) { failure = ex; ex.printStackTrace(); Platform.exit(); } }); pause.play();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    @Override public void stop() {
        if (view != null) { view.close(); view.close(); }
        if (failure == null && closing) check(!stage.isShowing(), "Close hides window and shuts down JavaFX");
        else if (failure == null) failure = new AssertionError("Application exited before close check");
    }
    public static void main(String[] args) { launch(args); if (failure != null) System.exit(1); }
}
