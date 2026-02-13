package com.phys.dungeonmiceapp;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader fxmlLoader = new FXMLLoader(
            App.class.getResource("/com/phys/dungeonmiceapp/main.fxml")
        );

        Scene scene = new Scene(fxmlLoader.load());
        stage.setTitle("Dungeon Mice");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}
