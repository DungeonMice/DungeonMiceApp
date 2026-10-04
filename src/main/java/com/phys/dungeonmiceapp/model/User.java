package com.phys.dungeonmiceapp.model;

public class User {

    private String id;
    private String username;
    private String password;
    private String role;
    private String fullName = "";
    private String identification = "";
    private String photoPath = "";

    public String getFullName() { return fullName; }
    public void setFullName(String value) { fullName = value; }
    public String getIdentification() { return identification; }
    public void setIdentification(String value) { identification = value; }
    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String value) { photoPath = value; }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public User() {}

    public User(String id, String username, String password, String role) {
        this.id       = id;
        this.username = username;
        this.password = password;
        this.role     = role;
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public String getId()                  { return id; }
    public void   setId(String id)         { this.id = id; }

    public String getUsername()                    { return username; }
    public void   setUsername(String username)     { this.username = username; }

    public String getPassword()                    { return password; }
    public void   setPassword(String password)     { this.password = password; }

    public String getRole()                { return role; }
    public void   setRole(String role)     { this.role = role; }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "User{id='" + id + "', username='" + username + "', role='" + role + "'}";
    }
}
