package com.phys.dungeonmiceapp.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordCodec {
    private static final String PREFIX = "pbkdf2$";
    private PasswordCodec() {}

    public static String encode(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return PREFIX + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt));
    }

    public static boolean matches(String password, String stored) {
        if (stored == null) return false;
        if (!stored.startsWith(PREFIX)) {
            return MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
        }
        try {
            String[] parts = stored.split("\\$");
            return parts.length == 3 && MessageDigest.isEqual(derive(password, Base64.getDecoder().decode(parts[1])),
                    Base64.getDecoder().decode(parts[2]));
        } catch (IllegalArgumentException e) { return false; }
    }

    private static byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 210000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException("No se pudo proteger la contrasena", e); }
        finally { spec.clearPassword(); }
    }
}
