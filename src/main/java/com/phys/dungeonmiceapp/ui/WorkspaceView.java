package com.phys.dungeonmiceapp.ui;

import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
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
    private File pendingPhoto;
    private Image profileImage;
    private String screen = "login";
    private double dragX, dragY;
    private boolean maximized;
    private Rectangle2D restoreBounds;
    private boolean closed;

    public WorkspaceView(Stage stage) {
        this.stage = stage;
        stage.getIcons().add(brandLogo);
        getStyleClass().add("workspace");
        stage.setOnCloseRequest(e -> { e.consume(); exitApplication(); });
        show("login");
    }

    private static TextField field(String id) { TextField field = new TextField(); field.setId(id); return field; }
    private static PasswordField password(String id) { PasswordField field = new PasswordField(); field.setId(id); return field; }
    private String username() { return SessionManager.isLoggedIn() ? SessionManager.getCurrentUser().getUsername() : ""; }

    private void show(String next) {
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
        if (Arrays.asList("projects", "video", "data", "user").contains(next)) header.getChildren().add(navigation(next));
        setTop(header);
        switch (next) {
            case "register": setCenter(userForm(false)); break;
            case "user": setCenter(userForm(true)); break;
            case "projects": setCenter(projects()); break;
            case "create-project": setCenter(projectForm(false)); break;
            case "edit-project": setCenter(projectForm(true)); break;
            case "video": setCenter(video()); break;
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
        String[] names = {"Proyectos", "Video", "Datos", "Usuario"}, targets = {"projects", "video", "data", "user"};
        for (int i = 0; i < names.length; i++) {
            final String target = targets[i];
            Button tab = button(names[i], "tab", () -> { if (target.equals("user")) beginProfile(true); else show(target); });
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
        logout.setOnAction(e -> { SessionManager.logout(); selectedProject = null; selectedVideo = null; profileImage = null; pendingVideos.clear(); loginPassword.clear(); loginMessage.setText(""); show("login"); });
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
            selectedVideo = item; open.setDisable(item == null); if (item != null) preview.open(MediaFiles.resolve(item.getFilePath())); else preview.close();
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
        pendingVideos.clear(); VideoGroup p = edit ? selectedProject : null;
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
        FlowPane thumbnails = new FlowPane(12, 14); thumbnails.setPadding(new Insets(12));
        if (edit) for (Video video : VideoRepository.findByGroup(selectedProject.getId())) thumbnails.getChildren().add(thumbnail(video.getTitle(), MediaFiles.resolve(video.getFilePath()), () -> previewFile(MediaFiles.resolve(video.getFilePath()), null)));
        for (File file : pendingVideos) thumbnails.getChildren().add(thumbnail(file.getName(), file, () -> previewFile(file, null)));
        ScrollPane scroll = new ScrollPane(thumbnails); scroll.setFitToWidth(true); scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        BorderPane gallery = new BorderPane(scroll); gallery.getStyleClass().add("gallery"); gallery.setPrefHeight(390);
        Button add = button("A\u00f1adir video", "primary", () -> chooseVideo(file -> { pendingVideos.add(file); show(screen); }));
        BorderPane.setAlignment(add, Pos.CENTER); BorderPane.setMargin(add, new Insets(15)); gallery.setBottom(add);
        VBox right = new VBox(16, heading("Videos asociados", false), gallery); HBox columns = new HBox(35, left, right); HBox.setHgrow(right, Priority.ALWAYS); columns.setPadding(new Insets(26, 0, 25, 0)); page.setCenter(columns);
        HBox footer = new HBox(10, button("Cancelar", "outline", () -> { pendingVideos.clear(); show("projects"); }), button(edit ? "Guardar cambios" : "Crear proyecto", "primary", () -> saveProject(edit)));
        footer.setAlignment(Pos.CENTER); page.setBottom(footer); return page;
    }

    private void saveProject(boolean edit) {
        try {
            selectedProject = VideoGroupRepository.saveProject(edit ? selectedProject.getId() : null, projectName.getText(), description.getText(), category.getText(), owner.getText(), participants.getText(), location.getText());
            if (pendingVideos.isEmpty()) { show("projects"); return; }
            List<File> sources = new ArrayList<>(pendingVideos);
            show("edit-project");
            importVideos(sources, () -> { pendingVideos.clear(); show("projects"); });
        } catch (RuntimeException e) { error(e); }
    }

    private void deleteProject() {
        if (selectedProject == null || !confirm("Eliminar proyecto", "Se eliminar\u00e1n el proyecto y sus asociaciones de video. Los archivos originales se conservar\u00e1n.")) return;
        try { VideoRepository.deleteByGroup(selectedProject.getId()); VideoGroupRepository.deleteById(selectedProject.getId()); selectedProject = null; selectedVideo = null; pendingVideos.clear(); show("projects"); }
        catch (RuntimeException e) { error(e); }
    }

    private Node thumbnail(String title, File source, Runnable action) {
        ImageView image = new ImageView(); image.setFitWidth(160); image.setFitHeight(90); image.setPreserveRatio(true);
        Label state = new Label("Cargando..."); StackPane frame = new StackPane(image, state); frame.setMinSize(160, 90); frame.setMaxSize(160, 90); frame.getStyleClass().add("thumbnail");
        background(() -> MediaFiles.thumbnail(source), file -> { image.setImage(new Image(file.toURI().toString())); state.setVisible(false); }, ex -> state.setText("Sin miniatura"));
        Label label = new Label(title); label.setMaxWidth(160); label.setTooltip(new Tooltip(title));
        VBox tile = new VBox(7, frame, label); tile.setAlignment(Pos.CENTER); tile.setOnMouseClicked(e -> action.run()); tile.setStyle("-fx-cursor: hand;"); return tile;
    }

    private void chooseVideo(Consumer<File> accepted) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Seleccionar video");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.m4v", "*.mov", "*.avi", "*.mkv", "*.wmv"));
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
        dialog.setOnHidden(e -> preview.close()); dialog.show();
        background(() -> MediaFiles.preview(file), ready -> {
            if (!dialog.isShowing()) return; state.setText(file.getName()); preview.open(ready); use.setDisable(false);
        }, ex -> { if (dialog.isShowing()) { state.setText("No se pudo generar el previo: " + ex.getMessage()); use.setDisable(false); } });
    }

    private void importVideos(List<File> sources, Runnable after) {
        if (selectedProject == null) return;
        String groupId = selectedProject.getId(), userId = SessionManager.getCurrentUser().getId();
        setContentBusy(true);
        Stage progress = new Stage(); progress.initOwner(stage); progress.setTitle("Importando videos");
        Label message = new Label("Procesando videos..."); VBox panel = new VBox(20, message, new ProgressIndicator()); panel.setPadding(new Insets(30)); panel.setAlignment(Pos.CENTER);
        progress.setScene(new Scene(panel, 340, 160)); progress.setOnCloseRequest(e -> e.consume()); progress.show();
        Task<List<File>> task = new Task<List<File>>() {
            @Override protected List<File> call() throws Exception {
                List<File> imported = new ArrayList<>();
                try { for (File source : sources) { updateMessage("Importando " + source.getName()); imported.add(MediaFiles.importVideo(source, userId, groupId)); } return imported; }
                catch (Exception e) { for (File output : imported) java.nio.file.Files.deleteIfExists(output.toPath()); throw e; }
            }
        };
        message.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            progress.close(); if (closed) return; setContentBusy(false);
            try { for (int i = 0; i < sources.size(); i++) VideoRepository.addVideo(groupId, sources.get(i).getName(), task.getValue().get(i).getAbsolutePath()); after.run(); }
            catch (RuntimeException ex) { error(ex); }
        });
        task.setOnFailed(e -> { progress.close(); if (!closed) { setContentBusy(false); error(task.getException()); } }); files.submit(task);
    }

    private Node video() {
        if (selectedProject == null) {
            VBox empty = new VBox(20, heading("Selecciona un proyecto", false), button("Ver proyectos", "primary", () -> show("projects"))); empty.setAlignment(Pos.CENTER); return empty;
        }
        List<Video> videos = VideoRepository.findByGroup(selectedProject.getId());
        VBox logs = sidePanel("Registros"); ListView<String> entries = new ListView<>(); entries.setPrefHeight(200); VBox.setVgrow(entries, Priority.ALWAYS); logs.getChildren().add(entries);
        entries.setStyle("-fx-border-width: 0; -fx-background-color: transparent; -fx-padding: 0;");
        entries.setCellFactory(list -> new ListCell<String>() {
            private final Label text = new Label();
            { text.setWrapText(true); text.maxWidthProperty().bind(entries.widthProperty().subtract(30)); }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty); setText(null); text.setText(value); setGraphic(empty ? null : text);
            }
        });
        PlaybackPane player = newPlayer(false, text -> { entries.getItems().add(PlaybackPane.format(playerTime()) + "  " + text); if (entries.getItems().size() > 100) entries.getItems().remove(0); entries.scrollTo(entries.getItems().size() - 1); });
        ComboBox<Video> choices = new ComboBox<>(); choices.setId("video-choice"); choices.setPrefWidth(300); choices.getItems().setAll(videos);
        choices.setConverter(new javafx.util.StringConverter<Video>() {
            @Override public String toString(Video value) { return value == null ? "Seleccionar video" : value.getTitle(); }
            @Override public Video fromString(String text) { return null; }
        });
        choices.valueProperty().addListener((obs, old, item) -> { selectedVideo = item; if (item != null) player.open(MediaFiles.resolve(item.getFilePath())); else player.close(); });
        Video initial = selectedVideo == null ? null : videos.stream().filter(v -> v.getId().equals(selectedVideo.getId())).findFirst().orElse(null);
        choices.setValue(initial == null && !videos.isEmpty() ? videos.get(0) : initial);
        Button load = button("A\u00f1adir video", "outline", () -> chooseVideo(file -> importVideos(Collections.singletonList(file), () -> { List<Video> all = VideoRepository.findByGroup(selectedProject.getId()); selectedVideo = all.get(all.size() - 1); show("video"); })));
        Button remove = button("Eliminar video", "outline", () -> {
            if (selectedVideo != null && confirm("Eliminar video", "\u00bfEliminar este video del proyecto? El archivo original se conservar\u00e1.")) {
                VideoRepository.deleteById(selectedVideo.getId()); selectedVideo = null; show("video");
            }
        });
        remove.disableProperty().bind(choices.valueProperty().isNull());
        HBox tools = new HBox(10, choices, load, remove, iconButton("zoom", "Acercar", () -> player.setZoom(2)), iconButton("reset", "Restablecer zoom", player::resetZoom));
        tools.getStyleClass().add("mode-toolbar"); tools.setAlignment(Pos.CENTER_LEFT);
        VBox regions = sidePanel("Regiones"); regions.getChildren().add(new Label("Sin regiones"));
        VBox sidebar = new VBox(20, identity(), logs, regions); sidebar.setMinWidth(240); sidebar.setPrefWidth(240); sidebar.setMaxWidth(240); sidebar.setPadding(new Insets(16, 20, 20, 0)); VBox.setVgrow(logs, Priority.ALWAYS);
        BorderPane page = new BorderPane(); page.setTop(tools); player.setPadding(new Insets(20, 25, 30, 20)); page.setCenter(player); page.setRight(sidebar); return page;
    }

    private javafx.util.Duration playerTime() {
        return players.isEmpty() || players.get(players.size() - 1).getPlayer() == null ? javafx.util.Duration.ZERO : players.get(players.size() - 1).getPlayer().getCurrentTime();
    }

    private PlaybackPane newPlayer(boolean compact, Consumer<String> log) { PlaybackPane player = new PlaybackPane(compact, log); players.add(player); return player; }
    private VBox sidePanel(String title) { VBox box = new VBox(10, heading(title, false)); box.getStyleClass().add("side-panel"); box.setPadding(new Insets(10)); return box; }

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
        for (PlaybackPane player : players) {
            try { player.close(); } catch (RuntimeException ex) { System.err.println("No se pudo liberar el reproductor: " + ex.getMessage()); }
        }
        players.clear();
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { disposePlayers(); } finally { files.shutdownNow(); }
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
            List<String> screens = SessionManager.isLoggedIn() ? Arrays.asList("projects", "video", "data") : Arrays.asList("login");
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
