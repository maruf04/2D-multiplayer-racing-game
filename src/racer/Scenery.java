package racer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Everything around the road, client side only:
 *  - a pre-rendered map image: real streets and buildings from OpenStreetMap, the landmark inside the
 *    circuit (lake with bridges, cricket stadium, forest park or university campus), the lakeside walkway,
 *    the fenced road with its narrow sections, grandstands, food carts and trees
 *  - live animation drawn each frame: people walking, cheering fans, speed boats with wakes.
 */
final class Scenery {
    static final double VERGE = 190;                          // free space outside the road (stands, stalls)
    static final double WALK_OFFSET = Track.HALF_WIDTH + 58;  // walkway, measured from the race line toward the inside

    private static final Color GROUND = new Color(170, 166, 156);
    private static final Color GRASS = new Color(70, 138, 62);
    private static final Color WATER = new Color(44, 112, 180);
    private static final Color[] SKIN = { new Color(141, 85, 36), new Color(198, 134, 66), new Color(224, 172, 105),
            new Color(170, 110, 60), new Color(120, 72, 40) };
    private static final Color[] HAIR = { new Color(20, 15, 12), new Color(35, 25, 20), new Color(60, 40, 25) };
    private static final Color[] CLOTHES = { new Color(220, 40, 50), new Color(40, 110, 200), new Color(250, 250, 250),
            new Color(250, 200, 40), new Color(30, 150, 80), new Color(240, 120, 30), new Color(150, 60, 160),
            new Color(30, 30, 35), new Color(0, 160, 160), new Color(230, 90, 160) };
    private static final Color[] ROOFS = { new Color(226, 222, 214), new Color(205, 200, 190), new Color(236, 226, 205),
            new Color(214, 190, 170), new Color(190, 196, 204), new Color(232, 214, 180), new Color(196, 160, 140),
            new Color(186, 186, 180), new Color(240, 236, 228) };

    final BufferedImage image;
    final BufferedImage minimap;
    final double miniScale;

    private final Track t;
    private final MapData map;
    private final Path2D water;
    private final Path2D stadium, field, forest, lawn;
    private final List<Stand> stands = new ArrayList<>();
    private final List<Walker> walkers = new ArrayList<>();
    private final List<SpeedBoat> boats = new ArrayList<>();
    private final double[] walkX, walkY, walkLen;   // walkway polyline with cumulative length
    private final Random live = new Random(99);
    private final boolean rural;                    // few buildings: village with paddy fields instead of a city

    /** A grandstand (or the whole stadium) with its spectators. */
    private static final class Stand {
        final double cx, cy, angle, radius;
        final boolean stadium;
        final List<double[]> fans = new ArrayList<>();  // x, y, skinIndex, phase, facing angle
        double excitement;
        Stand(double cx, double cy, double angle, double radius, boolean stadium) {
            this.cx = cx; this.cy = cy; this.angle = angle; this.radius = radius; this.stadium = stadium;
        }
    }

    private static final class Walker {
        double s, speed, lateral, x1, y1, x2, y2;   // walkway walkers use s; bridge walkers use the segment
        boolean onBridge;
        Color shirt, hair, skin;
        double phase;
    }

    private static final class SpeedBoat {
        double x, y, heading, speed, turn, turnTimer;
        Color stripe;
        final double[] trailX = new double[18], trailY = new double[18];
        int trailN;
        double trailTimer;
    }

    Scenery(Track t, int seed) {
        this.t = t;
        this.map = t.map;
        this.water = t.waterShape();
        Path2D st = null, fi = null;
        Path2D fo = new Path2D.Double(Path2D.WIND_EVEN_ODD), la = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (int r = 0; r < map.rings.length; r++) {
            Path2D p = polygon(map.rings[r]);
            switch (map.ringRole[r]) {
                case MapData.STADIUM -> st = p;
                case MapData.FIELD -> fi = p;
                case MapData.FOREST -> fo.append(p, false);
                case MapData.LAWN -> la.append(p, false);
                default -> { }
            }
        }
        stadium = st;
        field = fi;
        rural = map.buildings.length < 150;
        forest = fo;
        lawn = la;
        Random r = new Random(7);           // fixed scenery (same for everybody)
        Random mobile = new Random(seed);   // food carts move around: the seed comes from the server

        int n = t.samples;
        walkX = new double[n + 1];
        walkY = new double[n + 1];
        walkLen = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            int k = i % n;
            walkX[i] = t.xs[k] - t.ty[k] * WALK_OFFSET * t.insideSign;
            walkY[i] = t.ys[k] + t.tx[k] * WALK_OFFSET * t.insideSign;
            if (i > 0) walkLen[i] = walkLen[i - 1] + Math.hypot(walkX[i] - walkX[i - 1], walkY[i] - walkY[i - 1]);
        }

        image = new BufferedImage(t.worldW, t.worldH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // occupancy mask at 1/4 scale: white = something is built there (no trees)
        BufferedImage mask = new BufferedImage(t.worldW / 4 + 1, t.worldH / 4 + 1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D m = mask.createGraphics();
        m.scale(0.25, 0.25);
        m.setColor(Color.WHITE);

        drawGround(g, r);
        drawLandmarkGround(g);
        drawCity(g, m, r);
        drawWater(g, m, r);
        drawStadium(g, m, r);
        drawBank(g, m, r);
        drawBridges(g, 0, 0);
        markBridges(m);
        drawRoad(g, m);
        buildStands(g, m, r);
        drawFoodStalls(g, m, mobile);
        drawTrees(g, mask, r);
        drawStartLine(g);
        g.dispose();
        m.dispose();

        miniScale = 210.0 / Math.max(t.worldW, t.worldH);
        minimap = new BufferedImage((int) (t.worldW * miniScale), (int) (t.worldH * miniScale), BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = minimap.createGraphics();
        mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        mg.scale(miniScale, miniScale);
        mg.setColor(new Color(60, 130, 60, 200));
        mg.fill(forest);
        mg.setColor(new Color(110, 180, 90, 200));
        mg.fill(lawn);
        if (stadium != null) {
            mg.setColor(new Color(170, 170, 175));
            mg.fill(stadium);
        }
        if (field != null) {
            mg.setColor(new Color(80, 170, 80));
            mg.fill(field);
        }
        mg.setColor(new Color(70, 140, 220));
        mg.fill(water);
        mg.setStroke(new BasicStroke((float) (2 * Track.HALF_WIDTH), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        mg.setColor(new Color(215, 215, 215, 235));
        mg.draw(t.centerPath());
        mg.dispose();

        makeWalkers(r);
        makeBoats(r);
        buildSoundMap();
    }

    // ------------------------------------------------------------------ what is around: for sound

    private static final int CELL = 64;
    private int gw, gh;
    private float[] waterCell, cityCell, greenCell;

    /** Coarse map of water, city (buildings + traffic) and trees, used for the surround sound. */
    private void buildSoundMap() {
        gw = t.worldW / CELL + 1;
        gh = t.worldH / CELL + 1;
        waterCell = new float[gw * gh];
        cityCell = new float[gw * gh];
        greenCell = new float[gw * gh];
        double reach = Math.max(Track.HALF_WIDTH + VERGE, Track.HALF_WIDTH + map.bank + 15);
        Path2D loop = t.centerPath();
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                double px = x * CELL + CELL / 2.0, py = y * CELL + CELL / 2.0;
                int i = y * gw + x;
                if (water.contains(px, py)) { waterCell[i] = 1; continue; }
                if (forest.contains(px, py) || lawn.contains(px, py) || loop.contains(px, py)
                        || t.distanceToCenter(px, py) < reach) greenCell[i] = 1;
                else if (rural) greenCell[i] = 0.6f;
            }
        }
        for (int[] b : map.buildings) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (int k = 0; k < b.length; k += 2) {
                minX = Math.min(minX, b[k]); maxX = Math.max(maxX, b[k]);
                minY = Math.min(minY, b[k + 1]); maxY = Math.max(maxY, b[k + 1]);
            }
            addCity((minX + maxX) / 2, (minY + maxY) / 2, (maxX - minX) * (float) (maxY - minY) / (CELL * CELL));
        }
        for (int s = 0; s < map.streets.length; s++) {              // traffic on the streets
            int[] p = map.streets[s];
            float weight = 0.12f * map.streetWidth[s] / 13f;
            for (int k = 0; k + 3 < p.length; k += 2) {
                double len = Math.hypot(p[k + 2] - p[k], p[k + 3] - p[k + 1]);
                for (double d = 0; d < len; d += 32) {
                    addCity((int) (p[k] + (p[k + 2] - p[k]) * d / len), (int) (p[k + 1] + (p[k + 3] - p[k + 1]) * d / len), weight);
                }
            }
        }
    }

    private void addCity(int x, int y, float amount) {
        int cx = x / CELL, cy = y / CELL;
        if (cx < 0 || cy < 0 || cx >= gw || cy >= gh) return;
        cityCell[cy * gw + cx] = Math.min(1, cityCell[cy * gw + cx] + amount);
    }

    /**
     * Environment sound levels around a listener, left and right:
     * {waterL, waterR, cityL, cityR, birdsL, birdsR, crowdL, crowdR}. (rx, ry) = the listener's "right" direction.
     */
    float[] ambient(double x, double y, double rx, double ry) {
        float[] acc = new float[6];
        double wsum = 0, radius = 640;
        int r = (int) (radius / CELL) + 1, gx = (int) (x / CELL), gy = (int) (y / CELL);
        for (int cy = Math.max(0, gy - r); cy <= Math.min(gh - 1, gy + r); cy++) {
            for (int cx = Math.max(0, gx - r); cx <= Math.min(gw - 1, gx + r); cx++) {
                double dx = cx * CELL + CELL / 2.0 - x, dy = cy * CELL + CELL / 2.0 - y, d = Math.hypot(dx, dy);
                if (d > radius) continue;
                double wgt = (1 - d / radius) * (1 - d / radius);
                double side = d < 1 ? 0 : (dx * rx + dy * ry) / d;        // -1 left .. +1 right
                double l = wgt * (0.6 - 0.45 * side), rr = wgt * (0.6 + 0.45 * side);
                int i = cy * gw + cx;
                acc[0] += waterCell[i] * l; acc[1] += waterCell[i] * rr;
                acc[2] += cityCell[i] * l;  acc[3] += cityCell[i] * rr;
                acc[4] += greenCell[i] * l; acc[5] += greenCell[i] * rr;
                wsum += wgt;
            }
        }
        float[] out = new float[8];
        if (wsum > 0) {
            float[] gain = { 2.2f, 2.6f, 1.5f };
            for (int k = 0; k < 6; k++) out[k] = (float) Math.min(1, acc[k] / wsum * gain[k / 2]);
        }
        for (Stand s : stands) {                                     // the crowd: loudest close to the stands
            double dx = s.cx - x, dy = s.cy - y, d = Math.hypot(dx, dy);
            double edge = d - (s.stadium ? s.radius : 0), reach = s.stadium ? 900 : 650;
            double level = Math.max(0, 1 - Math.max(0, edge) / reach) * (0.45 + 0.55 * s.excitement);
            if (level <= 0) continue;
            double side = d < 1 || (s.stadium && edge < 0) ? 0 : (dx * rx + dy * ry) / d;
            out[6] = (float) Math.max(out[6], level * Math.min(1, 1.15 - 0.85 * side));
            out[7] = (float) Math.max(out[7], level * Math.min(1, 1.15 + 0.85 * side));
        }
        return out;
    }

    private static Path2D polygon(int[] pts) {
        Path2D p = new Path2D.Double();
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        p.closePath();
        return p;
    }

    // ------------------------------------------------------------------ static map layers

    private void drawGround(Graphics2D g, Random r) {
        if (rural) {
            drawPaddyFields(g, r);
        } else {
            g.setColor(GROUND);
            g.fillRect(0, 0, t.worldW, t.worldH);
            for (int i = 0; i < t.worldW * t.worldH / 7000; i++) {
                int v = 150 + r.nextInt(30);
                g.setColor(new Color(v, v - 3, v - 10));
                int s = 10 + r.nextInt(50);
                g.fillOval(r.nextInt(t.worldW), r.nextInt(t.worldH), s, s / 2 + 6);
            }
        }
        // green park along the circuit (both sides of the road and the bank)
        Area park = parkArea();
        g.setColor(GRASS);
        g.fill(park);
        Shape oldClip = g.getClip();
        g.setClip(park);
        for (int i = 0; i < t.worldW * t.worldH / 6500; i++) {
            g.setColor(r.nextBoolean() ? new Color(60, 126, 55) : new Color(82, 150, 70));
            int s = 20 + r.nextInt(60);
            g.fillOval(r.nextInt(t.worldW), r.nextInt(t.worldH), s, s / 2 + 10);
        }
        g.setClip(oldClip);
    }

    /** The park: both sides of the road, the bank, and the land inside the loop. */
    /** Village surroundings: green and golden paddy fields separated by earth bunds. */
    private void drawPaddyFields(Graphics2D g, Random r) {
        g.setColor(new Color(150, 128, 92));                        // earth between the fields
        g.fillRect(0, 0, t.worldW, t.worldH);
        Color[] crops = { new Color(96, 160, 64), new Color(120, 176, 70), new Color(150, 186, 80),
                new Color(196, 186, 96), new Color(84, 148, 72), new Color(170, 196, 90) };
        double angle = r.nextDouble() * 0.6 - 0.3;
        AffineTransform saved = g.getTransform();
        g.rotate(angle, t.worldW / 2.0, t.worldH / 2.0);
        int pad = Math.max(t.worldW, t.worldH);
        for (int y = -pad / 2; y < t.worldH + pad / 2; ) {
            int rowH = 70 + r.nextInt(90);
            for (int x = -pad / 2; x < t.worldW + pad / 2; ) {
                int w = 90 + r.nextInt(160);
                Color c = crops[r.nextInt(crops.length)];
                g.setColor(c);
                g.fillRect(x + 3, y + 3, w - 6, rowH - 6);
                g.setColor(c.darker());                             // rows of rice plants
                for (int k = y + 10; k < y + rowH - 6; k += 9) g.drawLine(x + 6, k, x + w - 6, k);
                x += w;
            }
            y += rowH;
        }
        g.setTransform(saved);
    }

    private Area parkArea() {
        double reach = Math.max(Track.HALF_WIDTH + VERGE, Track.HALF_WIDTH + map.bank + 15);
        Area a = new Area(new BasicStroke((float) (2 * reach), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                .createStrokedShape(t.centerPath()));
        a.add(new Area(t.centerPath()));
        return a;
    }

    /** Lawns of a campus / garden, forest floor of a park. */
    private void drawLandmarkGround(Graphics2D g) {
        Shape oldClip = g.getClip();
        if (!lawn.getBounds().isEmpty()) {
            g.setColor(new Color(104, 172, 86));
            g.fill(lawn);
            g.setClip(lawn);
            g.setColor(new Color(116, 182, 96));
            Rectangle b = lawn.getBounds();
            for (int x = b.x; x < b.x + b.width; x += 48) g.fillRect(x, b.y, 24, b.height);   // mowing stripes
            g.setClip(oldClip);
        }
        if (!forest.getBounds().isEmpty()) {
            g.setColor(new Color(36, 92, 40));
            g.fill(forest);
        }
    }

    /** Real streets and building footprints (OpenStreetMap). */
    private void drawCity(Graphics2D g, Graphics2D m, Random r) {
        List<Path2D> streets = new ArrayList<>();
        for (int[] s : map.streets) {
            Path2D p = new Path2D.Float();
            for (int k = 0; k < s.length; k += 2) if (k == 0) p.moveTo(s[k], s[k + 1]); else p.lineTo(s[k], s[k + 1]);
            streets.add(p);
        }
        for (int i = 0; i < streets.size(); i++) {
            int w = map.streetWidth[i];
            g.setStroke(new BasicStroke(w + 8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(196, 192, 182));
            g.draw(streets.get(i));
            m.setStroke(g.getStroke());
            m.draw(streets.get(i));
        }
        for (int i = 0; i < streets.size(); i++) {
            g.setStroke(new BasicStroke(map.streetWidth[i], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(86, 86, 90));
            g.draw(streets.get(i));
        }
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 14, 14 }, 0));
        g.setColor(new Color(235, 235, 220, 170));
        for (int i = 0; i < streets.size(); i++) if (map.streetWidth[i] >= 18) g.draw(streets.get(i));

        // buildings: shadow by height, flat roofs with parapet, water tanks, stair rooms, plants
        for (int i = 0; i < map.buildings.length; i++) {
            int[] pts = map.buildings[i];
            Polygon p = new Polygon();
            for (int k = 0; k < pts.length; k += 2) p.addPoint(pts[k], pts[k + 1]);
            int levels = map.buildingLevels[i];
            if (levels <= 0) levels = 3 + r.nextInt(6);
            int sh = Math.min(22, 3 + levels * 2);
            g.setColor(new Color(0, 0, 0, 70));
            g.translate(sh, sh);
            g.fill(p);
            g.translate(-sh, -sh);
            Color roof = ROOFS[r.nextInt(ROOFS.length)];
            g.setColor(roof);
            g.fill(p);
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(roof.darker());
            g.draw(p);
            m.fill(p);
            Rectangle b = p.getBounds();
            if (b.width < 18 || b.height < 18) continue;
            int[] pt = pointInside(p, b, r);
            if (pt != null) {                                     // stair room
                g.setColor(new Color(0, 0, 0, 60));
                g.fillRect(pt[0] - 5, pt[1] - 4, 15, 12);
                g.setColor(roof.darker());
                g.fillRect(pt[0] - 7, pt[1] - 6, 14, 11);
            }
            for (int k = 0; k < 1 + r.nextInt(2); k++) {          // black water tanks
                pt = pointInside(p, b, r);
                if (pt == null) continue;
                g.setColor(new Color(30, 30, 34));
                g.fillOval(pt[0] - 4, pt[1] - 4, 9, 9);
                g.setColor(new Color(70, 70, 76));
                g.fillOval(pt[0] - 2, pt[1] - 3, 4, 4);
            }
            int extra = r.nextInt(10);
            if (extra < 3) {                                      // rooftop garden pots
                for (int k = 0; k < 6; k++) {
                    pt = pointInside(p, b, r);
                    if (pt == null) continue;
                    g.setColor(new Color(40 + r.nextInt(30), 120 + r.nextInt(40), 40));
                    g.fillOval(pt[0] - 3, pt[1] - 3, 6, 6);
                }
            } else if (extra == 3 && b.width > 30 && b.height > 30) { // solar panels
                pt = pointInside(p, b, r);
                if (pt != null) {
                    g.setColor(new Color(40, 60, 110));
                    g.fillRect(pt[0] - 8, pt[1] - 5, 16, 10);
                    g.setColor(new Color(120, 140, 190));
                    g.setStroke(new BasicStroke(1));
                    g.drawLine(pt[0], pt[1] - 5, pt[0], pt[1] + 5);
                }
            } else if (extra == 4) {                              // clothes drying on a line
                int[] a = pointInside(p, b, r), c = pointInside(p, b, r);
                if (a != null && c != null) {
                    g.setStroke(new BasicStroke(1));
                    g.setColor(new Color(90, 90, 90));
                    g.drawLine(a[0], a[1], c[0], c[1]);
                    for (int k = 1; k < 5; k++) {
                        g.setColor(CLOTHES[r.nextInt(CLOTHES.length)]);
                        g.fillRect(a[0] + (c[0] - a[0]) * k / 5 - 2, a[1] + (c[1] - a[1]) * k / 5 - 1, 4, 3);
                    }
                }
            }
        }
    }

    private static int[] pointInside(Polygon p, Rectangle b, Random r) {
        for (int k = 0; k < 8; k++) {
            int x = b.x + 6 + r.nextInt(Math.max(1, b.width - 12)), y = b.y + 6 + r.nextInt(Math.max(1, b.height - 12));
            if (p.contains(x, y) && p.contains(x - 6, y - 6) && p.contains(x + 6, y + 6)) return new int[] { x, y };
        }
        return null;
    }

    private void drawWater(Graphics2D g, Graphics2D m, Random r) {
        Rectangle lb = water.getBounds();
        if (lb.isEmpty()) return;
        g.setStroke(new BasicStroke(16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(176, 164, 136));          // stone embankment
        g.draw(water);
        g.setColor(WATER);
        g.fill(water);
        m.fill(water);
        g.setStroke(new BasicStroke(2));
        g.setColor(new Color(110, 170, 225));
        for (int i = 0; i < lb.width * (long) lb.height / 1500; i++) {
            int x = lb.x + r.nextInt(lb.width), y = lb.y + r.nextInt(lb.height);
            if (water.contains(x - 6, y) && water.contains(x + 6, y)) g.drawArc(x - 6, y - 3, 12, 6, 20, 140);
        }
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(150, 200, 240));
        g.draw(water);

        // moored paddle boats and families of ducks
        Color[] hulls = { new Color(250, 210, 40), new Color(230, 60, 60), new Color(60, 140, 230), Color.WHITE };
        for (int i = 0, boats = 0; i < 6000 && boats < 26; i++) {
            int x = lb.x + r.nextInt(lb.width), y = lb.y + r.nextInt(lb.height);
            double a = r.nextDouble() * 2 * Math.PI;
            if (!roomOnWater(x, y, a, 32, 22)) continue;
            boats++;
            AffineTransform saved = g.getTransform();
            g.translate(x, y);
            g.rotate(a);
            if (r.nextInt(3) == 0) {
                g.setColor(Color.WHITE);
                for (int d = 0; d < 4; d++) g.fillOval(-d * 12, (d % 2) * 6 - 3, d == 0 ? 9 : 6, d == 0 ? 7 : 5);
            } else {
                g.setColor(hulls[r.nextInt(hulls.length)]);
                g.fillRoundRect(-16, -9, 32, 18, 12, 12);
                g.setColor(new Color(255, 255, 255, 200));
                g.fillRoundRect(-8, -7, 14, 14, 6, 6);
            }
            g.setTransform(saved);
        }
    }

    private boolean roomOnWater(double x, double y, double a, double len, double wid) {
        double ca = Math.cos(a), sa = Math.sin(a);
        return water.contains(x, y) && water.contains(x + ca * len, y + sa * len) && water.contains(x - ca * len, y - sa * len)
                && water.contains(x - sa * wid, y + ca * wid) && water.contains(x + sa * wid, y - ca * wid);
    }

    /** Cricket stadium: plaza, packed stands, floodlights, the field with its pitch. */
    private void drawStadium(Graphics2D g, Graphics2D m, Random r) {
        if (stadium == null) return;
        Rectangle b = stadium.getBounds();
        double cx = b.getCenterX(), cy = b.getCenterY();
        g.setStroke(new BasicStroke(240, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(192, 188, 178));                       // paved plaza
        g.draw(stadium);
        m.setStroke(g.getStroke());
        m.draw(stadium);
        m.fill(stadium);
        g.setColor(new Color(0, 0, 0, 80));
        g.translate(16, 16);
        g.fill(stadium);
        g.translate(-16, -16);
        g.setColor(new Color(110, 110, 118));
        g.fill(stadium);

        Area ring = new Area(stadium);
        if (field != null) ring.subtract(new Area(field));
        Shape inner = field != null ? field : stadium;
        Shape oldClip = g.getClip();
        g.setClip(ring);
        int maxW = 2 * Math.max(b.width, b.height);
        for (int w = maxW, row = 0; w > 0; w -= 14, row++) {        // rows of seats around the field
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(row % 2 == 0 ? new Color(150, 150, 158) : new Color(125, 125, 134));
            g.draw(inner);
        }
        Color[] sections = { new Color(30, 140, 70, 90), new Color(210, 40, 50, 90), new Color(250, 200, 40, 90), new Color(40, 100, 200, 90) };
        int R = Math.max(b.width, b.height);
        for (int k = 0; k < 12; k++) {                              // coloured seating sections
            g.setColor(sections[k % sections.length]);
            g.fillArc((int) cx - R, (int) cy - R, 2 * R, 2 * R, k * 30 + 3, 24);
        }
        Stand s = new Stand(cx, cy, 0, Math.max(b.width, b.height) / 2.0, true);
        Rectangle rb = ring.getBounds();
        long fans = rb.width * (long) rb.height / 75;
        for (long i = 0; i < fans; i++) {                           // the crowd
            int x = rb.x + r.nextInt(rb.width), y = rb.y + r.nextInt(rb.height);
            if (!ring.contains(x, y) || r.nextInt(100) < 25) continue;
            g.setColor(CLOTHES[r.nextInt(CLOTHES.length)]);
            g.fillOval(x - 5, y - 3, 10, 7);
            int skin = r.nextInt(SKIN.length);
            g.setColor(SKIN[skin]);
            g.fillOval(x - 3, y - 4, 6, 6);
            g.setColor(r.nextInt(4) == 0 ? CLOTHES[r.nextInt(CLOTHES.length)] : HAIR[r.nextInt(HAIR.length)]);
            g.fillOval(x - 3, y - 4, 6, 5);
            if (s.fans.size() < 1500 && r.nextInt(3) == 0) {
                s.fans.add(new double[] { x, y, skin, r.nextDouble() * 6.28, Math.atan2(cy - y, cx - x) });
            }
        }
        g.setClip(oldClip);
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(70, 70, 76));                          // roof rim
        g.draw(stadium);
        if (field != null) {
            g.setColor(new Color(62, 150, 62));
            g.fill(field);
            g.setClip(field);
            Rectangle fb = field.getBounds();
            boolean tall = fb.height > fb.width;
            g.setColor(new Color(74, 164, 70));                     // mowing stripes
            if (tall) for (int y = fb.y; y < fb.y + fb.height; y += 52) g.fillRect(fb.x, y, fb.width, 26);
            else for (int x = fb.x; x < fb.x + fb.width; x += 52) g.fillRect(x, fb.y, 26, fb.height);
            g.setStroke(new BasicStroke(44, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.WHITE);                                // boundary rope
            g.draw(field);
            g.setStroke(new BasicStroke(38, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(58, 140, 58));
            g.draw(field);
            g.setClip(oldClip);
            double len = 20.12 * map.scale, wid = 3.05 * map.scale;  // the 22-yard pitch
            AffineTransform saved = g.getTransform();
            g.translate(fb.getCenterX(), fb.getCenterY());
            if (tall) g.rotate(Math.PI / 2);
            g.setColor(new Color(206, 186, 136));
            g.fillRect((int) (-len / 2 - 6), (int) (-wid / 2 - 4), (int) len + 12, (int) wid + 8);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2));
            g.drawLine((int) (-len / 2 + 8), (int) (-wid / 2 - 4), (int) (-len / 2 + 8), (int) (wid / 2 + 4));
            g.drawLine((int) (len / 2 - 8), (int) (-wid / 2 - 4), (int) (len / 2 - 8), (int) (wid / 2 + 4));
            g.setColor(new Color(120, 70, 30));
            for (int k = -1; k <= 1; k++) {                         // stumps
                g.fillRect((int) (-len / 2 + 2), k * 3 - 1, 3, 3);
                g.fillRect((int) (len / 2 - 5), k * 3 - 1, 3, 3);
            }
            g.setTransform(saved);
        }
        for (int k = 0; k < 4; k++) {                               // floodlight towers
            double a = Math.PI / 4 + k * Math.PI / 2, d = 0;
            while (d < 4000 && stadium.contains(cx + Math.cos(a) * d, cy + Math.sin(a) * d)) d += 4;
            int x = (int) (cx + Math.cos(a) * (d + 14)), y = (int) (cy + Math.sin(a) * (d + 14));
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, 70));
            g.drawLine(x, y, x + 90, y + 90);
            g.setColor(new Color(70, 70, 75));
            g.fillOval(x - 7, y - 7, 14, 14);
            AffineTransform saved = g.getTransform();
            g.translate(x, y);
            g.rotate(a + Math.PI / 2);
            g.setColor(new Color(60, 60, 64));
            g.fillRect(-16, -6, 32, 12);
            g.setColor(new Color(255, 250, 200));
            for (int q = -12; q <= 8; q += 7) g.fillRect(q, -3, 5, 6);
            g.setTransform(saved);
        }
        stands.add(s);
    }

    /** Wide bank between the road and the landmark: paved walkway with benches and lamp posts. */
    private void drawBank(Graphics2D g, Graphics2D m, Random r) {
        Path2D walk = t.offsetPath(WALK_OFFSET);
        g.setStroke(new BasicStroke(40, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(150, 120, 100));
        g.draw(walk);
        m.setStroke(g.getStroke());
        m.draw(walk);
        g.setStroke(new BasicStroke(34, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(196, 160, 130));
        g.draw(walk);
        g.setStroke(new BasicStroke(34, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 2, 10 }, 0));
        g.setColor(new Color(176, 140, 112));            // tile joints
        g.draw(walk);

        for (int i = 0; i < t.samples; i += 3) {
            double nx = -t.ty[i] * t.insideSign, ny = t.tx[i] * t.insideSign;
            if (i % 6 == 0) {                             // lamp post on the road side of the walkway
                double lx = t.xs[i] + nx * (WALK_OFFSET - 24), ly = t.ys[i] + ny * (WALK_OFFSET - 24);
                g.setColor(new Color(255, 240, 170, 60));
                g.fillOval((int) lx - 12, (int) ly - 12, 24, 24);
                g.setColor(new Color(45, 45, 50));
                g.fillOval((int) lx - 4, (int) ly - 4, 8, 8);
                g.setColor(new Color(255, 246, 200));
                g.fillOval((int) lx - 2, (int) ly - 2, 4, 4);
            } else if (i % 6 == 3 && r.nextInt(3) > 0) {   // bench on the far side of the walkway
                double bx = t.xs[i] + nx * (WALK_OFFSET + 27), by = t.ys[i] + ny * (WALK_OFFSET + 27);
                if (water.contains(bx, by)) continue;
                AffineTransform saved = g.getTransform();
                g.translate(bx, by);
                g.rotate(Math.atan2(t.ty[i], t.tx[i]));
                g.setColor(new Color(0, 0, 0, 60));
                g.fillRect(-11, -2, 24, 8);
                g.setColor(new Color(130, 85, 45));
                g.fillRect(-12, -4, 24, 8);
                g.setColor(new Color(100, 65, 35));
                g.drawLine(-12, 0, 12, 0);
                g.setTransform(saved);
            }
        }
    }

    /** Real bridges across the lake (OpenStreetMap). Drawn into the map and again over the speed boats. */
    private void drawBridges(Graphics2D g, int cx, int cy) {
        for (int[] b : map.bridges) {
            int w = b[4];
            int x1 = b[0] - cx, y1 = b[1] - cy, x2 = b[2] - cx, y2 = b[3] - cy;
            boolean road = w > 20;
            g.setStroke(new BasicStroke(w + 10, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, 70));
            g.drawLine(x1 + 6, y1 + 6, x2 + 6, y2 + 6);
            g.setColor(new Color(225, 225, 228));        // railings
            g.drawLine(x1, y1, x2, y2);
            g.setStroke(new BasicStroke(w + 10, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 3, 9 }, 0));
            g.setColor(new Color(110, 110, 115));        // railing posts
            g.drawLine(x1, y1, x2, y2);
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.setColor(road ? new Color(120, 120, 124) : new Color(200, 172, 136));
            g.drawLine(x1, y1, x2, y2);
            if (road) {
                g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 10, 10 }, 0));
                g.setColor(new Color(240, 240, 230));
                g.drawLine(x1, y1, x2, y2);
            }
        }
    }

    private void markBridges(Graphics2D m) {
        for (int[] b : map.bridges) {
            m.setStroke(new BasicStroke(b[4] + 10));
            m.drawLine(b[0], b[1], b[2], b[3]);
        }
    }

    /** Fences, kerbs, asphalt and lanes - the road is narrower in a few places. */
    private void drawRoad(Graphics2D g, Graphics2D m) {
        Path2D shadowArea = band(16);
        m.fill(shadowArea);
        for (int side = -1; side <= 1; side += 2) {
            Path2D fence = t.edgePath(side, 9);
            g.translate(4, 4);
            g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, 70));                     // fence shadow
            g.draw(fence);
            g.translate(-4, -4);
        }
        g.setColor(Color.WHITE);
        g.fill(band(2));
        for (int side = -1; side <= 1; side += 2) {
            g.setStroke(new BasicStroke(12, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 24, 24 }, 0));
            g.setColor(new Color(210, 40, 40));                     // kerbs
            g.draw(t.edgePath(side, -4));
        }
        g.setColor(new Color(70, 70, 76));                          // asphalt
        g.fill(band(-10));
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(240, 240, 240, 200));                  // edge lines
        g.draw(t.edgePath(1, -16));
        g.draw(t.edgePath(-1, -16));
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 26, 30 }, 0));
        g.setColor(new Color(255, 255, 255, 170));                  // lane dividers (fewer lanes where narrow)
        for (int lane = 1; lane < Track.LANES; lane++) {
            double off = -Track.HALF_WIDTH + lane * Track.LANE_WIDTH;
            Path2D p = new Path2D.Double();
            boolean drawing = false;
            for (int i = 0; i <= t.samples; i++) {
                int k = i % t.samples;
                boolean fits = Math.abs(off) < t.half[k] - 24;
                double x = t.xs[k] - t.ty[k] * off, y = t.ys[k] + t.tx[k] * off;
                if (fits && drawing) p.lineTo(x, y);
                else if (fits) p.moveTo(x, y);
                drawing = fits;
            }
            g.draw(p);
        }
        for (int side = -1; side <= 1; side += 2) {
            Path2D fence = t.edgePath(side, 9);
            g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(205, 208, 212));                   // steel rail
            g.draw(fence);
            g.setStroke(new BasicStroke(9, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 4, 16 }, 0));
            g.setColor(new Color(60, 62, 68));                      // fence posts
            g.draw(fence);
        }
    }

    /** The area between both road edges moved out by extra pixels. */
    private Path2D band(double extra) {
        Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        p.append(t.edgePath(1, extra), false);
        p.append(t.edgePath(-1, extra), false);
        return p;
    }

    /** Fixed grandstands on the outer side of the road, packed with spectators. */
    private void buildStands(Graphics2D g, Graphics2D m, Random r) {
        int count = 9;
        for (int k = 0; k < count; k++) {
            int i = (k * t.samples / count + 4) % t.samples;
            for (double len : new double[] { 280, 200, 140 }) {
                Stand s = tryStand(i, len);
                if (s == null) continue;
                drawStand(g, m, s, len, r);
                stands.add(s);
                break;
            }
        }
    }

    private Stand tryStand(int i, double len) {
        double out = -t.insideSign;          // outer side of the loop
        double nx = -t.ty[i] * out, ny = t.tx[i] * out;
        double near = Track.HALF_WIDTH + 26, far = near + 128;
        double cx = t.xs[i] + nx * (near + far) / 2, cy = t.ys[i] + ny * (near + far) / 2;
        for (double u = -0.5; u <= 0.5; u += 0.125) {
            for (double v : new double[] { near, far }) {
                double x = t.xs[i] + t.tx[i] * len * u + nx * v, y = t.ys[i] + t.ty[i] * len * u + ny * v;
                if (x < 10 || y < 10 || x > t.worldW - 10 || y > t.worldH - 10) return null;
                if (t.distanceToCenter(x, y) < Track.HALF_WIDTH + 20 || water.contains(x, y)) return null;
                if (stadium != null && stadium.contains(x, y)) return null;
            }
        }
        return new Stand(cx, cy, Math.atan2(ny, nx), 150, false);
    }

    private void drawStand(Graphics2D g, Graphics2D m, Stand s, double len, Random r) {
        int rows = 6, rowH = 19, depth = rows * rowH + 14, half = (int) (len / 2);
        AffineTransform saved = g.getTransform();
        g.translate(s.cx, s.cy);
        g.rotate(s.angle - Math.PI / 2);   // local +y points away from the road
        int top = -depth / 2;
        g.setColor(new Color(0, 0, 0, 80));
        g.fillRect(-half + 10, top + 10, 2 * half, depth);
        for (int row = 0; row < rows; row++) {                     // stepped concrete seating
            int shade = 150 + row * 12;
            g.setColor(new Color(shade, shade, shade + 4));
            g.fillRect(-half, top + row * rowH, 2 * half, rowH);
            g.setColor(new Color(shade - 40, shade - 40, shade - 36));
            g.fillRect(-half, top + row * rowH + rowH - 3, 2 * half, 3);
        }
        g.setColor(new Color(30, 80, 160));                         // back board with a sign
        g.fillRect(-half, top + rows * rowH, 2 * half, 14);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        String sign = (map.name + " Grand Prix").toUpperCase();
        if (g.getFontMetrics().stringWidth(sign) > 2 * half - 10) sign = "GRAND PRIX";
        g.drawString(sign, -g.getFontMetrics().stringWidth(sign) / 2, top + rows * rowH + 11);
        for (int x = -half; x < half; x += 12) {                   // bunting along the front
            g.setColor(CLOTHES[((x + half) / 12) % CLOTHES.length]);
            g.fillPolygon(new int[] { x, x + 12, x + 6 }, new int[] { top - 2, top - 2, top + 6 }, 3);
        }
        double ca = Math.cos(s.angle - Math.PI / 2), sa = Math.sin(s.angle - Math.PI / 2);
        for (int row = 0; row < rows; row++) {                     // spectators facing the road
            for (int x = -half + 8; x < half - 6; x += 11) {
                if (r.nextInt(100) < 14) continue;
                double fx = x + r.nextInt(3) - 1, fy = top + row * rowH + 9;
                g.setColor(CLOTHES[r.nextInt(CLOTHES.length)]);
                g.fillOval((int) fx - 6, (int) fy - 2, 12, 8);
                int skin = r.nextInt(SKIN.length);
                g.setColor(SKIN[skin]);
                g.fillOval((int) fx - 3, (int) fy - 5, 7, 7);
                int hat = r.nextInt(10);
                if (hat < 6) { g.setColor(HAIR[r.nextInt(HAIR.length)]); g.fillOval((int) fx - 3, (int) fy - 4, 7, 6); }
                else if (hat < 8) { g.setColor(CLOTHES[r.nextInt(CLOTHES.length)]); g.fillOval((int) fx - 4, (int) fy - 5, 8, 8); }
                s.fans.add(new double[] { s.cx + fx * ca - fy * sa, s.cy + fx * sa + fy * ca, skin, r.nextDouble() * 6.28, s.angle + Math.PI });
            }
        }
        g.setTransform(saved);
        AffineTransform mt = m.getTransform();
        m.translate(s.cx, s.cy);
        m.rotate(s.angle - Math.PI / 2);
        m.fillRect(-half - 6, top - 6, 2 * half + 12, depth + 12);
        m.setTransform(mt);
    }

    /** Mobile food carts (fuchka, jhalmuri, tea) with customers - positions change every session. */
    private void drawFoodStalls(Graphics2D g, Graphics2D m, Random r) {
        List<Rectangle> placed = new ArrayList<>();
        for (Stand s : stands) if (!s.stadium) placed.add(new Rectangle((int) s.cx - 160, (int) s.cy - 160, 320, 320));
        int made = 0;
        for (int attempt = 0; attempt < 500 && made < 18; attempt++) {
            int i = r.nextInt(t.samples);
            boolean bank = r.nextBoolean();
            double side = bank ? t.insideSign : -t.insideSign;
            double off = bank ? WALK_OFFSET + 36 : Track.HALF_WIDTH + 50 + r.nextInt(90);
            double nx = -t.ty[i] * side, ny = t.tx[i] * side;
            double x = t.xs[i] + nx * off, y = t.ys[i] + ny * off;
            Rectangle b = new Rectangle((int) x - 30, (int) y - 30, 60, 60);
            if (water.intersects(b) || t.distanceToCenter(x, y) < Track.HALF_WIDTH + 40) continue;
            if (stadium != null && stadium.intersects(b)) continue;
            if (placed.stream().anyMatch(b::intersects)) continue;
            placed.add(b);
            made++;
            AffineTransform saved = g.getTransform();
            g.translate(x, y);
            g.rotate(Math.atan2(t.ty[i], t.tx[i]) + r.nextDouble() - 0.5);
            g.setColor(new Color(0, 0, 0, 60));
            g.fillRect(-15, -8, 34, 20);
            g.setColor(new Color(150, 95, 50));            // wooden cart
            g.fillRect(-17, -10, 34, 20);
            g.setColor(new Color(30, 30, 30));
            g.fillRect(-12, -13, 8, 3); g.fillRect(-12, 10, 8, 3);
            g.setColor(new Color(220, 235, 240, 220));     // glass box of fuchka
            g.fillRect(-8, -6, 14, 12);
            Color c1 = CLOTHES[r.nextInt(CLOTHES.length)];
            for (int k = 0; k < 8; k++) {                  // umbrella with striped wedges
                g.setColor(k % 2 == 0 ? c1 : Color.WHITE);
                g.fillArc(-18, -18, 32, 32, k * 45, 45);
            }
            g.setColor(new Color(60, 60, 60));
            g.fillOval(-4, -4, 4, 4);
            for (int k = 0; k < 2 + r.nextInt(4); k++) {   // customers around the cart
                double a = r.nextDouble() * 2 * Math.PI, d = 26 + r.nextInt(6);
                drawPersonTop(g, Math.cos(a) * d, Math.sin(a) * d, a + Math.PI,
                        CLOTHES[r.nextInt(CLOTHES.length)], HAIR[r.nextInt(HAIR.length)], SKIN[r.nextInt(SKIN.length)], 0);
            }
            g.setTransform(saved);
            m.fillRect(b.x, b.y, b.width, b.height);
        }
    }

    /** Trees wherever nothing is built: dense in forests, plenty in parks and lawns, some in the city. */
    private void drawTrees(Graphics2D g, BufferedImage mask, Random r) {
        Area park = parkArea();
        Graphics2D mg = mask.createGraphics();
        mg.setColor(Color.WHITE);
        int attempts = t.worldW * t.worldH / 500;
        Rectangle fb = forest.getBounds();
        int forestAttempts = fb.isEmpty() ? 0 : (int) (fb.width * (long) fb.height / 150);
        for (int i = 0; i < attempts + forestAttempts; i++) {
            boolean inForestPass = i >= attempts;
            int x = inForestPass ? fb.x + r.nextInt(fb.width) : r.nextInt(t.worldW);
            int y = inForestPass ? fb.y + r.nextInt(fb.height) : r.nextInt(t.worldH);
            boolean inForest = inForestPass && forest.contains(x, y);
            if (inForestPass && !inForest) continue;
            boolean green = inForest || park.contains(x, y) || lawn.contains(x, y);
            if (!green && !rural && r.nextInt(3) > 0) continue;   // fewer trees in the city
            if (!green && rural && r.nextInt(5) > 0) continue;    // village trees in clumps, fields stay open
            int rad = inForest ? 11 + r.nextInt(12) : green ? 9 + r.nextInt(11) : 7 + r.nextInt(8);
            if (!free(mask, x, y, inForest ? rad / 2 : rad + 2)) continue;
            g.setColor(new Color(0, 0, 0, 60));
            g.fillOval(x - rad + 5, y - rad + 5, 2 * rad, 2 * rad);
            int shade = r.nextInt(3);
            g.setColor(shade == 0 ? new Color(30, 95, 40) : shade == 1 ? new Color(40, 110, 45) : new Color(55, 120, 35));
            g.fillOval(x - rad, y - rad, 2 * rad, 2 * rad);
            g.setColor(new Color(80, 150, 70));
            g.fillOval(x - rad / 2 - 2, y - rad / 2 - 2, rad, rad);
            if (!inForest) mg.fillOval((x - rad) / 4, (y - rad) / 4, rad / 2 + 1, rad / 2 + 1);
        }
        mg.dispose();
    }

    private static boolean free(BufferedImage mask, int x, int y, int rad) {
        int[][] pts = { { 0, 0 }, { rad, 0 }, { -rad, 0 }, { 0, rad }, { 0, -rad } };
        for (int[] p : pts) {
            int mx = (x + p[0]) / 4, my = (y + p[1]) / 4;
            if (mx < 0 || my < 0 || mx >= mask.getWidth() || my >= mask.getHeight()) return false;
            if ((mask.getRaster().getSample(mx, my, 0)) > 0) return false;
        }
        return true;
    }

    private void drawStartLine(Graphics2D g) {
        double hw = t.half[0] - 10;
        AffineTransform saved = g.getTransform();
        g.translate(t.xs[0], t.ys[0]);
        g.rotate(Math.atan2(t.ty[0], t.tx[0]));
        int rows = (int) (2 * hw / 10);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 3; col++) {
                g.setColor((row + col) % 2 == 0 ? Color.WHITE : Color.BLACK);
                g.fillRect(col * 10 - 15, (int) -hw + row * 10, 10, 10);
            }
        }
        g.rotate(Math.PI / 2);    // painted like real road text: readable for drivers approaching the line
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.setColor(new Color(255, 255, 255, 170));
        String text = "START / FINISH";
        g.drawString(text, -g.getFontMetrics().stringWidth(text) / 2, 58);
        g.setTransform(saved);
    }

    // ------------------------------------------------------------------ live animation

    private void makeWalkers(Random r) {
        double total = walkLen[walkLen.length - 1];
        int count = (int) Math.min(320, total / 45);
        for (int i = 0; i < count; i++) {
            Walker w = new Walker();
            w.s = r.nextDouble() * total;
            w.speed = (r.nextBoolean() ? 1 : -1) * (16 + r.nextDouble() * 22);
            w.lateral = r.nextDouble() * 24 - 12;
            styleWalker(w, r);
            walkers.add(w);
        }
        for (int[] b : map.bridges) {
            for (int k = 0; k < 3 + r.nextInt(4); k++) {
                Walker w = new Walker();
                w.onBridge = true;
                double lat = (r.nextDouble() - 0.5) * (b[4] - 6);
                double len = Math.hypot(b[2] - b[0], b[3] - b[1]);
                double nx = -(b[3] - b[1]) / len, ny = (b[2] - b[0]) / len;
                w.x1 = b[0] + nx * lat; w.y1 = b[1] + ny * lat;
                w.x2 = b[2] + nx * lat; w.y2 = b[3] + ny * lat;
                w.s = r.nextDouble() * len;
                w.speed = (r.nextBoolean() ? 1 : -1) * (14 + r.nextDouble() * 18);
                styleWalker(w, r);
                walkers.add(w);
            }
        }
    }

    private static void styleWalker(Walker w, Random r) {
        w.shirt = CLOTHES[r.nextInt(CLOTHES.length)];
        w.hair = r.nextInt(5) == 0 ? CLOTHES[r.nextInt(CLOTHES.length)] : HAIR[r.nextInt(HAIR.length)]; // some scarves
        w.skin = SKIN[r.nextInt(SKIN.length)];
        w.phase = r.nextDouble() * 6.28;
    }

    private void makeBoats(Random r) {
        Rectangle lb = water.getBounds();
        if (lb.isEmpty()) return;
        Color[] stripes = { new Color(220, 40, 50), new Color(30, 100, 200), new Color(250, 180, 20), new Color(20, 150, 90) };
        for (int i = 0; i < 8000 && boats.size() < 5; i++) {
            double x = lb.x + r.nextInt(lb.width), y = lb.y + r.nextInt(lb.height), a = r.nextDouble() * 6.28;
            if (!roomOnWater(x, y, a, 70, 45)) continue;
            SpeedBoat b = new SpeedBoat();
            b.x = x; b.y = y; b.heading = a;
            b.speed = 35 + r.nextInt(20);          // calm, slow cruising
            b.stripe = stripes[boats.size() % stripes.length];
            boats.add(b);
        }
    }

    /** Advances walkers, boats and crowd excitement. carXY = flattened vehicle positions. */
    void update(double dt, double[] carXY, boolean racing) {
        dt = Math.min(dt, 0.1);
        double total = walkLen[walkLen.length - 1];
        for (Walker w : walkers) {
            w.phase += dt * 7;
            if (w.onBridge) {
                double len = Math.hypot(w.x2 - w.x1, w.y2 - w.y1);
                w.s += w.speed * dt;
                if (w.s < 0 || w.s > len) { w.speed = -w.speed; w.s = Math.max(0, Math.min(len, w.s)); }
            } else {
                w.s = ((w.s + w.speed * dt) % total + total) % total;
            }
        }
        for (SpeedBoat b : boats) {
            b.turnTimer -= dt;
            if (b.turnTimer <= 0) {                 // gentle new course every few seconds
                b.turnTimer = 3 + live.nextDouble() * 4;
                b.turn = (live.nextDouble() - 0.5) * 0.5;
            }
            double look = 80;
            boolean ahead = water.contains(b.x + Math.cos(b.heading) * look, b.y + Math.sin(b.heading) * look);
            if (!ahead) {                           // steer toward the side with more water
                boolean left = water.contains(b.x + Math.cos(b.heading - 0.7) * look, b.y + Math.sin(b.heading - 0.7) * look);
                b.heading += (left ? -1 : 1) * 1.1 * dt;
            } else {
                b.heading += b.turn * dt;
            }
            double nx = b.x + Math.cos(b.heading) * b.speed * dt, ny = b.y + Math.sin(b.heading) * b.speed * dt;
            if (water.contains(nx, ny)) { b.x = nx; b.y = ny; } else b.heading += 2.0 * dt;
            b.trailTimer += dt;
            if (b.trailTimer > 0.09) {
                b.trailTimer = 0;
                System.arraycopy(b.trailX, 0, b.trailX, 1, b.trailX.length - 1);
                System.arraycopy(b.trailY, 0, b.trailY, 1, b.trailY.length - 1);
                b.trailX[0] = b.x;
                b.trailY[0] = b.y;
                b.trailN = Math.min(b.trailN + 1, b.trailX.length);
            }
        }
        for (Stand s : stands) {
            double nearest = Double.MAX_VALUE;
            for (int i = 0; i + 1 < carXY.length; i += 2) nearest = Math.min(nearest, Math.hypot(carXY[i] - s.cx, carXY[i + 1] - s.cy));
            if (s.stadium) nearest = Math.max(0, nearest - s.radius);
            double target = racing ? Math.max(0.2, 1 - nearest / 600) : 0.1;
            s.excitement += (target - s.excitement) * Math.min(1, dt * 3);
        }
    }

    /** 0..1: how loud the spectators are at a position. */
    double crowdLevel(double x, double y) {
        double best = 0;
        for (Stand s : stands) {
            double d = Math.hypot(x - s.cx, y - s.cy) - (s.stadium ? s.radius : 0);
            double reach = s.stadium ? 900 : 650;
            best = Math.max(best, Math.max(0, 1 - Math.max(0, d) / reach) * (0.45 + 0.55 * s.excitement));
        }
        return best;
    }

    /** True if a vehicle at (x,y) is right in front of the spectators. */
    boolean nearStand(double x, double y, double dist) {
        for (Stand s : stands) if (Math.hypot(x - s.cx, y - s.cy) - (s.stadium ? s.radius : 0) < dist) return true;
        return false;
    }

    /** Draws the animated parts inside the visible world area (world coordinates; the caller sets the camera). */
    void drawLive(Graphics2D g, Rectangle visible, double time) {
        int cx = 0, cy = 0;
        Rectangle view = new Rectangle(visible.x - 60, visible.y - 60, visible.width + 120, visible.height + 120);
        boolean boatOnScreen = false;
        for (SpeedBoat b : boats) {
            if (!view.contains(b.x, b.y)) continue;
            boatOnScreen = true;
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < b.trailN; i++) {                     // soft V-shaped wake
                double a = Math.atan2(b.trailY[i - 1] - b.trailY[i], b.trailX[i - 1] - b.trailX[i]);
                double spread = 5 + i * 1.6, nx = -Math.sin(a), ny = Math.cos(a);
                int alpha = (int) (110 * (1 - i / (double) b.trailN));
                g.setColor(new Color(235, 245, 255, alpha));
                g.drawLine((int) (b.trailX[i] + nx * spread - cx), (int) (b.trailY[i] + ny * spread - cy),
                        (int) (b.trailX[i - 1] + nx * (spread - 1.6) - cx), (int) (b.trailY[i - 1] + ny * (spread - 1.6) - cy));
                g.drawLine((int) (b.trailX[i] - nx * spread - cx), (int) (b.trailY[i] - ny * spread - cy),
                        (int) (b.trailX[i - 1] - nx * (spread - 1.6) - cx), (int) (b.trailY[i - 1] - ny * (spread - 1.6) - cy));
            }
            AffineTransform saved = g.getTransform();
            g.translate(b.x - cx, b.y - cy);
            g.rotate(b.heading);
            Path2D hull = new Path2D.Double();
            hull.moveTo(26, 0); hull.lineTo(10, -9); hull.lineTo(-18, -9); hull.lineTo(-18, 9); hull.lineTo(10, 9); hull.closePath();
            g.setColor(new Color(0, 0, 0, 70));
            g.translate(3, 3); g.fill(hull); g.translate(-3, -3);
            g.setColor(Color.WHITE);
            g.fill(hull);
            g.setColor(b.stripe);
            g.fillRect(-18, -2, 28, 4);
            g.setColor(new Color(40, 60, 90));
            g.fillRoundRect(0, -6, 7, 12, 3, 3);                     // windshield
            g.setColor(new Color(40, 40, 40));
            g.fillRect(-22, -4, 5, 8);                               // outboard motor
            g.setTransform(saved);
        }
        if (boatOnScreen) drawBridges(g, cx, cy);                    // boats pass under the bridges
        for (Walker wk : walkers) {                                  // people walking on the bank and bridges
            double x, y, dir;
            if (wk.onBridge) {
                double len = Math.hypot(wk.x2 - wk.x1, wk.y2 - wk.y1), f = wk.s / len;
                x = wk.x1 + (wk.x2 - wk.x1) * f;
                y = wk.y1 + (wk.y2 - wk.y1) * f;
                dir = Math.atan2(wk.y2 - wk.y1, wk.x2 - wk.x1) + (wk.speed < 0 ? Math.PI : 0);
            } else {
                int k = segmentAt(wk.s);
                double f = (wk.s - walkLen[k]) / Math.max(1e-6, walkLen[k + 1] - walkLen[k]);
                double sx = walkX[k + 1] - walkX[k], sy = walkY[k + 1] - walkY[k], sl = Math.hypot(sx, sy) + 1e-6;
                x = walkX[k] + sx * f - sy / sl * wk.lateral;
                y = walkY[k] + sy * f + sx / sl * wk.lateral;
                dir = Math.atan2(sy, sx) + (wk.speed < 0 ? Math.PI : 0);
            }
            if (!view.contains(x, y)) continue;
            AffineTransform saved = g.getTransform();
            g.translate(x - cx, y - cy);
            drawPersonTop(g, 0, 0, dir, wk.shirt, wk.hair, wk.skin, Math.sin(wk.phase));
            g.setTransform(saved);
        }
        for (Stand s : stands) {                                     // fans jump up and wave
            int reach = (int) s.radius + 160;
            if (!view.intersects(new Rectangle((int) s.cx - reach, (int) s.cy - reach, 2 * reach, 2 * reach))) continue;
            for (double[] f : s.fans) {
                if ((f[3] / 6.28) > s.excitement) continue;          // only the excited ones stand up
                if (!view.contains(f[0], f[1])) continue;
                int x = (int) f[0], y = (int) f[1];
                double wave = Math.sin(time * 9 + f[3]);
                double a = f[4];                                     // facing the action
                double px = -Math.sin(a), py = Math.cos(a);
                g.setColor(SKIN[(int) f[2]]);
                g.fillOval((int) (x + px * 6 + Math.cos(a) * (2 + wave * 2)) - 2, (int) (y + py * 6 + Math.sin(a) * (2 + wave * 2)) - 2, 5, 5);
                g.fillOval((int) (x - px * 6 + Math.cos(a) * (2 - wave * 2)) - 2, (int) (y - py * 6 + Math.sin(a) * (2 - wave * 2)) - 2, 5, 5);
            }
        }
    }

    private int segmentAt(double s) {
        int lo = 0, hi = walkLen.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (walkLen[mid] <= s) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** A person seen from above: shoulders, swinging arms, head with hair. */
    static void drawPersonTop(Graphics2D g, double x, double y, double dir, Color shirt, Color hair, Color skin, double swing) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.rotate(dir);
        g.setColor(new Color(0, 0, 0, 50));
        g.fillOval(-3, -5, 9, 13);
        g.setColor(skin);                                   // arms swinging while walking
        g.fillOval((int) (swing * 3) - 2, -8, 4, 4);
        g.fillOval((int) (-swing * 3) - 2, 4, 4, 4);
        g.setColor(shirt);
        g.fillOval(-4, -7, 8, 14);                          // shoulders
        g.setColor(skin);
        g.fillOval(-3, -3, 7, 7);                           // head
        g.setColor(hair);
        g.fillOval(-4, -3, 6, 7);                           // hair on the back of the head
        g.setTransform(saved);
    }
}
