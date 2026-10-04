package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.PythonVideoClient;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class PythonApiChecks {
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }
    private static byte[] hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] block = new byte[65536]; int count;
            while ((count = in.read(block)) >= 0) digest.update(block, 0, count);
        }
        return digest.digest();
    }
    private static void fails(Callable<?> action, String name) throws Exception {
        try { action.call(); } catch (IOException | IllegalArgumentException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }
    private static Path videoPath(String argument) throws IOException {
        Path path = Paths.get(argument);
        if (!Files.isDirectory(path)) return path.toRealPath();
        try (java.util.stream.Stream<Path> files = Files.list(path)) {
            return files.filter(Files::isRegularFile).filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".mov"))
                    .findFirst().orElseThrow(() -> new IOException("No MOV videos in directory"));
        }
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("dungeonmice-api-checks-");
        ExecutorService workers = Executors.newCachedThreadPool();
        HttpServer server = null;
        try {
            Path source = args.length > 1 ? videoPath(args[1]) : temp.resolve("D\u00eda con espacios.mov");
            if (args.length < 2) try (OutputStream out = Files.newOutputStream(source)) {
                byte[] block = new byte[65536]; new Random(42).nextBytes(block);
                for (int i = 0; i < 128; i++) out.write(block);
            }
            byte[] originalHash = hash(source);
            JsonArray regions = JsonParser.parseString("[{\"id\":\"zone-1\",\"points\":[{\"x\":10,\"y\":20}]}]").getAsJsonArray();
            if (args.length > 0) {
                Path result = temp.resolve("python-result.mov");
                new PythonVideoClient(URI.create(args[0])).process(source, result, "video-1", "project-1", regions, System.out::println);
                check(Arrays.equals(originalHash, hash(result)), "Python roundtrip preserves exact video bytes");
                return;
            }
            AtomicReference<String> mode = new AtomicReference<>("ok");
            AtomicReference<Throwable> handlerError = new AtomicReference<>();
            CountDownLatch downloadStarted = new CountDownLatch(1);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/api/v1/jobs", exchange -> {
                try {
                    if (exchange.getRequestMethod().equals("POST")) {
                        JsonObject data = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        check(data.get("filename").getAsString().equals(source.getFileName().toString()), "Filename metadata");
                        check(data.get("sizeBytes").getAsLong() == Files.size(source), "Video size metadata");
                        check(data.get("videoId").getAsString().equals("video-1") && data.get("projectId").getAsString().equals("project-1"), "Video and project IDs");
                        check(data.get("regions").equals(regions), "Regions JSON transported");
                        check(!data.has("filePath") && !data.has("password"), "No absolute path or credentials sent");
                        String json = mode.get().equals("bad-id") ? "{\"jobId\":\"../escape\"}" : "{\"jobId\":\"job-1\"}";
                        byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(201, body.length); exchange.getResponseBody().write(body);
                    } else {
                        check(exchange.getRequestURI().getPath().equals("/api/v1/jobs/job-1/video"), "Upload endpoint");
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] block = new byte[65536]; int count;
                        while ((count = exchange.getRequestBody().read(block)) >= 0) digest.update(block, 0, count);
                        check(Arrays.equals(originalHash, digest.digest()), "Original uploaded without conversion");
                        String current = mode.get();
                        if (current.equals("http-error")) { exchange.sendResponseHeaders(500, -1); return; }
                        exchange.getResponseHeaders().set("Content-Type", current.equals("json") ? "application/json" : "video/mp4");
                        if (current.equals("empty")) { exchange.sendResponseHeaders(200, -1); return; }
                        exchange.sendResponseHeaders(200, Files.size(source));
                        if (current.equals("truncated")) { exchange.getResponseBody().write(new byte[10]); return; }
                        if (current.equals("slow") || current.equals("timeout")) {
                            exchange.getResponseBody().write(new byte[1024]); exchange.getResponseBody().flush();
                            if (current.equals("slow")) downloadStarted.countDown(); Thread.sleep(2500); return;
                        }
                        try (InputStream in = Files.newInputStream(source)) { in.transferTo(exchange.getResponseBody()); }
                    }
                } catch (Throwable error) {
                    if (!(error instanceof IOException) && !(error instanceof InterruptedException)) handlerError.set(error);
                } finally { exchange.close(); }
            });
            server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            PythonVideoClient client = new PythonVideoClient(endpoint, Duration.ofSeconds(5));
            Path result = temp.resolve("result.mp4");
            client.process(source, result, "video-1", "project-1", regions, message -> {});
            check(Arrays.equals(originalHash, hash(result)), "Result streamed intact to chosen location");
            fails(() -> client.process(source, source, "video-1", "project-1", regions, message -> {}), "Cannot overwrite original");
            fails(() -> client.process(source, result, "video-1", "project-1", regions, message -> {}), "Cannot overwrite existing result");
            fails(() -> new PythonVideoClient(URI.create("http://example.com")), "Remote upload disabled");
            for (String failure : new String[]{"http-error", "json", "empty", "truncated", "bad-id"}) {
                mode.set(failure); Path failedResult = temp.resolve(failure + ".mp4");
                fails(() -> client.process(source, failedResult, "video-1", "project-1", regions, message -> {}), "Reject " + failure);
                check(!Files.exists(failedResult), "No result after " + failure);
            }
            mode.set("timeout"); long timeoutStart = System.nanoTime();
            fails(() -> new PythonVideoClient(endpoint, Duration.ofMillis(500)).process(source, temp.resolve("timeout.mp4"), "video-1", "project-1", regions, message -> {}), "Timeout includes downloading the response body");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timeoutStart) < 1800, "Stalled response interrupted promptly");
            mode.set("slow"); Path cancelled = temp.resolve("cancelled.mp4");
            Future<?> transfer = workers.submit(() -> {
                try { client.process(source, cancelled, "video-1", "project-1", regions, message -> {}); }
                catch (IOException | InterruptedException expected) { return; }
                throw new AssertionError("Cancelled transfer completed");
            });
            check(downloadStarted.await(5, TimeUnit.SECONDS), "Download started before cancellation");
            transfer.cancel(true);
            Thread.sleep(300);
            check(!Files.exists(cancelled), "Cancellation does not publish result");
            try (java.util.stream.Stream<Path> entries = Files.list(temp)) {
                check(entries.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "Partial downloads removed");
            }
            check(Arrays.equals(originalHash, hash(source)), "Original remains unchanged");
            if (handlerError.get() != null) throw new AssertionError("Server check failed", handlerError.get());
        } finally {
            if (server != null) server.stop(0);
            workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS);
            try (java.util.stream.Stream<Path> entries = Files.walk(temp)) {
                for (Path entry : (Iterable<Path>) entries.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(entry);
            }
        }
    }
}
