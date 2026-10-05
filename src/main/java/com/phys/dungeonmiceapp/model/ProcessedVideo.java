package com.phys.dungeonmiceapp.model;

import com.google.gson.JsonArray;

/** A reference to an external analysis result, not a copy of the video. */
public final class ProcessedVideo {
    private final String id, projectId, sourceId, sourceName, filePath;
    private final long createdAt;
    private final JsonArray regions;
    public ProcessedVideo(String id, String projectId, String sourceId, String sourceName, String filePath, long createdAt, JsonArray regions) {
        this.id = id; this.projectId = projectId; this.sourceId = sourceId; this.sourceName = sourceName; this.filePath = filePath; this.createdAt = createdAt; this.regions = regions.deepCopy();
    }
    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getSourceId() { return sourceId; }
    public String getSourceName() { return sourceName; }
    public String getFilePath() { return filePath; }
    public long getCreatedAt() { return createdAt; }
    public JsonArray getRegions() { return regions.deepCopy(); }
    public String getFilename() { return new java.io.File(filePath).getName(); }
}
