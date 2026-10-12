package com.phys.dungeonmiceapp;

import javafx.application.Application;
import com.phys.dungeonmiceapp.ui.WorkspaceView;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.geometry.Rectangle2D;
import com.phys.dungeonmiceapp.database.DatabaseManager;

public final class DungeonMiceApplication extends Application {
    private WorkspaceView view;
    private com.phys.dungeonmiceapp.api.ManagedPythonBridge python;

    @Override
    public void start(Stage stage) throws Exception {
        try {
            DatabaseManager.connect();
        } catch (RuntimeException e) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                    "No se pudo abrir la base de datos. Comprueba DB_MASTER_PASSWORD y el archivo data/database.enc.");
            alert.showAndWait(); javafx.application.Platform.exit(); return;
        }
        view = new WorkspaceView(stage);
        Rectangle2D available = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(view, Math.min(1440, available.getWidth()), Math.min(900, available.getHeight()));
        scene.getStylesheets().add(App.class.getResource("/styles/workspace.css").toExternalForm());
        stage.initStyle(javafx.stage.StageStyle.UNDECORATED);
        stage.setTitle("Dungeon Mice");
        stage.setScene(scene);
        stage.setMinWidth(Math.min(1000, available.getWidth()));
        stage.setMinHeight(Math.min(740, available.getHeight()));
        stage.show();
        stage.centerOnScreen();
        if (Boolean.parseBoolean(System.getProperty("dungeonmice.python.autostart", "true"))
                && System.getProperty("dungeonmice.python.url") == null) {
            view.pythonConnecting();
            python = new com.phys.dungeonmiceapp.api.ManagedPythonBridge(message -> javafx.application.Platform.runLater(() -> view.pythonUnavailable(message)));
            python.start().thenAccept(uri -> javafx.application.Platform.runLater(() -> view.pythonConnected(uri.toString(), python.frameToken())));
            if (getParameters().getRaw().contains("--python-startup-check")) {
                python.start().thenAccept(uri -> javafx.application.Platform.runLater(() -> {
                    if (!python.isRunning()) throw new AssertionError("Python is not running");
                    System.out.println("PASS App automatic Python startup PID=" + python.pid());
                    javafx.application.Platform.exit();
                })).exceptionally(ex -> { ex.printStackTrace(); javafx.application.Platform.exit(); return null; });
            }
        }
        if (getParameters().getRaw().contains("--ui-snapshots")) {
            javafx.application.Platform.runLater(() -> view.writeSnapshots());
        }
    }

    @Override
    public void stop() {
        if (view != null) view.close();
        if (python != null) {
            long pid = python.pid(); python.close();
            if (getParameters().getRaw().contains("--python-startup-check")) {
                if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) throw new AssertionError("Python was left running");
                System.out.println("PASS App automatic Python shutdown PID=" + pid);
            }
        }
    }
}
