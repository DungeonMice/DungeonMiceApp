package com.phys.dungeonmiceapp.controller;

import javafx.scene.shape.Rectangle;
import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;

import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;

import javafx.scene.layout.StackPane;
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

    @FXML
    private MediaView mediaView;
    @FXML
    private Button playButton;
    @FXML
    private Button pauseButton;
    @FXML
    private Button stopButton;
    @FXML
    private Button loadVideoButton;
    @FXML
    private Slider timeSlider;
    @FXML
    private Label timeLabel;
    @FXML
    private Slider volumeSlider;
    @FXML
    private ListView<Document> videoListView;
    @FXML
    private Button logoutButton;
    @FXML
    private Button resetZoomButton;
    @FXML
    private StackPane videoContainer;
    @FXML
    private Group zoomGroup;


    private ObjectId currentGroupId;
    private MediaPlayer mediaPlayer;
    private double zoomFactor = 1.0;
    private static final double ZOOM_DELTA = 0.1;
    private static final double MAX_ZOOM = 5.0;
    private static final double MIN_ZOOM = 1.0;
    private double mouseOldX;
    private double mouseOldY;

    @Override
    public void initialize(URL url, ResourceBundle rb) {

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(videoContainer.widthProperty());
        clip.heightProperty().bind(videoContainer.heightProperty());
        videoContainer.setClip(clip);

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

        videoContainer.setOnScroll(event -> {

            if (mediaPlayer == null) return;

            double oldScale = zoomFactor;

            if (event.getDeltaY() > 0) {
                zoomFactor += ZOOM_DELTA;
            } else {
                zoomFactor -= ZOOM_DELTA;
            }

            zoomFactor = clamp(zoomFactor, MIN_ZOOM, MAX_ZOOM);

            double scaleFactor = zoomFactor / oldScale;

            double mouseX = event.getX();
            double mouseY = event.getY();

            zoomGroup.setScaleX(zoomFactor);
            zoomGroup.setScaleY(zoomFactor);

            zoomGroup.setTranslateX(
                    zoomGroup.getTranslateX() - (mouseX - zoomGroup.getBoundsInParent().getWidth()/2) * (scaleFactor - 1)
            );

            zoomGroup.setTranslateY(
                    zoomGroup.getTranslateY() - (mouseY - zoomGroup.getBoundsInParent().getHeight()/2) * (scaleFactor - 1)
            );

        });

        videoContainer.setOnMousePressed(event -> {
            mouseOldX = event.getSceneX();
            mouseOldY = event.getSceneY();
        });

        videoContainer.setOnMouseDragged(event -> {

            if (zoomFactor <= 1.0) return;

            double deltaX = event.getSceneX() - mouseOldX;
            double deltaY = event.getSceneY() - mouseOldY;

            zoomGroup.setTranslateX(zoomGroup.getTranslateX() + deltaX);
            zoomGroup.setTranslateY(zoomGroup.getTranslateY() + deltaY);

            mouseOldX = event.getSceneX();
            mouseOldY = event.getSceneY();
        });

        resetZoomButton.setOnAction(e -> resetZoom());
    }

    private void resetZoom() {
        zoomFactor = 1.0;
        zoomGroup.setScaleX(1.0);
        zoomGroup.setScaleY(1.0);
        zoomGroup.setTranslateX(0);
        zoomGroup.setTranslateY(0);
    }

    private void applyLimits() {

        double containerWidth = videoContainer.getWidth();
        double containerHeight = videoContainer.getHeight();

        double scaledWidth = mediaView.getBoundsInParent().getWidth();
        double scaledHeight = mediaView.getBoundsInParent().getHeight();

        double maxX = (scaledWidth - containerWidth) / 2;
        double maxY = (scaledHeight - containerHeight) / 2;

        if (maxX < 0) maxX = 0;
        if (maxY < 0) maxY = 0;

        zoomGroup.setTranslateX(
                clamp(zoomGroup.getTranslateX(), -maxX, maxX)
        );

        zoomGroup.setTranslateY(
                clamp(zoomGroup.getTranslateY(), -maxY, maxY)
        );
    }

    private double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
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

        zoomFactor = 1.0;
        mediaView.setScaleX(1.0);
        mediaView.setScaleY(1.0);
        mediaView.setTranslateX(0);
        mediaView.setTranslateY(0);
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