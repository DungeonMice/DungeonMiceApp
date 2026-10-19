package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.api.ManagedPythonBridge;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Path selection only: never attempts to execute Linux files on Windows. */
public final class LinuxLayoutChecks {
    private static void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("dungeonmice-linux-layout-");
        String os = System.getProperty("os.name");
        String explicit = System.getProperty("dungeonmice.python.executable");
        try {
            System.setProperty("os.name", "Linux"); System.clearProperty("dungeonmice.python.executable");
            Files.createFile(root.resolve("DungeonMice"));
            Method packaged = ManagedPythonBridge.class.getDeclaredMethod("packagedRoot", Path.class); packaged.setAccessible(true);
            check((boolean) packaged.invoke(null, root), "Linux launcher identifies a packaged app without a Windows EXE");
            Path backend = root.resolve("backend"), python = backend.resolve("runtime/python/bin/python3");
            Files.createDirectories(python.getParent()); Files.createFile(python); python.toFile().setExecutable(true, true);
            Method executable = ManagedPythonBridge.class.getDeclaredMethod("pythonExecutable", Path.class, Path.class, boolean.class); executable.setAccessible(true);
            check(executable.invoke(null, backend, backend.resolve("engine"), true).equals(python), "Linux selects its bundled bin/python3");
            Files.delete(python);
            boolean rejected = false;
            try { executable.invoke(null, backend, backend.resolve("engine"), true); }
            catch (InvocationTargetException error) { rejected = error.getCause() instanceof java.io.IOException; }
            check(rejected, "Incomplete Linux bundle fails locally instead of falling back to a host Python");
        } finally {
            System.setProperty("os.name", os);
            if (explicit != null) System.setProperty("dungeonmice.python.executable", explicit);
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
        }
    }
}
