package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.api.ManagedPythonBridge;
import com.phys.dungeonmiceapp.api.PythonVideoClient;
import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ManagedPythonBridgeChecks {
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); System.out.println("PASS " + name); }

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpServer unrelated = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        unrelated.createContext("/", request -> { request.sendResponseHeaders(200, -1); request.close(); }); unrelated.start();
        try {
            ManagedPythonBridge bridge = new ManagedPythonBridge(System.err::println);
            long child;
            try {
                URI endpoint = bridge.start().get(25, TimeUnit.SECONDS); child = bridge.pid();
                check(bridge.isRunning(), "Managed Python service starts automatically");
                check(endpoint.getPort() != unrelated.getAddress().getPort(), "Free port selected without replacing another service");
                HttpRequest forbidden = HttpRequest.newBuilder(endpoint.resolve("/api/v1/shutdown")).timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                check(http.send(forbidden, HttpResponse.BodyHandlers.discarding()).statusCode() == 403, "Shutdown requires owner's token");
                check(bridge.isRunning(), "Unauthorized shutdown does not stop service");
                HttpRequest metadata = HttpRequest.newBuilder(endpoint.resolve("/api/v1/jobs")).timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"protocolVersion\":1,\"filename\":\"test.mp4\",\"sizeBytes\":1,\"regions\":[]}")).build();
                check(http.send(metadata, HttpResponse.BodyHandlers.discarding()).statusCode() == 201, "Automatic connection accepts API requests");
            } finally { bridge.close(); }
            check(!ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false), "Owned Python process stops on close");
            check(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + unrelated.getAddress().getPort())).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200,
                    "Unrelated service remains running");
            if (args.length > 0) closeDuringAnalysis(Paths.get(args[0]));
            ManagedPythonBridge early = new ManagedPythonBridge(message -> {}); early.start(); early.close();
            check(early.pid() < 0 || !ProcessHandle.of(early.pid()).map(ProcessHandle::isAlive).orElse(false), "Closing during startup leaves no process");
            String previous = System.getProperty("dungeonmice.python.executable");
            System.setProperty("dungeonmice.python.executable", "missing-python-environment.exe");
            ManagedPythonBridge missing = new ManagedPythonBridge(message -> {});
            try {
                try { missing.start().get(25, TimeUnit.SECONDS); throw new AssertionError("Missing Python accepted"); }
                catch (ExecutionException expected) { check(true, "Missing Python fails clearly without blocking UI"); }
            } finally { missing.close(); if (previous == null) System.clearProperty("dungeonmice.python.executable"); else System.setProperty("dungeonmice.python.executable", previous); }
        } finally { unrelated.stop(0); }
    }

    private static void closeDuringAnalysis(Path source) throws Exception {
        ManagedPythonBridge bridge = new ManagedPythonBridge(System.err::println);
        ExecutorService transfer = Executors.newSingleThreadExecutor();
        Path directory = Files.createTempDirectory("dungeonmice-active-close-");
        List<ProcessHandle> workers = new ArrayList<>();
        try {
            URI endpoint = bridge.start().get(25, TimeUnit.SECONDS);
            long pid = bridge.pid();
            Future<Path> result = transfer.submit(() -> new PythonVideoClient(endpoint).process(source,
                    directory.resolve("result.mp4"), "close-check", "test", new JsonArray(), message -> {}));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (workers.isEmpty() && System.nanoTime() < deadline && !result.isDone()) {
                ProcessHandle.of(pid).ifPresent(parent -> parent.descendants().filter(ProcessHandle::isAlive).forEach(workers::add));
                Thread.sleep(25);
            }
            check(!workers.isEmpty(), "Real analysis worker starts through managed API");
            bridge.close();
            check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Closing during analysis stops service");
            check(workers.stream().noneMatch(ProcessHandle::isAlive), "Closing during analysis stops owned workers");
            try { result.get(10, TimeUnit.SECONDS); throw new AssertionError("Interrupted analysis returned a result"); }
            catch (ExecutionException expected) { check(true, "Active transfer ends when service closes"); }
            try (var files = Files.list(directory)) { check(files.findAny().isEmpty(), "No partial result remains after active shutdown"); }
        } finally {
            bridge.close(); transfer.shutdownNow(); transfer.awaitTermination(10, TimeUnit.SECONDS);
            try (var files = Files.list(directory)) {
                Iterator<Path> remaining = files.iterator();
                while (remaining.hasNext()) Files.delete(remaining.next());
            }
            Files.delete(directory);
        }
    }
}
