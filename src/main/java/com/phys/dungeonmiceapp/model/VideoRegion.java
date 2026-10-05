package com.phys.dungeonmiceapp.model;

import com.google.gson.*;
import java.util.*;
import javafx.geometry.Point2D;

/** Polygon coordinates are pixels in the original decoded frame, never screen pixels. */
public final class VideoRegion {
    private final String name, color;
    private final List<Point2D> points;
    private final double threshold;
    private final int width, height;

    public VideoRegion(String name, String color, List<Point2D> points, double threshold, int width, int height) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 60) throw new IllegalArgumentException("Escribe un nombre de 1 a 60 caracteres.");
        if (color == null || !color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Color invalido.");
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) throw new IllegalArgumentException("Solapamiento invalido.");
        validate(points, width, height);
        this.name = name.trim(); this.color = color; this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.threshold = threshold; this.width = width; this.height = height;
    }

    private static void validate(List<Point2D> points, int width, int height) {
        if (width <= 0 || height <= 0 || points == null || points.size() < 3 || points.size() > 128)
            throw new IllegalArgumentException("La region necesita entre 3 y 128 vertices.");
        double area = 0;
        for (int i = 0; i < points.size(); i++) {
            Point2D a = points.get(i), b = points.get((i + 1) % points.size());
            if (a == null || !Double.isFinite(a.getX()) || !Double.isFinite(a.getY()) || a.getX() < 0 || a.getY() < 0 || a.getX() > width - 1 || a.getY() > height - 1)
                throw new IllegalArgumentException("Un vertice esta fuera del video.");
            if (a.distance(b) < .01) throw new IllegalArgumentException("Hay vertices repetidos.");
            area += a.getX() * b.getY() - b.getX() * a.getY();
        }
        if (Math.abs(area) < 2) throw new IllegalArgumentException("La region no tiene area suficiente.");
        for (int i = 0; i < points.size(); i++) for (int j = i + 1; j < points.size(); j++) {
            if (j == i + 1 || (i == 0 && j == points.size() - 1)) continue;
            Point2D a = points.get(i), b = points.get((i + 1) % points.size()), c = points.get(j), d = points.get((j + 1) % points.size());
            if (java.awt.geom.Line2D.linesIntersect(a.getX(), a.getY(), b.getX(), b.getY(), c.getX(), c.getY(), d.getX(), d.getY()))
                throw new IllegalArgumentException("Los bordes de la region se cruzan.");
        }
    }

    public String getName() { return name; }
    public String getColor() { return color; }
    public List<Point2D> getPoints() { return points; }
    public double getThreshold() { return threshold; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public JsonObject toJson() {
        JsonObject json = new JsonObject(); json.addProperty("id", name); json.addProperty("type", "PolygonRegion");
        json.addProperty("color", color); json.addProperty("overlap_threshold", threshold);
        json.addProperty("frameWidth", width); json.addProperty("frameHeight", height);
        JsonArray coordinates = new JsonArray();
        for (Point2D point : points) { JsonArray pair = new JsonArray(); pair.add(point.getX()); pair.add(point.getY()); coordinates.add(pair); }
        json.add("points", coordinates); return json;
    }

    public static VideoRegion fromJson(JsonObject json) {
        List<Point2D> points = new ArrayList<>();
        for (JsonElement item : json.getAsJsonArray("points")) { JsonArray pair = item.getAsJsonArray(); points.add(new Point2D(pair.get(0).getAsDouble(), pair.get(1).getAsDouble())); }
        return new VideoRegion(json.get("id").getAsString(), json.get("color").getAsString(), points,
                json.get("overlap_threshold").getAsDouble(), json.get("frameWidth").getAsInt(), json.get("frameHeight").getAsInt());
    }
}
