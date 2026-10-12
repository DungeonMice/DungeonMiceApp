package com.phys.dungeonmiceapp.ui;

import com.google.gson.JsonArray;
import com.phys.dungeonmiceapp.database.VideoRepository;
import com.phys.dungeonmiceapp.model.*;
import java.util.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/** Per-video polygon editing. Drafts are never sent or persisted until saved. */
public final class RegionEditor extends VBox {
    private final PlaybackPane player;
    private final ObservableList<VideoRegion> regions = FXCollections.observableArrayList();
    private final ObservableList<Point2D> draft = FXCollections.observableArrayList();
    private final ListView<VideoRegion> list = new ListView<>(regions);
    private final TextField name = new TextField();
    private final ColorPicker color = new ColorPicker(Color.web("#00874b"));
    private final Slider threshold = new Slider(0, 100, 80);
    private final Label message = new Label();
    private final Label frame = new Label();
    private final VBox form = new VBox(8);
    private final javafx.beans.property.BooleanProperty editing = new javafx.beans.property.SimpleBooleanProperty(false);
    private final javafx.beans.property.BooleanProperty locked = new javafx.beans.property.SimpleBooleanProperty(false);
    private Video video;
    private VideoRegion replacing;
    private final ComboBox<String> shape = new ComboBox<>();
    private final ComboBox<String> quadrant = new ComboBox<>();
    private final Spinner<Double> poolX = number("pool-x"), poolY = number("pool-y"), poolRadius = number("pool-radius");
    private final Spinner<Double> poolAngle = number("pool-angle");
    private boolean updatingPool, awaitingEdge;
    private static Spinner<Double> number(String id) {
        Spinner<Double> spinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 100000, 0, 1)); spinner.setId(id); spinner.setEditable(true); spinner.setMinWidth(0); spinner.setMaxWidth(Double.MAX_VALUE);
        spinner.getValueFactory().setConverter(new javafx.util.StringConverter<Double>() {
            @Override public String toString(Double value) { return new java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value); }
            @Override public Double fromString(String text) { return Double.parseDouble(text.trim()); }
        }); return spinner;
    }
    private boolean morris() { return shape.getSelectionModel().getSelectedIndex() == 1; }

    public RegionEditor(PlaybackPane player) {
        this.player = player;
        setId("region-editor"); setSpacing(10); setPadding(new Insets(12));
        Label title = new Label("Regiones"); title.getStyleClass().add("section-heading");
        Label countSaved = new Label(); countSaved.textProperty().bind(javafx.beans.binding.Bindings.size(regions).asString("%d guardadas")); countSaved.getStyleClass().add("muted");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox header = new HBox(8, title, spacer, countSaved); header.setAlignment(Pos.CENTER_LEFT);
        shape.setId("region-shape"); shape.getItems().setAll("Poligono (CrossMaze)", "Cuadrante (Morris)"); shape.getSelectionModel().select(0); shape.setMaxWidth(Double.MAX_VALUE);
        shape.disableProperty().bind(editing.or(locked));
        list.setId("region-list"); list.setMinHeight(90); list.setPrefHeight(130); list.setPlaceholder(new Label("Sin regiones"));
        list.setCellFactory(view -> new ListCell<VideoRegion>() {
            @Override protected void updateItem(VideoRegion item, boolean empty) {
                super.updateItem(item, empty); setText(null); setGraphic(null);
                if (!empty && item != null) {
                    Label label = new Label(item.getName()); label.setWrapText(true); label.setMaxWidth(200);
                    label.textFillProperty().bind(javafx.beans.binding.Bindings.when(selectedProperty()).then(Color.WHITE).otherwise(Color.web("#202020")));
                    Label vertices = new Label(item.isMorris() ? String.format("Cuadrante - radio %.1f px", item.getRadius()) : item.getPoints().size() + " vertices"); vertices.getStyleClass().add("muted");
                    vertices.textFillProperty().bind(javafx.beans.binding.Bindings.when(selectedProperty()).then(Color.web("#dddddd")).otherwise(Color.web("#686868")));
                    HBox row = new HBox(8, new Circle(5, Color.web(item.getColor())), new VBox(2, label, vertices)); row.setAlignment(Pos.CENTER_LEFT); setGraphic(row);
                }
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> overlay());
        name.setId("region-name"); name.setPromptText("Nombre"); name.setMaxWidth(Double.MAX_VALUE);
        color.setId("region-color"); color.setMinWidth(70); color.setPrefWidth(90); color.setMaxWidth(90);
        threshold.setId("region-overlap"); threshold.setBlockIncrement(5);
        Label overlap = new Label(); overlap.textProperty().bind(threshold.valueProperty().asString("Solapamiento: %.0f%%"));
        Button add = button("Agregar", "region-add", () -> begin(null)); add.setTooltip(new Tooltip("Agregar region"));
        add.disableProperty().bind(player.videoReadyProperty().not().or(editing).or(locked).or(javafx.beans.binding.Bindings.size(regions).greaterThanOrEqualTo(32)));
        Button edit = button("Editar", "region-edit", () -> begin(list.getSelectionModel().getSelectedItem()));
        edit.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull().or(editing).or(locked).or(player.videoReadyProperty().not()));
        Button delete = button("Eliminar", "region-delete", this::remove);
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull().or(editing).or(locked));
        HBox tools = new HBox(6, add, edit, delete);
        for (Button b : new Button[]{add, edit, delete}) { b.setMinWidth(0); b.setMaxWidth(Double.MAX_VALUE); }
        HBox.setHgrow(add, Priority.ALWAYS); HBox.setHgrow(edit, Priority.ALWAYS); HBox.setHgrow(delete, Priority.ALWAYS);
        Button undo = symbol("\u21b6", "region-undo", "Deshacer vertice", () -> { if (!draft.isEmpty()) draft.remove(draft.size() - 1); });
        undo.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(draft));
        Button redraw = symbol("\u21bb", "region-redraw", "Redibujar region", draft::clear);
        Label count = new Label(); count.textProperty().bind(javafx.beans.binding.Bindings.size(draft).asString("Vertices: %d"));
        HBox drawingTools = new HBox(8, undo, redraw, count); drawingTools.setAlignment(Pos.CENTER_LEFT);
        Button save = button("Guardar", "region-save", this::save);
        save.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> draft.size() < (morris() ? 2 : 3), draft, shape.valueProperty()).or(player.videoReadyProperty().not()).or(locked));
        Button cancel = button("Cancelar", "region-cancel", this::cancel);
        save.setMinWidth(0); cancel.setMinWidth(0); save.setMaxWidth(Double.MAX_VALUE); cancel.setMaxWidth(Double.MAX_VALUE);
        HBox identity = new HBox(8, name, color); name.setMinWidth(0); HBox.setHgrow(name, Priority.ALWAYS);
        HBox actions = new HBox(8, save, cancel); HBox.setHgrow(save, Priority.ALWAYS); HBox.setHgrow(cancel, Priority.ALWAYS);
        quadrant.setId("pool-quadrant"); quadrant.getItems().setAll("Inferior derecho", "Inferior izquierdo", "Superior izquierdo", "Superior derecho"); quadrant.getSelectionModel().select(3); quadrant.setMaxWidth(Double.MAX_VALUE);
        quadrant.setTooltip(new Tooltip("Cuadrante objetivo. Angulos en coordenadas de imagen: 0 derecha, 90 abajo."));
        quadrant.getSelectionModel().selectedIndexProperty().addListener((obs, old, value) -> { if (!updatingPool) poolAngle.getValueFactory().setValue(value.doubleValue() * 90); });
        poolAngle.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 359, 270, 1));
        GridPane geometry = new GridPane(); geometry.setHgap(6); geometry.setVgap(6);
        Spinner<?>[] values = {poolX, poolY, poolRadius}; String[] labels = {"Centro X", "Centro Y", "Radio"};
        for (int i = 0; i < 3; i++) { geometry.add(new Label(labels[i]), i, 0); geometry.add(values[i], i, 1); GridPane.setHgrow(values[i], Priority.ALWAYS); ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(100.0 / 3); geometry.getColumnConstraints().add(column); }
        HBox rotation = new HBox(8, new Label("Angulo"), poolAngle); HBox.setHgrow(poolAngle, Priority.ALWAYS);
        VBox pool = new VBox(8, geometry, quadrant, rotation); pool.visibleProperty().bind(shape.valueProperty().isEqualTo("Cuadrante (Morris)")); pool.managedProperty().bind(pool.visibleProperty());
        drawingTools.visibleProperty().bind(pool.visibleProperty().not()); drawingTools.managedProperty().bind(drawingTools.visibleProperty());
        form.getChildren().addAll(identity, pool, overlap, threshold, drawingTools, actions);
        for (Spinner<Double> value : Arrays.asList(poolX, poolY, poolRadius, poolAngle)) value.valueProperty().addListener((obs, old, next) -> updatePool());
        poolRadius.setTooltip(new Tooltip("Marca el centro y luego un punto del borde sobre el video; tambien puedes ajustar el radio."));
        form.visibleProperty().bind(editing); form.managedProperty().bind(editing);
        add.visibleProperty().bind(editing.not()); add.managedProperty().bind(editing.not());
        tools.visibleProperty().bind(editing.not()); tools.managedProperty().bind(editing.not());
        list.disableProperty().bind(editing.or(locked));
        list.visibleProperty().bind(editing.not()); list.managedProperty().bind(editing.not());
        message.setWrapText(true); message.setMaxWidth(Double.MAX_VALUE); message.getStyleClass().add("validation-message"); message.setId("region-message");
        frame.setId("region-frame-status"); frame.getStyleClass().add("muted");
        player.videoReadyProperty().addListener((obs, old, ready) -> frame.setText(ready ? player.getPlayer().getMedia().getWidth() + " x " + player.getPlayer().getMedia().getHeight() + " px" : "Cargando video..."));
        getChildren().addAll(header, frame, shape, tools, list, form, message);
        draft.addListener((ListChangeListener<Point2D>) change -> overlay());
        player.videoReadyProperty().addListener((obs, old, ready) -> { if (ready) overlay(); });
    }

    public void setVideo(Video video) {
        cancel(); this.video = video; regions.clear();
        if (video != null) {
            try { regions.setAll(VideoRepository.findRegions(video.getId())); shape.getSelectionModel().select(regions.stream().anyMatch(VideoRegion::isMorris) ? 1 : 0); }
            catch (RuntimeException error) { message.setText(error.getMessage()); }
        }
        overlay();
    }

    private void begin(VideoRegion region) {
        if (video == null || !player.videoReadyProperty().get() || locked.get()) return;
        cancel(); replacing = region; name.setText(region == null ? (morris() ? "Plataforma" : "Region " + (regions.size() + 1)) : region.getName());
        if (region != null) shape.getSelectionModel().select(region.isMorris() ? 1 : 0);
        color.setValue(Color.web(region == null ? palette(regions.size()) : region.getColor()));
        threshold.setValue(region == null ? 80 : region.getThreshold() * 100);
        if (region != null) {
            if (region.getWidth() != player.getPlayer().getMedia().getWidth() || region.getHeight() != player.getPlayer().getMedia().getHeight()) { message.setText("La resolucion cambio. Elimina y vuelve a dibujar la region."); replacing = null; return; }
            if (!region.isMorris()) draft.setAll(region.getPoints());
        }
        if (morris()) {
            updatingPool = true;
            int width = player.getPlayer().getMedia().getWidth(), height = player.getPlayer().getMedia().getHeight();
            poolX.getValueFactory().setValue(region == null ? (width - 1) / 2.0 : region.getCenter().getX());
            poolY.getValueFactory().setValue(region == null ? (height - 1) / 2.0 : region.getCenter().getY());
            poolRadius.getValueFactory().setValue(region == null ? Math.min(width, height) / 3.0 : region.getRadius());
            poolAngle.getValueFactory().setValue(region == null ? 270.0 : region.getAngle()); quadrant.getSelectionModel().select((int) (poolAngle.getValue() / 90));
            updatingPool = false; awaitingEdge = false; updatePool();
        }
        editing.set(true); player.pause();
        player.setRegionClickHandler(point -> {
            if (morris()) {
                updatingPool = true;
                if (!awaitingEdge) { poolX.getValueFactory().setValue(point.getX()); poolY.getValueFactory().setValue(point.getY()); }
                else poolRadius.getValueFactory().setValue(point.distance(new Point2D(poolX.getValue(), poolY.getValue())));
                awaitingEdge = !awaitingEdge; updatingPool = false; updatePool(); return;
            }
            if (draft.size() >= 128) { message.setText("Maximo 128 vertices."); return; }
            if (!draft.isEmpty() && draft.get(draft.size() - 1).distance(point) < 1) return;
            draft.add(point); message.setText("");
        });
        name.requestFocus(); overlay();
    }

    private void save() {
        if (video == null || !player.videoReadyProperty().get()) return;
        try {
            Color c = color.getValue(); String hex = String.format("#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
            for (Spinner<Double> spinner : Arrays.asList(poolX, poolY, poolRadius, poolAngle)) spinner.increment(0);
            VideoRegion region = morris() ? poolRegion(hex) : new VideoRegion(name.getText(), hex, draft, threshold.getValue() / 100,
                    player.getPlayer().getMedia().getWidth(), player.getPlayer().getMedia().getHeight());
            List<VideoRegion> updated = new ArrayList<>(regions); int index = replacing == null ? -1 : updated.indexOf(replacing);
            if (region.isMorris() && (updated.size() > 1 || (updated.size() == 1 && index < 0))) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Morris usa un unico cuadrante. Se reemplazaran las regiones de este video.", ButtonType.OK, ButtonType.CANCEL); alert.initOwner(getScene().getWindow()); alert.setHeaderText(null);
                if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                updated.clear(); index = -1;
            }
            if (index < 0) updated.add(region); else updated.set(index, region);
            VideoRepository.saveRegions(video.getId(), updated); regions.setAll(updated); cancel(); list.getSelectionModel().select(region); overlay();
        } catch (RuntimeException error) { message.setText(error.getMessage()); }
    }

    private void remove() {
        VideoRegion selected = list.getSelectionModel().getSelectedItem(); if (selected == null) return;
        try { List<VideoRegion> updated = new ArrayList<>(regions); updated.remove(selected); VideoRepository.saveRegions(video.getId(), updated); regions.setAll(updated); overlay(); message.setText(""); }
        catch (RuntimeException error) { message.setText(error.getMessage()); }
    }

    public void cancel() { editing.set(false); replacing = null; draft.clear(); player.setRegionClickHandler(null); message.setText(""); overlay(); }
    public void setLocked(boolean value) { locked.set(value); if (value) cancel(); }
    public void showAnalysisStatus() { frame.setText("Regiones del analisis"); }
    private void updatePool() {
        if (updatingPool || !morris()) return;
        Point2D center = new Point2D(poolX.getValue(), poolY.getValue()); draft.setAll(center, center.add(poolRadius.getValue(), 0)); overlay();
    }
    private VideoRegion poolRegion(String hex) {
        return VideoRegion.morris(name.getText(), hex, new Point2D(poolX.getValue(), poolY.getValue()), poolRadius.getValue(), poolAngle.getValue(), threshold.getValue() / 100,
                player.getPlayer().getMedia().getWidth(), player.getPlayer().getMedia().getHeight());
    }
    public void setOnApplyAll(Runnable action) {
        Button apply = button("Aplicar a todos", "region-apply-all", action);
        apply.setMaxWidth(Double.MAX_VALUE);
        apply.disableProperty().bind(editing.or(locked).or(player.videoReadyProperty().not()).or(javafx.beans.binding.Bindings.isEmpty(regions)));
        apply.visibleProperty().bind(editing.not()); apply.managedProperty().bind(editing.not());
        getChildren().add(4, apply);
    }
    public JsonArray requestRegions() {
        if (editing.get()) throw new IllegalArgumentException("Guarda o cancela la region en edicion.");
        if (!player.videoReadyProperty().get()) throw new IllegalArgumentException("Espera a que el video este listo.");
        JsonArray payload = new JsonArray();
        for (VideoRegion region : regions) {
            if (region.getWidth() != player.getPlayer().getMedia().getWidth() || region.getHeight() != player.getPlayer().getMedia().getHeight())
                throw new IllegalArgumentException("La resolucion del video cambio. Vuelve a dibujar sus regiones.");
            payload.add(region.toJson());
        }
        VideoRegion.analysisType(payload); return payload;
    }
    private void overlay() {
        List<VideoRegion> visible = new ArrayList<>(regions); if (replacing != null) visible.remove(replacing);
        VideoRegion selected = list.getSelectionModel().getSelectedItem();
        if (editing.get() && morris() && player.videoReadyProperty().get()) {
            try { VideoRegion preview = poolRegion("#00874b"); visible.add(preview); player.setRegionOverlay(visible, Collections.emptyList(), preview.getName()); return; }
            catch (IllegalArgumentException ex) { message.setText(ex.getMessage()); }
        }
        player.setRegionOverlay(visible, draft, selected == null ? null : selected.getName());
    }
    private static Button button(String text, String id, Runnable action) {
        Button b = new Button(text); b.setId(id); b.getStyleClass().add("outline"); b.setOnAction(e -> action.run()); return b;
    }
    private static Button symbol(String text, String id, String tooltip, Runnable action) {
        Button b = button(text, id, action); b.getStyleClass().add("icon-button"); b.setTooltip(new Tooltip(tooltip)); b.setAccessibleText(tooltip); return b;
    }
    private static String palette(int index) { return new String[]{"#00874b", "#bc2042", "#b08000", "#7424d4", "#237eb5", "#b058a0"}[index % 6]; }
}
