package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.ui.AnalysisReports;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Reject ZIP traversal, malformed metadata and partial publication. */
public final class ReportArchiveChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("report-archive-check-");
        try {
            for (String name : Arrays.asList("../outside.json", "/absolute.xlsx", "C:outside.xlsx", "reports/inside.json", "file.exe")) {
                Path destination = root.resolve("reports");
                try { AnalysisReports.store(destination, zip(name, "invalid")); throw new AssertionError("Invalid entry accepted: " + name); }
                catch (IOException expected) { if (Files.exists(destination)) throw new AssertionError("Partial report published"); }
            }
            try { AnalysisReports.store(root.resolve("reports"), zip("report.json", "{}")); throw new AssertionError("Malformed report accepted"); }
            catch (IOException expected) { }
            try (java.util.stream.Stream<Path> files = Files.list(root)) { if (files.count() != 0) throw new AssertionError("Temporary report files leaked"); }
            System.out.println("PASS Report archives reject traversal and invalid content without publishing or leaking files");
        } finally { Files.delete(root); }
    }
    private static byte[] zip(String name, String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry(); }
        return bytes.toByteArray();
    }
}
