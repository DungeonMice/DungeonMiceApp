package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.*;
import com.phys.dungeonmiceapp.ui.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Exercise event snapshots beyond cache eviction using a full, external source video. */
public final class FlagNativeChecks {
    private static void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    private static byte[] hash(Path file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) { byte[] block = new byte[65536]; int read; while ((read = in.read(block)) >= 0) hash.update(block, 0, read); }
        return hash.digest();
    }
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("dungeonmice-flag-native-");
        System.setProperty("dungeonmice.events.directory", temporary.resolve("events").toString());
        Path source = FrameApiChecks.source(args[0]); byte[] before = hash(source);
        ManagedPythonBridge bridge = new ManagedPythonBridge(System.err::println);
        try {
            JsonArray regions = JsonParser.parseString(Files.readString(Paths.get(args[1]))).getAsJsonArray();
            EventFrameArchive archive = new EventFrameArchive("native-owner", "native-video", source, regions);
            ActivityLog.Entry first = null, end; int count = 0, total;
            byte[] firstImage = null;
            try (PythonFrameClient client = new PythonFrameClient(bridge.start().get(30, TimeUnit.SECONDS), bridge.frameToken())) {
                JsonObject info = client.open(source, regions); total = info.get("total").getAsInt();
                PythonFrameClient.Frame last = null, frame;
                while ((frame = client.frame(-1)) != null) {
                    if (frame.metadata.get("index").getAsInt() != ++count) throw new AssertionError("Out of order frame");
                    List<ActivityLog.Entry> events = archive.capture(frame);
                    if (first == null) { first = events.get(0); firstImage = frame.image; }
                    last = frame;
                }
                check(count == total && count > 120 && last.metadata.get("firstCached").getAsInt() > 1, "Real video completes after the first event has left the rolling cache: " + count + " frames");
                end = archive.mark(last, "Analisis completado", false); archive.finish("completed");
            }
            bridge.close();
            PythonFrameClient.Frame stored = EventFrameArchive.load("native-owner", "native-video", first);
            check(Arrays.equals(stored.image, firstImage), "Evicted first event survives analyzer cleanup byte for byte");
            check(EventFrameArchive.load("native-owner", "native-video", end).views.size() == 3, "Final event retains all native annotated views");
            try (java.util.stream.Stream<Path> files = Files.walk(temporary)) { check(files.filter(path -> path.toString().endsWith(".frame")).count() < count, "Persistent archive stores sparse events instead of all video frames"); }
            check(Arrays.equals(hash(source), before), "Full native analysis leaves the original source unchanged");
        } finally {
            bridge.close();
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
        }
    }
}
