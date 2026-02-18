package com.phys.dungeonmiceapp.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class UserRepository {

    private static MongoCollection<Document> getCollection() {
        MongoDatabase database = DatabaseManager.getDatabase();
        return database.getCollection("users");
    }

    // 🔹 Crear usuario
    public static void createUser(String username, String password, String role) {

        Document user = new Document()
                .append("username", username)
                .append("password", password)
                .append("role", role)
                .append("createdAt", new Date());

        getCollection().insertOne(user);
    }

    // 🔹 Buscar usuario por username
    public static Document findByUsername(String username) {

        return getCollection()
                .find(new Document("username", username))
                .first();
    }

    // 🔹 Login
    public static Document login(String username, String password) {

        return getCollection()
                .find(new Document("username", username)
                        .append("password", password))
                .first();
    }

    // 🔹 Obtener todos los usuarios
    public static List<Document> findAll() {

        return getCollection()
                .find()
                .into(new ArrayList<>());
    }

    // 🔹 Eliminar usuario por ID
    public static void deleteById(ObjectId id) {

        getCollection()
                .deleteOne(new Document("_id", id));
    }
}
