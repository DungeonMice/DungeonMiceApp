package com.phys.dungeonmiceapp.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.phys.dungeonmiceapp.session.SessionManager;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class VideoRepository {

    private static MongoCollection<Document> getCollection() {
        MongoDatabase database = DatabaseManager.getDatabase();
        return database.getCollection("videos");
    }

    // 🔹 Agregar video a un grupo
    public static void addVideo(ObjectId groupId,
                                String title,
                                String filePath) {

        ObjectId ownerId =
                SessionManager.getCurrentUser().getObjectId("_id");

        Document video = new Document()
                .append("title", title)
                .append("filePath", filePath)
                .append("groupId", groupId)
                .append("ownerId", ownerId)
                .append("uploadedAt", new Date());

        getCollection().insertOne(video);
    }

    // 🔹 Obtener videos por grupo
    public static List<Document> findByGroup(ObjectId groupId) {

        return getCollection()
                .find(new Document("groupId", groupId))
                .into(new ArrayList<>());
    }

    // 🔹 Obtener videos del usuario actual
    public static List<Document> findByCurrentUser() {

        ObjectId ownerId =
                SessionManager.getCurrentUser().getObjectId("_id");

        return getCollection()
                .find(new Document("ownerId", ownerId))
                .into(new ArrayList<>());
    }

    // 🔹 Eliminar video
    public static void deleteById(ObjectId id) {

        getCollection()
                .deleteOne(new Document("_id", id));
    }

    public static void deleteByGroup(ObjectId groupId) {
        getCollection().deleteMany(new Document("groupId", groupId));
    }
}
