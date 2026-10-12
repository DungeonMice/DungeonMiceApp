package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

public final class FrameApiChecks {
    public static Path source(String argument) throws IOException {
        Path path = Paths.get(argument);
        if (!Files.isDirectory(path)) return path.toRealPath();
        try (java.util.stream.Stream<Path> files = Files.list(path)) { return files.filter(Files::isRegularFile).filter(file -> file.toString().toLowerCase(Locale.ROOT).endsWith(".mov")).sorted().findFirst().orElseThrow(() -> new IOException("No MOV video")); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); System.out.println("PASS " + message); }
    private static byte[] hash(Path source) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(source)) { byte[] block = new byte[65536]; int count; while ((count = input.read(block)) != -1) hash.update(block, 0, count); } return hash.digest();
    }
    private static JsonObject info(URI uri, String id, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri.resolve("/api/v1/frame-sessions/" + id)).header("X-DungeonMice-Token", token).GET().build();
        return JsonParser.parseString(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
    }
    public static void main(String[] args) throws Exception {
        Path source = source(args[0]); byte[] before = hash(source);
        JsonArray regions = JsonParser.parseString(Files.readString(Paths.get(args[1]))).getAsJsonArray();
        ManagedPythonBridge bridge = new ManagedPythonBridge(System.err::println); long pid;
        try {
            URI uri = bridge.start().get(30, TimeUnit.SECONDS); pid = bridge.pid();
            try (PythonFrameClient client = new PythonFrameClient(uri, bridge.frameToken())) {
                JsonObject open = client.open(source, regions); String id = open.get("sessionId").getAsString();
                check(open.get("processed").getAsInt() == 0, "Opening a session does not analyze frames");
                Thread.sleep(300); check(info(uri, id, bridge.frameToken()).get("processed").getAsInt() == 0, "No demand means no processing");
                PythonFrameClient.Frame first = client.frame(-1);
                check(first.metadata.get("index").getAsInt() == 1, "next processes exactly one frame");
                java.awt.image.BufferedImage image = ImageIO.read(new ByteArrayInputStream(first.image));
                check(image != null && image.getWidth() == open.get("width").getAsInt() && image.getHeight() == open.get("height").getAsInt(), "Rendered frame decodes at original dimensions");
                check(first.metadata.getAsJsonObject("regions").size() == regions.size(), "Region state accompanies the image");
                check(first.views.keySet().equals(new HashSet<>(Arrays.asList("video", "detection", "trajectory"))), "Video, detection and trajectory travel in one synchronized frame packet");
                for (byte[] bytes : first.views.values()) { java.awt.image.BufferedImage view = ImageIO.read(new ByteArrayInputStream(bytes)); check(view != null && view.getWidth() == image.getWidth() && view.getHeight() == image.getHeight(), "Auxiliary view decodes at source resolution"); }
                Thread.sleep(300); check(info(uri, id, bridge.frameToken()).get("processed").getAsInt() == 1, "Pausing leaves analysis suspended");
                check(Arrays.equals(first.image, client.frame(1).image), "Cached frame is returned without reanalysis");
                int count = Math.min((int) (open.get("fps").getAsDouble() * 26) + 1, open.get("total").getAsInt());
                PythonFrameClient.Frame last = first;
                for (int i = 2; i <= count; i++) {
                    last = client.frame(-1);
                    if (last.metadata.get("index").getAsInt() != i) throw new AssertionError("Skipped frame " + i);
                    if (i % 100 == 0) System.out.println("PASS Sequential demand through frame " + i);
                }
                check(last.metadata.get("recordingActive").getAsBoolean(), "Native engine advances through warmup into region analysis");
                JsonObject after = info(uri, id, bridge.frameToken());
                check(after.get("processed").getAsInt() == count && after.get("cachedBytes").getAsLong() <= 32L * 1024 * 1024, "Cache remains byte bounded");
                if (count > 120) {
                    check(after.get("firstCached").getAsInt() > 1, "Full-frame cache does not accumulate indefinitely");
                    try { client.frame(1); throw new AssertionError("Evicted frame unexpectedly returned"); } catch (IOException expected) { check(true, "Evicted frame returns a clear error"); }
                }
                HttpRequest forbidden = HttpRequest.newBuilder(uri.resolve("/api/v1/frame-sessions/" + id + "/next")).GET().build();
                check(HttpClient.newHttpClient().send(forbidden, HttpResponse.BodyHandlers.discarding()).statusCode() == 403, "Local path API rejects requests without the application token");
            }
            try (PythonFrameClient another = new PythonFrameClient(uri, bridge.frameToken())) { check(another.open(source, regions).get("processed").getAsInt() == 0, "Closing releases slot for another video"); }
            java.awt.image.BufferedImage shapeImage;
            try (PythonFrameClient dimensions = new PythonFrameClient(uri, bridge.frameToken())) { dimensions.open(source, regions); shapeImage = ImageIO.read(new ByteArrayInputStream(dimensions.frame(-1).image)); }
            JsonArray pool = new JsonArray(); pool.add(com.phys.dungeonmiceapp.model.VideoRegion.morris("plataforma", "#00874b", new javafx.geometry.Point2D(shapeImage.getWidth() / 2.0, shapeImage.getHeight() / 2.0), Math.min(shapeImage.getWidth(), shapeImage.getHeight()) / 4.0, 270, .75, shapeImage.getWidth(), shapeImage.getHeight()).toJson());
            try (PythonFrameClient morris = new PythonFrameClient(uri, bridge.frameToken())) {
                check(morris.open(source, pool).get("experiment").getAsString().equals("MorrisPool"), "Morris quadrant selects native Morris engine instead of CrossMaze");
                PythonFrameClient.Frame frame = morris.frame(-1); check(frame.views.size() == 3 && frame.metadata.getAsJsonObject("regions").size() == 1, "Native Morris session renders all views with one region");
            }
            check(Arrays.equals(before, hash(source)), "Original video remains unchanged");
        } finally { bridge.close(); }
        check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Owned Python service shuts down");
    }
}
