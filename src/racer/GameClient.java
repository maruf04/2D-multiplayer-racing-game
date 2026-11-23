package racer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Random;

import static racer.Protocol.*;

/** Swing client: draws the world received from the server and sends keyboard input. */
public final class GameClient extends JPanel {
    private static final long serialVersionUID = 1L;
    static final Color[] CAR_COLORS = {
            new Color(230, 57, 70), new Color(40, 120, 235), new Color(250, 200, 30), new Color(40, 190, 90) };

    private static final Font FONT_HUD = new Font(Font.SANS_SERIF, Font.BOLD, 16);
    private static final Font FONT_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font FONT_NAME = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 26);
    private static final Font FONT_HUGE = new Font(Font.SANS_SERIF, Font.BOLD, 110);
    private static final double MINI_SCALE = 210.0 / Math.max(Track.WORLD_W, Track.WORLD_H);

    private final Track track = new Track();
    private final BufferedImage trackImage = renderTrack(track);
    private final BufferedImage miniImage = renderMinimap(track);
    private final String hostInfo;
    private final Timer repaintTimer;

    private Socket socket;
    private DataOutputStream out;
    private int myId = -1;
    private int inputBits;          // currently held direction keys (IN_UP..IN_RIGHT)
    private boolean arcade = true;  // arcade steering (screen directions) vs classic (car-relative)
    private int sentBits = -1;
    private volatile Frames frames;
    private volatile String disconnectReason;

    /** Last two snapshots received; rendering interpolates between them. */
    private record Frames(Snapshot prev, Snapshot curr) { }

    public GameClient(String hostInfo) {
        this.hostInfo = hostInfo;
        setPreferredSize(new Dimension(1024, 680));
        setBackground(Color.BLACK);
        setFocusable(true);
        setDoubleBuffered(true);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { onKey(e, true); }
            @Override public void keyReleased(KeyEvent e) { onKey(e, false); }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { setInput(0); }
        });
        repaintTimer = new Timer(15, e -> {
            repaint();
            Toolkit.getDefaultToolkit().sync();
        });
    }

    // ------------------------------------------------------------------ networking

    /** Connects and performs the handshake. Throws IOException with a readable message on failure. */
    public void connect(String host, int port, String name) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            o.writeByte(C_HELLO);
            o.writeInt(VERSION);
            o.writeUTF(name);
            o.flush();

            byte type = in.readByte();
            if (type == S_REJECT) throw new IOException(in.readUTF());
            if (type != S_WELCOME) throw new IOException("Unexpected reply from server");
            myId = in.readByte();
            s.setSoTimeout(0);

            socket = s;
            out = o;
            Thread reader = new Thread(() -> readLoop(in), "net-reader");
            reader.setDaemon(true);
            reader.start();
            setInput(0); // tell the server our steering mode
            repaintTimer.start();
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    private void readLoop(DataInputStream in) {
        try {
            while (true) {
                byte type = in.readByte();
                if (type != S_STATE) throw new IOException("Unknown message " + type);
                Snapshot snap = Snapshot.read(in);
                Frames f = frames;
                frames = new Frames(f == null ? null : f.curr, snap);
            }
        } catch (IOException e) {
            disconnectReason = "Lost connection to server.";
            setInput(0);
        }
    }

    public void close() {
        repaintTimer.stop();
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    private void sendByte(byte type, Integer payload) {
        if (out == null || disconnectReason != null) return;
        try {
            synchronized (out) {
                out.writeByte(type);
                if (payload != null) out.writeByte(payload);
                out.flush();
            }
        } catch (IOException ignored) {
            // the reader thread reports the disconnect
        }
    }

    // ------------------------------------------------------------------ input

    private void onKey(KeyEvent e, boolean down) {
        int bit = switch (e.getKeyCode()) {
            case KeyEvent.VK_UP, KeyEvent.VK_W -> IN_UP;
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> IN_DOWN;
            case KeyEvent.VK_LEFT, KeyEvent.VK_A -> IN_LEFT;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> IN_RIGHT;
            default -> 0;
        };
        if (bit != 0) {
            setInput(down ? inputBits | bit : inputBits & ~bit);
        } else if (down && e.getKeyCode() == KeyEvent.VK_C) {
            arcade = !arcade;
            setInput(inputBits);
        } else if (down && e.getKeyCode() == KeyEvent.VK_ENTER) {
            sendByte(C_START, null);
        } else if (down && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            Window w = SwingUtilities.getWindowAncestor(this);
            if (w != null) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
        }
    }

    private void setInput(int bits) {
        inputBits = bits;
        int wire = bits | (arcade ? IN_ARCADE : 0);
        if (wire == sentBits) return;
        sentBits = wire;
        sendByte(C_INPUT, wire);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        int w = getWidth(), h = getHeight();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Frames f = frames;
        if (f == null) {
            centerText(g, disconnectReason != null ? disconnectReason : "Waiting for server...", FONT_TITLE, Color.WHITE, w / 2, h / 2);
            g.dispose();
            return;
        }
        Snapshot s = f.curr;
        int n = s.players.length;
        double[] px = new double[n], py = new double[n], pa = new double[n];
        interpolate(f, px, py, pa);

        int meIdx = -1;
        for (int i = 0; i < n; i++) if (s.players[i].id == myId) meIdx = i;

        // camera follows my car
        double camX = meIdx >= 0 ? px[meIdx] - w / 2.0 : 0, camY = meIdx >= 0 ? py[meIdx] - h / 2.0 : 0;
        camX = w >= Track.WORLD_W ? (Track.WORLD_W - w) / 2.0 : Math.max(0, Math.min(Track.WORLD_W - w, camX));
        camY = h >= Track.WORLD_H ? (Track.WORLD_H - h) / 2.0 : Math.max(0, Math.min(Track.WORLD_H - h, camY));
        int cx = (int) Math.round(camX), cy = (int) Math.round(camY);

        g.drawImage(trackImage, -cx, -cy, null);
        for (int i = 0; i < n; i++) drawCar(g, s.players[i], px[i] - cx, py[i] - cy, pa[i], i == meIdx);

        drawMinimap(g, s, px, py, w);
        drawHud(g, s, meIdx, w, h);

        if (disconnectReason != null) {
            shade(g, w, h, 0.6f);
            centerText(g, disconnectReason, FONT_TITLE, new Color(255, 110, 110), w / 2, h / 2 - 10);
            centerText(g, "Press ESC to quit", FONT_SMALL, Color.WHITE, w / 2, h / 2 + 24);
        }
        g.dispose();
    }

    private static void interpolate(Frames f, double[] px, double[] py, double[] pa) {
        Snapshot s = f.curr, p = f.prev;
        double alpha = 1;
        if (p != null && s.recvNanos > p.recvNanos) {
            alpha = (System.nanoTime() - s.recvNanos) / (double) (s.recvNanos - p.recvNanos);
            alpha = Math.max(0, Math.min(1, alpha));
        }
        for (int i = 0; i < s.players.length; i++) {
            PlayerView c = s.players[i];
            PlayerView o = p == null ? null : p.find(c.id);
            if (o == null || Math.hypot(c.x - o.x, c.y - o.y) > 150) { // new player or teleport to grid
                px[i] = c.x; py[i] = c.y; pa[i] = c.angle;
                continue;
            }
            px[i] = o.x + (c.x - o.x) * alpha;
            py[i] = o.y + (c.y - o.y) * alpha;
            pa[i] = o.angle + Math.IEEEremainder(c.angle - o.angle, 2 * Math.PI) * alpha;
        }
    }

    private static void drawCar(Graphics2D g, PlayerView p, double x, double y, double angle, boolean me) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        if (me) {
            g.setColor(new Color(255, 255, 255, 70));
            g.fillOval(-30, -30, 60, 60);
        }
        g.rotate(angle);
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(-17, -8, 40, 22, 8, 8);          // shadow
        g.setColor(new Color(20, 20, 20));
        g.fillRect(-15, -13, 9, 4); g.fillRect(-15, 9, 9, 4); // wheels
        g.fillRect(8, -13, 9, 4);   g.fillRect(8, 9, 9, 4);
        g.setColor(CAR_COLORS[p.id % CAR_COLORS.length]);
        g.fillRoundRect(-20, -11, 40, 22, 8, 8);          // body
        g.setColor(new Color(30, 40, 60));
        g.fillRoundRect(2, -8, 9, 16, 4, 4);              // windshield
        g.setColor(new Color(255, 255, 255, 160));
        g.fillRect(-14, -2, 14, 4);                       // stripe
        g.setTransform(saved);

        g.setFont(FONT_NAME);
        FontMetrics fm = g.getFontMetrics();
        String label = p.name;
        int tw = fm.stringWidth(label);
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRoundRect((int) x - tw / 2 - 4, (int) y - 42, tw + 8, 17, 6, 6);
        g.setColor(me ? Color.YELLOW : Color.WHITE);
        g.drawString(label, (int) x - tw / 2, (int) y - 29);
    }

    private void drawMinimap(Graphics2D g, Snapshot s, double[] px, double[] py, int w) {
        int mw = miniImage.getWidth(), mh = miniImage.getHeight();
        int mx = w - mw - 12, my = 12;
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(mx - 6, my - 6, mw + 12, mh + 12, 10, 10);
        g.drawImage(miniImage, mx, my, null);
        for (int i = 0; i < s.players.length; i++) {
            int dx = mx + (int) (px[i] * MINI_SCALE), dy = my + (int) (py[i] * MINI_SCALE);
            g.setColor(CAR_COLORS[s.players[i].id % CAR_COLORS.length]);
            g.fillOval(dx - 4, dy - 4, 8, 8);
            g.setColor(s.players[i].id == myId ? Color.WHITE : Color.BLACK);
            g.drawOval(dx - 4, dy - 4, 8, 8);
        }
    }

    private void drawHud(Graphics2D g, Snapshot s, int meIdx, int w, int h) {
        PlayerView me = meIdx >= 0 ? s.players[meIdx] : null;
        int n = s.players.length;

        // top-left status box
        panel(g, 12, 12, 210, 130);
        g.setFont(FONT_HUD);
        g.setColor(Color.WHITE);
        if (me != null) {
            boolean racing = s.phase == PH_RACING || s.phase == PH_RESULTS;
            g.drawString("LAP  " + (racing ? me.lap + " / " + LAPS : "-"), 26, 38);
            g.drawString("POS  " + (racing ? me.place + " / " + n : "-"), 26, 62);
            g.drawString("SPEED  " + Math.round(Math.abs(me.speed) * 0.4) + " km/h", 26, 86);
            String time = s.phase == PH_RACING ? formatTime(me.finished ? me.finishMs : s.timerMs) : "--:--.--";
            g.drawString("TIME  " + time, 26, 108);
            g.setFont(FONT_SMALL);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString((arcade ? "Steering: Arcade" : "Steering: Classic") + "  (C)", 26, 132);
        }

        // player list bottom-left
        int rowH = 20, boxH = 12 + n * rowH;
        panel(g, 12, h - boxH - 12, 210, boxH);
        g.setFont(FONT_SMALL);
        for (int i = 0; i < n; i++) {
            PlayerView p = s.players[i];
            int y = h - boxH - 12 + 22 + i * rowH;
            g.setColor(CAR_COLORS[p.id % CAR_COLORS.length]);
            g.fillOval(24, y - 10, 10, 10);
            g.setColor(p.id == myId ? Color.YELLOW : Color.WHITE);
            String tag = (p.id == s.hostId ? " (host)" : "") + (p.id == myId ? " (you)" : "");
            g.drawString(p.name + tag, 42, y);
        }

        switch (s.phase) {
            case PH_LOBBY -> drawLobby(g, s, w, h);
            case PH_COUNTDOWN -> {
                int secs = (int) Math.ceil(s.timerMs / 1000.0);
                centerText(g, String.valueOf(Math.max(1, secs)), FONT_HUGE, Color.WHITE, w / 2, h / 2 - 40);
            }
            case PH_RACING -> {
                if (s.timerMs < 1000) centerText(g, "GO!", FONT_HUGE, new Color(90, 255, 120), w / 2, h / 2 - 40);
                if (me != null && me.finished) {
                    panel(g, w / 2 - 210, 20, 420, 56);
                    centerText(g, "FINISHED  P" + me.place + "  " + formatTime(me.finishMs), FONT_TITLE, Color.YELLOW, w / 2, 58);
                }
            }
            case PH_RESULTS -> drawResults(g, s, w, h);
            default -> { }
        }
    }

    private void drawLobby(Graphics2D g, Snapshot s, int w, int h) {
        int bw = 560, bh = 190, bx = (w - bw) / 2, by = 24;
        panel(g, bx, by, bw, bh);
        centerText(g, Track.NAME + "  -  " + s.players.length + " / " + MAX_PLAYERS + " players", FONT_TITLE, Color.WHITE, w / 2, by + 42);
        String hostName = "?";
        for (PlayerView p : s.players) if (p.id == s.hostId) hostName = p.name;
        String action = s.hostId == myId ? "You are the host - press ENTER to start the race"
                : "Waiting for " + hostName + " (host) to start...";
        centerText(g, action, FONT_HUD, Color.YELLOW, w / 2, by + 82);
        centerText(g, "Free practice: drive around while you wait.", FONT_SMALL, Color.WHITE, w / 2, by + 112);
        centerText(g, "Arrows / WASD to drive   -   C switch steering   -   ESC quit   -   " + LAPS + " laps", FONT_SMALL, Color.LIGHT_GRAY, w / 2, by + 134);
        if (hostInfo != null) centerText(g, hostInfo, FONT_SMALL, new Color(140, 220, 255), w / 2, by + 164);
    }

    private void drawResults(Graphics2D g, Snapshot s, int w, int h) {
        int n = s.players.length, bw = 420, bh = 110 + n * 30, bx = (w - bw) / 2, by = (h - bh) / 2;
        panel(g, bx, by, bw, bh);
        centerText(g, "RACE RESULTS", FONT_TITLE, Color.WHITE, w / 2, by + 40);
        g.setFont(FONT_HUD);
        for (int i = 0; i < n; i++) {
            PlayerView p = s.players[i];
            int y = by + 80 + i * 30;
            g.setColor(CAR_COLORS[p.id % CAR_COLORS.length]);
            g.fillOval(bx + 30, y - 13, 14, 14);
            g.setColor(p.id == myId ? Color.YELLOW : Color.WHITE);
            g.drawString(p.place + ".  " + p.name, bx + 56, y);
            String t = p.finished ? formatTime(p.finishMs) : "DNF";
            g.drawString(t, bx + bw - 30 - g.getFontMetrics().stringWidth(t), y);
        }
        centerText(g, "Back to lobby in " + (int) Math.ceil(s.timerMs / 1000.0) + "s", FONT_SMALL, Color.LIGHT_GRAY, w / 2, by + bh - 16);
    }

    // ------------------------------------------------------------------ drawing helpers

    private static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(x, y, w, h, 12, 12);
    }

    private static void shade(Graphics2D g, int w, int h, float a) {
        var c = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.setComposite(c);
    }

    private static void centerText(Graphics2D g, String text, Font font, Color color, int cx, int baseline) {
        g.setFont(font);
        int tw = g.getFontMetrics().stringWidth(text);
        g.setColor(new Color(0, 0, 0, 160));
        g.drawString(text, cx - tw / 2 + 2, baseline + 2);
        g.setColor(color);
        g.drawString(text, cx - tw / 2, baseline);
    }

    static String formatTime(int ms) {
        return String.format("%d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms / 10) % 100);
    }

    /** Pre-renders the whole map once, so each frame is a single image blit (cheap on weak GPUs). */
    private static BufferedImage renderTrack(Track t) {
        BufferedImage img = new BufferedImage(Track.WORLD_W, Track.WORLD_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Random r = new Random(7);
        float hw = (float) Track.HALF_WIDTH;
        Path2D path = t.centerPath();
        Path2D lake = t.lakeShape();

        // grass
        g.setColor(new Color(62, 132, 58));
        g.fillRect(0, 0, Track.WORLD_W, Track.WORLD_H);
        for (int i = 0; i < 1400; i++) {
            g.setColor(r.nextBoolean() ? new Color(55, 122, 52) : new Color(72, 144, 64));
            int s = 20 + r.nextInt(60);
            g.fillOval(r.nextInt(Track.WORLD_W), r.nextInt(Track.WORLD_H), s, s / 2 + 10);
        }

        // Dhanmondi houses and apartment blocks beyond the lakeside road
        Color[] walls = { new Color(236, 226, 205), new Color(245, 245, 240), new Color(205, 205, 210),
                new Color(200, 120, 90), new Color(232, 200, 150), new Color(180, 200, 215) };
        java.util.List<Rectangle> houses = new java.util.ArrayList<>();
        for (int i = 0; i < 6000 && houses.size() < 420; i++) {
            int w = 70 + r.nextInt(90), h = 60 + r.nextInt(80);
            Rectangle b = new Rectangle(r.nextInt(Track.WORLD_W - w), r.nextInt(Track.WORLD_H - h), w, h);
            if (!clearOfTrack(t, b, Track.HALF_WIDTH + 70) || lake.intersects(b)) continue;
            Rectangle grown = new Rectangle(b.x - 18, b.y - 18, b.width + 36, b.height + 36);
            if (houses.stream().anyMatch(grown::intersects)) continue;
            houses.add(b);
            g.setColor(new Color(0, 0, 0, 60));
            g.fillRect(b.x + 8, b.y + 8, b.width, b.height);
            Color c = walls[r.nextInt(walls.length)];
            g.setColor(c);
            g.fillRect(b.x, b.y, b.width, b.height);
            g.setColor(c.darker());
            g.setStroke(new BasicStroke(3));
            g.drawRect(b.x, b.y, b.width, b.height);
            g.setColor(new Color(90, 90, 100));                // rooftop water tank
            g.fillRect(b.x + 8 + r.nextInt(b.width - 30), b.y + 8 + r.nextInt(b.height - 30), 14, 14);
        }

        // lake: sandy walkway rim, water, ripples, shoreline highlight
        g.setStroke(new BasicStroke(26, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(214, 196, 156));
        g.draw(lake);
        g.setColor(new Color(44, 112, 180));
        g.fill(lake);
        g.setStroke(new BasicStroke(2));
        g.setColor(new Color(110, 170, 225));
        Rectangle lb = lake.getBounds();
        for (int i = 0; i < 2500; i++) {
            int x = lb.x + r.nextInt(lb.width), y = lb.y + r.nextInt(lb.height);
            if (lake.contains(x - 6, y) && lake.contains(x + 6, y)) g.drawArc(x - 6, y - 3, 12, 6, 20, 140);
        }
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(150, 200, 240));
        g.draw(lake);

        // road: kerbs, asphalt, center line
        g.setStroke(new BasicStroke(2 * hw + 16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        g.draw(path);
        g.setStroke(new BasicStroke(2 * hw + 16, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 24, 24 }, 0));
        g.setColor(new Color(210, 40, 40));
        g.draw(path);
        g.setStroke(new BasicStroke(2 * hw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(72, 72, 78));
        g.draw(path);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 30, 30 }, 0));
        g.setColor(new Color(255, 255, 255, 150));
        g.draw(path);

        // trees along the lake and between the houses
        for (int i = 0; i < 9000; i++) {
            int x = r.nextInt(Track.WORLD_W), y = r.nextInt(Track.WORLD_H), rad = 9 + r.nextInt(9);
            Rectangle b = new Rectangle(x - rad, y - rad, 2 * rad, 2 * rad);
            if (lake.intersects(b) || !clearOfTrack(t, b, Track.HALF_WIDTH + 12)) continue;
            if (houses.stream().anyMatch(b::intersects)) continue;
            g.setColor(new Color(0, 0, 0, 60));
            g.fillOval(x - rad + 5, y - rad + 5, 2 * rad, 2 * rad);
            g.setColor(r.nextBoolean() ? new Color(30, 95, 40) : new Color(40, 110, 45));
            g.fillOval(x - rad, y - rad, 2 * rad, 2 * rad);
            g.setColor(new Color(80, 150, 70));
            g.fillOval(x - rad / 2 - 2, y - rad / 2 - 2, rad, rad);
        }

        // checkered start/finish line
        g.translate(t.xs[0], t.ys[0]);
        g.rotate(Math.atan2(t.ty[0], t.tx[0]));
        int rows = (int) (2 * hw / 10);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 2; col++) {
                g.setColor((row + col) % 2 == 0 ? Color.WHITE : Color.BLACK);
                g.fillRect(col * 10 - 10, (int) -hw + row * 10, 10, 10);
            }
        }
        g.dispose();
        return img;
    }

    /** True if every corner and the center of the box are at least minDist from the track centerline. */
    private static boolean clearOfTrack(Track t, Rectangle b, double minDist) {
        double pad = Math.hypot(b.width, b.height) / 2;
        return t.distanceToCenter(b.getCenterX(), b.getCenterY()) >= minDist + pad;
    }

    private static BufferedImage renderMinimap(Track t) {
        int w = (int) (Track.WORLD_W * MINI_SCALE), h = (int) (Track.WORLD_H * MINI_SCALE);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(MINI_SCALE, MINI_SCALE);
        g.setStroke(new BasicStroke((float) (2 * Track.HALF_WIDTH), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(200, 200, 200, 220));
        g.draw(t.centerPath());
        g.setColor(new Color(70, 140, 220));
        g.fill(t.lakeShape());
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ snapshot data

    static final class PlayerView {
        int id, lap, place, finishMs;
        String name;
        float x, y, angle, speed;
        boolean finished;
    }

    static final class Snapshot {
        byte phase;
        int timerMs, hostId;
        PlayerView[] players;
        long recvNanos;

        PlayerView find(int id) {
            for (PlayerView p : players) if (p.id == id) return p;
            return null;
        }

        static Snapshot read(DataInputStream in) throws IOException {
            Snapshot s = new Snapshot();
            s.phase = in.readByte();
            s.timerMs = in.readInt();
            s.hostId = in.readByte();
            int n = in.readByte();
            if (n < 0 || n > MAX_PLAYERS) throw new IOException("bad player count " + n);
            s.players = new PlayerView[n];
            for (int i = 0; i < n; i++) {
                PlayerView p = new PlayerView();
                p.id = in.readByte();
                p.name = in.readUTF();
                p.x = in.readFloat();
                p.y = in.readFloat();
                p.angle = in.readFloat();
                p.speed = in.readFloat();
                p.lap = in.readByte();
                p.place = in.readByte();
                p.finished = in.readBoolean();
                p.finishMs = in.readInt();
                s.players[i] = p;
            }
            s.recvNanos = System.nanoTime();
            return s;
        }
    }
}
