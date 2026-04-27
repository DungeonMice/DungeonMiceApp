package com.phys.dungeonmiceapp.controller;

import com.phys.dungeonmiceapp.database.UserRepository;
import com.phys.dungeonmiceapp.model.User;
import com.phys.dungeonmiceapp.session.SessionManager;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label messageLabel;

    @FXML
    private void handleLogin(ActionEvent event) {
        String username = usernameField.getText();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            messageLabel.setText("Please enter username and password");
            return;
        }

        User user = UserRepository.login(username, password);

        if (user != null) {
            SessionManager.setCurrentUser(user);
            loadGroupSelectionView();
        } else {
            messageLabel.setText("Invalid credentials");
        }
    }

    private void loadGroupSelectionView() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/phys/dungeonmiceapp/group-selection.fxml")
            );
            Parent root = loader.load();
            Stage stage = (Stage) usernameField.getScene().getWindow();
            stage.setScene(new Scene(root, 800, 600));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}