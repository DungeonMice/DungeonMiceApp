package com.phys.dungeonmiceapp.ui;

import com.google.gson.*;
import com.phys.dungeonmiceapp.model.Video;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.StringConverter;

/** Persistent workbook preview and exports, independent of the Python connection. */
public final class ReportPane extends BorderPane {
    private final Stage stage; private final ExecutorService worker;
    private final ComboBox<Video> videos = new ComboBox<>();
    private final ComboBox<AnalysisReports.Report> reports = new ComboBox<>();
    private final ComboBox<String> sheets = new ComboBox<>();
    private final TableView<List<String>> table = new TableView<>();
    private final Label status = new Label();
    private final Button excel = new Button("Guardar Excel"), pdf = new Button("Exportar PDF");
    private final ProgressIndicator progress = new ProgressIndicator();
    private long generation;

    public ReportPane(Stage stage, List<Video> sources, ExecutorService worker) {
        this.stage = stage; this.worker = worker; setPadding(new Insets(24)); setId("reports-page");
        videos.setId("report-videos"); reports.setId("report-runs"); sheets.setId("report-sheets"); table.setId("report-preview");
        status.setId("report-status"); status.setWrapText(true); status.setMaxWidth(Double.MAX_VALUE);
        excel.setId("report-excel"); pdf.setId("report-pdf"); excel.setDisable(true); pdf.setDisable(true);
        videos.setConverter(new StringConverter<Video>() { public String toString(Video video) { return video == null ? "" : video.getTitle(); } public Video fromString(String text) { return null; } });
        reports.setConverter(new StringConverter<AnalysisReports.Report>() {
            public String toString(AnalysisReports.Report report) { return report == null ? "" : java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").format(java.time.Instant.ofEpochMilli(report.directory.toFile().lastModified()).atZone(java.time.ZoneId.systemDefault())); }
            public AnalysisReports.Report fromString(String text) { return null; }
        });
        for (ComboBox<?> box : new ComboBox<?>[]{videos, reports, sheets}) { box.setMaxWidth(Double.MAX_VALUE); box.setMinWidth(80); HBox.setHgrow(box, Priority.ALWAYS); }
        videos.setPromptText("Video"); reports.setPromptText("Analisis"); sheets.setPromptText("Hoja");
        progress.setMaxSize(24, 24); progress.setVisible(false); progress.managedProperty().bind(progress.visibleProperty());
        Button refresh = new Button("Actualizar"); refresh.setId("report-refresh"); refresh.setOnAction(event -> load());
        for (Button button : new Button[]{excel, pdf, refresh}) { button.getStyleClass().add(button == pdf ? "primary" : "outline"); button.setPrefWidth(150); }
        HBox filters = new HBox(10, videos, reports, sheets); HBox actions = new HBox(10, excel, pdf, refresh, progress);
        Label title = new Label("Reportes de analisis"); title.getStyleClass().add("section-heading");
        VBox header = new VBox(12, title, filters, actions, status); header.setPadding(new Insets(0, 0, 16, 0)); setTop(header); setCenter(table);
        table.setPlaceholder(new Label("Sin reportes para este video")); table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        videos.valueProperty().addListener((obs, old, value) -> load());
        reports.valueProperty().addListener((obs, old, report) -> {
            sheets.getItems().clear(); excel.setDisable(report == null); pdf.setDisable(report == null);
            if (report != null) { for (JsonElement sheet : report.metadata.getAsJsonArray("sheets")) sheets.getItems().add(sheet.getAsJsonObject().get("name").getAsString()); sheets.getSelectionModel().selectFirst(); }
        });
        sheets.valueProperty().addListener((obs, old, value) -> preview());
        excel.setOnAction(event -> export(false)); pdf.setOnAction(event -> export(true));
        videos.getItems().setAll(sources); videos.getSelectionModel().selectFirst();
    }
    private void load() {
        long ticket = ++generation; Video video = videos.getValue(); reports.getItems().clear(); table.getItems().clear(); table.getColumns().clear();
        if (video == null) { status.setText("Selecciona un proyecto con videos"); return; }
        run(() -> AnalysisReports.find(video), found -> {
            if (ticket != generation) return;
            reports.getItems().setAll(found); reports.getSelectionModel().selectFirst();
            status.setText(found.isEmpty() ? "Sin reportes. Disponibles al completar un analisis por frames." : found.size() + " analisis guardados");
        });
    }
    private void preview() {
        table.getItems().clear(); table.getColumns().clear(); AnalysisReports.Report report = reports.getValue();
        if (report == null || sheets.getValue() == null) return;
        for (JsonElement item : report.metadata.getAsJsonArray("sheets")) {
            JsonObject sheet = item.getAsJsonObject(); if (!sheets.getValue().equals(sheet.get("name").getAsString())) continue;
            List<List<String>> rows = new ArrayList<>(); int columns = 0;
            for (JsonElement row : sheet.getAsJsonArray("rows")) {
                List<String> values = new ArrayList<>(); for (JsonElement cell : row.getAsJsonArray()) values.add(cell.getAsString());
                while (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) values.remove(values.size() - 1);
                columns = Math.max(columns, values.size()); rows.add(values);
            }
            for (int i = 0; i < columns; i++) {
                final int index = i; TableColumn<List<String>, String> column = new TableColumn<>("" + (i + 1)); column.setPrefWidth(i == 0 ? 240 : 140);
                column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(index < cell.getValue().size() ? cell.getValue().get(index) : "")); table.getColumns().add(column);
            }
            table.getItems().setAll(rows);
        }
    }
    private void export(boolean asPdf) {
        AnalysisReports.Report report = reports.getValue(); if (report == null) return;
        FileChooser chooser = new FileChooser(); chooser.setTitle(asPdf ? "Exportar PDF" : "Guardar Excel");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(asPdf ? "PDF" : "Excel", asPdf ? "*.pdf" : "*.xlsx"));
        chooser.setInitialFileName(asPdf ? "reporte.pdf" : report.workbook().getFileName().toString());
        File selected = chooser.showSaveDialog(stage); if (selected == null) return;
        String suffix = asPdf ? ".pdf" : ".xlsx";
        Path destination = selected.getName().toLowerCase(Locale.ROOT).endsWith(suffix) ? selected.toPath() : selected.toPath().resolveSibling(selected.getName() + suffix);
        if (destination.toAbsolutePath().normalize().startsWith(report.directory.toAbsolutePath().normalize())) { status.setText("Selecciona una ubicacion fuera del archivo del analisis"); return; }
        run(() -> {
            Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), ".report-export-", asPdf ? ".pdf" : ".xlsx");
            try {
                if (asPdf) ReportPdf.write(report, temporary); else Files.copy(report.workbook(), temporary, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            return destination;
        }, path -> status.setText("Guardado: " + path));
    }
    private <T> void run(java.util.concurrent.Callable<T> action, java.util.function.Consumer<T> success) {
        progress.setVisible(true); excel.setDisable(true); pdf.setDisable(true);
        Task<T> task = new Task<T>() { @Override protected T call() throws Exception { return action.call(); } };
        task.setOnSucceeded(event -> { progress.setVisible(false); excel.setDisable(reports.getValue() == null); pdf.setDisable(reports.getValue() == null); success.accept(task.getValue()); });
        task.setOnFailed(event -> { progress.setVisible(false); excel.setDisable(reports.getValue() == null); pdf.setDisable(reports.getValue() == null); status.setText("No se pudo completar: " + task.getException().getMessage()); }); worker.submit(task);
    }
}
