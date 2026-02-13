module com.phys.dungeonmiceapp {

    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;

    opens com.phys.dungeonmiceapp to javafx.fxml;
    opens com.phys.dungeonmiceapp.controller to javafx.fxml;

    exports com.phys.dungeonmiceapp;
}