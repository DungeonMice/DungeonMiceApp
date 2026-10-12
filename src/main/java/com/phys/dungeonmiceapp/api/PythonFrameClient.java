package com.phys.dungeonmiceapp.api;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** One authenticated local session, one bounded image per request. */
public final class PythonFrameClient implements AutoCloseable {
    public static final class Frame {
        public final JsonObject metadata;
        public final byte[] image;
        public Frame(JsonObject metadata, byte[] image) { this.metadata = metadata; this.image = image; }
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI base;
    private final String token, id = UUID.randomUUID().toString().replace("-", "");
    private volatile boolean closed;

    public PythonFrameClient(URI address, String token) {
        this.base = PythonVideoClient.localEndpoint(address);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("La API de frames requiere la conexion automatica local de Python.");
        this.token = token;
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30)).header("X-DungeonMice-Token", token);
    }
    public JsonObject open(Path source, JsonArray regions) throws IOException, InterruptedException {
        if (closed) throw new IOException("Sesion cerrada");
        JsonObject body = new JsonObject(); body.addProperty("sessionId", id); body.addProperty("sourcePath", source.toRealPath().toString()); body.add("regions", regions.deepCopy());
        HttpRequest call = request("api/v1/frame-sessions").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try (InputStream input = send(call, 201)) {
            byte[] bytes = input.readNBytes(65537); if (bytes.length > 65536) throw new IOException("Metadatos demasiado grandes");
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    public Frame frame(int index) throws IOException, InterruptedException {
        if (closed) throw new IOException("Sesion cerrada");
        HttpRequest call = request("api/v1/frame-sessions/" + id + "/" + (index < 0 ? "next" : index)).GET().build();
        HttpResponse<InputStream> reply = http.send(call, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = reply.body()) {
            if (reply.statusCode() == 204) return null;
            if (reply.statusCode() != 200) throw failure(reply.statusCode(), input);
            if (!reply.headers().firstValue("Content-Type").orElse("").equals("application/vnd.dungeonmice.frame")) throw new IOException("Respuesta de frame invalida");
            DataInputStream data = new DataInputStream(input); int length = data.readInt();
            if (length <= 0 || length > 65536) throw new IOException("Metadatos de frame invalidos");
            byte[] json = new byte[length]; data.readFully(json);
            JsonObject metadata = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] image = data.readNBytes(32 * 1024 * 1024 + 1);
            if (image.length == 0 || image.length > 32 * 1024 * 1024) throw new IOException("Imagen de frame invalida");
            return new Frame(metadata, image);
        }
    }
    private InputStream send(HttpRequest call, int expected) throws IOException, InterruptedException {
        HttpResponse<InputStream> reply = http.send(call, HttpResponse.BodyHandlers.ofInputStream());
        if (reply.statusCode() == expected) return reply.body();
        try (InputStream input = reply.body()) { throw failure(reply.statusCode(), input); }
    }
    private IOException failure(int code, InputStream input) throws IOException {
        String detail = "";
        try { detail = JsonParser.parseString(new String(input.readNBytes(65536), StandardCharsets.UTF_8)).getAsJsonObject().get("error").getAsString(); }
        catch (RuntimeException ignored) { }
        return new IOException("Python (HTTP " + code + "): " + detail);
    }
    @Override public void close() {
        closed = true;
        try (InputStream ignored = send(request("api/v1/frame-sessions/" + id).DELETE().build(), 200)) { }
        catch (IOException | InterruptedException ignored) { if (ignored instanceof InterruptedException) Thread.currentThread().interrupt(); }
    }
}
