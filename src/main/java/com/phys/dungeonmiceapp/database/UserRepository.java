package com.phys.dungeonmiceapp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.phys.dungeonmiceapp.model.User;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class UserRepository {

    private static final String COLLECTION = "users";

    // CREATE
    public static void createUser(String username, String password, String role) {
        JsonObject user = new JsonObject();
        user.addProperty("username",  username);
        user.addProperty("password",  password);
        user.addProperty("role",      role);
        user.addProperty("createdAt", new Date().toString());
        DatabaseManager.insertOne(COLLECTION, user);
    }

    // READ
    public static User findByUsername(String username) {
        JsonObject filter = new JsonObject();
        filter.addProperty("username", username);
        JsonObject result = DatabaseManager.findOne(COLLECTION, filter);
        return result != null ? toUser(result) : null;
    }

    public static User login(String username, String password) {
        JsonObject filter = new JsonObject();
        filter.addProperty("username", username);
        filter.addProperty("password", password);
        JsonObject result = DatabaseManager.findOne(COLLECTION, filter);
        return result != null ? toUser(result) : null;
    }

    public static List<User> findAll() {
        JsonArray docs = DatabaseManager.findAll(COLLECTION);
        List<User> users = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            users.add(toUser(docs.get(i).getAsJsonObject()));
        }
        return users;
    }

    // DELETE
    public static void deleteById(String id) {
        JsonObject filter = new JsonObject();
        filter.addProperty("_id", id);
        DatabaseManager.deleteOne(COLLECTION, filter);
    }

    // Mapper
    private static User toUser(JsonObject doc) {
        return new User(
                doc.has("_id")      ? doc.get("_id").getAsString()      : null,
                doc.has("username") ? doc.get("username").getAsString()  : null,
                doc.has("password") ? doc.get("password").getAsString()  : null,
                doc.has("role")     ? doc.get("role").getAsString()      : null
        );
    }
}