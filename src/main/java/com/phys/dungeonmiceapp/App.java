package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.database.DatabaseManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;


public class App extends Application {

    @Override
    public void start(Stage stage) throws Exception {

        // 🔥 CONECTAR A MONGO AQUÍ
        DatabaseManager.connect();

        FXMLLoader fxmlLoader = new FXMLLoader(
                App.class.getResource("/com/phys/dungeonmiceapp/login.fxml")
        );

        Scene scene = new Scene(fxmlLoader.load());

        Image image = new Image(
                App.class.getResource("/images/DungeonMiceApp.png").toExternalForm()
        );

        stage.getIcons().add(image);
        stage.setTitle("Dungeon Mice");
        stage.setScene(scene);

        stage.setMinWidth(1400);
        stage.setMinHeight(900);

        stage.setWidth(1400);
        stage.setHeight(900);

        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }

}
