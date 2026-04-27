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
import com.phys.dungeonmiceapp.model.Video;
import com.phys.dungeonmiceapp.session.SessionManager;
import javafx.scene.control.ListView;

import java.util.List;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.concurrent.Task;
import javafx.scene.control.ProgressIndicator;
import java.text.Normalizer;


public class MainController implements Initializable {

    @FXML private MediaView mediaView;
    @FXML private Button playButton;
    @FXML private Button pauseButton;
    @FXML private Button stopButton;
    @FXML private Button loadVideoButton;
    @FXML private Slider timeSlider;
    @FXML private Label timeLabel;
    @FXML private Slider volumeSlider;
    @FXML private ListView<Video> videoListView;
    @FXML private Button logoutButton;
    @FXML private Button resetZoomButton;
    @FXML private StackPane videoContainer;
    @FXML private Group zoomGroup;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label loadingLabel;

    private String currentGroupId;
    private MediaPlayer mediaPlayer;
    private double zoomFactor = 1.0;
    private static final double ZOOM_DELTA = 0.1;
    private static final double MAX_ZOOM   = 5.0;
    private static final double MIN_ZOOM   = 1.0;
    private double mouseOldX;
    private double mouseOldY;

    @Override
    public void initialize(URL url, ResourceBundle rb) {

        mediaView.setPreserveRatio(true);
        mediaView.fitWidthProperty().bind(videoContainer.widthProperty());
        mediaView.fitHeightProperty().bind(videoContainer.heightProperty());

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(videoContainer.widthProperty());
        clip.heightProperty().bind(videoContainer.heightProperty());
        videoContainer.setClip(clip);

        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newVal.doubleValue() / 100.0);
            }
        });

        playButton.setOnAction(e -> { if (mediaPlayer != null) mediaPlayer.play(); });
        pauseButton.setOnAction(e -> { if (mediaPlayer != null) mediaPlayer.pause(); });
        stopButton.setOnAction(e -> { if (mediaPlayer != null) mediaPlayer.stop(); });

        loadVideoButton.setOnAction(e -> openVideo());
        logoutButton.setOnAction(e -> logout());

        videoContainer.setOnScroll(event -> {
            if (mediaPlayer == null) return;

            double oldScale = zoomFactor;
            zoomFactor += event.getDeltaY() > 0 ? ZOOM_DELTA : -ZOOM_DELTA;
            zoomFactor = clamp(zoomFactor, MIN_ZOOM, MAX_ZOOM);

            double scaleFactor = zoomFactor / oldScale;
            double mouseX = event.getX();
            double mouseY = event.getY();

            zoomGroup.setScaleX(zoomFactor);
            zoomGroup.setScaleY(zoomFactor);
            zoomGroup.setTranslateX(
                    zoomGroup.getTranslateX() - (mouseX - zoomGroup.getBoundsInParent().getWidth() / 2) * (scaleFactor - 1)
            );
            zoomGroup.setTranslateY(
                    zoomGroup.getTranslateY() - (mouseY - zoomGroup.getBoundsInParent().getHeight() / 2) * (scaleFactor - 1)
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

    // -------------------------------------------------------------------------
    // Zoom
    // -------------------------------------------------------------------------

    private void resetZoom() {
        zoomFactor = 1.0;
        zoomGroup.setScaleX(1.0);
        zoomGroup.setScaleY(1.0);
        zoomGroup.setTranslateX(0);
        zoomGroup.setTranslateY(0);
    }

    private double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    // -------------------------------------------------------------------------
    // Cargar video desde disco
    // -------------------------------------------------------------------------

    private void openVideo() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar video");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Archivos de video", "*.mp4", "*.avi", "*.mov", "*.m4v")
        );

        File file = fileChooser.showOpenDialog(null);
        if (file == null || currentGroupId == null) return;

        String username = SessionManager.getCurrentUser().getUsername();
        File targetDir  = new File("videos/" + username + "/" + currentGroupId);
        targetDir.mkdirs();

        Task<File> convertTask = new Task<>() {
            @Override
            protected File call() throws Exception {
                if (!file.getName().toLowerCase().endsWith(".mp4")) {
                    return convertToMp4(file, targetDir);
                } else {
                    File copiedFile = new File(targetDir, file.getName());
                    java.nio.file.Files.copy(
                            file.toPath(),
                            copiedFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                    return copiedFile;
                }
            }
        };

        loadingLabel.setVisible(true);

        convertTask.setOnSucceeded(event -> {
            loadingLabel.setVisible(false);
            try {
                File processedFile = convertTask.getValue();
                VideoRepository.addVideo(
                        currentGroupId,
                        processedFile.getName(),
                        processedFile.getAbsolutePath()
                );
                loadVideos();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        convertTask.setOnFailed(event -> {
            loadingLabel.setVisible(false);
            convertTask.getException().printStackTrace();
        });

        new Thread(convertTask).start();
    }

    // -------------------------------------------------------------------------
    // Reproducción
    // -------------------------------------------------------------------------

    private void loadVideo(File file) {
        if (mediaPlayer != null) mediaPlayer.dispose();

        String normalizedPath = Normalizer.normalize(file.getAbsolutePath(), Normalizer.Form.NFC);
        File normalizedFile   = new File(normalizedPath);

        if (!normalizedFile.exists()) {
            System.out.println("Archivo no encontrado: " + normalizedPath);
            return;
        }

        Media media  = new Media(normalizedFile.toURI().toString());
        mediaPlayer  = new MediaPlayer(media);
        mediaView.setMediaPlayer(mediaPlayer);
        mediaPlayer.setVolume(volumeSlider.getValue());

        mediaPlayer.setOnReady(() -> {
            Duration total = media.getDuration();
            timeSlider.setMax(total.toSeconds());
            updateTimeLabel(Duration.ZERO, total);
        });

        mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            if (!timeSlider.isValueChanging()) timeSlider.setValue(newTime.toSeconds());
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
        timeLabel.setText(formatTime(current) + " / " + formatTime(total));
    }

    private String formatTime(Duration duration) {
        int minutes = (int) duration.toMinutes();
        int seconds = (int) duration.toSeconds() % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    // -------------------------------------------------------------------------
    // Lista de videos
    // -------------------------------------------------------------------------

    public void setGroup(String groupId) {
        this.currentGroupId = groupId;
        loadVideos();
    }

    private void loadVideos() {
        if (currentGroupId == null) return;

        List<Video> videos = VideoRepository.findByGroup(currentGroupId);

        videoListView.getItems().clear();
        videoListView.getItems().addAll(videos);

        videoListView.setCellFactory(param -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Video item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTitle());
            }
        });

        videoListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Video selected = videoListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    loadVideo(new File(selected.getFilePath()));
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    private void logout() {
        try {
            SessionManager.logout();
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/phys/dungeonmiceapp/login.fxml")
            );
            Stage stage = (Stage) logoutButton.getScene().getWindow();
            stage.setScene(new Scene(loader.load()));
            stage.setTitle("Login - Dungeon Mice");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Conversión de video
    // -------------------------------------------------------------------------

    private File convertToMp4(File inputFile, File outputDirectory) throws Exception {
        outputDirectory.mkdirs();

        String safeName = inputFile.getName().replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
        File outputFile = new File(outputDirectory, safeName.replaceAll("\\..*$", "") + ".mp4");

        ProcessBuilder processBuilder = new ProcessBuilder(
                "ffmpeg",
                "-i", inputFile.getAbsolutePath(),
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "28",
                "-c:a", "aac",
                "-y",
                outputFile.getAbsolutePath()
        );

        processBuilder.inheritIO();
        Process process = processBuilder.start();
        int exitCode    = process.waitFor();

        if (exitCode != 0 || !outputFile.exists()) {
            throw new RuntimeException("Error al convertir video");
        }

        return outputFile;
    }
}