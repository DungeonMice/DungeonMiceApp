package com.phys.dungeonmiceapp.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Writable per-user data, kept separate from binaries and external video paths. */
public final class AppPaths {
    private AppPaths() { }

    public static Path userDataDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Paths.get(System.getProperty("user.home"));
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            Path base = local == null || local.isBlank() ? home.resolve("AppData/Local") : Paths.get(local);
            return base.resolve("DungeonMice").toAbsolutePath().normalize();
        }
        if (os.contains("mac")) return home.resolve("Library/Application Support/DungeonMice").toAbsolutePath().normalize();
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg == null || xdg.isBlank() || !Paths.get(xdg).isAbsolute() ? home.resolve(".local/share") : Paths.get(xdg);
        return base.resolve("DungeonMice").toAbsolutePath().normalize();
    }

    public static Path databaseFile() {
        String explicit = System.getProperty("dungeonmice.database");
        if (explicit == null) return userDataDirectory().resolve("data/database.enc");
        if (explicit.isBlank()) throw new IllegalArgumentException("dungeonmice.database no puede estar vacio");
        return Paths.get(explicit).toAbsolutePath().normalize();
    }
}
