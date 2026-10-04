package com.phys.dungeonmiceapp;

import com.phys.dungeonmiceapp.database.*;
import com.phys.dungeonmiceapp.model.*;
import com.phys.dungeonmiceapp.session.SessionManager;
import com.phys.dungeonmiceapp.ui.MediaFiles;

/** Read a disposable copy of the existing encrypted database without revealing its contents. */
public final class ExistingDataChecks {
    public static void main(String[] args) {
        System.setProperty("dungeonmice.database", args[0]);
        System.setProperty("dungeonmice.storage", args[1]);
        DatabaseManager.connect(); int users = 0, projects = 0, videos = 0, missing = 0;
        for (User user : UserRepository.findAll()) {
            users++; SessionManager.setCurrentUser(user);
            for (VideoGroup project : VideoGroupRepository.findGroupsByCurrentUser()) {
                projects++;
                for (Video video : VideoRepository.findByGroup(project.getId())) { videos++; if (!MediaFiles.resolve(video.getFilePath()).isFile()) missing++; }
            }
        }
        System.out.println("Existing encrypted data read successfully: users=" + users + ", projects=" + projects + ", videos=" + videos + ", missingFiles=" + missing);
    }
}
