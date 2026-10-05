package com.phys.dungeonmiceapp.ui;

import javafx.collections.*;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.input.*;
import javafx.scene.paint.Color;

/** Bounded session history is retained independently of the rebuilt video view. */
public final class ActivityLog extends VBox {
    public static final class Entry {
        public final String time, text;
        public final boolean error;
        public Entry(String time, String text, boolean error) { this.time = time; this.text = text; this.error = error; }
        @Override public String toString() { return time + "  " + text; }
    }
    private final ListView<Entry> list;
    public ActivityLog(ObservableList<Entry> history) {
        setSpacing(10); setPadding(new Insets(12)); setMinSize(0, 0);
        TextField search = new TextField(); search.setPromptText("Buscar registros"); search.setId("log-search");
        FilteredList<Entry> filtered = new FilteredList<>(history); search.textProperty().addListener((obs, old, value) -> filtered.setPredicate(entry -> entry.text.toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT))));
        list = new ListView<>(filtered); list.setId("activity-log"); list.setMinHeight(120); list.setPrefHeight(300); list.getStyleClass().add("activity-list"); VBox.setVgrow(list, Priority.ALWAYS); list.setPlaceholder(new Label("Sin registros"));
        list.setCellFactory(view -> new ListCell<Entry>() {
            @Override protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty); setText(null); setGraphic(null);
                if (!empty && entry != null) {
                    Label time = new Label(entry.time); time.getStyleClass().add("timecode");
                    Label text = new Label(entry.text); text.setWrapText(true); text.maxWidthProperty().bind(list.widthProperty().subtract(40));
                    text.textFillProperty().bind(javafx.beans.binding.Bindings.when(selectedProperty()).then(Color.WHITE).otherwise(entry.error ? Color.web("#a31432") : Color.web("#202020")));
                    time.textFillProperty().bind(javafx.beans.binding.Bindings.when(selectedProperty()).then(Color.web("#dddddd")).otherwise(Color.web("#686868")));
                    VBox row = new VBox(4, time, text); setGraphic(row);
                }
            }
        });
        CheckBox follow = new CheckBox("Seguir"); follow.setSelected(true); follow.setTooltip(new Tooltip("Seguir los registros nuevos")); follow.setId("log-follow");
        ListChangeListener<Entry> follower = change -> { if (follow.isSelected() && !filtered.isEmpty()) list.scrollTo(filtered.size() - 1); };
        sceneProperty().addListener((obs, old, value) -> { if (old != null) history.removeListener(follower); if (value != null) history.addListener(follower); });
        Button copy = new Button("\u2398"); copy.getStyleClass().add("icon-button"); copy.setTooltip(new Tooltip("Copiar registro")); copy.setAccessibleText("Copiar registro"); copy.setId("log-copy");
        copy.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull()); copy.setOnAction(event -> {
            Entry entry = list.getSelectionModel().getSelectedItem(); if (entry == null) return; ClipboardContent content = new ClipboardContent(); content.putString(entry.toString()); Clipboard.getSystemClipboard().setContent(content);
        });
        Button clear = new Button("\u00d7"); clear.getStyleClass().add("icon-button"); clear.setTooltip(new Tooltip("Limpiar registros")); clear.setAccessibleText("Limpiar registros"); clear.setId("log-clear"); clear.setOnAction(event -> history.clear());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox toolbar = new HBox(6, follow, spacer, copy, clear); toolbar.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(search, toolbar, list);
    }
    public static void append(ObservableList<Entry> history, String time, String text, boolean error) {
        if (!history.isEmpty() && history.get(history.size() - 1).text.equals(text)) return;
        history.add(new Entry(time, text, error)); if (history.size() > 200) history.remove(0, history.size() - 200);
    }
}
