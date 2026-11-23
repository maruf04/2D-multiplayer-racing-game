package racer;

import java.awt.geom.Path2D;

/**
 * Track geometry shared by server (physics, lap counting) and client (drawing).
 * The circuit runs around Dhanmondi Lake (see LakeData). The track is a closed
 * centerline polyline with a fixed half-width; the lake in the middle is a wall.
 * Checkpoint "gates" are placed every CP_STEP samples; gate 0 is the start/finish line.
 */
public final class Track {
    public static final String NAME = "Dhanmondi Lake Circuit";
    public static final int WORLD_W = LakeData.WORLD_W;
    public static final int WORLD_H = LakeData.WORLD_H;
    public static final double HALF_WIDTH = 95;
    public static final int SAMPLES = LakeData.CENTER.length / 2;
    public static final int CP_STEP = 12;
    public static final int CHECKPOINTS = SAMPLES / CP_STEP;

    public final double[] xs = new double[SAMPLES];
    public final double[] ys = new double[SAMPLES];
    public final double[] tx = new double[SAMPLES]; // unit tangent (direction of travel)
    public final double[] ty = new double[SAMPLES];
    private final Path2D lake = new Path2D.Double(Path2D.WIND_EVEN_ODD);

    public Track() {
        for (int i = 0; i < SAMPLES; i++) {
            xs[i] = LakeData.CENTER[2 * i];
            ys[i] = LakeData.CENTER[2 * i + 1];
        }
        for (int i = 0; i < SAMPLES; i++) {
            int a = (i + SAMPLES - 1) % SAMPLES, b = (i + 1) % SAMPLES;
            double dx = xs[b] - xs[a], dy = ys[b] - ys[a];
            double len = Math.hypot(dx, dy);
            tx[i] = dx / len;
            ty[i] = dy / len;
        }
        addPolygon(lake, LakeData.LAKE);
        for (int[] island : LakeData.ISLANDS) addPolygon(lake, island);
    }

    private static void addPolygon(Path2D p, int[] pts) {
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        p.closePath();
    }

    public Path2D centerPath() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < SAMPLES; i++) p.lineTo(xs[i], ys[i]);
        p.closePath();
        return p;
    }

    /** Lake water (islands are holes). */
    public Path2D lakeShape() {
        return (Path2D) lake.clone();
    }

    public boolean inLake(double x, double y) {
        return lake.contains(x, y);
    }

    /** Shortest distance from a point to the track centerline. */
    public double distanceToCenter(double px, double py) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            int j = (i + 1) % SAMPLES;
            double ax = xs[i], ay = ys[i], bx = xs[j], by = ys[j];
            double vx = bx - ax, vy = by - ay;
            double t = ((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy);
            t = Math.max(0, Math.min(1, t));
            double dx = ax + t * vx - px, dy = ay + t * vy - py;
            double d = dx * dx + dy * dy;
            if (d < best) best = d;
        }
        return Math.sqrt(best);
    }

    public boolean onTrack(double x, double y) {
        return distanceToCenter(x, y) <= HALF_WIDTH;
    }

    /** True if moving from (x0,y0) to (x1,y1) crossed checkpoint gate cp in the forward direction. */
    public boolean crossedGate(int cp, double x0, double y0, double x1, double y1) {
        int i = cp * CP_STEP;
        double d0 = (x0 - xs[i]) * tx[i] + (y0 - ys[i]) * ty[i];
        double d1 = (x1 - xs[i]) * tx[i] + (y1 - ys[i]) * ty[i];
        if (!(d0 < 0 && d1 >= 0)) return false;
        double lateral = Math.abs(-(x1 - xs[i]) * ty[i] + (y1 - ys[i]) * tx[i]);
        return lateral <= HALF_WIDTH + 40;
    }

    public double distanceToGate(int cp, double x, double y) {
        int i = cp * CP_STEP;
        return Math.hypot(x - xs[i], y - ys[i]);
    }

    /** Starting grid slot: returns {x, y, angle}. Two rows of two, behind the start line. */
    public double[] gridSlot(int slot) {
        int i = SAMPLES - (3 + (slot / 2) * 4);
        double side = (slot % 2 == 0 ? -1 : 1) * 42;
        double nx = -ty[i], ny = tx[i];
        return new double[] { xs[i] + nx * side, ys[i] + ny * side, Math.atan2(ty[i], tx[i]) };
    }
}
