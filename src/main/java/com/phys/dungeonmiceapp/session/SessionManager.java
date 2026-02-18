package com.phys.dungeonmiceapp.session;

import org.bson.Document;

public class SessionManager {

    private static Document currentUser;

    public static void setCurrentUser(Document user) {
        currentUser = user;
    }

    public static Document getCurrentUser() {
        return currentUser;
    }

    public static void clearSession() {
        currentUser = null;
    }

    public static boolean isLoggedIn() {
        return currentUser != null;
    }

    public static void logout() {
        currentUser = null;
    }
}
