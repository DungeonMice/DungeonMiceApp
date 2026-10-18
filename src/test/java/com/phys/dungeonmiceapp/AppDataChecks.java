package com.phys.dungeonmiceapp;

import com.google.gson.JsonObject;
import com.phys.dungeonmiceapp.database.DatabaseManager;
import com.phys.dungeonmiceapp.ui.MediaFiles;
import java.nio.file.*;
import java.util.Arrays;

/** Run in separate JVMs with disposable LocalAppData/home/override directories. */
public final class AppDataChecks {
    private static void check(boolean value, String text) {
        if (!value) throw new AssertionError(text);
        System.out.println("PASS " + text);
    }
    public static void main(String[] args) throws Exception {
        Path expectedDatabase = Paths.get(args[0]).toAbsolutePath().normalize();
        Path expectedProfiles = Paths.get(args[1]).toAbsolutePath().normalize();
        check(DatabaseManager.databasePath().equals(expectedDatabase), "Database location: " + expectedDatabase);
        DatabaseManager.connect();
        check(Files.isRegularFile(expectedDatabase), "Writable database directory created automatically");
        if (DatabaseManager.findAll("storage-check").size() == 0) {
            JsonObject record = new JsonObject(); record.addProperty("marker", "preserved"); DatabaseManager.insertOne("storage-check", record);
        }
        byte[] encrypted = Files.readAllBytes(expectedDatabase);
        DatabaseManager.connect();
        check(DatabaseManager.findAll("storage-check").size() == 1 && DatabaseManager.findAll("storage-check").get(0).getAsJsonObject().get("marker").getAsString().equals("preserved"), "Existing records survive reconnect/startup");
        check(Arrays.equals(encrypted, Files.readAllBytes(expectedDatabase)), "Opening an existing database never rewrites it");
        Path fixture = Files.createTempFile(expectedDatabase.getParent(), "profile-check-", ".png");
        Path copied = null;
        try {
            Files.write(fixture, new byte[]{1, 2, 3}); copied = Paths.get(MediaFiles.copyPhoto(fixture.toFile()));
            check(copied.getParent().equals(expectedProfiles), "Profile photos use writable user/override storage");
            check(Arrays.equals(Files.readAllBytes(fixture), Files.readAllBytes(copied)), "Photo contents preserved");
        } finally { Files.deleteIfExists(fixture); if (copied != null) Files.deleteIfExists(copied); }
        Path decoy = Paths.get("data/database.enc");
        if (!decoy.toAbsolutePath().normalize().equals(expectedDatabase) && Files.isRegularFile(decoy))
            check(Files.readString(decoy).equals("Do not import or modify this bundled database"), "Bundled database is neither imported nor modified");
    }
}
