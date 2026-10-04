package com.phys.dungeonmiceapp.model;

import java.util.Date;

public class VideoGroup {

    private String id;
    private String name;
    private String description;
    private String ownerId;
    private Date createdAt;
    private String category = "";
    private String responsible = "";
    private String participants = "";
    private String location = "";

    public String getCategory() { return category; }
    public void setCategory(String value) { category = value; }
    public String getResponsible() { return responsible; }
    public void setResponsible(String value) { responsible = value; }
    public String getParticipants() { return participants; }
    public void setParticipants(String value) { participants = value; }
    public String getLocation() { return location; }
    public void setLocation(String value) { location = value; }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public VideoGroup() {}

    public VideoGroup(String id, String name, String description, String ownerId, Date createdAt) {
        this.id          = id;
        this.name        = name;
        this.description = description;
        this.ownerId     = ownerId;
        this.createdAt   = createdAt;
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public String getId()                { return id; }
    public void   setId(String id)       { this.id = id; }

    public String getName()                  { return name; }
    public void   setName(String name)       { this.name = name; }

    public String getDescription()                       { return description; }
    public void   setDescription(String description)     { this.description = description; }

    public String getOwnerId()                   { return ownerId; }
    public void   setOwnerId(String ownerId)     { this.ownerId = ownerId; }

    public Date getCreatedAt()                     { return createdAt; }
    public void setCreatedAt(Date createdAt)       { this.createdAt = createdAt; }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "VideoGroup{id='" + id + "', name='" + name + "', ownerId='" + ownerId + "'}";
    }
}
