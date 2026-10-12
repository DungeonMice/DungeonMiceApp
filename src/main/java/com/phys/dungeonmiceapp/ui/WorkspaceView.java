package com.phys.dungeonmiceapp.ui;

import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import com.phys.dungeonmiceapp.api.PythonVideoClient;
import com.phys.dungeonmiceapp.api.VideoBatch;
import com.google.gson.JsonArray;
import java.io.File;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.*;

public final class WorkspaceView extends BorderPane implements AutoCloseable {
    private final Stage stage;
    private final Label windowTitle = new Label("Dungeon Mice");
    private final Image mouseLogo = new Image(getClass().getResource("/images/dungeon-mouse.png").toExternalForm());
    private final Image brandLogo = new Image(getClass().getResource("/images/dungeon-mouse-yellow.png").toExternalForm());
    private final ExecutorService files = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "dungeonmice-media"); thread.setDaemon(true); return thread;
    });
    private final List<PlaybackPane> players = new ArrayList<>();
    private final List<File> pendingVideos = new ArrayList<>();
    private final TextField loginName = field("login-username");
    private final PasswordField loginPassword = password("login-password");
    private final TextField profileName = field("profile-username");
    private final TextField fullName = field("profile-name");
    private final TextField identification = field("profile-identification");
    private final PasswordField currentPassword = password("current-password");
    private final PasswordField newPassword = password("new-password");
    private final PasswordField repeatPassword = password("repeat-password");
    private final TextField projectName = field("project-name");
    private final TextField description = field("project-description");
    private final TextField category = field("project-category");
    private final TextField owner = field("project-responsible");
    private final TextField participants = field("project-participants");
    private final TextField location = field("project-location");
    private final Label loginMessage = new Label();
    private VideoGroup selectedProject;
    private Video selectedVideo;
    private String selectedProcessedId;
    private final Map<String, javafx.collections.ObservableList<ActivityLog.Entry>> activity = new HashMap<>();
    private File pendingPhoto;
    private Image profileImage;
    private String screen = "login";
    private double dragX, dragY;
    private boolean maximized;
    private Rectangle2D restoreBounds;
    private boolean closed;
    private Task<?> apiTask;
    private FramePlaybackPane framePlayer;
    private Runnable restoreOriginal;
    private String frameToken = System.getProperty("dungeonmice.python.token", "");
    private final javafx.beans.property.BooleanProperty processingBusy = new javafx.beans.property.SimpleBooleanProperty(false);
    private final TextField pythonAddress = new TextField(System.getProperty("dungeonmice.python.url", "http://127.0.0.1:8765"));
    private final javafx.beans.property.BooleanProperty pythonAvailable = new javafx.beans.property.SimpleBooleanProperty(true);
    private final javafx.beans.property.StringProperty pythonStatus = new javafx.beans.property.SimpleStringProperty("");
    private final javafx.animation.PauseTransition directoryDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(350));
    private ListView<File> projectFiles;
    private Label directoryMessage;
    private List<File> directoryVideos = Collections.emptyList();
    private long directoryGeneration;
    private final Map<PlaybackPane, Task<File>> playbackRequests = new HashMap<>();
    private final ExecutorService mediaPreparation = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dungeonmice-preparation"); thread.setDaemon(true); return thread;
    });

    public WorkspaceView(Stage stage) {
        this.stage = stage;
        stage.getIcons().add(brandLogo);
        getStyleClass().add("workspace");
        stage.setOnCloseRequest(e -> { e.consume(); exitApplication(); });
        location.textProperty().addListener((obs, old, value) -> scheduleDirectoryScan());
        show("login");
    }

    private static TextField field(String id) { TextField field = new TextField(); field.setId(id); return field; }
    private static PasswordField password(String id) { PasswordField field = new PasswordField(); field.setId(id); return field; }
    private String username() { return SessionManager.isLoggedIn() ? SessionManager.getCurrentUser().getUsername() : ""; }

    private void show(String next) {
        cancelApi();
        directoryDelay.stop(); directoryGeneration++; projectFiles = null; directoryMessage = null;
        disposePlayers();
        if (!next.equals("login") && !next.equals("register") && !SessionManager.isLoggedIn()) next = "login";
        screen = next;
        String suffix;
        switch (next) {
            case "register": suffix = "Crear usuario"; break;
            case "user": suffix = "Editar usuario"; break;
            case "create-project": suffix = "Crear proyecto"; break;
            case "edit-project": suffix = "Editar proyecto"; break;
            case "login": suffix = ""; break;
            default: suffix = username();
        }
        windowTitle.setText("Dungeon Mice" + (suffix.isEmpty() ? "" : " - " + suffix)); stage.setTitle(windowTitle.getText());
        VBox header = new VBox(titleBar());
        if (Arrays.asList("projects", "video", "processed", "data", "user").contains(next)) header.getChildren().add(navigation(next));
        setTop(header);
        switch (next) {
            case "register": setCenter(userForm(false)); break;
            case "user": setCenter(userForm(true)); break;
            case "projects": setCenter(projects()); break;
            case "create-project": setCenter(projectForm(false)); break;
            case "edit-project": setCenter(projectForm(true)); break;
            case "video": setCenter(video()); break;
            case "processed": setCenter(processed()); break;
            case "data": setCenter(data()); break;
            default: setCenter(login());
        }
    }

    private HBox titleBar() {
        HBox bar = new HBox(8); bar.getStyleClass().add("title-bar"); bar.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Button minimize = iconButton("minimize", "Minimizar", () -> stage.setIconified(true));
        Button maximize = iconButton("maximize", "Maximizar / restaurar", this::toggleMaximized);
        Button close = iconButton("close", "Cerrar", this::exitApplication);
        minimize.setId("window-minimize"); maximize.setId("window-maximize"); close.setId("window-close");
        for (Button control : Arrays.asList(minimize, maximize, close)) {
            control.setPickOnBounds(true);
            control.setMinSize(40, 38); control.setPrefSize(40, 38); control.setMaxSize(40, 38);
        }
        minimize.getStyleClass().add("window-minimize"); maximize.getStyleClass().add("window-maximize"); close.getStyleClass().add("window-close");
        HBox dragArea = new HBox(8, logo(26), windowTitle, spacer); dragArea.setId("window-drag-area");
        dragArea.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(dragArea, Priority.ALWAYS);
        HBox controls = new HBox(minimize, maximize, close);
        controls.setMinSize(120, 38); controls.setPrefSize(120, 38); controls.setMaxSize(120, 38);
        bar.getChildren().addAll(dragArea, controls);
        dragArea.setOnMousePressed(e -> { if (e.getButton() == MouseButton.PRIMARY) { dragX = e.getScreenX() - stage.getX(); dragY = e.getScreenY() - stage.getY(); } });
        dragArea.setOnMouseDragged(e -> { if (e.isPrimaryButtonDown() && !maximized) { stage.setX(e.getScreenX() - dragX); stage.setY(e.getScreenY() - dragY); } });
        dragArea.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) toggleMaximized(); });
        return bar;
    }

    private void toggleMaximized() {
        // Explicit bounds also work for undecorated windows on Windows.
        if (!maximized) {
            restoreBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            Screen monitor = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())
                    .stream().max(Comparator.comparingDouble(s -> overlap(s.getVisualBounds(), restoreBounds))).orElse(Screen.getPrimary());
            applyBounds(monitor.getVisualBounds()); maximized = true;
        } else {
            applyBounds(restoreBounds); maximized = false;
        }
    }

    private static double overlap(Rectangle2D a, Rectangle2D b) {
        return Math.max(0, Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX()))
                * Math.max(0, Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY()));
    }

    private void applyBounds(Rectangle2D bounds) {
        stage.setX(bounds.getMinX()); stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth()); stage.setHeight(bounds.getHeight());
    }

    private void exitApplication() {
        try { close(); }
        finally { stage.hide(); Platform.exit(); }
    }

    private void setContentBusy(boolean busy) {
        getCenter().setDisable(busy);
        VBox header = (VBox) getTop();
        for (int i = 1; i < header.getChildren().size(); i++) header.getChildren().get(i).setDisable(busy);
    }

    private HBox navigation(String selected) {
        HBox tabs = new HBox(); tabs.getStyleClass().add("navigation");
        String[] names = {"Proyectos", "Video", "Procesados", "Datos", "Usuario"}, targets = {"projects", "video", "processed", "data", "user"};
        for (int i = 0; i < names.length; i++) {
            final String target = targets[i];
            Button tab = button(names[i], "tab", () -> { if (target.equals("user")) beginProfile(true); else show(target); });
            tab.setId("nav-" + target); tab.setMinWidth(0);
            if (target.equals(selected)) tab.getStyleClass().add("selected");
            tab.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(tab, Priority.ALWAYS); tabs.getChildren().add(tab);
        }
        return tabs;
    }

    private Node central(Node content) {
        StackPane column = new StackPane(content); column.getStyleClass().add("form-column"); column.setMaxWidth(550);
        StackPane surrounding = new StackPane(column); surrounding.getStyleClass().add("form-surrounding"); return surrounding;
    }

    private Node login() {
        VBox form = new VBox(); form.setAlignment(Pos.CENTER); form.setMaxWidth(220); form.setTranslateY(-28);
        VBox fields = new VBox(7, new Label("Nombre de usuario"), loginName, gap(7), new Label("Contrase\u00f1a"), loginPassword);
        fields.setMaxWidth(200);
        loginMessage.getStyleClass().add("validation-message"); loginMessage.setWrapText(true); loginMessage.setMaxWidth(220);
        loginMessage.setVisible(!loginMessage.getText().isEmpty());
        if (!loginMessage.managedProperty().isBound()) loginMessage.managedProperty().bind(loginMessage.visibleProperty());
        Button enter = button("Iniciar sesi\u00f3n", "primary", this::loginUser); enter.setDefaultButton(true);
        form.getChildren().addAll(logo(220), gap(32), fields, gap(16), loginMessage, gap(12), enter, gap(12), button("Crear cuenta", "outline", () -> beginProfile(false)));
        return central(form);
    }

    private void loginUser() {
        try {
            if (loginName.getText().trim().isEmpty() || loginPassword.getText().isEmpty()) throw new IllegalArgumentException("Ingresa tu usuario y contrase\u00f1a.");
            User user = UserRepository.login(loginName.getText(), loginPassword.getText());
            if (user == null) throw new IllegalArgumentException("Usuario o contrase\u00f1a incorrectos.");
            SessionManager.setCurrentUser(user); loginPassword.clear(); loginMessage.setText("");
            loadProfileImage(user.getPhotoPath()); selectedProject = null; selectedVideo = null; show("projects");
        } catch (RuntimeException e) { loginMessage.setText(e.getMessage()); loginMessage.setVisible(true); }
    }

    private void beginProfile(boolean edit) {
        pendingPhoto = null; currentPassword.clear(); newPassword.clear(); repeatPassword.clear();
        User user = edit ? SessionManager.getCurrentUser() : null;
        profileName.setText(user == null ? "" : user.getUsername()); fullName.setText(user == null ? "" : user.getFullName());
        identification.setText(user == null ? "" : user.getIdentification());
        loadProfileImage(user == null ? "" : user.getPhotoPath()); show(edit ? "user" : "register");
    }

    private Node userForm(boolean edit) {
        VBox photo = new VBox(18, avatar(150), button("Cambiar foto", "outline photo-button", this::choosePhoto)); photo.setAlignment(Pos.TOP_CENTER); photo.setMinWidth(150);
        GridPane fields = new GridPane(); fields.setHgap(12); fields.setVgap(13);
        addField(fields, 0, "Nombre de usuario", profileName); addField(fields, 1, "Nombre completo", fullName); addField(fields, 2, "Identificaci\u00f3n", identification);
        int row = 3;
        if (edit) { fields.add(new Label("Cambiar contrase\u00f1a"), 0, row++, 2, 1); addField(fields, row++, "Contrase\u00f1a actual", currentPassword); }
        addField(fields, row++, edit ? "Contrase\u00f1a nueva" : "Contrase\u00f1a", newPassword); addField(fields, row, "Repetir contrase\u00f1a", repeatPassword);
        HBox details = new HBox(20, photo, fields); details.setAlignment(Pos.CENTER);
        VBox actions = new VBox(13, button(edit ? "Guardar cambios" : "Crear cuenta", "primary", () -> saveProfile(edit)),
                button("Cancelar", "outline", () -> { pendingPhoto = null; if (edit) loadProfileImage(SessionManager.getCurrentUser().getPhotoPath()); else profileImage = null; show(edit ? "projects" : "login"); }));
        actions.setAlignment(Pos.CENTER);
        VBox form = new VBox(edit ? 65 : 58, details, actions); form.setAlignment(Pos.CENTER); form.setPadding(new Insets(20, 18, edit ? 60 : 0, 18)); return central(form);
    }

    private void saveProfile(boolean edit) {
        try {
            if (!newPassword.getText().equals(repeatPassword.getText())) throw new IllegalArgumentException("Las contrase\u00f1as no coinciden.");
            if (!edit && newPassword.getText().isEmpty()) throw new IllegalArgumentException("La contrase\u00f1a es obligatoria.");
            if (edit && !currentPassword.getText().isEmpty() && newPassword.getText().isEmpty()) throw new IllegalArgumentException("Ingresa la nueva contrase\u00f1a.");
            String photo = pendingPhoto == null ? (edit ? SessionManager.getCurrentUser().getPhotoPath() : "") : MediaFiles.copyPhoto(pendingPhoto);
            if (edit) {
                User user = UserRepository.updateProfile(SessionManager.getCurrentUser(), profileName.getText(), fullName.getText(), identification.getText(), photo, currentPassword.getText(), newPassword.getText());
                SessionManager.setCurrentUser(user); loadProfileImage(user.getPhotoPath()); show("projects");
            } else {
                UserRepository.register(profileName.getText(), newPassword.getText(), "user", fullName.getText(), identification.getText(), photo);
                loginName.setText(profileName.getText()); loginMessage.setText("Cuenta creada. Inicia sesi\u00f3n."); show("login");
            }
            pendingPhoto = null; currentPassword.clear(); newPassword.clear(); repeatPassword.clear();
        } catch (Exception e) { error(e); }
    }

    private void addField(GridPane grid, int row, String text, Control field) {
        Label label = new Label(text); label.setMinWidth(132); field.setPrefWidth(195); grid.add(label, 0, row); grid.add(field, 1, row);
    }

    private HBox identity() {
        Hyperlink logout = new Hyperlink("Cerrar sesi\u00f3n");
        logout.setOnAction(e -> { SessionManager.logout(); selectedProject = null; selectedVideo = null; selectedProcessedId = null; activity.clear(); profileImage = null; pendingVideos.clear(); loginPassword.clear(); loginMessage.setText(""); show("login"); });
        HBox user = new HBox(7, avatar(40), new VBox(0, new Label(username()), logout)); user.setAlignment(Pos.CENTER_LEFT); return user;
    }

    private Node projects() {
        List<VideoGroup> available = VideoGroupRepository.findGroupsByCurrentUser();
        if (selectedProject != null) selectedProject = available.stream().filter(p -> p.getId().equals(selectedProject.getId())).findFirst().orElse(null);
        if (selectedProject == null && !available.isEmpty()) selectedProject = available.get(0);
        ListView<VideoGroup> list = new ListView<>(); list.setId("projects-list"); list.getItems().setAll(available); list.setPrefSize(320, 220); list.setMaxSize(360, 250);
        list.setPlaceholder(new Label("No hay proyectos")); list.setCellFactory(v -> new ListCell<VideoGroup>() {
            @Override protected void updateItem(VideoGroup item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.getName()); }
        });
        Label detail = new Label(); detail.setWrapText(true); detail.setMaxWidth(430);
        ListView<Video> videos = videoList(); videos.setId("project-videos"); videos.setPrefHeight(120); videos.setMaxHeight(150);
        PlaybackPane preview = newPlayer(true, text -> {}); preview.setPrefHeight(260); preview.setMaxHeight(300);
        Button edit = button("Editar proyecto", "outline", () -> beginProject(true));
        Button open = button("Abrir video", "primary", () -> show("video")); open.setDisable(true);
        Runnable refresh = () -> {
            edit.setDisable(selectedProject == null); selectedVideo = null; preview.close(); open.setDisable(true);
            if (selectedProject == null) { detail.setText("Selecciona o crea un proyecto."); videos.getItems().clear(); return; }
            detail.setText(selectedProject.getName() + "\n\n" + selectedProject.getDescription()
                    + "\nCategor\u00eda: " + selectedProject.getCategory() + "\nPersona a cargo: " + selectedProject.getResponsible()
                    + "\nParticipantes: " + selectedProject.getParticipants() + "\nUbicaci\u00f3n: " + selectedProject.getLocation());
            videos.getItems().setAll(VideoRepository.findByGroup(selectedProject.getId()));
        };
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> { selectedProject = item; refresh.run(); });
        videos.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            selectedVideo = item; open.setDisable(item == null); if (item != null) openVideo(preview, MediaFiles.resolve(item.getFilePath()), true); else { cancelPreparation(preview); preview.close(); }
        });
        videos.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && selectedVideo != null) show("video"); });
        list.getSelectionModel().select(selectedProject); refresh.run();
        VBox selection = new VBox(24, heading("Proyectos disponibles", false), list,
                button("Crear proyecto", "primary", () -> beginProject(false)), edit); selection.setAlignment(Pos.CENTER);
        StackPane leftContent = new StackPane(selection); VBox.setVgrow(leftContent, Priority.ALWAYS);
        VBox left = new VBox(20, identity(), leftContent); left.getStyleClass().add("project-list-side"); left.setPadding(new Insets(16, 24, 30, 24));
        ScrollPane details = new ScrollPane(detail); details.setFitToWidth(true); details.setMinHeight(60); details.setPrefHeight(100); details.setMaxHeight(140);
        details.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox right = new VBox(12, heading("Detalles de proyecto", true), details, heading("Videos asociados", false), videos, preview, open);
        right.getStyleClass().add("project-details-side"); right.setPadding(new Insets(22, 30, 22, 30)); VBox.setVgrow(preview, Priority.ALWAYS);
        GridPane split = new GridPane(); ColumnConstraints a = new ColumnConstraints(); a.setPercentWidth(45); ColumnConstraints b = new ColumnConstraints(); b.setPercentWidth(55);
        split.getColumnConstraints().addAll(a, b); RowConstraints row = new RowConstraints(); row.setVgrow(Priority.ALWAYS); split.getRowConstraints().add(row);
        split.add(left, 0, 0); split.add(right, 1, 0); return split;
    }

    private ListView<Video> videoList() {
        ListView<Video> list = new ListView<>(); list.setPlaceholder(new Label("No hay videos")); list.setFixedCellSize(40);
        list.setCellFactory(v -> new ListCell<Video>() {
            @Override protected void updateItem(Video item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.getTitle()); }
        }); return list;
    }

    private void beginProject(boolean edit) {
        if (edit && selectedProject == null) return;
        pendingVideos.clear(); directoryVideos = Collections.emptyList(); VideoGroup p = edit ? selectedProject : null;
        projectName.setText(p == null ? "" : p.getName()); description.setText(p == null ? "" : p.getDescription());
        category.setText(p == null ? "" : p.getCategory()); owner.setText(p == null ? "" : p.getResponsible());
        participants.setText(p == null ? "" : p.getParticipants()); location.setText(p == null ? "" : p.getLocation()); show(edit ? "edit-project" : "create-project");
    }

    private Node projectForm(boolean edit) {
        BorderPane page = new BorderPane(); page.setPadding(new Insets(30, 45, 40, 45));
        HBox user = identity(); user.setMaxWidth(220);
        StackPane top = new StackPane(heading(edit ? "Editar proyecto" : "Crear proyecto", true), user); StackPane.setAlignment(user, Pos.CENTER_LEFT); page.setTop(top);
        GridPane fields = new GridPane(); fields.setHgap(20); fields.setVgap(18); fields.getColumnConstraints().addAll(new ColumnConstraints(192), new ColumnConstraints(195));
        addField(fields, 0, "Nombre del proyecto", projectName); addField(fields, 1, "Categor\u00eda", category); addField(fields, 2, "Persona a cargo", owner);
        addField(fields, 3, "Participantes", participants);
        HBox path = new HBox(4, iconButton("folder", "Seleccionar ubicaci\u00f3n", () -> {
            DirectoryChooser chooser = new DirectoryChooser(); File folder = chooser.showDialog(stage); if (folder != null) location.setText(folder.getAbsolutePath());
        }), location); path.getStyleClass().add("path-field"); HBox.setHgrow(location, Priority.ALWAYS); path.setMaxWidth(195);
        fields.add(new Label("Ubicaci\u00f3n en el sistema"), 0, 4); fields.add(path, 1, 4); addField(fields, 5, "Descripci\u00f3n", description);
        VBox left = new VBox(32, fields); if (edit) left.getChildren().add(button("Eliminar proyecto", "danger wide", this::deleteProject));
        projectFiles = new ListView<>(); projectFiles.setId("project-files"); projectFiles.setPlaceholder(new Label("No hay videos"));
        projectFiles.setCellFactory(list -> new ListCell<File>() {
            @Override protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty); setText(empty || file == null ? null : file.getName());
                setTooltip(empty || file == null ? null : new Tooltip(file.getAbsolutePath()));
            }
        });
        projectFiles.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && projectFiles.getSelectionModel().getSelectedItem() != null) previewFile(projectFiles.getSelectionModel().getSelectedItem(), null); });
        directoryMessage = new Label(); directoryMessage.setWrapText(true); directoryMessage.setId("directory-status");
        BorderPane gallery = new BorderPane(projectFiles); gallery.setTop(directoryMessage); gallery.getStyleClass().add("gallery"); gallery.setPrefHeight(390);
        Button add = button("A\u00f1adir video", "primary", () -> chooseVideo(file -> { pendingVideos.add(file); refreshProjectFiles(); }));
        ListView<File> list = projectFiles;
        Button preview = button("Vista previa", "outline", () -> previewFile(list.getSelectionModel().getSelectedItem(), null));
        preview.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        HBox actions = new HBox(10, preview, add); actions.setAlignment(Pos.CENTER);
        BorderPane.setMargin(actions, new Insets(15)); gallery.setBottom(actions);
        VBox right = new VBox(16, heading("Videos asociados", false), gallery); HBox columns = new HBox(35, left, right); HBox.setHgrow(right, Priority.ALWAYS); columns.setPadding(new Insets(26, 0, 25, 0)); page.setCenter(columns);
        HBox footer = new HBox(10, button("Cancelar", "outline", () -> { pendingVideos.clear(); show("projects"); }), button(edit ? "Guardar cambios" : "Crear proyecto", "primary", () -> saveProject(edit)));
        footer.setAlignment(Pos.CENTER); page.setBottom(footer); refreshProjectFiles(); scheduleDirectoryScan(); return page;
    }

    private void refreshProjectFiles() {
        if (projectFiles == null) return;
        Map<java.nio.file.Path, File> files = new LinkedHashMap<>();
        if (screen.equals("edit-project") && selectedProject != null) {
            for (Video video : VideoRepository.findByGroup(selectedProject.getId())) {
                File file = MediaFiles.resolve(video.getFilePath()); files.put(file.toPath().toAbsolutePath().normalize(), file);
            }
        }
        for (File file : directoryVideos) files.put(file.toPath().toAbsolutePath().normalize(), file);
        for (File file : pendingVideos) files.put(file.toPath().toAbsolutePath().normalize(), file);
        projectFiles.getItems().setAll(files.values());
    }

    private void scheduleDirectoryScan() {
        if (projectFiles == null) return;
        long generation = ++directoryGeneration;
        directoryDelay.stop(); directoryVideos = Collections.emptyList(); refreshProjectFiles();
        directoryMessage.setText(location.getText().trim().isEmpty() ? "" : "Buscando videos...");
        String directory = location.getText();
        directoryDelay.setOnFinished(e -> background(() -> MediaFiles.videosInDirectory(directory), videos -> {
            if (generation != directoryGeneration || projectFiles == null) return;
            directoryVideos = videos; refreshProjectFiles(); directoryMessage.setText(videos.size() + " videos en el directorio");
        }, ex -> {
            if (generation != directoryGeneration || projectFiles == null) return;
            directoryMessage.setText(ex.getMessage());
        }));
        directoryDelay.playFromStart();
    }

    private void saveProject(boolean edit) {
        if (projectName.getText().trim().isEmpty()) { error(new IllegalArgumentException("El nombre del proyecto es obligatorio.")); return; }
        if (location.getText().trim().isEmpty()) {
            try { finishSaveProject(edit, MediaFiles.references(pendingVideos)); } catch (Exception ex) { error(ex); }
            return;
        }
        String directory = location.getText(); List<File> selected = new ArrayList<>(pendingVideos);
        setContentBusy(true); directoryDelay.stop(); directoryGeneration++;
        background(() -> {
            List<File> sources = new ArrayList<>(MediaFiles.videosInDirectory(directory)); sources.addAll(selected);
            return MediaFiles.references(sources);
        }, sources -> { setContentBusy(false); finishSaveProject(edit, sources); }, ex -> { setContentBusy(false); error(ex); });
    }

    private void finishSaveProject(boolean edit, List<File> sources) {
        boolean saved = false;
        try {
            selectedProject = VideoGroupRepository.saveProject(edit ? selectedProject.getId() : null, projectName.getText(), description.getText(), category.getText(), owner.getText(), participants.getText(), location.getText().trim());
            saved = true;
            VideoRepository.addReferences(selectedProject.getId(), sources);
            pendingVideos.clear(); show("projects");
        } catch (Exception e) { if (saved) show("edit-project"); error(e); }
    }

    private void deleteProject() {
        if (selectedProject == null || !confirm("Eliminar proyecto", "Se eliminar\u00e1n el proyecto y sus asociaciones de video. Los archivos originales se conservar\u00e1n.")) return;
        try { ProcessedVideoRepository.removeByProject(selectedProject.getId()); VideoRepository.deleteByGroup(selectedProject.getId()); VideoGroupRepository.deleteById(selectedProject.getId()); selectedProject = null; selectedVideo = null; pendingVideos.clear(); show("projects"); }
        catch (RuntimeException e) { error(e); }
    }

    private void chooseVideo(Consumer<File> accepted) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Seleccionar video");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.m4v", "*.mov", "*.avi", "*.mkv", "*.wmv", "*.webm", "*.mpg", "*.mpeg"));
        File file = chooser.showOpenDialog(stage); if (file != null) previewFile(file, accepted);
    }

    private void previewFile(File file, Consumer<File> accepted) {
        Stage dialog = new Stage(); dialog.initOwner(stage); dialog.initModality(Modality.WINDOW_MODAL); dialog.setTitle(file.getName());
        PlaybackPane preview = new PlaybackPane(true, text -> {});
        Label state = new Label("Preparando vista previa..."); state.setWrapText(true);
        Button use = button("A\u00f1adir video", "primary", () -> { dialog.close(); if (accepted != null) accepted.accept(file); }); use.setDisable(true);
        Button cancel = button(accepted == null ? "Cerrar" : "Cancelar", "outline", dialog::close);
        HBox actions = accepted == null ? new HBox(cancel) : new HBox(cancel, use); actions.setSpacing(10); actions.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(14, state, preview, actions); content.setPadding(new Insets(20)); VBox.setVgrow(preview, Priority.ALWAYS);
        Scene scene = new Scene(content, 800, 520); scene.getStylesheets().add(getClass().getResource("/styles/workspace.css").toExternalForm()); dialog.setScene(scene);
        dialog.setOnHidden(e -> { cancelPreparation(preview); preview.close(); }); dialog.show();
        prepareVideo(preview, file, true, ready -> {
            if (!dialog.isShowing()) return; state.setText(file.getName()); preview.open(ready); use.setDisable(false);
        }, ex -> { if (dialog.isShowing()) { state.setText("No se pudo generar el previo: " + ex.getMessage()); use.setDisable(false); } });
    }

    private void associateVideos(List<File> sources, Runnable after) {
        if (selectedProject == null) return;
        try { VideoRepository.addReferences(selectedProject.getId(), sources); after.run(); }
        catch (Exception ex) { error(ex); }
    }

    private void openVideo(PlaybackPane player, File source, boolean preview) {
        prepareVideo(player, source, preview, player::open,
                ex -> player.showError("No se pudo abrir " + source.getName() + ": " + ex.getMessage()));
    }

    private void prepareVideo(PlaybackPane player, File source, boolean preview, Consumer<File> ready, Consumer<Throwable> failure) {
        cancelPreparation(player); player.prepare();
        Task<File> task = new Task<File>() {
            @Override protected File call() throws Exception {
                updateMessage("Abriendo archivo original...");
                return preview ? MediaFiles.preview(source) : MediaFiles.playable(source);
            }
        };
        playbackRequests.put(player, task);
        task.messageProperty().addListener((obs, old, text) -> { if (playbackRequests.get(player) == task && !closed) player.preparationMessage(text); });
        task.setOnSucceeded(e -> { if (playbackRequests.get(player) == task && !closed) ready.accept(task.getValue()); });
        task.setOnFailed(e -> { if (playbackRequests.get(player) == task && !closed) failure.accept(task.getException()); });
        mediaPreparation.execute(task);
    }

    private void cancelPreparation(PlaybackPane player) {
        Task<File> task = playbackRequests.remove(player);
        if (task != null) task.cancel(true);
    }

    private Node video() {
        if (selectedProject == null) {
            VBox empty = new VBox(20, heading("Selecciona un proyecto", false), button("Ver proyectos", "primary", () -> show("projects"))); empty.setAlignment(Pos.CENTER); return empty;
        }
        List<Video> videos = VideoRepository.findByGroup(selectedProject.getId());
        javafx.collections.ObservableList<ActivityLog.Entry> history = javafx.collections.FXCollections.observableArrayList();
        PlaybackPane player = newPlayer(false, text -> logVideo(selectedVideo, PlaybackPane.format(playerTime()), text, text.startsWith("No ") || text.startsWith("Error")));
        RegionEditor regions = new RegionEditor(player);
        regions.setOnApplyAll(() -> {
            try {
                regions.requestRegions();
                Dialog<Boolean> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("Aplicar regiones al proyecto");
                CheckBox replace = new CheckBox("Reemplazar tambien las regiones existentes"); replace.setId("region-replace-existing");
                Label warning = new Label("Se copiaran las regiones guardadas del video actual. Las coordenadas requieren la misma resolucion y encuadre."); warning.setWrapText(true); warning.setMaxWidth(360);
                dialog.getDialogPane().setContent(new VBox(12, warning, replace));
                dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                dialog.setResultConverter(type -> type == ButtonType.OK ? replace.isSelected() : null);
                dialog.showAndWait().ifPresent(overwrite -> {
                    int count = VideoRepository.applyRegionsToGroup(selectedProject.getId(), selectedVideo.getId(), overwrite);
                    logVideo(selectedVideo, PlaybackPane.format(playerTime()), "Regiones aplicadas a " + count + " videos", false);
                    Alert notice = new Alert(Alert.AlertType.INFORMATION, "Regiones aplicadas a " + count + " videos. Cada video conserva una copia editable."); notice.initOwner(stage); notice.setHeaderText(null); notice.showAndWait();
                });
            } catch (RuntimeException ex) { error(ex); }
        });
        ComboBox<Video> choices = new ComboBox<>(); choices.setId("video-choice"); choices.setPrefWidth(300); choices.getItems().setAll(videos);
        choices.setConverter(new javafx.util.StringConverter<Video>() {
            @Override public String toString(Video value) { return value == null ? "Seleccionar video" : value.getTitle(); }
            @Override public Video fromString(String text) { return null; }
        });
        TabPane inspector = new TabPane(); inspector.setId("video-inspector"); inspector.getStyleClass().add("inspector-tabs"); inspector.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); inspector.setMinHeight(0);
        ScrollPane regionScroll = new ScrollPane(regions); regionScroll.setFitToWidth(true); regionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); regionScroll.getStyleClass().add("plain-scroll");
        Tab regionTab = new Tab("Regiones", regionScroll); Tab logTab = new Tab("Registros", new ActivityLog(history)); inspector.getTabs().addAll(regionTab, logTab);
        choices.valueProperty().addListener((obs, old, item) -> {
            selectedVideo = item; regions.setVideo(item);
            logTab.setContent(new ActivityLog(item == null ? history : historyFor(item)));
            if (item != null) openVideo(player, MediaFiles.resolve(item.getFilePath()), false); else { cancelPreparation(player); player.close(); }
        });
        Video initial = selectedVideo == null ? null : videos.stream().filter(v -> v.getId().equals(selectedVideo.getId())).findFirst().orElse(null);
        choices.setValue(initial == null && !videos.isEmpty() ? videos.get(0) : initial);
        Button load = button("A\u00f1adir video", "outline", () -> chooseVideo(file -> associateVideos(Collections.singletonList(file), () -> { selectedVideo = VideoRepository.findByGroup(selectedProject.getId()).stream().filter(v -> MediaFiles.resolve(v.getFilePath()).equals(file)).findFirst().orElse(null); show("video"); })));
        Button remove = button("Eliminar video", "outline", () -> {
            if (selectedVideo != null && confirm("Eliminar video", "\u00bfEliminar este video del proyecto? El archivo original se conservar\u00e1.")) {
                VideoRepository.deleteById(selectedVideo.getId()); selectedVideo = null; show("video");
            }
        });
        remove.disableProperty().bind(choices.valueProperty().isNull().or(processingBusy));
        load.disableProperty().bind(processingBusy);
        choices.setMinWidth(120); choices.setMaxWidth(300); choices.setPrefWidth(250);
        load.setMinWidth(0); load.setPrefWidth(145); remove.setMinWidth(0); remove.setPrefWidth(145);
        HBox tools = new HBox(10, choices, load, remove, iconButton("zoom", "Acercar", () -> { if (framePlayer != null) framePlayer.setZoom(2); else player.setZoom(2); }), iconButton("reset", "Restablecer zoom", () -> { if (framePlayer != null) framePlayer.setZoom(1); else player.resetZoom(); }));
        tools.getStyleClass().add("mode-toolbar"); tools.setAlignment(Pos.CENTER_LEFT);
        VBox sidebar = new VBox(12, identity(), inspector, pythonPanel(choices, player, regions)); sidebar.setId("video-sidebar"); sidebar.setMinWidth(310); sidebar.setPrefWidth(310); sidebar.setMaxWidth(310); sidebar.setMinHeight(0); sidebar.setPadding(new Insets(12, 16, 16, 0)); VBox.setVgrow(inspector, Priority.ALWAYS);
        BorderPane page = new BorderPane(); page.setTop(tools); player.setPadding(new Insets(12, 16, 16, 16)); page.setCenter(player); page.setRight(sidebar); return page;
    }

    private javafx.collections.ObservableList<ActivityLog.Entry> historyFor(Video video) {
        return activity.computeIfAbsent(video.getId(), id -> javafx.collections.FXCollections.observableArrayList());
    }
    private void logVideo(Video video, String time, String text, boolean error) {
        if (video != null && SessionManager.isLoggedIn() && SessionManager.getCurrentUser().getId().equals(video.getOwnerId())) ActivityLog.append(historyFor(video), time, Objects.toString(text, "Error sin detalle"), error);
    }

    private javafx.util.Duration playerTime() {
        return players.isEmpty() || players.get(players.size() - 1).getPlayer() == null ? javafx.util.Duration.ZERO : players.get(players.size() - 1).getPlayer().getCurrentTime();
    }

    private VBox pythonPanel(ComboBox<Video> choices, PlaybackPane player, RegionEditor regions) {
        final String paneProject = selectedProject.getId();
        VBox panel = sidePanel("Procesamiento"); panel.setId("processing-panel"); panel.setMinHeight(VBox.USE_PREF_SIZE);
        pythonAddress.setId("python-api-url");
        pythonAddress.setTooltip(new Tooltip("Direccion de la API local de Python"));
        Label connection = new Label(); connection.textProperty().bind(javafx.beans.binding.Bindings.when(pythonStatus.isEmpty()).then("Servidor local").otherwise(pythonStatus)); connection.setWrapText(true); connection.getStyleClass().add("muted");
        connection.setMaxWidth(Double.MAX_VALUE); connection.setId("python-connection-status");
        Label status = new Label(); status.setId("processing-status"); status.setWrapText(true); status.setMaxWidth(Double.MAX_VALUE);
        javafx.beans.property.BooleanProperty busy = new javafx.beans.property.SimpleBooleanProperty(false);
        ProgressBar progress = new ProgressBar(); progress.setId("processing-progress"); progress.setMaxWidth(Double.MAX_VALUE); progress.visibleProperty().bind(busy); progress.managedProperty().bind(busy);
        Button view = button("Ver resultado", "outline", () -> {
            Video current = choices.getValue(); if (current == null) return;
            ProcessedVideo result = ProcessedVideoRepository.findByProject(paneProject).stream().filter(v -> current.getId().equals(v.getSourceId())).findFirst().orElse(null);
            if (result != null) { selectedProcessedId = result.getId(); show("processed"); }
        }); view.setDisable(true);
        Runnable refreshResult = () -> {
            Video current = choices.getValue();
            try { view.setDisable(current == null || busy.get() || !SessionManager.isLoggedIn() || ProcessedVideoRepository.findByProject(paneProject).stream().noneMatch(v -> current.getId().equals(v.getSourceId()))); }
            catch (IllegalArgumentException ex) { view.setDisable(true); }
        };
        choices.valueProperty().addListener((obs, old, current) -> refreshResult.run()); refreshResult.run();
        Button process = button("Procesar video", "primary", () -> {
            Video video = choices.getValue(); if (video == null || apiTask != null) return;
            final JsonArray regionPayload;
            try { regionPayload = regions.requestRegions(); }
            catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); return; }
            File source = MediaFiles.resolve(video.getFilePath());
            FileChooser chooser = new FileChooser(); chooser.setTitle("Guardar video de Python");
            String name = source.getName(); int dot = name.lastIndexOf('.');
            chooser.setInitialFileName((dot < 0 ? name : name.substring(0, dot)) + "-procesado.mp4");
            if (source.getParentFile() != null && source.getParentFile().isDirectory()) chooser.setInitialDirectory(source.getParentFile());
            File destination = chooser.showSaveDialog(stage); if (destination == null) return;
            try {
                java.nio.file.Path path = destination.getCanonicalFile().toPath();
                if (path.startsWith(MediaFiles.storage().toRealPath())) throw new IOException("Guarda el resultado fuera del directorio de la aplicacion.");
                PythonVideoClient client = new PythonVideoClient(java.net.URI.create(pythonAddress.getText().trim()));
                String projectId = selectedProject.getId();
                Task<java.nio.file.Path> task = new Task<java.nio.file.Path>() {
                    @Override protected java.nio.file.Path call() throws Exception {
                        return client.process(source.toPath(), path, video.getId(), projectId, regionPayload, this::updateMessage);
                    }
                };
                apiTask = task; busy.set(true); view.setDisable(true);
                progress.setProgress(-1); status.getStyleClass().remove("validation-message");
                logVideo(video, PlaybackPane.format(playerTime()), "Analisis iniciado", false);
                status.textProperty().bind(task.messageProperty());
                task.messageProperty().addListener((obs, old, value) -> {
                    if (apiTask != task || closed) return;
                    java.util.regex.Matcher frames = java.util.regex.Pattern.compile("(\\d+)/(\\d+) frames").matcher(value);
                    if (frames.find()) { double total = Double.parseDouble(frames.group(2)); progress.setProgress(total > 0 ? Math.min(.98, Double.parseDouble(frames.group(1)) / total) : -1); }
                    else progress.setProgress(-1);
                    logVideo(video, PlaybackPane.format(playerTime()), value, false);
                });
                task.setOnSucceeded(e -> {
                    if (apiTask != task || closed) return;
                    apiTask = null; busy.set(false); status.textProperty().unbind();
                    try {
                        ProcessedVideo result = ProcessedVideoRepository.record(projectId, video.getId(), task.getValue().toFile(), regionPayload);
                        selectedProcessedId = result.getId(); status.setText("Resultado guardado"); refreshResult.run();
                        logVideo(video, PlaybackPane.format(playerTime()), "Resultado listo: " + result.getFilename(), false);
                        view.fire();
                    } catch (IOException | RuntimeException ex) { status.setText("No se pudo registrar el resultado"); logVideo(video, PlaybackPane.format(playerTime()), ex.getMessage(), true); error(ex); }
                });
                task.setOnFailed(e -> {
                    if (apiTask != task || closed) return;
                    apiTask = null; busy.set(false); status.textProperty().unbind(); status.setText("Error de conexion o procesamiento"); status.getStyleClass().add("validation-message"); refreshResult.run(); logVideo(video, PlaybackPane.format(playerTime()), task.getException().getMessage(), true); error(task.getException());
                });
                task.setOnCancelled(e -> { if (apiTask != null && apiTask != task) return; busy.set(false); status.textProperty().unbind(); status.setText("Cancelado"); refreshResult.run(); logVideo(video, PlaybackPane.format(playerTime()), "Analisis cancelado", false); });
                files.submit(task);
            } catch (RuntimeException | IOException ex) { error(ex); }
        });
        process.setId("python-process"); process.disableProperty().bind(choices.valueProperty().isNull().or(busy).or(pythonAvailable.not()));
        Button processAll = button("Procesar todos", "outline", () -> {
            if (apiTask != null) return;
            try {
                regions.requestRegions();
                List<Video> originals = VideoRepository.findByGroup(paneProject);
                Map<String, JsonArray> snapshots = new HashMap<>();
                for (Video original : originals) {
                    JsonArray payload = new JsonArray(); for (VideoRegion region : VideoRepository.findRegions(original.getId())) payload.add(region.toJson());
                    if (payload.size() < 2) throw new IllegalArgumentException(original.getTitle() + ": guarda al menos dos regiones. Puedes usar Aplicar a todos.");
                    snapshots.put(original.getId(), payload);
                }
                DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Carpeta para los videos procesados");
                File folder = chooser.showDialog(stage); if (folder == null) return;
                List<VideoBatch.Item> plan = VideoBatch.plan(originals, snapshots, folder.toPath(), MediaFiles.storage());
                PythonVideoClient client = new PythonVideoClient(java.net.URI.create(pythonAddress.getText().trim()));
                String ownerId = SessionManager.getCurrentUser().getId();
                Task<Integer> task = new Task<Integer>() {
                    @Override protected Integer call() throws Exception {
                        return VideoBatch.run(plan,
                            (item, report) -> client.process(item.getSource(), item.getDestination(), item.getVideo().getId(), paneProject, item.getRegions(), report),
                            (item, result) -> {
                                // Database and observable log mutations stay on the JavaFX thread.
                                FutureTask<Void> record = new FutureTask<>(() -> {
                                    if (closed || !SessionManager.isLoggedIn() || !ownerId.equals(SessionManager.getCurrentUser().getId())) throw new IOException("Sesion cerrada. Resultado disponible en " + result);
                                    ProcessedVideo stored = ProcessedVideoRepository.record(paneProject, item.getVideo().getId(), result.toFile(), item.getRegions());
                                    selectedProcessedId = stored.getId(); logVideo(item.getVideo(), "00:00:00.000", "Resultado listo: " + result, false); return null;
                                }); Platform.runLater(record); record.get();
                            },
                            (item, failure) -> Platform.runLater(() -> {
                                if (!closed) logVideo(item.getVideo(), "00:00:00.000", "No se pudo completar: " + Objects.toString(failure.getMessage(), "Error") + ". Destino: " + item.getDestination(), true);
                            }), this::updateMessage);
                    }
                };
                apiTask = task; busy.set(true); view.setDisable(true); progress.setProgress(-1);
                status.getStyleClass().remove("validation-message"); status.textProperty().bind(task.messageProperty());
                task.messageProperty().addListener((obs, old, value) -> {
                    if (apiTask != task || closed) return;
                    java.util.regex.Matcher count = java.util.regex.Pattern.compile("^(\\d+)/(\\d+) videos").matcher(value);
                    java.util.regex.Matcher frames = java.util.regex.Pattern.compile("(\\d+)/(\\d+) frames").matcher(value);
                    if (count.find()) {
                        double fraction = frames.find() && Double.parseDouble(frames.group(2)) > 0 ? Math.min(.98, Double.parseDouble(frames.group(1)) / Double.parseDouble(frames.group(2))) : 0;
                        progress.setProgress((Double.parseDouble(count.group(1)) - 1 + fraction) / plan.size());
                        int index = Integer.parseInt(count.group(1)) - 1;
                        if (index >= 0 && index < plan.size()) logVideo(plan.get(index).getVideo(), "00:00:00.000", value, false);
                    }
                });
                task.setOnSucceeded(event -> {
                    if (apiTask != task || closed) return;
                    apiTask = null; busy.set(false); status.textProperty().unbind(); refreshResult.run();
                    status.setText("Lote terminado: " + task.getValue() + "/" + plan.size() + " completados, " + (plan.size() - task.getValue()) + " fallidos. Resultados en Procesados.");
                });
                task.setOnFailed(event -> {
                    if (apiTask != task || closed) return;
                    apiTask = null; busy.set(false); status.textProperty().unbind(); refreshResult.run(); status.setText("Lote interrumpido: " + task.getException().getMessage());
                });
                task.setOnCancelled(event -> { if (apiTask != null && apiTask != task) return; busy.set(false); status.textProperty().unbind(); refreshResult.run(); status.setText("Lote cancelado. Los resultados completados se conservan en Procesados."); });
                files.submit(task);
            } catch (IOException | RuntimeException ex) { error(ex); }
        });
        processAll.setId("python-process-all"); processAll.disableProperty().bind(process.disableProperty());
        Button frames = button("Analizar por frames", "primary", () -> {
            if (framePlayer != null) { cancelApi(); return; }
            Video source = choices.getValue(); if (source == null || apiTask != null) return;
            try {
                JsonArray payload = regions.requestRegions();
                com.phys.dungeonmiceapp.api.PythonFrameClient client = new com.phys.dungeonmiceapp.api.PythonFrameClient(java.net.URI.create(pythonAddress.getText().trim()), frameToken);
                BorderPane page = (BorderPane) player.getParent();
                cancelPreparation(player); player.close(); busy.set(true); status.setText("Analisis por frames activo");
                regions.showAnalysisStatus();
                restoreOriginal = () -> { progress.progressProperty().unbind(); page.setCenter(player); busy.set(false); status.setText(""); openVideo(player, MediaFiles.resolve(source.getFilePath()), false); };
                framePlayer = new FramePlaybackPane(client, MediaFiles.resolve(source.getFilePath()).toPath(), payload,
                        text -> logVideo(source, "00:00:00.000", text, text.startsWith("Error")));
                page.setCenter(framePlayer);
                progress.progressProperty().bind(framePlayer.progressProperty());
            } catch (RuntimeException ex) { error(ex); }
        });
        frames.setId("python-frames"); frames.setMinWidth(0); frames.setMaxWidth(Double.MAX_VALUE);
        frames.disableProperty().bind(choices.valueProperty().isNull().or(busy).or(pythonAvailable.not()));
        pythonAddress.disableProperty().bind(busy);
        choices.disableProperty().bind(busy);
        busy.addListener((obs, old, value) -> { processingBusy.set(value); regions.setLocked(value); });
        Button cancel = button("Cancelar", "outline", this::cancelApi); cancel.disableProperty().bind(busy.not());
        view.setId("python-result");
        for (Button button : new Button[]{process, processAll, cancel, view}) { button.setMinWidth(0); button.setMaxWidth(Double.MAX_VALUE); }
        cancel.visibleProperty().bind(busy); cancel.managedProperty().bind(busy);
        view.visibleProperty().bind(busy.not()); view.managedProperty().bind(busy.not());
        TitledPane advanced = new TitledPane("Conexion", pythonAddress); advanced.setExpanded(false); advanced.getStyleClass().add("connection-settings");
        HBox actions = new HBox(6, process, processAll); HBox.setHgrow(process, Priority.ALWAYS); HBox.setHgrow(processAll, Priority.ALWAYS);
        panel.getChildren().addAll(connection, progress, status, frames, actions, cancel, view, advanced); return panel;
    }

    public void pythonConnecting() { pythonAvailable.set(false); pythonStatus.set("Conectando..."); pythonAddress.setEditable(false); }
    public void pythonConnected(String url) { if (closed) return; pythonAddress.setText(url); pythonAvailable.set(true); pythonStatus.set("Conectado"); }
    public void pythonConnected(String url, String token) { frameToken = token; pythonConnected(url); }
    public void pythonUnavailable(String message) { if (closed) return; pythonAvailable.set(false); pythonStatus.set("Python no disponible"); pythonAddress.setTooltip(new Tooltip(message)); System.err.println("[Python] " + message); }

    private void cancelApi() {
        if (apiTask != null) { apiTask.cancel(true); apiTask = null; }
        if (framePlayer != null) {
            framePlayer.close(); framePlayer = null;
            Runnable restore = restoreOriginal; restoreOriginal = null;
            if (!closed && restore != null) restore.run();
        }
        processingBusy.set(false);
    }

    private PlaybackPane newPlayer(boolean compact, Consumer<String> log) { PlaybackPane player = new PlaybackPane(compact, log); players.add(player); return player; }
    private VBox sidePanel(String title) { VBox box = new VBox(10, heading(title, false)); box.getStyleClass().add("side-panel"); box.setPadding(new Insets(10)); return box; }

    private Node processed() {
        if (selectedProject == null) {
            VBox empty = new VBox(20, heading("Selecciona un proyecto", false), button("Ver proyectos", "primary", () -> show("projects"))); empty.setAlignment(Pos.CENTER); return empty;
        }
        String projectId = selectedProject.getId();
        javafx.collections.ObservableList<ProcessedVideo> stored = javafx.collections.FXCollections.observableArrayList(ProcessedVideoRepository.findByProject(projectId));
        javafx.collections.transformation.FilteredList<ProcessedVideo> filtered = new javafx.collections.transformation.FilteredList<>(stored);
        ListView<ProcessedVideo> list = new ListView<>(filtered); list.setId("processed-list"); list.setPlaceholder(new Label("Sin videos procesados")); list.setMinHeight(0); VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(view -> new ListCell<ProcessedVideo>() {
            @Override protected void updateItem(ProcessedVideo item, boolean empty) {
                super.updateItem(item, empty); setText(null); setGraphic(null);
                if (!empty && item != null) {
                    Label name = new Label(item.getFilename()); name.setWrapText(true); name.setMaxWidth(250); name.getStyleClass().add("result-title");
                    Label date = new Label(resultDate(item.getCreatedAt())); date.getStyleClass().add("muted");
                    Label source = new Label(item.getSourceName()); source.setWrapText(true); source.setMaxWidth(250); source.getStyleClass().add("muted");
                    for (Label label : new Label[]{name, date, source}) label.textFillProperty().bind(javafx.beans.binding.Bindings.when(selectedProperty()).then(Color.WHITE).otherwise(label == name ? Color.web("#202020") : Color.web("#686868")));
                    setGraphic(new VBox(4, name, date, source)); setTooltip(new Tooltip(item.getFilePath()));
                } else setTooltip(null);
            }
        });
        Label name = new Label("Selecciona un resultado"); name.setId("processed-name"); name.getStyleClass().add("section-heading"); name.setWrapText(true); name.setMaxWidth(Double.MAX_VALUE);
        Label details = new Label(); details.setId("processed-details"); details.setWrapText(true); details.setMaxWidth(Double.MAX_VALUE); details.getStyleClass().add("muted");
        Label state = new Label(); state.setWrapText(true); state.setMaxWidth(Double.MAX_VALUE); state.setId("processed-state");
        PlaybackPane player = newPlayer(false, state::setText);
        Button original = button("Ver original", "outline", () -> {
            ProcessedVideo item = list.getSelectionModel().getSelectedItem(); if (item == null) return;
            selectedVideo = VideoRepository.findByGroup(projectId).stream().filter(v -> item.getSourceId().equals(v.getId())).findFirst().orElse(null);
            if (selectedVideo == null) { state.setText("El original ya no esta asociado al proyecto."); return; } show("video");
        }); original.setId("processed-original"); original.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        Button remove = button("Quitar del historial", "outline", () -> {
            ProcessedVideo item = list.getSelectionModel().getSelectedItem();
            if (item != null && confirm("Quitar resultado", "Se quitara del historial. El archivo de video se conservara.")) {
                ProcessedVideoRepository.remove(projectId, item.getId()); stored.remove(item); if (!filtered.isEmpty()) list.getSelectionModel().select(0);
            }
        }); remove.setId("processed-remove"); remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        for (Button button : new Button[]{original, remove}) { button.setMinWidth(0); button.setMaxWidth(Double.MAX_VALUE); }
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item == null) { cancelPreparation(player); player.close(); name.setText("Selecciona un resultado"); details.setText(""); state.setText(""); return; }
            selectedProcessedId = item.getId(); name.setText(item.getFilename());
            int count = item.getRegions().size(); details.setText(resultDate(item.getCreatedAt()) + "  |  " + count + (count == 1 ? " region\n" : " regiones\n") + item.getFilePath());
            File file = new File(item.getFilePath());
            if (!file.isFile()) { cancelPreparation(player); player.showError("El archivo procesado fue movido o eliminado."); return; }
            openVideo(player, file, false);
        });
        TextField search = new TextField(); search.setId("processed-search"); search.setPromptText("Buscar resultados");
        search.textProperty().addListener((obs, old, value) -> {
            String query = value.toLowerCase(Locale.ROOT); filtered.setPredicate(item -> (item.getFilename() + " " + item.getSourceName()).toLowerCase(Locale.ROOT).contains(query));
            if (list.getSelectionModel().getSelectedItem() == null && !filtered.isEmpty()) list.getSelectionModel().select(0);
        });
        Button refresh = iconButton("reset", "Actualizar resultados", () -> {
            stored.setAll(ProcessedVideoRepository.findByProject(projectId));
            ProcessedVideo active = filtered.stream().filter(item -> item.getId().equals(selectedProcessedId)).findFirst().orElse(filtered.isEmpty() ? null : filtered.get(0)); list.getSelectionModel().select(active);
        }); refresh.setId("processed-refresh");
        Button link = button("Vincular resultado", "outline", () -> {
            List<Video> originals = VideoRepository.findByGroup(projectId);
            if (originals.isEmpty()) { state.setText("Asocia primero un video original al proyecto."); return; }
            Dialog<Video> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("Video original del resultado");
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles/workspace.css").toExternalForm()); dialog.getDialogPane().getStyleClass().add("workspace");
            ComboBox<Video> sources = new ComboBox<>(); sources.getItems().setAll(originals); sources.setMaxWidth(Double.MAX_VALUE);
            sources.setConverter(new javafx.util.StringConverter<Video>() {
                @Override public String toString(Video value) { return value == null ? "" : value.getTitle(); }
                @Override public Video fromString(String text) { return null; }
            });
            sources.setValue(originals.stream().filter(v -> selectedVideo != null && v.getId().equals(selectedVideo.getId())).findFirst().orElse(originals.get(0)));
            dialog.getDialogPane().setContent(new VBox(8, new Label("Video original"), sources));
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); dialog.setResultConverter(type -> type == ButtonType.OK ? sources.getValue() : null);
            dialog.showAndWait().ifPresent(source -> {
                FileChooser chooser = new FileChooser(); chooser.setTitle("Seleccionar video procesado"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.mov", "*.avi", "*.mkv"));
                File file = chooser.showOpenDialog(stage); if (file == null) return;
                try {
                    JsonArray regions = new JsonArray(); for (VideoRegion region : VideoRepository.findRegions(source.getId())) regions.add(region.toJson());
                    ProcessedVideo result = ProcessedVideoRepository.record(projectId, source.getId(), file, regions); stored.setAll(ProcessedVideoRepository.findByProject(projectId)); search.clear(); list.getSelectionModel().select(stored.stream().filter(v -> v.getId().equals(result.getId())).findFirst().orElse(null));
                } catch (IOException | RuntimeException ex) { error(ex); }
            });
        }); link.setId("processed-link"); link.setMinWidth(0); link.setMaxWidth(Double.MAX_VALUE);
        HBox searchbar = new HBox(6, search, refresh); HBox.setHgrow(search, Priority.ALWAYS);
        Label title = heading("Procesados", true); Label project = new Label(selectedProject.getName()); project.setWrapText(true); project.getStyleClass().add("muted");
        VBox library = new VBox(12, title, project, searchbar, link, list, original, remove); library.setId("processed-library"); library.setMinWidth(300); library.setPrefWidth(300); library.setMaxWidth(300); library.setPadding(new Insets(20, 12, 20, 20));
        VBox preview = new VBox(10, name, details, player, state); preview.setMinWidth(0); preview.setPadding(new Insets(20)); VBox.setVgrow(player, Priority.ALWAYS);
        BorderPane page = new BorderPane(preview); page.setLeft(library);
        ProcessedVideo initial = filtered.stream().filter(item -> item.getId().equals(selectedProcessedId)).findFirst().orElse(filtered.isEmpty() ? null : filtered.get(0)); list.getSelectionModel().select(initial);
        return page;
    }

    private static String resultDate(long time) {
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()));
    }

    private Node data() {
        TableView<Video> table = new TableView<>(); table.setPlaceholder(new Label("No hay videos en el proyecto"));
        for (String name : new String[]{"Video", "Proyecto", "Archivo"}) {
            TableColumn<Video, String> column = new TableColumn<>(name);
            column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(name.equals("Video") ? cell.getValue().getTitle()
                    : name.equals("Proyecto") ? (selectedProject == null ? "" : selectedProject.getName()) : cell.getValue().getFilePath())); table.getColumns().add(column);
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        if (selectedProject != null) table.getItems().setAll(VideoRepository.findByGroup(selectedProject.getId()));
        VBox page = new VBox(25, identity(), heading("Datos", true), table); page.setPadding(new Insets(25, 35, 35, 35)); VBox.setVgrow(table, Priority.ALWAYS); return page;
    }

    private void choosePhoto() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Seleccionar foto"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Im\u00e1genes", "*.png", "*.jpg", "*.jpeg"));
        File file = chooser.showOpenDialog(stage); if (file == null) return;
        Image image = new Image(file.toURI().toString()); if (image.isError()) { error(new IOException("La imagen no se pudo abrir.")); return; }
        pendingPhoto = file; profileImage = image; show(screen);
    }

    private void loadProfileImage(String path) { profileImage = path == null || path.isEmpty() ? null : new Image(MediaFiles.resolve(path).toURI().toString()); if (profileImage != null && profileImage.isError()) profileImage = null; }
    private Node avatar(double size) {
        ImageView view = new ImageView(profileImage == null ? mouseLogo : profileImage); double imageSize = profileImage == null ? size * .88 : size;
        view.setFitWidth(imageSize); view.setFitHeight(imageSize); view.setPreserveRatio(true); view.setSmooth(true);
        StackPane frame = new StackPane(view); frame.getStyleClass().add("avatar"); frame.setMinSize(size, size); frame.setMaxSize(size, size); frame.setClip(new javafx.scene.shape.Circle(size / 2, size / 2, size / 2)); return frame;
    }
    private ImageView logo(double size) { ImageView view = new ImageView(brandLogo); view.setFitWidth(size); view.setFitHeight(size); view.setPreserveRatio(true); Rectangle clip = new Rectangle(size, size); clip.setArcWidth(16); clip.setArcHeight(16); view.setClip(clip); return view; }

    private <T> void background(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
        Task<T> task = new Task<T>() { @Override protected T call() throws Exception { return work.call(); } };
        task.setOnSucceeded(e -> { if (!closed) success.accept(task.getValue()); }); task.setOnFailed(e -> { if (!closed) failure.accept(task.getException()); }); files.submit(task);
    }

    private boolean confirm(String title, String text) { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(null); return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK; }
    private void error(Throwable ex) { Alert alert = new Alert(Alert.AlertType.ERROR, ex.getMessage() == null ? "No se pudo completar la operaci\u00f3n." : ex.getMessage()); alert.initOwner(stage); alert.setTitle("Dungeon Mice"); alert.setHeaderText(null); alert.showAndWait(); }
    private void disposePlayers() {
        for (Task<File> task : playbackRequests.values()) task.cancel(true);
        playbackRequests.clear();
        for (PlaybackPane player : players) {
            try { player.close(); } catch (RuntimeException ex) { System.err.println("No se pudo liberar el reproductor: " + ex.getMessage()); }
        }
        players.clear();
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelApi();
        directoryDelay.stop(); directoryGeneration++;
        try { disposePlayers(); } finally { files.shutdownNow(); mediaPreparation.shutdownNow(); }
    }

    private Button iconButton(String kind, String tip, Runnable action) {
        Canvas icon = new Canvas(18, 18); GraphicsContext g = icon.getGraphicsContext2D(); g.setStroke(Color.BLACK); g.setFill(Color.BLACK); g.setLineWidth(1.8);
        switch (kind) {
            case "close": g.strokeLine(3, 3, 15, 15); g.strokeLine(15, 3, 3, 15); break;
            case "minimize": g.strokeLine(4, 12, 14, 12); break;
            case "maximize": g.strokeRect(4, 4, 10, 10); break;
            case "folder": g.strokeRect(2, 5, 14, 11); g.strokeRect(2, 2, 6, 3); break;
            case "zoom": g.strokeOval(2, 2, 10, 10); g.strokeLine(11, 11, 16, 16); g.strokeLine(4, 7, 10, 7); g.strokeLine(7, 4, 7, 10); break;
            case "reset": g.strokeArc(3, 3, 12, 12, 30, 290, javafx.scene.shape.ArcType.OPEN); g.strokeLine(12, 2, 15, 5); g.strokeLine(15, 5, 16, 1); break;
            default: throw new IllegalArgumentException(kind);
        }
        icon.setMouseTransparent(true);
        Button button = new Button(); button.setGraphic(icon); button.getStyleClass().add("icon-button"); button.setTooltip(new Tooltip(tip)); button.setAccessibleText(tip); button.setOnAction(e -> action.run()); return button;
    }
    private Button button(String text, String styles, Runnable action) { Button button = new Button(text); button.getStyleClass().addAll(styles.split(" ")); button.setOnAction(e -> action.run()); return button; }
    private Label heading(String text, boolean large) { Label label = new Label(text); label.getStyleClass().add(large ? "heading" : "section-heading"); return label; }
    private Region gap(double height) { Region gap = new Region(); gap.setMinHeight(height); gap.setPrefHeight(height); gap.setMaxHeight(height); return gap; }

    public void writeSnapshots() {
        try {
            File output = new File("target/ui-snapshots"); if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create snapshots");
        List<String> screens = SessionManager.isLoggedIn() ? Arrays.asList("projects", "video", "processed", "data") : Arrays.asList("login");
            for (String name : screens) for (int[] size : new int[][]{{1000, 740}, {1440, 900}}) {
                show(name); resize(size[0], size[1]); applyCss(); layout(); WritableImage snapshot = snapshot(null, null);
                BufferedImage image = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                ImageIO.write(image, "png", new File(output, name + "-" + size[0] + "x" + size[1] + ".png"));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        finally { close(); Platform.exit(); }
    }
}
