package com.phys.dungeonmiceapp.model;

import java.util.Date;

public class Video {

    private String id;
    private String title;
    private String filePath;
    private String groupId;
    private String ownerId;
    private Date uploadedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public Video() {}

    public Video(String id, String title, String filePath, String groupId, String ownerId, Date uploadedAt) {
        this.id         = id;
        this.title      = title;
        this.filePath   = filePath;
        this.groupId    = groupId;
        this.ownerId    = ownerId;
        this.uploadedAt = uploadedAt;
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public String getId()                { return id; }
    public void   setId(String id)       { this.id = id; }

    public String getTitle()                   { return title; }
    public void   setTitle(String title)       { this.title = title; }

    public String getFilePath()                    { return filePath; }
    public void   setFilePath(String filePath)     { this.filePath = filePath; }

    public String getGroupId()                   { return groupId; }
    public void   setGroupId(String groupId)     { this.groupId = groupId; }

    public String getOwnerId()                   { return ownerId; }
    public void   setOwnerId(String ownerId)     { this.ownerId = ownerId; }

    public Date getUploadedAt()                      { return uploadedAt; }
    public void setUploadedAt(Date uploadedAt)       { this.uploadedAt = uploadedAt; }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "Video{id='" + id + "', title='" + title + "', ownerId='" + ownerId + "'}";
    }
}