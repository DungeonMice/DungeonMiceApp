package com.phys.dungeonmiceapp.controller;

import com.phys.dungeonmiceapp.database.VideoGroupRepository;
import com.phys.dungeonmiceapp.database.VideoRepository;
import com.phys.dungeonmiceapp.model.VideoGroup;
import com.phys.dungeonmiceapp.session.SessionManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;
import java.util.Optional;

public class GroupSelectionController {

    @FXML private ListView<VideoGroup> groupListView;
    @FXML private TextField groupNameField;
    @FXML private TextField groupDescriptionField;

    @FXML
    public void initialize() {
        loadGroups();
    }

    // -------------------------------------------------------------------------
    // Cargar grupos
    // -------------------------------------------------------------------------

    private void loadGroups() {
        List<VideoGroup> groups = VideoGroupRepository.findGroupsByCurrentUser();

        groupListView.getItems().clear();
        groupListView.getItems().addAll(groups);

        groupListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(VideoGroup item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : "📁 " + item.getName());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Crear grupo
    // -------------------------------------------------------------------------

    @FXML
    private void handleCreateGroup() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Crear nuevo grupo");
        dialog.setHeaderText("Ingrese los datos del grupo");

        ButtonType createButtonType = new ButtonType("Crear", ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        TextField nameField = new TextField();
        nameField.setPromptText("Nombre del grupo");

        TextField descriptionField = new TextField();
        descriptionField.setPromptText("Descripción");

        dialog.getDialogPane().setContent(new VBox(10, nameField, descriptionField));

        dialog.showAndWait().ifPresent(response -> {
            if (response == createButtonType) {
                String name = nameField.getText();
                if (name == null || name.trim().isEmpty()) {
                    showAlert("El nombre del grupo es obligatorio.");
                    return;
                }
                VideoGroupRepository.createGroup(name.trim(), descriptionField.getText());
                loadGroups();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Entrar a grupo
    // -------------------------------------------------------------------------

    @FXML
    private void handleEnterGroup() {
        VideoGroup selected = groupListView.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/phys/dungeonmiceapp/main.fxml")
            );
            Parent root = loader.load();

            MainController controller = loader.getController();
            controller.setGroup(selected.getId());

            Stage stage = (Stage) groupListView.getScene().getWindow();
            stage.setScene(new Scene(root, 1400, 900));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Eliminar grupo
    // -------------------------------------------------------------------------

    @FXML
    private void handleDeleteGroup() {
        VideoGroup selected = groupListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("Selecciona un grupo primero.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirmar eliminación");
        confirm.setHeaderText("Eliminar grupo");
        confirm.setContentText("¿Seguro que quieres eliminar el grupo \""
                + selected.getName() + "\"?\nSe eliminarán todos los videos asociados.");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String groupId = selected.getId();

            VideoRepository.deleteByGroup(groupId);
            VideoGroupRepository.deleteById(groupId);
            deleteGroupFolder(groupId);
            loadGroups();
        }
    }

    private void deleteGroupFolder(String groupId) {
        String username = SessionManager.getCurrentUser().getUsername();
        deleteRecursively(new File("videos/" + username + "/" + groupId));
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Información");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}