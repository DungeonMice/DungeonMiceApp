package com.phys.dungeonmiceapp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.phys.dungeonmiceapp.model.Video;
import com.phys.dungeonmiceapp.model.VideoRegion;
import com.phys.dungeonmiceapp.session.SessionManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.phys.dungeonmiceapp.ui.MediaFiles;
import com.phys.dungeonmiceapp.model.VideoGroup;

public class VideoRepository {

    private static final String COLLECTION = "videos";

    public static void addReferences(String groupId, List<File> sources) throws IOException {
        String ownerId = SessionManager.getCurrentUser().getId();
        VideoGroup group = VideoGroupRepository.findById(groupId);
        if (group == null || !ownerId.equals(group.getOwnerId())) throw new IllegalArgumentException("Proyecto no disponible.");
        List<File> references = MediaFiles.references(sources);
        Set<Path> paths = new HashSet<>();
        for (Video existing : findByGroup(groupId)) paths.add(MediaFiles.resolve(existing.getFilePath()).getCanonicalFile().toPath());
        JsonArray collection = DatabaseManager.getCollection(COLLECTION);
        List<JsonObject> added = new ArrayList<>();
        for (File source : references) {
            if (!paths.add(source.toPath())) continue;
            JsonObject video = new JsonObject();
            video.addProperty("_id", UUID.randomUUID().toString());
            video.addProperty("title", source.getName()); video.addProperty("filePath", source.getAbsolutePath());
            video.addProperty("groupId", groupId); video.addProperty("ownerId", ownerId);
            video.addProperty("uploadedAt", new Date().toString());
            collection.add(video); added.add(video);
        }
        if (!added.isEmpty()) {
            try { DatabaseManager.persist(); }
            catch (RuntimeException ex) { for (JsonObject video : added) collection.remove(video); throw ex; }
        }
    }

    // CREATE
    public static void addVideo(String groupId, String title, String filePath) {
        String ownerId = SessionManager.getCurrentUser().getId();
        JsonObject video = new JsonObject();
        video.addProperty("title",      title);
        video.addProperty("filePath",   filePath);
        video.addProperty("groupId",    groupId);
        video.addProperty("ownerId",    ownerId);
        video.addProperty("uploadedAt", new Date().toString());
        DatabaseManager.insertOne(COLLECTION, video);
    }

    // READ
    public static List<Video> findByGroup(String groupId) {
        JsonObject filter = new JsonObject();
        filter.addProperty("groupId", groupId);
        return toVideoList(DatabaseManager.findByFilter(COLLECTION, filter));
    }

    public static List<Video> findByCurrentUser() {
        String ownerId = SessionManager.getCurrentUser().getId();
        JsonObject filter = new JsonObject();
        filter.addProperty("ownerId", ownerId);
        return toVideoList(DatabaseManager.findByFilter(COLLECTION, filter));
    }

    public static List<VideoRegion> findRegions(String videoId) {
        JsonObject video = ownedVideo(videoId);
        List<VideoRegion> regions = new ArrayList<>();
        if (video.has("regions")) for (com.google.gson.JsonElement item : video.getAsJsonArray("regions")) regions.add(VideoRegion.fromJson(item.getAsJsonObject()));
        return regions;
    }

    public static void saveRegions(String videoId, List<VideoRegion> regions) {
        JsonObject video = ownedVideo(videoId);
        if (regions.size() > 32) throw new IllegalArgumentException("Maximo 32 regiones por video.");
        Set<String> names = new HashSet<>(); JsonArray data = new JsonArray();
        for (VideoRegion region : regions) {
            if (!names.add(region.getName().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Los nombres de las regiones deben ser diferentes.");
            data.add(region.toJson());
        }
        com.google.gson.JsonElement previous = video.get("regions"); video.add("regions", data);
        try { DatabaseManager.persist(); }
        catch (RuntimeException ex) { if (previous == null) video.remove("regions"); else video.add("regions", previous); throw ex; }
    }

    private static JsonObject ownedVideo(String videoId) {
        if (!SessionManager.isLoggedIn()) throw new IllegalArgumentException("Inicia sesion.");
        JsonObject filter = new JsonObject(); filter.addProperty("_id", videoId); filter.addProperty("ownerId", SessionManager.getCurrentUser().getId());
        JsonObject video = DatabaseManager.findOne(COLLECTION, filter);
        if (video == null) throw new IllegalArgumentException("Video no disponible.");
        return video;
    }

    public static int applyRegionsToGroup(String groupId, String sourceId, boolean replaceExisting) {
        JsonObject source = ownedVideo(sourceId);
        if (!groupId.equals(source.get("groupId").getAsString())) throw new IllegalArgumentException("Video de otro proyecto.");
        List<VideoRegion> template = findRegions(sourceId);
        if (template.isEmpty()) throw new IllegalArgumentException("Guarda primero las regiones del video.");
        JsonArray data = new JsonArray(); for (VideoRegion region : template) data.add(region.toJson());
        java.util.Map<JsonObject, com.google.gson.JsonElement> previous = new java.util.LinkedHashMap<>();
        for (Video target : findByGroup(groupId)) {
            if (target.getId().equals(sourceId)) continue;
            JsonObject document = ownedVideo(target.getId());
            if (!replaceExisting && document.has("regions") && document.getAsJsonArray("regions").size() > 0) continue;
            previous.put(document, document.get("regions"));
        }
        // Validate ownership before mutating, then persist all independent copies together.
        for (JsonObject document : previous.keySet()) document.add("regions", data.deepCopy());
        if (!previous.isEmpty()) try { DatabaseManager.persist(); }
        catch (RuntimeException error) {
            previous.forEach((document, old) -> { if (old == null) document.remove("regions"); else document.add("regions", old); });
            throw error;
        }
        return previous.size();
    }

    // DELETE
    public static void deleteById(String id) {
        JsonObject filter = new JsonObject();
        filter.addProperty("_id", id);
        DatabaseManager.deleteOne(COLLECTION, filter);
    }

    public static void deleteByGroup(String groupId) {
        JsonObject filter = new JsonObject();
        filter.addProperty("groupId", groupId);
        DatabaseManager.deleteMany(COLLECTION, filter);
    }

    // Mapper
    private static List<Video> toVideoList(JsonArray docs) {
        List<Video> videos = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            videos.add(toVideo(docs.get(i).getAsJsonObject()));
        }
        return videos;
    }

    private static Video toVideo(JsonObject doc) {
        return new Video(
                doc.has("_id")      ? doc.get("_id").getAsString()      : null,
                doc.has("title")    ? doc.get("title").getAsString()    : null,
                doc.has("filePath") ? doc.get("filePath").getAsString() : null,
                doc.has("groupId")  ? doc.get("groupId").getAsString()  : null,
                doc.has("ownerId")  ? doc.get("ownerId").getAsString()  : null,
                null
        );
    }
}
