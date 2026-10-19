package com.phys.dungeonmiceapp;

import com.google.gson.*;
import com.phys.dungeonmiceapp.api.PythonFrameClient;
import com.phys.dungeonmiceapp.ui.*;
import java.nio.file.*;
import java.util.*;

public final class EventArchiveChecks {
    private static void check(boolean value, String text) { if (!value) throw new AssertionError(text); System.out.println("PASS " + text); }
    private static PythonFrameClient.Frame frame(int index, boolean inside) throws Exception {
        JsonObject metadata = new JsonObject(); metadata.addProperty("index", index); metadata.addProperty("time", index / 30.0);
        JsonObject region = new JsonObject(); region.addProperty("inside", inside); region.addProperty("entries", inside ? 1 : 0); region.addProperty("totalTime", .1);
        JsonObject states = new JsonObject(); states.add("Platform", region); metadata.add("regions", states);
        return new PythonFrameClient.Frame(metadata, new byte[]{1, 4, (byte) index});
    }
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("dungeonmice-event-archive-");
        System.setProperty("dungeonmice.events.directory", temporary.resolve("events").toString());
        try {
            Path source = temporary.resolve("source.avi"); Files.write(source, new byte[]{7, 8, 9});
            EventFrameArchive archive = new EventFrameArchive("owner", "video", source, new JsonArray());
            ActivityLog.Entry first = archive.capture(frame(1, false)).get(0);
            check(archive.capture(frame(2, false)).isEmpty(), "Frames without events do not create snapshots");
            ActivityLog.Entry entry = archive.capture(frame(3, true)).get(0);
            check(entry.frame == 3 && entry.text.equals("Platform: entrada"), "Region transition records exact native frame and time");
            ActivityLog.Entry leave = archive.capture(frame(4, false)).get(0);
            check(leave.text.equals("Platform: salida"), "Region exits are marked separately");
            archive.finish("completed");
            Path run = temporary.resolve("events/owner/video").resolve(entry.archive);
            check(!Files.exists(run.resolve("2.frame")), "No copy of uneventful frames is retained");
            JsonObject manifest = JsonParser.parseString(Files.readString(run.resolve("markers.json"))).getAsJsonObject();
            check(manifest.get("state").getAsString().equals("completed") && manifest.getAsJsonArray("events").size() == 3, "Marker file publishes the run state and all events");
            Files.delete(source);
            ActivityLog.Entry restored = ActivityLog.Entry.fromJson(JsonParser.parseString(entry.toJson().toString()).getAsJsonObject());
            PythonFrameClient.Frame saved = EventFrameArchive.load("owner", "video", restored);
            check(Arrays.equals(saved.image, frame(3, true).image) && saved.metadata.get("index").getAsInt() == 3, "Reloaded marker retrieves exact snapshot after original is removed");
            boolean rejected = false;
            try { EventFrameArchive.load("other-owner", "video", restored); } catch (java.io.IOException error) { rejected = true; }
            check(rejected, "An event cannot resolve through another owner's archive");
            Files.delete(run.resolve("3.frame")); rejected = false;
            try { EventFrameArchive.load("owner", "video", restored); } catch (java.io.IOException error) { rejected = true; }
            check(rejected, "Missing snapshot produces an explicit error instead of seeking original video");
            Files.write(source, new byte[]{7, 8, 9});
            EventFrameArchive another = new EventFrameArchive("owner", "video", source, new JsonArray());
            ActivityLog.Entry second = another.capture(frame(1, true)).get(0); another.finish("cancelled");
            check(!first.archive.equals(second.archive) && EventFrameArchive.load("owner", "video", first) != null, "Reanalysis has its own run and preserves older snapshots");
            check(ActivityLog.Entry.fromJson(new ActivityLog.Entry(.5, 15, "Historical", false).toJson()).archive == null, "Historical logs remain compatible");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
        }
    }
}
