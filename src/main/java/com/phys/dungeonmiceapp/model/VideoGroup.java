package com.phys.dungeonmiceapp.model;

import org.bson.types.ObjectId;

import java.util.Date;

public class VideoGroup {

    private ObjectId id;
    private String name;
    private String description;
    private ObjectId ownerId;
    private Date createdAt;

}
