package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.*;
import com.phys.dungeonmiceapp.model.Video;
import com.phys.dungeonmiceapp.ui.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Complete real frame analysis, preserve native reports and export after Python exits. */
public final class NativeReportChecks {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[2]).toAbsolutePath(); Files.createDirectories(output);
        System.setProperty("dungeonmice.events.directory", output.resolve("events").toString());
        Path source = FrameApiChecks.source(args[0]); byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
        JsonArray regions = JsonParser.parseString(Files.readString(Paths.get(args[1]))).getAsJsonArray();
        Video video = new Video("report-video", source.getFileName().toString(), source.toString(), "project", "report-owner", new Date());
        EventFrameArchive archive = new EventFrameArchive(video.getOwnerId(), video.getId(), source, regions);
        ManagedPythonBridge bridge = new ManagedPythonBridge(System.err::println);
        try {
            try (PythonFrameClient client = new PythonFrameClient(bridge.start().get(30, TimeUnit.SECONDS), bridge.frameToken())) {
                JsonObject info = client.open(source, regions); int count = 0; PythonFrameClient.Frame frame;
                try { client.reports(); throw new AssertionError("Premature report accepted"); } catch (java.io.IOException expected) { check(expected.getMessage().contains("finalizar"), "Reports unavailable during analysis"); }
                while ((frame = client.frame(-1)) != null) { archive.capture(frame); count++; }
                check(count == info.get("total").getAsInt(), "All original frames analyzed before reports");
                byte[] reports = client.reports(); check(Arrays.equals(reports, client.reports()), "Repeated report retrieval does not reanalyze video");
                archive.saveReports(reports); archive.finish("completed");
            }
        } finally { bridge.close(); }
        List<AnalysisReports.Report> stored = AnalysisReports.find(video); check(stored.size() == 1, "Completed native Excel persists after Python shutdown");
        Video other = new Video(video.getId(), "other", source.toString(), "project", "different-owner", new Date());
        check(AnalysisReports.find(other).isEmpty(), "Reports are isolated by owner");
        AnalysisReports.Report report = stored.get(0); Path pdf = output.resolve("native-report.pdf"); ReportPdf.write(report, pdf);
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(document);
            check(text.contains("Dungeon Mice") && text.contains(source.getFileName().toString()), "PDF contains actual native workbook and video identity");
            check(document.getNumberOfPages() >= 3, "PDF includes native trajectory and heatmap pages");
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++)
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(page, 110), "png", output.resolve(page == 0 ? "report-preview.png" : "report-page-" + (page + 1) + ".png").toFile());
        }
        Files.copy(report.workbook(), output.resolve("native-report.xlsx"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(output.resolve("report.json"), report.metadata.toString());
        check(Arrays.equals(hash, java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))), "Native reports do not alter source video");
        System.out.println("Report artifacts: " + output);
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); System.out.println("PASS " + text); }
}
