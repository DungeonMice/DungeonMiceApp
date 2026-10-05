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
    private final VBox form = new VBox(8);
    private final javafx.beans.property.BooleanProperty editing = new javafx.beans.property.SimpleBooleanProperty(false);
    private final javafx.beans.property.BooleanProperty locked = new javafx.beans.property.SimpleBooleanProperty(false);
    private Video video;
    private VideoRegion replacing;

    public RegionEditor(PlaybackPane player) {
        this.player = player;
        setId("region-editor"); setSpacing(10); setPadding(new Insets(10)); getStyleClass().add("side-panel");
        Label title = new Label("Regiones"); title.getStyleClass().add("section-heading");
        list.setId("region-list"); list.setMinHeight(90); list.setPrefHeight(130); list.setPlaceholder(new Label("Sin regiones"));
        list.setCellFactory(view -> new ListCell<VideoRegion>() {
            @Override protected void updateItem(VideoRegion item, boolean empty) {
                super.updateItem(item, empty); setText(null); setGraphic(null);
                if (!empty && item != null) {
                    Label label = new Label(item.getName()); label.setWrapText(true); label.setMaxWidth(130);
                    label.textFillProperty().bind(javafx.beans.binding.Bindings.when(selectedProperty()).then(Color.WHITE).otherwise(Color.web("#202020")));
                    HBox row = new HBox(8, new Circle(5, Color.web(item.getColor())), label); row.setAlignment(Pos.CENTER_LEFT); setGraphic(row);
                }
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> overlay());
        name.setId("region-name"); name.setPromptText("Nombre"); name.setMaxWidth(Double.MAX_VALUE);
        color.setId("region-color"); color.setMaxWidth(Double.MAX_VALUE);
        threshold.setId("region-overlap"); threshold.setBlockIncrement(5);
        Label overlap = new Label(); overlap.textProperty().bind(threshold.valueProperty().asString("Solapamiento: %.0f%%"));
        Button add = button("Agregar region", "region-add", () -> begin(null));
        add.disableProperty().bind(player.videoReadyProperty().not().or(editing).or(locked).or(javafx.beans.binding.Bindings.size(regions).greaterThanOrEqualTo(32)));
        Button edit = button("Editar", "region-edit", () -> begin(list.getSelectionModel().getSelectedItem()));
        edit.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull().or(editing).or(locked).or(player.videoReadyProperty().not()));
        Button delete = button("Eliminar", "region-delete", this::remove);
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull().or(editing).or(locked));
        HBox tools = new HBox(8, edit, delete);
        for (Button b : new Button[]{add, edit, delete}) b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(edit, Priority.ALWAYS); HBox.setHgrow(delete, Priority.ALWAYS);
        Button undo = symbol("\u21b6", "region-undo", "Deshacer vertice", () -> { if (!draft.isEmpty()) draft.remove(draft.size() - 1); });
        undo.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(draft));
        Button redraw = symbol("\u21bb", "region-redraw", "Redibujar region", draft::clear);
        Label count = new Label(); count.textProperty().bind(javafx.beans.binding.Bindings.size(draft).asString("Vertices: %d"));
        HBox drawingTools = new HBox(8, undo, redraw, count); drawingTools.setAlignment(Pos.CENTER_LEFT);
        Button save = button("Guardar region", "region-save", this::save);
        save.disableProperty().bind(javafx.beans.binding.Bindings.size(draft).lessThan(3).or(player.videoReadyProperty().not()).or(locked));
        Button cancel = button("Cancelar", "region-cancel", this::cancel);
        save.setMaxWidth(Double.MAX_VALUE); cancel.setMaxWidth(Double.MAX_VALUE);
        form.getChildren().addAll(name, color, overlap, threshold, drawingTools, save, cancel);
        form.visibleProperty().bind(editing); form.managedProperty().bind(editing);
        message.setWrapText(true); message.setMaxWidth(Double.MAX_VALUE); message.getStyleClass().add("validation-message"); message.setId("region-message");
        getChildren().addAll(title, list, add, tools, form, message);
        draft.addListener((ListChangeListener<Point2D>) change -> overlay());
        player.videoReadyProperty().addListener((obs, old, ready) -> { if (ready) overlay(); });
    }

    public void setVideo(Video video) {
        cancel(); this.video = video; regions.clear();
        if (video != null) {
            try { regions.setAll(VideoRepository.findRegions(video.getId())); }
            catch (RuntimeException error) { message.setText(error.getMessage()); }
        }
        overlay();
    }

    private void begin(VideoRegion region) {
        if (video == null || !player.videoReadyProperty().get() || locked.get()) return;
        cancel(); replacing = region; name.setText(region == null ? "Region " + (regions.size() + 1) : region.getName());
        color.setValue(Color.web(region == null ? palette(regions.size()) : region.getColor()));
        threshold.setValue(region == null ? 80 : region.getThreshold() * 100);
        if (region != null) {
            if (region.getWidth() != player.getPlayer().getMedia().getWidth() || region.getHeight() != player.getPlayer().getMedia().getHeight()) { message.setText("La resolucion cambio. Elimina y vuelve a dibujar la region."); replacing = null; return; }
            draft.setAll(region.getPoints());
        }
        editing.set(true); player.pause();
        player.setRegionClickHandler(point -> {
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
            VideoRegion region = new VideoRegion(name.getText(), hex, draft, threshold.getValue() / 100,
                    player.getPlayer().getMedia().getWidth(), player.getPlayer().getMedia().getHeight());
            List<VideoRegion> updated = new ArrayList<>(regions); int index = replacing == null ? -1 : updated.indexOf(replacing);
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
    public JsonArray requestRegions() {
        if (editing.get()) throw new IllegalArgumentException("Guarda o cancela la region en edicion.");
        if (regions.size() < 2) throw new IllegalArgumentException("CrossMaze requiere al menos dos regiones guardadas.");
        if (!player.videoReadyProperty().get()) throw new IllegalArgumentException("Espera a que el video este listo.");
        JsonArray payload = new JsonArray();
        for (VideoRegion region : regions) {
            if (region.getWidth() != player.getPlayer().getMedia().getWidth() || region.getHeight() != player.getPlayer().getMedia().getHeight())
                throw new IllegalArgumentException("La resolucion del video cambio. Vuelve a dibujar sus regiones.");
            payload.add(region.toJson());
        }
        return payload;
    }
    private void overlay() {
        List<VideoRegion> visible = new ArrayList<>(regions); if (replacing != null) visible.remove(replacing);
        VideoRegion selected = list.getSelectionModel().getSelectedItem();
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
