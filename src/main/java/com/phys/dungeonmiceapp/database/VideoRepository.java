package com.phys.dungeonmiceapp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.phys.dungeonmiceapp.model.Video;
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
