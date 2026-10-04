package com.phys.dungeonmiceapp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.phys.dungeonmiceapp.model.User;
import com.phys.dungeonmiceapp.session.PasswordCodec;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class UserRepository {

    private static final String COLLECTION = "users";

    // CREATE
    public static void createUser(String username, String password, String role) {
        register(username, password, role, "", "", "");
    }

    public static User register(String username, String password, String role, String fullName, String identification, String photoPath) {
        username = username.trim();
        if (username.isEmpty() || password.isEmpty()) throw new IllegalArgumentException("Usuario y contrasena son obligatorios.");
        if (findByUsername(username) != null) throw new IllegalArgumentException("Ese nombre de usuario ya existe.");
        JsonObject user = new JsonObject();
        user.addProperty("username",  username);
        user.addProperty("password",  PasswordCodec.encode(password));
        user.addProperty("role",      role);
        user.addProperty("createdAt", new Date().toString());
        user.addProperty("fullName", fullName);
        user.addProperty("identification", identification);
        user.addProperty("photoPath", photoPath);
        return toUser(DatabaseManager.insertOne(COLLECTION, user));
    }

    // READ
    public static User findByUsername(String username) {
        JsonObject filter = new JsonObject();
        filter.addProperty("username", username);
        JsonObject result = DatabaseManager.findOne(COLLECTION, filter);
        return result != null ? toUser(result) : null;
    }

    public static User login(String username, String password) {
        User user = findByUsername(username.trim());
        return user != null && PasswordCodec.matches(password, user.getPassword()) ? user : null;
    }

    public static User updateProfile(User current, String username, String fullName, String identification,
                                     String photoPath, String oldPassword, String newPassword) {
        username = username.trim();
        if (username.isEmpty()) throw new IllegalArgumentException("El nombre de usuario es obligatorio.");
        User duplicate = findByUsername(username);
        if (duplicate != null && !duplicate.getId().equals(current.getId())) throw new IllegalArgumentException("Ese nombre de usuario ya existe.");
        JsonObject updates = new JsonObject();
        updates.addProperty("username", username);
        updates.addProperty("fullName", fullName);
        updates.addProperty("identification", identification);
        updates.addProperty("photoPath", photoPath);
        if (!newPassword.isEmpty()) {
            User actual = findByUsername(current.getUsername());
            if (actual == null || !PasswordCodec.matches(oldPassword, actual.getPassword())) throw new IllegalArgumentException("La contrasena actual no es correcta.");
            updates.addProperty("password", PasswordCodec.encode(newPassword));
        }
        JsonObject filter = new JsonObject(); filter.addProperty("_id", current.getId());
        if (!DatabaseManager.updateOne(COLLECTION, filter, updates)) throw new IllegalArgumentException("Usuario no encontrado.");
        return findByUsername(username);
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
        User user = new User(
                doc.has("_id")      ? doc.get("_id").getAsString()      : null,
                doc.has("username") ? doc.get("username").getAsString()  : null,
                doc.has("password") ? doc.get("password").getAsString()  : null,
                doc.has("role")     ? doc.get("role").getAsString()      : null
        );
        user.setFullName(value(doc, "fullName"));
        user.setIdentification(value(doc, "identification"));
        user.setPhotoPath(value(doc, "photoPath"));
        return user;
    }

    private static String value(JsonObject doc, String key) {
        return doc.has(key) && !doc.get(key).isJsonNull() ? doc.get(key).getAsString() : "";
    }
}
