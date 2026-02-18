package com.phys.dungeonmiceapp.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.phys.dungeonmiceapp.session.SessionManager;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class VideoGroupRepository {

    private static MongoCollection<Document> getCollection() {
        MongoDatabase database = DatabaseManager.getDatabase();
        return database.getCollection("videoGroups");
    }

    // 🔹 Crear grupo
    public static void createGroup(String name, String description) {

        ObjectId ownerId =
                SessionManager.getCurrentUser().getObjectId("_id");

        Document group = new Document()
                .append("name", name)
                .append("description", description)
                .append("ownerId", ownerId)
                .append("createdAt", new Date());

        getCollection().insertOne(group);
    }

    // 🔹 Obtener grupos del usuario logueado
    public static List<Document> findGroupsByCurrentUser() {

        ObjectId ownerId =
                SessionManager.getCurrentUser().getObjectId("_id");

        return getCollection()
                .find(new Document("ownerId", ownerId))
                .into(new ArrayList<>());
    }

    // 🔹 Buscar grupo por ID
    public static Document findById(ObjectId id) {

        return getCollection()
                .find(new Document("_id", id))
                .first();
    }

    // 🔹 Eliminar grupo
    public static void deleteById(ObjectId id) {

        getCollection()
                .deleteOne(new Document("_id", id));
    }

}
