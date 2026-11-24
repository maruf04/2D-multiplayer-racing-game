package racer;

import java.awt.geom.Path2D;

/**
 * Track geometry shared by server (physics, lap counting) and client (drawing).
 * The road is a closed race line (driven anti-clockwise) with a width that changes along the lap:
 * 4 lanes normally, narrowing to 2 lanes in a few places. Fences on both sides.
 * Checkpoint "gates" are placed every CP_STEP samples; gate 0 is the start/finish line.
 */
public final class Track {
    /** Widest road half width (4 lanes). */
    public static final double HALF_WIDTH = 110;
    /** Half width in the narrow sections (2 lanes). */
    public static final double NARROW_HALF = 66;
    public static final int LANES = 4;
    public static final double LANE_WIDTH = 2 * HALF_WIDTH / LANES;
    public static final int CP_STEP = 12;

    public final MapData map;
    public final String name;
    public final int worldW, worldH, samples, checkpoints;
    public final double[] xs, ys;
    public final double[] tx, ty;     // unit tangent (direction of travel)
    public final double[] half;       // road half width at each sample
    /** +1 if the (-ty, tx) side of the road is the inside of the loop (lake side), else -1. */
    public final int insideSign;
    private final Path2D water = new Path2D.Double(Path2D.WIND_EVEN_ODD);
    private final Path2D loop;

    public Track(MapData m) {
        map = m;
        name = m.name + ", " + m.city;
        worldW = m.worldW;
        worldH = m.worldH;
        samples = m.center.length / 2;
        checkpoints = samples / CP_STEP;
        xs = new double[samples];
        ys = new double[samples];
        tx = new double[samples];
        ty = new double[samples];
        half = new double[samples];
        for (int i = 0; i < samples; i++) {
            xs[i] = m.center[2 * i];
            ys[i] = m.center[2 * i + 1];
        }
        for (int i = 0; i < samples; i++) {
            int a = (i + samples - 1) % samples, b = (i + 1) % samples;
            double dx = xs[b] - xs[a], dy = ys[b] - ys[a];
            double len = Math.hypot(dx, dy);
            tx[i] = dx / len;
            ty[i] = dy / len;
        }
        for (int r = 0; r < m.rings.length; r++) {
            if (m.ringRole[r] == MapData.WATER || m.ringRole[r] == MapData.ISLAND) addPolygon(water, m.rings[r]);
        }
        loop = offsetPathRaw(0);
        int votes = 0;
        for (int i = 0; i < samples; i += 5) {
            if (loop.contains(xs[i] - ty[i] * 150, ys[i] + tx[i] * 150)) votes++; else votes--;
        }
        insideSign = votes >= 0 ? 1 : -1;
        computeWidths();
    }

    /** Three narrow sections per lap (never near the start), placed a little differently on every map. */
    private void computeWidths() {
        double shift = ((map.id.hashCode() & 0xff) / 255.0 - 0.5) * 0.08;
        double[] centers = { 0.30 + shift, 0.55 - shift, 0.79 + shift / 2 };
        double hold = 0.035, ramp = 0.03;
        for (int i = 0; i < samples; i++) {
            double f = i / (double) samples, narrow = 0;
            for (double c : centers) {
                double d = Math.abs(f - c);
                d = Math.min(d, 1 - d);
                double v = d < hold ? 1 : d < hold + ramp ? 0.5 * (1 + Math.cos(Math.PI * (d - hold) / ramp)) : 0;
                narrow = Math.max(narrow, v);
            }
            half[i] = HALF_WIDTH - (HALF_WIDTH - NARROW_HALF) * narrow;
        }
    }

    private static void addPolygon(Path2D p, int[] pts) {
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        p.closePath();
    }

    public Path2D centerPath() {
        return (Path2D) loop.clone();
    }

    /** Closed line parallel to the race line; positive offsets go toward the inside of the loop. */
    public Path2D offsetPath(double offset) {
        return offsetPathRaw(offset * insideSign);
    }

    private Path2D offsetPathRaw(double o) {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < samples; i++) {
            double x = xs[i] - ty[i] * o, y = ys[i] + tx[i] * o;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    /** Road edge (side +1 = inside, -1 = outside) moved out by extra pixels; follows the changing width. */
    public Path2D edgePath(int side, double extra) {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < samples; i++) {
            double o = (half[i] + extra) * side * insideSign;
            double x = xs[i] - ty[i] * o, y = ys[i] + tx[i] * o;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    /** Water of the lake and any ponds (islands are holes). */
    public Path2D waterShape() {
        return (Path2D) water.clone();
    }

    public boolean inWater(double x, double y) {
        return water.contains(x, y);
    }

    /** Closest point on the race line: returns {x, y, distance, segment, t}. */
    public double[] nearestOnCenter(double px, double py) {
        double best = Double.MAX_VALUE, bx = 0, by = 0, bt = 0;
        int bseg = 0;
        for (int i = 0; i < samples; i++) {
            int j = (i + 1) % samples;
            double ax = xs[i], ay = ys[i], vx = xs[j] - ax, vy = ys[j] - ay;
            double t = ((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy);
            t = Math.max(0, Math.min(1, t));
            double cx = ax + t * vx, cy = ay + t * vy;
            double d = (cx - px) * (cx - px) + (cy - py) * (cy - py);
            if (d < best) { best = d; bx = cx; by = cy; bseg = i; bt = t; }
        }
        return new double[] { bx, by, Math.sqrt(best), bseg, bt };
    }

    /** Road half width between sample seg and the next one. */
    public double halfAt(int seg, double t) {
        return half[seg] * (1 - t) + half[(seg + 1) % samples] * t;
    }

    public double distanceToCenter(double px, double py) {
        return nearestOnCenter(px, py)[2];
    }

    /** Index of the race-line sample closest to a point. */
    public int nearestSample(double x, double y) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < samples; i++) {
            double d = (xs[i] - x) * (xs[i] - x) + (ys[i] - y) * (ys[i] - y);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** True if moving from (x0,y0) to (x1,y1) crossed checkpoint gate cp in the forward direction. */
    public boolean crossedGate(int cp, double x0, double y0, double x1, double y1) {
        int i = cp * CP_STEP;
        double d0 = (x0 - xs[i]) * tx[i] + (y0 - ys[i]) * ty[i];
        double d1 = (x1 - xs[i]) * tx[i] + (y1 - ys[i]) * ty[i];
        if (!(d0 < 0 && d1 >= 0)) return false;
        double lateral = Math.abs(-(x1 - xs[i]) * ty[i] + (y1 - ys[i]) * tx[i]);
        return lateral <= half[i] + 20;
    }

    public double distanceToGate(int cp, double x, double y) {
        int i = cp * CP_STEP;
        return Math.hypot(x - xs[i], y - ys[i]);
    }

    /**
     * Starting grid: 4 lanes across, rows of 4 behind the start line (the start area is always full width).
     * Returns {x, y, angle} for grid position 0..15.
     */
    public double[] gridSlot(int slot) {
        int row = slot / LANES, lane = slot % LANES;
        double back = 60 + row * 72;
        int i = 0;
        double x = xs[0], y = ys[0];
        while (back > 0) {
            int j = (i + samples - 1) % samples;
            double seg = Math.hypot(xs[j] - x, ys[j] - y);
            if (seg >= back) {
                x += (xs[j] - x) * back / seg;
                y += (ys[j] - y) * back / seg;
                back = 0;
            } else {
                back -= seg;
                x = xs[j];
                y = ys[j];
                i = j;
            }
        }
        double side = (lane - (LANES - 1) / 2.0) * LANE_WIDTH;
        return new double[] { x - ty[i] * side, y + tx[i] * side, Math.atan2(ty[i], tx[i]) };
    }
}
