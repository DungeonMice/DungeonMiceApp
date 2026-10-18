package com.phys.dungeonmiceapp.model;

import com.google.gson.JsonArray;

/** Stable category values shared by project forms and native region validation. */
public enum ExperimentType {
    CROSS_MAZE("CrossMaze", "CrossMaze"),
    MORRIS_POOL("MorrisPool", "Piscina de Morris");

    private final String code, label;
    ExperimentType(String code, String label) { this.code = code; this.label = label; }
    public String code() { return code; }
    @Override public String toString() { return label; }
    public static ExperimentType fromCategory(String value) {
        if (value == null) return null;
        for (ExperimentType type : values())
            if (type.code.equalsIgnoreCase(value.trim()) || type.label.equalsIgnoreCase(value.trim())) return type;
        return null;
    }
    public boolean supports(VideoRegion region) { return region.isMorris() == (this == MORRIS_POOL); }
    public void validateRegions(JsonArray regions) {
        if (!code.equals(VideoRegion.analysisType(regions)))
            throw new IllegalArgumentException("Las regiones no corresponden a " + label + ". Revisa las regiones o la categoria del proyecto.");
    }
    public static String categoryLabel(String category) {
        ExperimentType type = fromCategory(category); return type == null ? category : type.toString();
    }
}
