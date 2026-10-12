package com.phys.dungeonmiceapp.api;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.concurrent.*;

/** Blocking HTTP transport; call it from a background task, never the FX thread. */
public final class PythonVideoClient {
    private static final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "dungeonmice-api-deadlines"); thread.setDaemon(true); return thread;
    });
    static { deadlines.setRemoveOnCancelPolicy(true); }
    private final URI base;
    private final Duration timeout;
    private final HttpClient http;

    public PythonVideoClient(URI base) { this(base, Duration.ofMinutes(30)); }

    public PythonVideoClient(URI base, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("El timeout debe ser positivo.");
        this.base = localEndpoint(base);
        this.timeout = timeout;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    static URI localEndpoint(URI base) {
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("http".equals(base.getScheme()) || "https".equals(base.getScheme())))
            throw new IllegalArgumentException("La direccion de Python debe ser una URL HTTP o HTTPS.");
        // This first version is a local bridge, not an unauthenticated remote upload service.
        if (!("127.0.0.1".equals(base.getHost()) || "localhost".equalsIgnoreCase(base.getHost())
                || "[::1]".equals(base.getHost()) || "::1".equals(base.getHost())))
            throw new IllegalArgumentException("La API de Python debe ejecutarse en este equipo (localhost).");
        return URI.create(base.toString().replaceAll("/+$", "") + "/");
    }

    public Path process(Path source, Path destination, String videoId, String projectId,
                        JsonArray regions, Consumer<String> progress) throws IOException, InterruptedException {
        Thread caller = Thread.currentThread();
        Object gate = new Object(); boolean[] state = {true, false};
        ScheduledFuture<?> deadline = deadlines.schedule(() -> {
            synchronized (gate) { if (state[0]) { state[1] = true; caller.interrupt(); } }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try { return exchange(source, destination, videoId, projectId, regions, progress); }
        catch (IOException | InterruptedException ex) {
            synchronized (gate) {
                if (state[1]) throw new HttpTimeoutException("Python excedio el tiempo limite de procesamiento.");
            }
            throw ex;
        } finally {
            synchronized (gate) { state[0] = false; if (state[1]) Thread.interrupted(); }
            deadline.cancel(false);
        }
    }

    private Path exchange(Path source, Path destination, String videoId, String projectId,
                          JsonArray regions, Consumer<String> progress) throws IOException, InterruptedException {
        source = source.toRealPath();
        destination = destination.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || Files.size(source) == 0) throw new IOException("El video original no esta disponible.");
        if (Files.exists(destination)) throw new IOException("El archivo de resultado ya existe. Elige otro nombre.");
        Path parent = destination.getParent().toRealPath();
        destination = parent.resolve(destination.getFileName());
        JsonObject metadata = new JsonObject();
        metadata.addProperty("protocolVersion", 1);
        metadata.addProperty("videoId", videoId);
        metadata.addProperty("projectId", projectId);
        metadata.addProperty("filename", source.getFileName().toString());
        metadata.addProperty("sizeBytes", Files.size(source));
        metadata.add("regions", regions == null ? new JsonArray() : regions.deepCopy());
        progress.accept("Enviando metadatos a Python...");
        HttpRequest create = HttpRequest.newBuilder(base.resolve("api/v1/jobs")).timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(metadata.toString())).build();
        HttpResponse<InputStream> job = http.send(create, HttpResponse.BodyHandlers.ofInputStream());
        String id;
        try (InputStream body = job.body()) {
            byte[] bytes = body.readNBytes(65537);
            if (job.statusCode() != 201) throw responseError("Python rechazo los metadatos", job.statusCode(), bytes);
            if (bytes.length > 65536) throw new IOException("Respuesta de metadatos demasiado grande.");
            try {
                id = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().get("jobId").getAsString();
            } catch (RuntimeException ex) { throw new IOException("Respuesta de Python invalida: falta jobId.", ex); }
        }
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Identificador de trabajo invalido.");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Transferencia cancelada.");
        progress.accept("Enviando video y procesando en Python...");
        Path partial = Files.createTempFile(parent, ".dungeonmice-result-", ".part");
        ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "dungeonmice-api-progress"); thread.setDaemon(true); return thread;
        });
        URI statusUrl = base.resolve("api/v1/jobs/" + id + "/status");
        polling.scheduleWithFixedDelay(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(statusUrl).timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    byte[] bytes = body.readNBytes(4097);
                    if (response.statusCode() != 200 || bytes.length > 4096) return;
                    JsonElement message = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().get("message");
                    if (message != null && message.isJsonPrimitive() && message.getAsJsonPrimitive().isString()) progress.accept(message.getAsString());
                }
            } catch (IOException | InterruptedException | RuntimeException ignored) { /* Optional for older API servers. */ }
        }, 1, 1, TimeUnit.SECONDS);
        try {
            HttpRequest upload = HttpRequest.newBuilder(base.resolve("api/v1/jobs/" + id + "/video")).timeout(timeout)
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofFile(source)).build();
            // The HTTP implementation streams both files; the complete video never becomes a byte[].
            HttpResponse<Void> result = download(upload, partial);
            if (result.statusCode() != 200) {
                try (InputStream error = Files.newInputStream(partial)) { throw responseError("Python no pudo procesar el video", result.statusCode(), error.readNBytes(65537)); }
            }
            String type = result.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
            if (!(type.startsWith("video/") || type.startsWith("application/octet-stream")))
                throw new IOException("Python no devolvio un archivo de video.");
            if (Files.size(partial) == 0) throw new IOException("Python devolvio un video vacio.");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Transferencia cancelada.");
            Files.move(partial, destination); // Never replace the original or an existing result.
            progress.accept("Video recibido: " + destination);
            return destination;
        } finally { polling.shutdownNow(); Files.deleteIfExists(partial); }
    }

    private HttpResponse<Void> download(HttpRequest request, Path partial) throws IOException, InterruptedException {
        Object gate = new Object(); boolean[] closed = {false};
        OutputStream output = Files.newOutputStream(partial);
        try {
            // Own the file handle so a late HTTP callback cannot recreate a deleted
            // partial file after cancellation (BodyHandlers.ofFile can do this).
            return http.send(request, HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk -> chunk.ifPresent(bytes -> {
                synchronized (gate) {
                    if (closed[0]) return;
                    try { output.write(bytes); } catch (IOException ex) { throw new UncheckedIOException(ex); }
                }
            })));
        } finally { synchronized (gate) { closed[0] = true; output.close(); } }
    }

    private static IOException responseError(String message, int code, byte[] body) {
        String detail = "";
        if (body.length <= 65536) try {
            JsonElement error = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject().get("error");
            if (error != null && error.isJsonPrimitive() && error.getAsJsonPrimitive().isString()) detail = ": " + error.getAsString().substring(0, Math.min(600, error.getAsString().length()));
        } catch (RuntimeException ignored) { /* Non-JSON errors retain their HTTP status. */ }
        return new IOException(message + " (HTTP " + code + ")" + detail);
    }
}
