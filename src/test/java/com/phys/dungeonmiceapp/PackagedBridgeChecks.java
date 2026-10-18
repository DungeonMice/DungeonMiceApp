package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.api.ManagedPythonBridge;
import java.nio.file.*;
import java.util.concurrent.*;

/** Runs with the relocated distribution JAR first on the classpath. */
public final class PackagedBridgeChecks {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toRealPath();
        Path code = Paths.get(ManagedPythonBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(code.startsWith(root.resolve("app")), "Bridge loaded from relocated distribution, not target/classes");
        try (ManagedPythonBridge bridge = new ManagedPythonBridge(System.err::println)) {
            bridge.start().get(30, TimeUnit.SECONDS);
            ProcessHandle.Info child = ProcessHandle.of(bridge.pid()).orElseThrow().info();
            check(Paths.get(child.command().orElseThrow()).toRealPath().equals(root.resolve("backend/runtime/python/python.exe").toRealPath()), "Uses bundled Python without a host Python on PATH");
        }
        FrameApiChecks.main(new String[] { args[1], args[2] });

        Path incomplete = Files.createTempDirectory("dungeonmice-incomplete-package-");
        try {
            Files.createFile(incomplete.resolve("DungeonMice.exe"));
            System.setProperty("dungeonmice.app.root", incomplete.toString());
            try (ManagedPythonBridge missing = new ManagedPythonBridge(message -> {})) {
                try { missing.start().get(30, TimeUnit.SECONDS); throw new AssertionError("Missing backend silently accepted"); }
                catch (ExecutionException expected) {
                    check(expected.getCause().getMessage().contains(incomplete.toString()), "Incomplete installation fails locally instead of using the development checkout");
                }
            }
        } finally {
            System.clearProperty("dungeonmice.app.root");
            Files.deleteIfExists(incomplete.resolve("DungeonMice.exe"));
            Files.deleteIfExists(incomplete);
        }
    }
}
