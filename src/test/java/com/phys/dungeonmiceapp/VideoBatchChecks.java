package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.*;
import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.geometry.Point2D;

public final class VideoBatchChecks {
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); System.out.println("PASS " + name); }
    private static VideoRegion region(String name) { return new VideoRegion(name, "#00874b", Arrays.asList(new Point2D(10, 10), new Point2D(80, 10), new Point2D(80, 80)), .8, 640, 360); }
    private static JsonArray payload(Video video) { JsonArray json = new JsonArray(); for (VideoRegion region : VideoRepository.findRegions(video.getId())) json.add(region.toJson()); return json; }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("dungeonmice-batch-check-"); HttpServer server = null;
        try {
            Path application = Files.createDirectory(temp.resolve("app")); Path output = Files.createDirectory(temp.resolve("results"));
            System.setProperty("dungeonmice.database", temp.resolve("data/database.enc").toString()); System.setProperty("dungeonmice.storage", application.toString());
            DatabaseManager.connect(); User admin = UserRepository.login("admin", "admin123"); SessionManager.setCurrentUser(admin);
            VideoGroup project = VideoGroupRepository.saveProject(null, "Batch", "", "", "", "", "");
            List<java.io.File> sources = new ArrayList<>();
            for (String name : new String[]{"same.mov", "same.mp4", "special.mov"}) { Path source = temp.resolve(name); Files.write(source, new byte[]{1, 2, 3}); sources.add(source.toFile()); }
            VideoRepository.addReferences(project.getId(), sources); List<Video> videos = VideoRepository.findByGroup(project.getId());
            VideoRepository.saveRegions(videos.get(0).getId(), Arrays.asList(region("norte"), region("sur")));
            VideoRepository.saveRegions(videos.get(2).getId(), Arrays.asList(region("especial-1"), region("especial-2")));
            check(VideoRepository.applyRegionsToGroup(project.getId(), videos.get(0).getId(), false) == 1, "Apply fills missing regions without replacing exceptions");
            check(payload(videos.get(2)).get(0).getAsJsonObject().get("id").getAsString().equals("especial-1"), "Special video retains its own regions");
            VideoRepository.saveRegions(videos.get(1).getId(), Arrays.asList(region("edited"), region("other")));
            check(payload(videos.get(0)).get(0).getAsJsonObject().get("id").getAsString().equals("norte"), "Copied regions remain independently editable");
            DatabaseManager.connect(); check(payload(videos.get(1)).get(0).getAsJsonObject().get("id").getAsString().equals("edited"), "Individual edits survive database reload");
            User other = UserRepository.register("batch-other", "password", "user", "", "", ""); SessionManager.setCurrentUser(other);
            try { VideoRepository.applyRegionsToGroup(project.getId(), videos.get(0).getId(), true); throw new AssertionError("Owner check missing"); }
            catch (IllegalArgumentException expected) { check(true, "Bulk copy rejects other owners"); } SessionManager.setCurrentUser(admin);
            Files.write(output.resolve("same-procesado.mp4"), new byte[]{9});
            Map<String, JsonArray> snapshots = new HashMap<>(); for (Video video : videos) snapshots.put(video.getId(), payload(video));
            List<VideoBatch.Item> plan = VideoBatch.plan(videos, snapshots, output, application);
            check(!plan.get(0).getDestination().equals(plan.get(1).getDestination()) && plan.get(0).getDestination().getFileName().toString().equals("same-procesado-2.mp4"), "Batch reserves unique paths and preserves existing files");
            snapshots.get(videos.get(1).getId()).remove(0); check(plan.get(1).getRegions().size() == 2, "Batch snapshots do not share mutable JSON");
            try { VideoBatch.plan(videos, snapshots, output, application); throw new AssertionError("Missing regions accepted"); }
            catch (IllegalArgumentException expected) { check(true, "Unconfigured videos blocked before starting a batch"); }
            try { VideoBatch.plan(videos, snapshots, application, application); throw new AssertionError("Internal output accepted"); }
            catch (java.io.IOException expected) { check(true, "Application directory cannot receive batch videos"); }
            AtomicInteger uploads = new AtomicInteger(); List<String> ids = new ArrayList<>(); List<JsonArray> received = new ArrayList<>();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/jobs", exchange -> {
                try {
                    byte[] body;
                    if (exchange.getRequestMethod().equals("POST")) {
                        JsonObject data = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        ids.add(data.get("videoId").getAsString()); received.add(data.getAsJsonArray("regions"));
                        body = ("{\"jobId\":\"job-" + ids.size() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(201, body.length);
                    } else if (exchange.getRequestURI().getPath().endsWith("/status")) {
                        body = "{\"message\":\"Procesando: 1/2 frames\"}".getBytes(); exchange.sendResponseHeaders(200, body.length);
                    } else {
                        exchange.getRequestBody().readAllBytes(); int count = uploads.incrementAndGet();
                        exchange.getResponseHeaders().set("Content-Type", count == 2 ? "application/json" : "video/mp4");
                        body = count == 2 ? "{\"error\":\"resolution mismatch\"}".getBytes() : new byte[]{4, 5, 6}; exchange.sendResponseHeaders(count == 2 ? 422 : 200, body.length);
                    }
                    exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            }); server.start();
            PythonVideoClient client = new PythonVideoClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort())); AtomicInteger failures = new AtomicInteger();
            int count = VideoBatch.run(plan, (item, report) -> client.process(item.getSource(), item.getDestination(), item.getVideo().getId(), project.getId(), item.getRegions(), report),
                (item, result) -> ProcessedVideoRepository.record(project.getId(), item.getVideo().getId(), result.toFile(), item.getRegions()), (item, error) -> failures.incrementAndGet(), text -> {});
            check(count == 2 && failures.get() == 1 && uploads.get() == 3, "Batch continues after one API failure");
            check(received.get(1).get(0).getAsJsonObject().get("id").getAsString().equals("edited") && received.get(2).get(0).getAsJsonObject().get("id").getAsString().equals("especial-1"), "Python receives each video's individual regions in order");
            check(ProcessedVideoRepository.findByProject(project.getId()).size() == 2, "Each successful result appears in the persistent library");
            check(!Files.exists(plan.get(1).getDestination()) && Files.readAllBytes(output.resolve("same-procesado.mp4"))[0] == 9, "Failed outputs and existing files handled safely");
            AtomicInteger started = new AtomicInteger();
            try { VideoBatch.run(plan, (item, report) -> { started.incrementAndGet(); throw new InterruptedException(); }, (item, result) -> {}, (item, error) -> {}, text -> {}); throw new AssertionError("Not cancelled"); }
            catch (InterruptedException expected) { check(started.get() == 1, "Cancellation stops before the next video"); }
            check(ProcessedVideoRepository.findByProject(project.getId()).size() == 2, "Cancellation preserves completed results");
            check(VideoRepository.applyRegionsToGroup(project.getId(), videos.get(0).getId(), true) == 2 && payload(videos.get(2)).equals(payload(videos.get(0))), "Explicit replacement applies template to all videos");
            for (java.io.File source : sources) check(Arrays.equals(Files.readAllBytes(source.toPath()), new byte[]{1, 2, 3}), "Original external video unchanged");
            check(!Files.exists(application.resolve("videos")), "No videos copied into the application");
        } finally {
            if (server != null) server.stop(0);
            SessionManager.logout();
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
        }
    }
}
