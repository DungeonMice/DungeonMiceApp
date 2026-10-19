package com.phys.dungeonmiceapp.ui;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.PythonFrameClient;
import com.phys.dungeonmiceapp.config.AppPaths;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persistent event snapshots, independent of the analyzer's rolling preview cache. */
public final class EventFrameArchive {
    private static final int MAX_PACKET = 33 * 1024 * 1024;
    private final Path directory;
    private final String run = UUID.randomUUID().toString();
    private final JsonObject manifest = new JsonObject();
    private final JsonArray markers = new JsonArray();
    private JsonObject previous;
    private boolean finished;

    public EventFrameArchive(String owner, String video, Path source, JsonArray regions) throws IOException {
        directory = location(owner, video, run);
        Files.createDirectories(directory);
        manifest.addProperty("version", 1); manifest.addProperty("run", run);
        manifest.addProperty("owner", owner); manifest.addProperty("video", video);
        manifest.addProperty("createdAt", java.time.Instant.now().toString());
        manifest.addProperty("sourcePath", source.toRealPath().toString());
        manifest.addProperty("sourceSize", Files.size(source));
        manifest.addProperty("sourceModified", Files.getLastModifiedTime(source).toMillis());
        manifest.add("regions", regions.deepCopy()); manifest.add("events", markers);
        manifest.addProperty("state", "running"); saveManifest();
    }

    private static Path location(String owner, String video, String run) {
        for (String key : Arrays.asList(owner, video, run))
            if (key == null || !key.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Identificador de archivo de eventos invalido");
        String root = System.getProperty("dungeonmice.events.directory");
        Path base = root == null ? AppPaths.userDataDirectory().resolve("events") : Paths.get(root);
        return base.toAbsolutePath().normalize().resolve(owner).resolve(video).resolve(run);
    }

    public List<ActivityLog.Entry> capture(PythonFrameClient.Frame frame) throws IOException {
        JsonObject states = frame.metadata.getAsJsonObject("regions");
        List<ActivityLog.Entry> events = new ArrayList<>();
        if (previous == null) events.add(mark(frame, "Inicio del analisis", false));
        for (String name : states.keySet()) {
            boolean inside = states.getAsJsonObject(name).get("inside").getAsBoolean();
            if (previous != null && previous.has(name) && previous.getAsJsonObject(name).get("inside").getAsBoolean() != inside)
                events.add(mark(frame, name + ": " + (inside ? "entrada" : "salida"), false));
        }
        previous = states.deepCopy();
        return events;
    }

    public ActivityLog.Entry mark(PythonFrameClient.Frame frame, String text, boolean error) throws IOException {
        if (finished) throw new IOException("El archivo de eventos ya esta cerrado");
        int index = frame.metadata.get("index").getAsInt();
        double seconds = frame.metadata.get("time").getAsDouble();
        if (index < 1) throw new IOException("Frame de evento invalido");
        ActivityLog.Entry entry = new ActivityLog.Entry(seconds, index, text, error, run);
        Path snapshot = directory.resolve(index + ".frame");
        if (!Files.exists(snapshot)) {
            ByteArrayOutputStream payload = new ByteArrayOutputStream(); JsonArray images = new JsonArray();
            for (Map.Entry<String, byte[]> view : frame.views.entrySet()) {
                JsonObject image = new JsonObject(); image.addProperty("id", view.getKey());
                image.addProperty("offset", payload.size()); image.addProperty("length", view.getValue().length);
                images.add(image); payload.write(view.getValue());
            }
            JsonObject metadata = frame.metadata.deepCopy(); metadata.add("images", images);
            byte[] header = metadata.toString().getBytes(StandardCharsets.UTF_8);
            if (header.length > 65536 || (long) header.length + payload.size() + 4 > MAX_PACKET) throw new IOException("Captura de evento demasiado grande");
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(packet)) { out.writeInt(header.length); out.write(header); payload.writeTo(out); }
            writeAtomic(snapshot, packet.toByteArray());
        }
        markers.add(entry.toJson()); saveManifest();
        return entry;
    }

    public void finish(String state) throws IOException {
        if (finished) return;
        manifest.addProperty("state", state); manifest.addProperty("finishedAt", java.time.Instant.now().toString());
        saveManifest(); finished = true;
    }

    private void saveManifest() throws IOException { writeAtomic(directory.resolve("markers.json"), manifest.toString().getBytes(StandardCharsets.UTF_8)); }
    private static void writeAtomic(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".event-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static PythonFrameClient.Frame load(String owner, String video, ActivityLog.Entry entry) throws IOException {
        if (entry.archive == null || entry.frame < 1) throw new IOException("El registro no tiene una captura guardada");
        Path directory = location(owner, video, entry.archive);
        Path file = directory.resolve(entry.frame + ".frame");
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_PACKET) throw new IOException("La captura del evento no esta disponible");
        Path markerFile = directory.resolve("markers.json");
        if (!Files.isRegularFile(markerFile) || Files.size(markerFile) > 32 * 1024 * 1024) throw new IOException("El archivo de marcadores no esta disponible");
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(markerFile)).getAsJsonObject();
            if (!owner.equals(manifest.get("owner").getAsString()) || !video.equals(manifest.get("video").getAsString()) || !entry.archive.equals(manifest.get("run").getAsString())) throw new IOException("El marcador pertenece a otro analisis");
            boolean found = false;
            for (JsonElement item : manifest.getAsJsonArray("events")) {
                JsonObject saved = item.getAsJsonObject();
                if (saved.get("frame").getAsInt() == entry.frame && Double.compare(saved.get("seconds").getAsDouble(), entry.seconds) == 0 && saved.get("text").getAsString().equals(entry.text)) { found = true; break; }
            }
            if (!found) throw new IOException("Marcador de evento no encontrado");
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                int length = input.readInt(); if (length < 1 || length > 65536) throw new IOException("Cabecera de evento invalida");
                byte[] header = new byte[length]; input.readFully(header);
                JsonObject metadata = JsonParser.parseString(new String(header, StandardCharsets.UTF_8)).getAsJsonObject();
                if (metadata.get("index").getAsInt() != entry.frame || Double.compare(metadata.get("time").getAsDouble(), entry.seconds) != 0) throw new IOException("Captura y marcador no coinciden");
                byte[] payload = input.readNBytes(MAX_PACKET + 1); if (payload.length < 1 || payload.length > MAX_PACKET) throw new IOException("Captura de evento invalida");
                return new PythonFrameClient.Frame(metadata, payload);
            }
        } catch (RuntimeException ex) { throw new IOException("El archivo de eventos esta danado", ex); }
    }
}
