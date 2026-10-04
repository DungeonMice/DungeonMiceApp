package com.phys.dungeonmiceapp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.phys.dungeonmiceapp.model.VideoGroup;
import com.phys.dungeonmiceapp.session.SessionManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class VideoGroupRepository {

    private static final String COLLECTION = "videoGroups";

    // CREATE
    public static void createGroup(String name, String description) {
        saveProject(null, name, description, "", "", "", "");
    }

    public static VideoGroup saveProject(String id, String name, String description, String category,
                                         String responsible, String participants, String location) {
        if (name.trim().isEmpty()) throw new IllegalArgumentException("El nombre del proyecto es obligatorio.");
        String ownerId = SessionManager.getCurrentUser().getId();
        if (id != null) {
            VideoGroup existing = findById(id);
            if (existing == null || !ownerId.equals(existing.getOwnerId())) throw new IllegalArgumentException("Proyecto no disponible.");
        }
        JsonObject group = new JsonObject();
        group.addProperty("name",        name.trim());
        group.addProperty("description", description);
        group.addProperty("ownerId",     ownerId);
        group.addProperty("createdAt",   new Date().toString());
        group.addProperty("category", category);
        group.addProperty("responsible", responsible);
        group.addProperty("participants", participants);
        group.addProperty("location", location);
        if (id == null) return toVideoGroup(DatabaseManager.insertOne(COLLECTION, group));
        group.remove("createdAt");
        JsonObject filter = new JsonObject(); filter.addProperty("_id", id);
        DatabaseManager.updateOne(COLLECTION, filter, group);
        return findById(id);
    }

    // READ
    public static List<VideoGroup> findGroupsByCurrentUser() {
        String ownerId = SessionManager.getCurrentUser().getId();
        JsonObject filter = new JsonObject();
        filter.addProperty("ownerId", ownerId);
        JsonArray docs = DatabaseManager.findByFilter(COLLECTION, filter);
        List<VideoGroup> groups = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            groups.add(toVideoGroup(docs.get(i).getAsJsonObject()));
        }
        return groups;
    }

    public static VideoGroup findById(String id) {
        JsonObject filter = new JsonObject();
        filter.addProperty("_id", id);
        JsonObject result = DatabaseManager.findOne(COLLECTION, filter);
        return result != null ? toVideoGroup(result) : null;
    }

    // DELETE
    public static void deleteById(String id) {
        JsonObject filter = new JsonObject();
        filter.addProperty("_id", id);
        DatabaseManager.deleteOne(COLLECTION, filter);
    }

    // Mapper
    private static VideoGroup toVideoGroup(JsonObject doc) {
        VideoGroup group = new VideoGroup(
                doc.has("_id")         ? doc.get("_id").getAsString()         : null,
                doc.has("name")        ? doc.get("name").getAsString()         : null,
                doc.has("description") ? doc.get("description").getAsString()  : null,
                doc.has("ownerId")     ? doc.get("ownerId").getAsString()      : null,
                null
        );
        group.setCategory(value(doc, "category"));
        group.setResponsible(value(doc, "responsible"));
        group.setParticipants(value(doc, "participants"));
        group.setLocation(value(doc, "location"));
        return group;
    }

    private static String value(JsonObject doc, String key) {
        return doc.has(key) && !doc.get(key).isJsonNull() ? doc.get(key).getAsString() : "";
    }
}
