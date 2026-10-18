package com.phys.dungeonmiceapp.database;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public class DatabaseManager {

    // -------------------------------------------------------------------------
    // Configuración
    // -------------------------------------------------------------------------

    private static final Path DB_FILE_PATH = com.phys.dungeonmiceapp.config.AppPaths.databaseFile();
    private static final String MASTER_PASSWORD = System.getenv()
            .getOrDefault("DB_MASTER_PASSWORD", "changeme_load_from_env");

    private static final String CIPHER_ALGO   = "AES/GCM/NoPadding";
    private static final String KEY_ALGO      = "AES";
    private static final String KDF_ALGO      = "PBKDF2WithHmacSHA256";
    private static final int    KEY_BITS       = 256;
    private static final int    GCM_TAG_BITS   = 128;
    private static final int    IV_BYTES       = 12;
    private static final int    SALT_BYTES     = 16;
    private static final int    KDF_ITERATIONS = 310_000;

    private static final Gson gson = new Gson();

    // -------------------------------------------------------------------------
    // Estado interno
    // -------------------------------------------------------------------------

    private static JsonObject inMemoryDb = null;

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    public static Path databasePath() { return DB_FILE_PATH; }

    public static void connect() {
        File dbFile = DB_FILE_PATH.toFile();
        try { Files.createDirectories(DB_FILE_PATH.getParent()); }
        catch (java.io.IOException e) { throw new RuntimeException("No se pudo crear el directorio de datos: " + DB_FILE_PATH.getParent(), e); }

        if (dbFile.exists()) {
            try {
                inMemoryDb = loadAndDecrypt(dbFile);
                System.out.println("[DatabaseManager] Base de datos cargada desde " + DB_FILE_PATH);
            } catch (Exception e) {
                throw new RuntimeException("[DatabaseManager] Error al descifrar la base de datos.", e);
            }
        } else {
            inMemoryDb = new JsonObject();
            System.out.println("[DatabaseManager] Nueva base de datos inicializada.");
        }

        initializeIfEmpty();
    }

    /**
     * Si la colección "users" está vacía, inserta el usuario admin por defecto.
     * Esto solo ocurre la primera vez que se ejecuta la app (database.enc no existe).
     */
    private static void initializeIfEmpty() {
        JsonArray users = getCollection("users");
        if (users.size() == 0) {
            JsonObject admin = new JsonObject();
            admin.addProperty("_id",       UUID.randomUUID().toString());
            admin.addProperty("username",  "admin");
            admin.addProperty("password",  "admin123");
            admin.addProperty("role",      "admin");
            admin.addProperty("createdAt", new Date().toString());
            users.add(admin);
            persist();
            System.out.println("[DatabaseManager] Usuario admin creado por defecto.");
        }
    }

    public static void persist() {
        try {
            encryptAndSave(inMemoryDb, DB_FILE_PATH.toFile());
        } catch (Exception e) {
            throw new RuntimeException("[DatabaseManager] Error al persistir la base de datos.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Autenticación
    // -------------------------------------------------------------------------

    public static boolean login(String username, String password) {
        JsonArray users = getCollection("users");
        for (int i = 0; i < users.size(); i++) {
            JsonObject user = users.get(i).getAsJsonObject();
            String u = user.has("username") ? user.get("username").getAsString() : null;
            String p = user.has("password") ? user.get("password").getAsString() : null;
            if (username.equals(u) && com.phys.dungeonmiceapp.session.PasswordCodec.matches(password, p)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // CRUD genérico
    // -------------------------------------------------------------------------

    public static JsonObject insertOne(String collectionName, JsonObject document) {
        if (!document.has("_id")) {
            document.addProperty("_id", UUID.randomUUID().toString());
        }
        getCollection(collectionName).add(document);
        persist();
        return document;
    }

    public static JsonArray findAll(String collectionName) {
        return getCollection(collectionName);
    }

    public static JsonArray findByFilter(String collectionName, JsonObject filter) {
        JsonArray results = new JsonArray();
        JsonArray all     = getCollection(collectionName);
        for (int i = 0; i < all.size(); i++) {
            JsonObject doc = all.get(i).getAsJsonObject();
            if (matchesFilter(doc, filter)) results.add(doc);
        }
        return results;
    }

    public static JsonObject findOne(String collectionName, JsonObject filter) {
        JsonArray matches = findByFilter(collectionName, filter);
        return matches.size() > 0 ? matches.get(0).getAsJsonObject() : null;
    }

    public static int updateMany(String collectionName, JsonObject filter, JsonObject updates) {
        JsonArray collection = getCollection(collectionName);
        int count = 0;
        for (int i = 0; i < collection.size(); i++) {
            JsonObject doc = collection.get(i).getAsJsonObject();
            if (matchesFilter(doc, filter)) {
                updates.entrySet().forEach(e -> doc.add(e.getKey(), e.getValue()));
                count++;
            }
        }
        if (count > 0) persist();
        return count;
    }

    public static boolean updateOne(String collectionName, JsonObject filter, JsonObject updates) {
        return updateMany(collectionName, filter, updates) > 0;
    }

    public static int deleteMany(String collectionName, JsonObject filter) {
        JsonArray collection = getCollection(collectionName);
        JsonArray updated    = new JsonArray();
        int removed = 0;
        for (int i = 0; i < collection.size(); i++) {
            JsonObject doc = collection.get(i).getAsJsonObject();
            if (matchesFilter(doc, filter)) removed++;
            else updated.add(doc);
        }
        if (removed > 0) {
            inMemoryDb.add(collectionName, updated);
            persist();
        }
        return removed;
    }

    public static boolean deleteOne(String collectionName, JsonObject filter) {
        JsonArray collection = getCollection(collectionName);
        for (int i = 0; i < collection.size(); i++) {
            if (matchesFilter(collection.get(i).getAsJsonObject(), filter)) {
                collection.remove(i);
                persist();
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Acceso a colecciones
    // -------------------------------------------------------------------------

    public static JsonArray getCollection(String collectionName) {
        if (!inMemoryDb.has(collectionName)) {
            inMemoryDb.add(collectionName, new JsonArray());
        }
        return inMemoryDb.getAsJsonArray(collectionName);
    }

    // -------------------------------------------------------------------------
    // Helpers internos
    // -------------------------------------------------------------------------

    private static boolean matchesFilter(JsonObject doc, JsonObject filter) {
        for (String key : filter.keySet()) {
            if (!doc.has(key) || !doc.get(key).equals(filter.get(key))) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Cifrado: AES-256-GCM + PBKDF2
    // -------------------------------------------------------------------------

    private static JsonObject loadAndDecrypt(File file) throws Exception {
        String raw     = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
        String[] parts = raw.split(":");
        if (parts.length != 3) throw new IllegalArgumentException("Formato de archivo inválido.");

        byte[] salt       = Base64.getDecoder().decode(parts[0]);
        byte[] iv         = Base64.getDecoder().decode(parts[1]);
        byte[] ciphertext = Base64.getDecoder().decode(parts[2]);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(MASTER_PASSWORD.toCharArray(), salt),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);

        return JsonParser.parseString(new String(plaintext, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void encryptAndSave(JsonObject data, File file) throws Exception {
        SecureRandom rng = new SecureRandom();
        byte[] salt      = new byte[SALT_BYTES];
        byte[] iv        = new byte[IV_BYTES];
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(MASTER_PASSWORD.toCharArray(), salt),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(gson.toJson(data).getBytes(StandardCharsets.UTF_8));

        String encoded = Base64.getEncoder().encodeToString(salt)  + ":"
                + Base64.getEncoder().encodeToString(iv)    + ":"
                + Base64.getEncoder().encodeToString(ciphertext);

        Path temporary = Files.createTempFile(file.toPath().toAbsolutePath().getParent(), "database-", ".tmp");
        try {
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS);
        byte[] keyBytes = SecretKeyFactory.getInstance(KDF_ALGO).generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, KEY_ALGO);
    }
}
