package com.phys.dungeonmiceapp.database;

import com.google.gson.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import java.io.*;
import java.util.*;

public final class ProcessedVideoRepository {
    private static final String COLLECTION = "processedVideos";
    private ProcessedVideoRepository() {}

    public static ProcessedVideo record(String projectId, String sourceId, File result, JsonArray regions) throws IOException {
        String owner = owner(projectId);
        Video source = VideoRepository.findByCurrentUser().stream().filter(v -> sourceId.equals(v.getId()) && projectId.equals(v.getGroupId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Video original no disponible."));
        File file = result.getCanonicalFile();
        if (!file.isFile() || file.length() == 0) throw new IOException("El resultado no esta disponible.");
        if (file.equals(com.phys.dungeonmiceapp.ui.MediaFiles.resolve(source.getFilePath()).getCanonicalFile())) throw new IOException("El resultado debe ser distinto del video original.");
        for (ProcessedVideo existing : findByProject(projectId)) if (existing.getFilePath().equals(file.getPath())) return existing;
        JsonObject doc = new JsonObject(); doc.addProperty("_id", UUID.randomUUID().toString());
        doc.addProperty("projectId", projectId); doc.addProperty("ownerId", owner); doc.addProperty("sourceId", sourceId);
        doc.addProperty("sourceName", source.getTitle()); doc.addProperty("filePath", file.getPath()); doc.addProperty("createdAt", System.currentTimeMillis()); doc.add("regions", regions.deepCopy());
        JsonArray collection = DatabaseManager.getCollection(COLLECTION); collection.add(doc);
        try { DatabaseManager.persist(); } catch (RuntimeException ex) { collection.remove(doc); throw ex; }
        return fromJson(doc);
    }

    public static List<ProcessedVideo> findByProject(String projectId) {
        JsonObject filter = new JsonObject(); filter.addProperty("projectId", projectId); filter.addProperty("ownerId", owner(projectId));
        List<ProcessedVideo> results = new ArrayList<>();
        for (JsonElement item : DatabaseManager.findByFilter(COLLECTION, filter)) results.add(fromJson(item.getAsJsonObject()));
        results.sort(Comparator.comparingLong(ProcessedVideo::getCreatedAt).reversed()); return results;
    }

    public static void remove(String projectId, String id) {
        JsonObject filter = new JsonObject(); filter.addProperty("projectId", projectId); filter.addProperty("ownerId", owner(projectId)); filter.addProperty("_id", id);
        DatabaseManager.deleteOne(COLLECTION, filter);
    }

    public static void removeByProject(String projectId) {
        JsonObject filter = new JsonObject(); filter.addProperty("projectId", projectId); filter.addProperty("ownerId", owner(projectId)); DatabaseManager.deleteMany(COLLECTION, filter);
    }

    private static String owner(String projectId) {
        if (!SessionManager.isLoggedIn()) throw new IllegalArgumentException("Inicia sesion.");
        VideoGroup project = VideoGroupRepository.findById(projectId); String owner = SessionManager.getCurrentUser().getId();
        if (project == null || !owner.equals(project.getOwnerId())) throw new IllegalArgumentException("Proyecto no disponible.");
        return owner;
    }
    private static ProcessedVideo fromJson(JsonObject doc) {
        return new ProcessedVideo(doc.get("_id").getAsString(), doc.get("projectId").getAsString(), doc.get("sourceId").getAsString(),
                doc.get("sourceName").getAsString(), doc.get("filePath").getAsString(), doc.get("createdAt").getAsLong(), doc.getAsJsonArray("regions"));
    }
}
