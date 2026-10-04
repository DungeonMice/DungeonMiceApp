package com.phys.dungeonmiceapp;

import javafx.application.Application;
import com.phys.dungeonmiceapp.ui.WorkspaceView;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.geometry.Rectangle2D;
import com.phys.dungeonmiceapp.database.DatabaseManager;

public class App extends Application {
    private WorkspaceView view;

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
        if (getParameters().getRaw().contains("--ui-snapshots")) {
            javafx.application.Platform.runLater(() -> view.writeSnapshots());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void stop() {
        if (view != null) view.close();
    }
}
