package com.phys.dungeonmiceapp.controller;

import com.phys.dungeonmiceapp.database.VideoGroupRepository;
import com.phys.dungeonmiceapp.database.VideoRepository;
import com.phys.dungeonmiceapp.session.SessionManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.File;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;


import java.util.List;

public class GroupSelectionController {

    @FXML
    private ListView<Document> groupListView;

    @FXML
    private TextField groupNameField;

    @FXML
    private TextField groupDescriptionField;

    @FXML
    public void initialize() {
        loadGroups();
    }

    private void loadGroups() {
        List<Document> groups =
                VideoGroupRepository.findGroupsByCurrentUser();

        groupListView.getItems().clear();
        groupListView.getItems().addAll(groups);

        groupListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Document item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText("📁 " + item.getString("name"));
                }
            }
        });


    }

    @FXML
    private void handleCreateGroup() {

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Crear nuevo grupo");
        dialog.setHeaderText("Ingrese los datos del grupo");

        ButtonType createButtonType =
                new ButtonType("Crear", ButtonType.OK.getButtonData());

        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        // Campos
        TextField nameField = new TextField();
        nameField.setPromptText("Nombre del grupo");

        TextField descriptionField = new TextField();
        descriptionField.setPromptText("Descripción");

        VBox content = new VBox(10, nameField, descriptionField);
        dialog.getDialogPane().setContent(content);

        dialog.showAndWait().ifPresent(response -> {

            if (response == createButtonType) {

                String name = nameField.getText();
                String description = descriptionField.getText();

                if (name == null || name.trim().isEmpty()) {
                    showAlert("El nombre del grupo es obligatorio.");
                    return;
                }

                VideoGroupRepository.createGroup(name.trim(), description);

                loadGroups();
            }
        });
    }


    @FXML
    private void handleEnterGroup() {

        Document selected =
                groupListView.getSelectionModel().getSelectedItem();

        if (selected == null) return;

        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/phys/dungeonmiceapp/main.fxml")
            );

            Parent root = loader.load();

            // Pasar el groupId al MainController
            MainController controller = loader.getController();
            controller.setGroup(selected.getObjectId("_id"));

            Stage stage =
                    (Stage) groupListView.getScene().getWindow();

            stage.setScene(new Scene(root, 1400, 900));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleDeleteGroup() {

        Document selectedGroup =
                groupListView.getSelectionModel().getSelectedItem();

        if (selectedGroup == null) {
            showAlert("Selecciona un grupo primero.");
            return;
        }

        String groupName = selectedGroup.getString("name");
        ObjectId groupId = selectedGroup.getObjectId("_id");

        // Confirmación
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirmar eliminación");
        confirm.setHeaderText("Eliminar grupo");
        confirm.setContentText("¿Seguro que quieres eliminar el grupo \"" + groupName + "\"?\nSe eliminarán todos los videos asociados.");

        Optional<ButtonType> result = confirm.showAndWait();

        if (result.isPresent() && result.get() == ButtonType.OK) {

            // 1️⃣ Eliminar videos de Mongo
            VideoRepository.deleteByGroup(groupId);

            // 2️⃣ Eliminar grupo de Mongo
            VideoGroupRepository.deleteById(groupId);

            // 3️⃣ Eliminar carpeta física
            deleteGroupFolder(groupId);

            // 4️⃣ Recargar lista
            loadGroups();
        }
    }

    private void deleteGroupFolder(ObjectId groupId) {

        String username =
                SessionManager.getCurrentUser().getString("username");

        File folder = new File("videos/" + username + "/" + groupId);

        deleteRecursively(folder);
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

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Información");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }


}
