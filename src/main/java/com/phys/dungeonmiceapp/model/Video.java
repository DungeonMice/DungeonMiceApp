package com.phys.dungeonmiceapp.model;

import org.bson.types.ObjectId;

import java.util.Date;

public class Video {

    private ObjectId id;
    private String title;
    private String filePath;
    private ObjectId groupId;
    private ObjectId ownerId;
    private Date uploadedAt;

}

