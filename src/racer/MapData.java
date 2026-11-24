package racer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One race track, loaded from src/racer/maps/&lt;id&gt;.map (made by tools/make_maps.py from OpenStreetMap data).
 * All coordinates are world pixels.
 */
final class MapData {
    // ring roles
    static final int WATER = 0, ISLAND = 1, STADIUM = 2, FIELD = 3, FOREST = 4, LAWN = 5;

    /** Short description of a track for menus. */
    record Info(String id, String name, String city, String type) {
        String title() { return name + ", " + city; }
    }

    final String id, name, city, type;
    final float scale;          // pixels per metre
    final int bank;             // road edge -> landmark distance in pixels
    final int worldW, worldH;
    final int[] center;         // race line, flattened x,y pairs, anti-clockwise, index 0 = start/finish
    final int[] ringRole;
    final int[][] rings;        // flattened x,y pairs
    final int[][] bridges;      // x1, y1, x2, y2, width
    final int[] streetWidth;
    final int[][] streets;
    final int[] buildingLevels;
    final int[][] buildings;

    private static final Map<String, MapData> CACHE = new ConcurrentHashMap<>();
    private static volatile List<Info> index;

    /** All tracks shipped with the game, in menu order. */
    static List<Info> list() {
        List<Info> l = index;
        if (l != null) return l;
        l = new ArrayList<>();
        try (InputStream in = MapData.class.getResourceAsStream("maps/index.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    String[] p = line.split("\\|");
                    if (p.length == 4) l.add(new Info(p[0], p[1], p[2], p[3]));
                }
            }
        } catch (IOException ignored) { }
        index = l;
        return l;
    }

    static Info info(String id) {
        for (Info i : list()) if (i.id().equals(id)) return i;
        return null;
    }

    static String defaultId() {
        List<Info> l = list();
        return l.isEmpty() ? "dhanmondi" : l.get(0).id();
    }

    static MapData load(String id) throws IOException {
        MapData m = CACHE.get(id);
        if (m != null) return m;
        if (!id.matches("[a-z0-9_]+")) throw new IOException("Bad track name: " + id);
        try (InputStream raw = MapData.class.getResourceAsStream("maps/" + id + ".map")) {
            if (raw == null) throw new IOException("Track '" + id + "' is missing. Update the game (run compile.sh).");
            m = new MapData(new DataInputStream(new BufferedInputStream(raw)));
        }
        CACHE.put(id, m);
        return m;
    }

    private MapData(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("RMAP")) throw new IOException("not a track file");
        int version = in.readShort();
        if (version != 1) throw new IOException("unsupported track file version " + version);
        id = in.readUTF();
        name = in.readUTF();
        city = in.readUTF();
        type = in.readUTF();
        scale = in.readFloat();
        bank = in.readShort();
        worldW = in.readInt();
        worldH = in.readInt();
        center = readPoints(in, in.readShort());
        int nr = in.readShort();
        ringRole = new int[nr];
        rings = new int[nr][];
        for (int i = 0; i < nr; i++) {
            ringRole[i] = in.readByte();
            rings[i] = readPoints(in, in.readShort());
        }
        int nb = in.readShort();
        bridges = new int[nb][5];
        for (int i = 0; i < nb; i++) for (int k = 0; k < 5; k++) bridges[i][k] = in.readShort();
        int ns = in.readInt();
        streetWidth = new int[ns];
        streets = new int[ns][];
        for (int i = 0; i < ns; i++) {
            streetWidth[i] = in.readByte();
            streets[i] = readPoints(in, in.readShort());
        }
        int nbu = in.readInt();
        buildingLevels = new int[nbu];
        buildings = new int[nbu][];
        for (int i = 0; i < nbu; i++) {
            buildingLevels[i] = in.readByte();
            buildings[i] = readPoints(in, in.readShort());
        }
    }

    private static int[] readPoints(DataInputStream in, int count) throws IOException {
        int[] p = new int[count * 2];
        for (int i = 0; i < p.length; i++) p[i] = in.readShort();
        return p;
    }

    Info info() {
        return new Info(id, name, city, type);
    }
}
