package com.phys.dungeonmiceapp.ui;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Paginated PDF of the native workbook data and Python's trajectory/heatmap. */
public final class ReportPdf {
    private ReportPdf() { }
    public static void write(AnalysisReports.Report report, Path destination) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (JsonElement item : report.metadata.getAsJsonArray("sheets")) {
                JsonObject sheet = item.getAsJsonObject(); JsonArray rows = sheet.getAsJsonArray("rows");
                int columns = 1;
                for (JsonElement row : rows) {
                    JsonArray cells = row.getAsJsonArray();
                    for (int i = 0; i < cells.size(); i++) if (!cells.get(i).getAsString().isEmpty()) columns = Math.max(columns, i + 1);
                }
                if (columns > 40) throw new IOException("Demasiadas columnas para exportar el PDF");
                PDRectangle size = columns > 6 ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
                float[] widths = new float[columns]; Arrays.fill(widths, 6);
                for (JsonElement row : rows) {
                    JsonArray cells = row.getAsJsonArray();
                    for (int i = 0; i < Math.min(columns, cells.size()); i++) widths[i] = Math.max(widths[i], Math.min(26, cells.get(i).getAsString().length()));
                }
                float sum = 0; for (float width : widths) sum += width;
                for (int i = 0; i < columns; i++) widths[i] *= (size.getWidth() - 64) / sum;
                try (Table table = new Table(document, size, font, report.metadata.get("sourceName").getAsString(), sheet.get("name").getAsString())) {
                    for (JsonElement row : rows) table.row(row.getAsJsonArray(), widths);
                }
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(report.directory, "*.png")) {
                for (Path file : files) {
                    PDPage page = new PDPage(PDRectangle.A4); document.addPage(page);
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        text(stream, font, 14, 32, 805, file.getFileName().toString());
                        PDImageXObject image = PDImageXObject.createFromFileByContent(file.toFile(), document);
                        float scale = Math.min(530f / image.getWidth(), 730f / image.getHeight());
                        stream.drawImage(image, 32, 50, image.getWidth() * scale, image.getHeight() * scale);
                    }
                }
            }
            document.save(destination.toFile());
        }
    }
    private static String printable(PDFont font, String value) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int code : value.replace('\t', ' ').codePoints().toArray()) {
            String letter = new String(Character.toChars(code));
            if (code < 32) { text.append(' '); continue; }
            try { font.encode(letter); text.append(letter); } catch (IllegalArgumentException ex) { text.append('?'); }
        }
        return text.toString();
    }
    private static void text(PDPageContentStream out, PDFont font, float size, float x, float y, String text) throws IOException {
        out.beginText(); out.setFont(font, size); out.newLineAtOffset(x, y); out.showText(printable(font, text)); out.endText();
    }
    private static final class Table implements AutoCloseable {
        private final PDDocument document; private final PDRectangle size; private final PDFont font; private final String source, sheet;
        private PDPageContentStream out; private float y;
        Table(PDDocument document, PDRectangle size, PDFont font, String source, String sheet) throws IOException {
            this.document = document; this.size = size; this.font = font; this.source = source; this.sheet = sheet; page();
        }
        private void page() throws IOException {
            if (out != null) close();
            PDPage page = new PDPage(size); document.addPage(page); out = new PDPageContentStream(document, page);
            text(out, font, 14, 32, size.getHeight() - 36, "Dungeon Mice - " + sheet);
            String label = printable(font, source);
            while (font.getStringWidth(label) / 1000 * 9 > size.getWidth() - 64) label = label.substring(0, label.length() - 1);
            text(out, font, 9, 32, size.getHeight() - 53, label); y = size.getHeight() - 75;
        }
        private List<String> wrap(String value, float width) throws IOException {
            List<String> lines = new ArrayList<>(); StringBuilder line = new StringBuilder();
            for (char ch : printable(font, value).toCharArray()) {
                if (line.length() > 0 && font.getStringWidth(line.toString() + ch) * .008f > width - 8) { lines.add(line.toString()); line.setLength(0); }
                line.append(ch);
            }
            lines.add(line.toString()); return lines;
        }
        void row(JsonArray cells, float[] widths) throws IOException {
            List<List<String>> lines = new ArrayList<>(); int height = 1;
            for (int i = 0; i < widths.length; i++) { List<String> column = wrap(i < cells.size() ? cells.get(i).getAsString() : "", widths[i]); lines.add(column); height = Math.max(height, column.size()); }
            for (int line = 0; line < height; line++) {
                if (y < 48) page();
                float x = 32;
                for (int column = 0; column < widths.length; column++) {
                    if (line < lines.get(column).size()) text(out, font, 8, x + 4, y, lines.get(column).get(line));
                    x += widths[column];
                }
                y -= 11;
            }
            out.setStrokingColor(.8f); out.setLineWidth(.3f); out.moveTo(32, y - 2); out.lineTo(size.getWidth() - 32, y - 2); out.stroke(); y -= 7;
        }
        @Override public void close() throws IOException {
            if (out != null) { text(out, font, 8, 32, 24, "Dungeon Mice | " + document.getNumberOfPages()); out.close(); out = null; }
        }
    }
}
