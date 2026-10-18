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
    public static final int MAX_ENTRIES = 2000;
    public static final class Entry {
        public final String time, text;
        public final boolean error;
        public final double seconds;
        public final int frame;
        public Entry(String time, String text, boolean error) {
            this.time = time; this.text = bounded(text); this.error = error; this.frame = 0;
            double parsed = Double.NaN;
            if (time != null && time.matches("\\d+:[0-5]\\d:[0-5]\\d\\.\\d{3}")) {
                String[] parts = time.split(":");
                try { parsed = Long.parseLong(parts[0]) * 3600.0 + Integer.parseInt(parts[1]) * 60 + Double.parseDouble(parts[2]); }
                catch (NumberFormatException ignored) { /* Invalid historical labels are not seek targets. */ }
            }
            this.seconds = parsed;
        }
        public Entry(double seconds, int frame, String text, boolean error) {
            if (!Double.isFinite(seconds) || seconds < 0 || frame < 0) throw new IllegalArgumentException("Marca de tiempo invalida");
            this.seconds = seconds; this.frame = frame; this.text = bounded(text); this.error = error;
            this.time = PlaybackPane.format(javafx.util.Duration.seconds(seconds));
        }
        public com.google.gson.JsonObject toJson() {
            com.google.gson.JsonObject data = new com.google.gson.JsonObject();
            data.addProperty("seconds", seconds); data.addProperty("frame", frame); data.addProperty("text", text); data.addProperty("error", error); return data;
        }
        public static Entry fromJson(com.google.gson.JsonObject data) { return new Entry(data.get("seconds").getAsDouble(), data.get("frame").getAsInt(), data.get("text").getAsString(), data.get("error").getAsBoolean()); }
        private static String bounded(String text) { java.util.Objects.requireNonNull(text); return text.length() <= 4000 ? text : text.substring(0, 4000); }
        @Override public String toString() { return time + (frame > 0 ? "  |  Frame " + frame : "") + "  " + text; }
    }
    private final ListView<Entry> list;
    public ActivityLog(ObservableList<Entry> history) {
        this(history, entry -> {});
    }
    public ActivityLog(ObservableList<Entry> history, java.util.function.Consumer<Entry> activate) {
        setSpacing(10); setPadding(new Insets(12)); setMinSize(0, 0);
        TextField search = new TextField(); search.setPromptText("Buscar registros"); search.setId("log-search");
        FilteredList<Entry> filtered = new FilteredList<>(history); search.textProperty().addListener((obs, old, value) -> filtered.setPredicate(entry -> entry.text.toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT))));
        list = new ListView<>(filtered); list.setId("activity-log"); list.setMinHeight(120); list.setPrefHeight(300); list.getStyleClass().add("activity-list"); VBox.setVgrow(list, Priority.ALWAYS); list.setPlaceholder(new Label("Sin registros"));
        list.setCellFactory(view -> new ListCell<Entry>() {
            {
                setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !isEmpty() && Double.isFinite(getItem().seconds)) {
                        activate.accept(getItem()); event.consume();
                    }
                });
            }
            @Override protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty); setText(null); setGraphic(null);
                if (!empty && entry != null) {
                    Label time = new Label(entry.time + (entry.frame > 0 ? "  |  Frame " + entry.frame : "")); time.getStyleClass().add("timecode");
                    time.setWrapText(true); time.maxWidthProperty().bind(list.widthProperty().subtract(40));
                    setTooltip(Double.isFinite(entry.seconds) ? new Tooltip("Ir a " + entry.time) : null);
                    Label text = new Label(entry.text); text.setWrapText(true); text.maxWidthProperty().bind(list.widthProperty().subtract(40));
                    text.textFillProperty().bind(javafx.beans.binding.Bindings.when(selectedProperty()).then(Color.WHITE).otherwise(entry.error ? Color.web("#a31432") : Color.web("#202020")));
                    time.textFillProperty().bind(javafx.beans.binding.Bindings.when(selectedProperty()).then(Color.web("#dddddd")).otherwise(Color.web("#686868")));
                    VBox row = new VBox(4, time, text); setGraphic(row);
                } else setTooltip(null);
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
        append(history, new Entry(time, text, error));
    }
    public static void append(ObservableList<Entry> history, Entry entry) {
        if (!history.isEmpty()) {
            Entry last = history.get(history.size() - 1);
            if (last.text.equals(entry.text) && last.seconds == entry.seconds && last.frame == entry.frame && last.error == entry.error) return;
        }
        history.add(entry); if (history.size() > MAX_ENTRIES) history.remove(0, history.size() - MAX_ENTRIES);
    }
}
