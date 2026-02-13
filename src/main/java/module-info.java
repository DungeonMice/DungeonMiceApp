module com.phys.dungeonmiceapp {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.base;

    opens com.phys.dungeonmiceapp to javafx.fxml;
    exports com.phys.dungeonmiceapp;
}
