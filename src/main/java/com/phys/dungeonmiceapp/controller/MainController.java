package com.phys.dungeonmiceapp.controller;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

import javafx.stage.FileChooser;
import javafx.util.Duration;

import com.phys.dungeonmiceapp.database.VideoRepository;
import com.phys.dungeonmiceapp.session.SessionManager;
import org.bson.Document;
import org.bson.types.ObjectId;
import javafx.scene.control.ListView;
import java.util.List;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;


public class MainController implements Initializable {

    @FXML private MediaView mediaView;
    @FXML private Button playButton;
    @FXML private Button pauseButton;
    @FXML private Button stopButton;
    @FXML private Button loadVideoButton;
    @FXML private Slider timeSlider;
    @FXML private Label timeLabel;
    @FXML private Slider volumeSlider;
    @FXML private ListView<Document> videoListView;
    @FXML private Button logoutButton;


    private ObjectId currentGroupId;
    private MediaPlayer mediaPlayer;

    @Override
    public void initialize(URL url, ResourceBundle rb) {

        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newVal.doubleValue() / 100.0);
            }
        });

        playButton.setOnAction(e -> {
            if (mediaPlayer != null) {
                mediaPlayer.play();
            }
        });

        pauseButton.setOnAction(e -> {
            if (mediaPlayer != null) {
                mediaPlayer.pause();
            }
        });

        stopButton.setOnAction(e -> {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
            }
        });

        loadVideoButton.setOnAction(e -> openVideo());
        logoutButton.setOnAction(e -> logout());
    }

    private void openVideo() {

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar video");

        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "Archivos de video", "*.mp4", "*.avi", "*.mov", "*.m4v"
                )
        );

        File file = fileChooser.showOpenDialog(null);

        if (file != null && currentGroupId != null) {

            try {

                String username =
                        SessionManager.getCurrentUser().getString("username");

                File targetDir = new File("videos/" + username + "/" + currentGroupId);
                targetDir.mkdirs();

                File targetFile = new File(targetDir, file.getName());

                java.nio.file.Files.copy(
                        file.toPath(),
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );

                // Guardar en Mongo
                VideoRepository.addVideo(
                        currentGroupId,
                        file.getName(),
                        targetFile.getPath()
                );

                loadVideos();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private void loadVideo(File file) {

        if (mediaPlayer != null) {
            mediaPlayer.dispose();
        }

        Media media = new Media(file.toURI().toString());
        mediaPlayer = new MediaPlayer(media);
        mediaView.setMediaPlayer(mediaPlayer);

        // Sincronizar volumen inicial
        mediaPlayer.setVolume(volumeSlider.getValue());

        mediaPlayer.setOnReady(() -> {

            Duration total = media.getDuration();
            timeSlider.setMax(total.toSeconds());
            updateTimeLabel(Duration.ZERO, total);
        });

        mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {

            if (!timeSlider.isValueChanging()) {
                timeSlider.setValue(newTime.toSeconds());
            }

            updateTimeLabel(newTime, mediaPlayer.getTotalDuration());
        });

        timeSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {

            if (!isChanging && mediaPlayer != null) {
                mediaPlayer.seek(Duration.seconds(timeSlider.getValue()));
            }
        });
    }

    private void updateTimeLabel(Duration current, Duration total) {

        if (current == null || total == null) return;

        String currentText = formatTime(current);
        String totalText = formatTime(total);

        timeLabel.setText(currentText + " / " + totalText);
    }

    private String formatTime(Duration duration) {

        int minutes = (int) duration.toMinutes();
        int seconds = (int) duration.toSeconds() % 60;

        return String.format("%02d:%02d", minutes, seconds);
    }

    public void setGroup(ObjectId groupId) {
        this.currentGroupId = groupId;
        loadVideos();
    }

    private void loadVideos() {

        if (currentGroupId == null) return;

        List<Document> videos =
                VideoRepository.findByGroup(currentGroupId);

        videoListView.getItems().clear();
        videoListView.getItems().addAll(videos);

        videoListView.setCellFactory(param -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Document item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getString("title"));
            }
        });

        videoListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Document selected =
                        videoListView.getSelectionModel().getSelectedItem();

                if (selected != null) {
                    File file = new File(selected.getString("filePath"));
                    loadVideo(file);
                }
            }
        });

    }

    private void logout() {

        try {

            // Limpiar sesión
            SessionManager.logout();

            // Cargar login
            FXMLLoader loader =
                    new FXMLLoader(getClass().getResource("/com/phys/dungeonmiceapp/login.fxml"));

            Stage stage = (Stage) logoutButton.getScene().getWindow();
            stage.setScene(new Scene(loader.load()));
            stage.setTitle("Login - Dungeon Mice");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



}