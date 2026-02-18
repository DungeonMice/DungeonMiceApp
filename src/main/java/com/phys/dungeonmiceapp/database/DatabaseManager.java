package com.phys.dungeonmiceapp.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

public class DatabaseManager {

    private static final String CONNECTION_STRING =
            "mongodb+srv://phis_db_user_admin:phis_administrator_2026@clustermongophis.cemdiax.mongodb.net/phisdb?retryWrites=true&w=majority";
    private static final String DATABASE_NAME = "phisdb";

    private static MongoClient mongoClient;
    private static MongoDatabase database;

    public static void connect() {
        mongoClient = MongoClients.create(CONNECTION_STRING);
        database = mongoClient.getDatabase(DATABASE_NAME);
        System.out.println("Connected to MongoDB");
    }

    public static boolean login(String username, String password) {

        MongoCollection<Document> users =
                database.getCollection("users");

        Document user = users.find(
                new Document("username", username)
                        .append("password", password)
        ).first();

        return user != null;
    }

    public static MongoDatabase getDatabase() {
        return database;
    }
}
