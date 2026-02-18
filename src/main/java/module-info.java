module com.phys.dungeonmiceapp {

    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires java.desktop;
    requires mongo.java.driver;

    opens com.phys.dungeonmiceapp to javafx.fxml;
    opens com.phys.dungeonmiceapp.controller to javafx.fxml;
    opens com.phys.dungeonmiceapp.database to javafx.fxml;

    exports com.phys.dungeonmiceapp;
    exports com.phys.dungeonmiceapp.database;
}
