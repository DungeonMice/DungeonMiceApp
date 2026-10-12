package com.phys.dungeonmiceapp.api;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Owns exactly one local Python service, including its startup and shutdown. */
public final class ManagedPythonBridge implements AutoCloseable {
    private final CompletableFuture<URI> ready = new CompletableFuture<>();
    private final Consumer<String> failure;
    private final String shutdownToken = UUID.randomUUID().toString();
    private final Deque<String> diagnostics = new ArrayDeque<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "dungeonmice-python-timeout"); thread.setDaemon(true); return thread;
    });
    private final Thread shutdownHook = new Thread(this::close, "dungeonmice-python-shutdown");
    private volatile boolean closed;
    private volatile Process process;
    private volatile URI endpoint;
    private Thread launcher;

    public ManagedPythonBridge(Consumer<String> failure) { this.failure = failure; }

    public synchronized CompletableFuture<URI> start() {
        if (launcher != null || closed) return ready;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        launcher = new Thread(this::launch, "dungeonmice-python-start"); launcher.setDaemon(true); launcher.start();
        timer.schedule(() -> {
            if (!ready.isDone()) { fail("Python no respondio al iniciar."); stopOwned(process); }
        }, 20, TimeUnit.SECONDS);
        return ready;
    }

    private void launch() {
        try {
            Path root = applicationRoot();
            Path project = Paths.get(System.getProperty("dungeonmice.python.project", root.resolveSibling("DungeonMice").toString())).toAbsolutePath();
            Path script = root.resolve("scripts/dungeonmice_api_bridge.py");
            if (!Files.isRegularFile(script)) throw new IOException("No se encontro el adaptador de Python: " + script);
            if (!Files.isRegularFile(project.resolve("src/RunExperiment.py"))) throw new IOException("No se encontro la app de Python: " + project);
            Path executable = pythonExecutable(root, project);
            List<String> command = new ArrayList<>(Arrays.asList(executable.toString(), "-B", script.toString(), "--python-project", project.toString(), "--port", "0"));
            String config = System.getProperty("dungeonmice.python.config");
            if (config != null && !config.isBlank()) command.addAll(Arrays.asList("--config", Paths.get(config).toAbsolutePath().toString()));
            ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("DUNGEONMICE_SHUTDOWN_TOKEN", shutdownToken);
            if (closed || ready.isDone()) return;
            Process child = builder.start();
            process = child;
            if (closed || ready.isDone()) { stopOwned(child); return; }
            Pattern address = Pattern.compile("^DungeonMice API: (http://127\\.0\\.0\\.1:[0-9]+)$");
            try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null) {
                    synchronized (diagnostics) { diagnostics.addLast(line.length() > 1000 ? line.substring(0, 1000) : line); if (diagnostics.size() > 15) diagnostics.removeFirst(); }
                    Matcher match = address.matcher(line);
                    if (match.matches() && !closed && !ready.isDone()) {
                        URI uri = URI.create(match.group(1));
                        if (uri.getPort() <= 0 || uri.getPort() > 65535) throw new IOException("Puerto de Python invalido.");
                        endpoint = uri;
                        if (ready.complete(uri)) System.out.println("[Python] Servicio iniciado: " + uri + " PID=" + child.pid());
                    }
                }
            }
            if (!closed) {
                String detail;
                synchronized (diagnostics) { detail = String.join("\n", diagnostics); }
                fail("El servicio Python se detuvo.\n" + detail);
            }
        } catch (Exception error) {
            stopOwned(process);
            if (!closed) fail(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private void fail(String message) {
        ready.completeExceptionally(new IOException(message));
        failure.accept(message);
    }

    private static Path applicationRoot() throws Exception {
        String configured = System.getProperty("dungeonmice.app.root");
        if (configured != null) return Paths.get(configured).toAbsolutePath().normalize();
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("scripts/dungeonmice_api_bridge.py"))) return cwd;
        Path location = Paths.get(ManagedPythonBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(location)) location = location.getParent();
        for (Path candidate = location; candidate != null; candidate = candidate.getParent())
            if (Files.isRegularFile(candidate.resolve("scripts/dungeonmice_api_bridge.py"))) return candidate;
        throw new IOException("No se encontro el directorio de la aplicacion Java.");
    }

    private static Path pythonExecutable(Path root, Path project) throws IOException {
        String explicit = System.getProperty("dungeonmice.python.executable", System.getenv("DUNGEONMICE_PYTHON"));
        if (explicit != null && !explicit.isBlank()) {
            Path path = Paths.get(explicit).toAbsolutePath();
            if (!Files.isRegularFile(path)) throw new IOException("Python no existe: " + path);
            return path;
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(root.resolve("runtime/python/python.exe"));
        candidates.add(project.resolve(".venv/Scripts/python.exe"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            candidates.add(Paths.get(local, "DungeonMice/api-venv/Scripts/python.exe"));
            candidates.add(Paths.get(local, "Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/DungeonMice/api-venv/Scripts/python.exe"));
        }
        for (Path candidate : candidates) if (Files.isRegularFile(candidate)) return candidate;
        throw new IOException("No se encontro el entorno Python. Configura dungeonmice.python.executable.");
    }

    public boolean isRunning() { return !closed && endpoint != null && process != null && process.isAlive(); }
    public String frameToken() { return shutdownToken; }
    public long pid() { return process == null ? -1 : process.pid(); }

    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        ready.completeExceptionally(new IOException("Servicio Python cerrado."));
        timer.shutdownNow();
        Process child = process;
        if (child != null && child.isAlive() && endpoint != null) {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
                HttpRequest shutdown = HttpRequest.newBuilder(endpoint.resolve("/api/v1/shutdown")).timeout(Duration.ofSeconds(2))
                        .header("X-DungeonMice-Token", shutdownToken).POST(HttpRequest.BodyPublishers.noBody()).build();
                client.send(shutdown, HttpResponse.BodyHandlers.discarding());
                child.waitFor(6, TimeUnit.SECONDS);
            } catch (Exception ignored) { /* Force cleanup below when graceful shutdown is unavailable. */ }
        }
        stopOwned(child);
        if (launcher != null && launcher != Thread.currentThread()) {
            try { launcher.join(2000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        stopOwned(process);
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { /* JVM shutdown already started. */ }
    }

    private static void stopOwned(Process child) {
        if (child == null || !child.isAlive()) return;
        List<ProcessHandle> descendants = new ArrayList<>(); child.descendants().forEach(descendants::add);
        Collections.reverse(descendants);
        for (ProcessHandle descendant : descendants) if (descendant.isAlive()) descendant.destroyForcibly();
        child.destroy();
        try { if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); } }
        catch (InterruptedException ex) { child.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}
