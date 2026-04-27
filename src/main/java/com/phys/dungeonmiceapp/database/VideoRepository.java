package com.phys.dungeonmiceapp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.phys.dungeonmiceapp.model.Video;
import com.phys.dungeonmiceapp.session.SessionManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class VideoRepository {

    private static final String COLLECTION = "videos";

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