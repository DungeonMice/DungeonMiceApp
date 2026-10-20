package com.phys.dungeonmiceapp.api;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** One authenticated local session, one bounded image per request. */
public final class PythonFrameClient implements AutoCloseable {
    public static final class Frame {
        public final JsonObject metadata;
        public final byte[] image;
        public final Map<String, byte[]> views;
        public Frame(JsonObject metadata, byte[] payload) throws IOException {
            this.metadata = metadata; Map<String, byte[]> images = new LinkedHashMap<>();
            if (metadata.has("images")) {
                int next = 0;
                for (JsonElement item : metadata.getAsJsonArray("images")) {
                    JsonObject descriptor = item.getAsJsonObject(); String id = descriptor.get("id").getAsString(); int offset = descriptor.get("offset").getAsInt(), length = descriptor.get("length").getAsInt();
                    if (images.size() >= 3 || !Arrays.asList("video", "detection", "trajectory").contains(id) || images.containsKey(id) || offset != next || length <= 0 || (long) offset + length > payload.length) throw new IOException("Vista de frame invalida");
                    images.put(id, Arrays.copyOfRange(payload, offset, offset + length)); next += length;
                }
                if (next != payload.length || !images.containsKey("video")) throw new IOException("Paquete de vistas incompleto");
            } else images.put("video", payload);
            views = Collections.unmodifiableMap(images); image = views.get("video");
        }
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
    public byte[] reports() throws IOException, InterruptedException {
        HttpRequest call = request("api/v1/frame-sessions/" + id + "/reports").timeout(Duration.ofMinutes(3)).GET().build();
        try (InputStream input = send(call, 200)) {
            byte[] packet = input.readNBytes(64 * 1024 * 1024 + 1);
            if (packet.length == 0 || packet.length > 64 * 1024 * 1024) throw new IOException("Reporte demasiado grande o vacio");
            return packet;
        }
    }
    public void closeChecked() throws IOException, InterruptedException {
        closed = true;
        HttpResponse<InputStream> reply = http.send(request("api/v1/frame-sessions/" + id).DELETE().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = reply.body()) {
            // Opening may have failed before the session existed; cleanup is idempotent.
            if (reply.statusCode() != 200 && reply.statusCode() != 410) throw failure(reply.statusCode(), input);
        }
    }
    @Override public void close() {
        try { closeChecked(); }
        catch (IOException | InterruptedException ignored) { if (ignored instanceof InterruptedException) Thread.currentThread().interrupt(); }
    }
}
