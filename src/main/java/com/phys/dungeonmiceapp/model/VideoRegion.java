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
    private String type = "PolygonRegion";
    private Point2D center;
    private double radius, angle;

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
    public boolean isMorris() { return "CircularFractionRegion".equals(type); }
    public Point2D getCenter() { return center; }
    public double getRadius() { return radius; }
    public double getAngle() { return angle; }

    public static VideoRegion morris(String name, String color, Point2D center, double radius, double angle, double threshold, int width, int height) {
        if (center == null || !Double.isFinite(radius) || radius < 1 || !Double.isFinite(angle)
                || center.getX() - radius < 0 || center.getY() - radius < 0 || center.getX() + radius > width - 1 || center.getY() + radius > height - 1)
            throw new IllegalArgumentException("La piscina debe quedar dentro del video y tener radio positivo.");
        double start = (angle % 360 + 360) % 360;
        List<Point2D> outline = new ArrayList<>(); outline.add(center);
        for (int i = 0; i <= 24; i++) { double radians = Math.toRadians(start + i * 90.0 / 24); outline.add(center.add(radius * Math.cos(radians), radius * Math.sin(radians))); }
        VideoRegion region = new VideoRegion(name, color, outline, threshold, width, height);
        region.type = "CircularFractionRegion"; region.center = center; region.radius = radius; region.angle = start; return region;
    }

    public static String analysisType(JsonArray regions) {
        if (regions != null && regions.size() == 1) {
            JsonObject region = regions.get(0).getAsJsonObject();
            if (region.has("type") && "CircularFractionRegion".equals(region.get("type").getAsString())) {
                double span = region.has("fraction") ? region.get("fraction").getAsDouble() * 360 : region.get("angle_end").getAsDouble() - region.get("angle_start").getAsDouble();
                if (Double.isFinite(span) && Math.abs((span % 360 + 360) % 360 - 90) < 1e-6) return "MorrisPool";
            }
        }
        if (regions != null && regions.size() >= 2) {
            boolean polygons = true;
            for (JsonElement item : regions) polygons &= "PolygonRegion".equals(item.getAsJsonObject().get("type").getAsString());
            if (polygons) return "CrossMaze";
        }
        throw new IllegalArgumentException("CrossMaze requiere al menos dos poligonos; Morris, un unico cuadrante circular de 90 grados.");
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject(); json.addProperty("id", name); json.addProperty("type", type);
        json.addProperty("color", color); json.addProperty("overlap_threshold", threshold);
        json.addProperty("frameWidth", width); json.addProperty("frameHeight", height);
        if (isMorris()) {
            JsonArray origin = new JsonArray(); origin.add(center.getX()); origin.add(center.getY()); json.add("center", origin);
            json.addProperty("radius", radius); json.addProperty("angle_start", angle); json.addProperty("fraction", .25); return json;
        }
        JsonArray coordinates = new JsonArray();
        for (Point2D point : points) { JsonArray pair = new JsonArray(); pair.add(point.getX()); pair.add(point.getY()); coordinates.add(pair); }
        json.add("points", coordinates); return json;
    }

    public static VideoRegion fromJson(JsonObject json) {
        if (json.has("type") && "CircularFractionRegion".equals(json.get("type").getAsString())) {
            JsonArray origin = json.getAsJsonArray("center");
            analysisType(single(json));
            return morris(json.get("id").getAsString(), json.get("color").getAsString(), new Point2D(origin.get(0).getAsDouble(), origin.get(1).getAsDouble()),
                    json.get("radius").getAsDouble(), json.get("angle_start").getAsDouble(), json.get("overlap_threshold").getAsDouble(), json.get("frameWidth").getAsInt(), json.get("frameHeight").getAsInt());
        }
        List<Point2D> points = new ArrayList<>();
        for (JsonElement item : json.getAsJsonArray("points")) { JsonArray pair = item.getAsJsonArray(); points.add(new Point2D(pair.get(0).getAsDouble(), pair.get(1).getAsDouble())); }
        return new VideoRegion(json.get("id").getAsString(), json.get("color").getAsString(), points,
                json.get("overlap_threshold").getAsDouble(), json.get("frameWidth").getAsInt(), json.get("frameHeight").getAsInt());
    }
    private static JsonArray single(JsonObject region) { JsonArray array = new JsonArray(); array.add(region); return array; }
}
