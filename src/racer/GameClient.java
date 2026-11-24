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
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static racer.Protocol.*;

/** Swing client: draws the world received from the server, plays the sounds and sends keyboard input. */
public final class GameClient extends JPanel {
    private static final long serialVersionUID = 1L;

    static final Color[] CAR_COLORS = {
            new Color(230, 57, 70), new Color(40, 120, 235), new Color(250, 200, 30), new Color(40, 190, 90),
            new Color(245, 130, 30), new Color(150, 70, 200), new Color(20, 190, 200), new Color(240, 100, 170),
            new Color(130, 200, 40), new Color(245, 245, 245), new Color(60, 60, 70), new Color(170, 110, 60),
            new Color(0, 90, 160), new Color(200, 0, 90), new Color(255, 160, 140), new Color(110, 150, 110) };

    private static final Font FONT_HUD = new Font(Font.SANS_SERIF, Font.BOLD, 18);
    private static final Font FONT_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 15);
    private static final Font FONT_LIST = new Font(Font.SANS_SERIF, Font.BOLD, 15);
    private static final Font FONT_LABEL = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font FONT_VALUE = new Font(Font.SANS_SERIF, Font.BOLD, 22);
    private static final Font FONT_NAME = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 26);
    private static final Font FONT_BIG = new Font(Font.SANS_SERIF, Font.BOLD, 54);
    private static final Font FONT_HUGE = new Font(Font.SANS_SERIF, Font.BOLD, 110);

    private volatile Track track;
    private int myVehicle = CAR;
    private volatile String hostInfo;
    private final Timer repaintTimer;
    private final SoundEngine sound = new SoundEngine();
    private final Random rnd = new Random();
    private volatile Scenery scenery;

    private Socket socket;
    private DataOutputStream out;
    private int myId = -1;
    private int inputBits;          // currently held direction keys (IN_UP..IN_RIGHT)
    /** 0 = still map, you steer the vehicle (default); 1 = camera turns with you; 2 = still map, arrows are directions. */
    private int viewMode;
    private static final String[] VIEW_NAMES = { "Map view", "Turning camera", "Arrow keys" };
    private double camAngle;        // smoothed heading the driver-view camera follows
    private boolean camReady;
    private double rightX = 1, rightY; // the screen's "right" direction in the world (for stereo sound)
    private int ambientTimer;
    private double paintMs;
    private int slowFrames;
    private int sentBits = -1;
    private volatile Frames frames;
    private volatile String disconnectReason;

    // HUD + sound bookkeeping (EDT only)
    private int lastLap = 1;
    private String banner;
    private long bannerUntil;
    private long lastPaintNanos;
    private Snapshot lastSoundSnap;
    private byte lastPhase = -1;
    private int lastCountdownSec = -1;
    private boolean wasFinished;
    private long crowdCooldown, hornCooldown, scrapeCooldown, crashCooldown;

    /** Last two snapshots received; rendering interpolates between them. */
    private record Frames(Snapshot prev, Snapshot curr) { }

    public GameClient(String hostInfo) {
        this.hostInfo = hostInfo;
        setPreferredSize(new Dimension(1100, 720));
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

    /** Line shown in the lobby, e.g. where friends find this game. */
    public void setHostInfo(String info) {
        hostInfo = info;
    }

    // ------------------------------------------------------------------ networking

    /**
     * Connects, performs the handshake and builds the map (takes a few seconds).
     * Throws IOException with a readable message on failure.
     */
    public void connect(String host, int port, String name, int vehicle) throws IOException {
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
            o.writeByte(vehicle);
            o.flush();

            byte type = in.readByte();
            if (type == S_REJECT) throw new IOException(in.readUTF());
            if (type != S_WELCOME) throw new IOException("Unexpected reply from server");
            myId = in.readByte();
            int seed = in.readInt();
            String trackId = in.readUTF();
            s.setSoTimeout(0);

            myVehicle = vehicle;
            Track t = new Track(MapData.load(trackId));
            scenery = new Scenery(t, seed);          // render the map (state messages wait in the socket buffer)
            track = t;
            socket = s;
            out = o;
            Thread reader = new Thread(() -> readLoop(in), "net-reader");
            reader.setDaemon(true);
            reader.start();
            setInput(0); // tell the server our steering mode
            sound.start();
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
        sound.stop();
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
            viewMode = (viewMode + 1) % VIEW_NAMES.length;
            camReady = false;
            setInput(inputBits);
        } else if (down && e.getKeyCode() == KeyEvent.VK_H) {
            honk();
        } else if (down && e.getKeyCode() == KeyEvent.VK_M) {
            sound.toggleMute();
        } else if (down && e.getKeyCode() == KeyEvent.VK_ENTER) {
            sendByte(C_READY, null);
        } else if (down && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            Window w = SwingUtilities.getWindowAncestor(this);
            if (w != null) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
        }
    }

    private void setInput(int bits) {
        inputBits = bits;
        int wire = bits | (viewMode == 2 ? IN_ARCADE : IN_ASSIST);
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
        Scenery sc = scenery;
        Track track = this.track;
        if (f == null || sc == null || track == null) {
            centerText(g, disconnectReason != null ? disconnectReason : "Waiting for server...", FONT_TITLE, Color.WHITE, w / 2, h / 2);
            g.dispose();
            return;
        }
        long nowNs = System.nanoTime();
        double dt = lastPaintNanos == 0 ? 0 : Math.min(0.1, (nowNs - lastPaintNanos) / 1e9);
        lastPaintNanos = nowNs;

        Snapshot s = f.curr;
        int n = s.players.length;
        double[] px = new double[n], py = new double[n], pa = new double[n];
        interpolate(f, px, py, pa);
        double[] carXY = new double[2 * n];
        for (int i = 0; i < n; i++) { carXY[2 * i] = px[i]; carXY[2 * i + 1] = py[i]; }
        sc.update(dt, carXY, s.phase == PH_RACING || s.phase == PH_COUNTDOWN);

        int meIdx = -1;
        for (int i = 0; i < n; i++) if (s.players[i].id == myId) meIdx = i;
        PlayerView me = meIdx >= 0 ? s.players[meIdx] : null;

        // camera: driver view turns with my vehicle (forward is always up), map view keeps north up
        AffineTransform view = new AffineTransform();
        double rot = 0;
        if (me != null && viewMode == 1) {
            if (!camReady) { camAngle = pa[meIdx]; camReady = true; }
            camAngle += Math.IEEEremainder(pa[meIdx] - camAngle, 2 * Math.PI) * Math.min(1, dt * 3.2);
            rot = -camAngle - Math.PI / 2;
            view.translate(w / 2.0, h * 0.64);
            view.rotate(rot);
            view.translate(-px[meIdx], -py[meIdx]);
        } else {
            double camX = me != null ? px[meIdx] - w / 2.0 : 0, camY = me != null ? py[meIdx] - h / 2.0 : 0;
            camX = w >= track.worldW ? (track.worldW - w) / 2.0 : Math.max(0, Math.min(track.worldW - w, camX));
            camY = h >= track.worldH ? (track.worldH - h) / 2.0 : Math.max(0, Math.min(track.worldH - h, camY));
            view.translate(-Math.round(camX), -Math.round(camY));
        }
        rightX = Math.cos(-rot);
        rightY = Math.sin(-rot);

        g.setColor(new Color(150, 146, 136));
        g.fillRect(0, 0, w, h);
        AffineTransform screen = g.getTransform();
        Rectangle visible;
        try {
            visible = view.createInverse().createTransformedShape(new Rectangle(0, 0, w, h)).getBounds();
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            visible = new Rectangle(0, 0, track.worldW, track.worldH);
        }
        g.transform(view);
        // only the part of the map that is on screen is drawn (much faster when the view is turned)
        Rectangle part = visible.intersection(new Rectangle(0, 0, track.worldW, track.worldH));
        if (!part.isEmpty()) {
            if (rot != 0) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, SMOOTH_TURN);
            g.drawImage(sc.image.getSubimage(part.x, part.y, part.width, part.height), part.x, part.y, null);
        }
        sc.drawLive(g, visible, nowNs / 1e9);
        boolean guiding = me != null && !me.finished && (s.phase == PH_RACING || s.phase == PH_COUNTDOWN);
        if (guiding) drawNextGate(g, me, 0, 0);
        g.setTransform(screen);

        // vehicles and labels in screen space, so the names always stay upright
        Point2D.Double p = new Point2D.Double();
        double[] sx = new double[n], sy = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            view.transform(new Point2D.Double(px[i], py[i]), p);
            sx[i] = p.x;
            sy[i] = p.y;
            if (p.x < -80 || p.y < -80 || p.x > w + 80 || p.y > h + 80) continue;
            drawCar(g, s.players[i], p.x, p.y, pa[i] + rot, i == meIdx);
        }
        drawLabels(g, s, sx, sy, meIdx, w, h);
        boolean wrongWay = false;
        if (guiding) {
            view.transform(new Point2D.Double(px[meIdx], py[meIdx]), p);
            wrongWay = drawDirectionArrow(g, me, px[meIdx], py[meIdx], pa[meIdx], p.x, p.y, rot, s.phase == PH_RACING);
        }

        drawMinimap(g, sc, s, px, py, w);
        drawHud(g, s, meIdx, w, h);
        if (me != null) drawRaceGuidance(g, s, me, wrongWay, w, h);
        updateSound(sc, s, me, meIdx, px, py);

        // slow computer? turn the map with the faster (slightly rougher) method
        paintMs = paintMs * 0.95 + (System.nanoTime() - nowNs) / 1e6 * 0.05;
        if (paintMs > 18 && SMOOTH_TURN == RenderingHints.VALUE_INTERPOLATION_BILINEAR && ++slowFrames > 90) {
            SMOOTH_TURN = RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
        }

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
            if (o == null || Math.hypot(c.x - o.x, c.y - o.y) > 150) { // new car or teleport to grid
                px[i] = c.x; py[i] = c.y; pa[i] = c.angle;
                continue;
            }
            px[i] = o.x + (c.x - o.x) * alpha;
            py[i] = o.y + (c.y - o.y) * alpha;
            pa[i] = o.angle + Math.IEEEremainder(c.angle - o.angle, 2 * Math.PI) * alpha;
        }
    }

    // ------------------------------------------------------------------ sound

    /** H: your horn (car / motorbike) or bell (rickshaw / cycle). */
    private void honk() {
        long now = System.currentTimeMillis();
        if (now < hornCooldown) return;
        hornCooldown = now + 350;
        float[] fx = switch (myVehicle) {
            case MOTORBIKE -> sound.hornBike;
            case RICKSHAW -> sound.bellRickshaw;
            case CYCLE -> sound.bellCycle;
            default -> sound.hornCar;
        };
        sound.play(fx, 0.55);
    }

    /** -1 (left of the screen) .. +1 (right) for a world position, as heard from (mx, my). */
    private double panOf(double x, double y, double mx, double my) {
        double dx = x - mx, dy = y - my, d = Math.hypot(dx, dy);
        return d < 1 ? 0 : (dx * rightX + dy * rightY) / d * 0.9;
    }

    private void updateSound(Scenery sc, Snapshot s, PlayerView me, int meIdx, double[] px, double[] py) {
        Track track = this.track;
        double mx = meIdx >= 0 ? px[meIdx] : track.worldW / 2.0, my = meIdx >= 0 ? py[meIdx] : track.worldH / 2.0;
        int moveKeys = viewMode == 2 ? (IN_UP | IN_DOWN | IN_LEFT | IN_RIGHT) : IN_UP;
        boolean throttle = (inputBits & moveKeys) != 0 && s.phase != PH_COUNTDOWN;
        // my vehicle: pitch and loudness rise as it speeds up
        if (me != null) {
            double frac = Math.min(1, Math.abs(me.speed) / 520);
            boolean pedal = me.vehicle == CYCLE || me.vehicle == RICKSHAW;
            double vol = pedal ? 0.22 + frac * 0.38 : 0.12 + frac * 0.30 + (throttle ? 0.08 : 0);
            sound.setVehicle(0, me.vehicle, me.speed, vol, throttle ? 1 : 0, 0);
        } else {
            sound.setVehicle(0, CAR, 0, 0, 0, 0);
        }
        // the three closest other vehicles, heard from their side
        int[] near = { -1, -1, -1 };
        double[] nd = { 1e9, 1e9, 1e9 };
        for (int i = 0; i < s.players.length; i++) {
            if (i == meIdx) continue;
            double d = Math.hypot(px[i] - mx, py[i] - my);
            for (int k = 0; k < 3; k++) {
                if (d < nd[k]) {
                    for (int j = 2; j > k; j--) { nd[j] = nd[j - 1]; near[j] = near[j - 1]; }
                    nd[k] = d; near[k] = i;
                    break;
                }
            }
        }
        for (int k = 0; k < 3; k++) {
            if (near[k] < 0 || nd[k] > 900) { sound.setVehicle(k + 1, CAR, 0, 0, 0, 0); continue; }
            double fall = 1 - nd[k] / 900;
            PlayerView o = s.players[near[k]];
            sound.setVehicle(k + 1, o.vehicle, o.speed, 0.24 * fall * fall, Math.abs(o.speed) > 50 ? 0.6 : 0,
                    panOf(px[near[k]], py[near[k]], mx, my));
        }
        // surroundings: water, city traffic, birds and the crowd, from the side they are on
        boolean live = s.phase == PH_RACING || s.phase == PH_COUNTDOWN;
        if (ambientTimer-- <= 0) {
            ambientTimer = 6;
            float[] a = sc.ambient(mx, my, rightX, rightY);
            if (!live) { a[6] *= 0.55f; a[7] *= 0.55f; }
            sound.setAmbient(a);
        }
        double crowd = sc.crowdLevel(mx, my);

        long now = System.currentTimeMillis();
        if (s == lastSoundSnap) return;     // events once per server update
        lastSoundSnap = s;
        // bumps and fence scrapes, from the side they happen on
        for (int i = 0; i < s.players.length; i++) {
            PlayerView pl = s.players[i];
            if (pl.events == 0) continue;
            double d = i == meIdx ? 0 : Math.hypot(px[i] - mx, py[i] - my);
            double pan = i == meIdx ? 0 : panOf(px[i], py[i], mx, my);
            if ((pl.events & EV_HIT) != 0 && d < 600 && now > crashCooldown) {
                sound.play(sound.crash, i == meIdx ? 0.5 : 0.32 * (1 - d / 600), pan);
                crashCooldown = now + (i == meIdx ? 220 : 400);
            }
            if ((pl.events & EV_FENCE) != 0 && d < 400 && now > scrapeCooldown) {
                sound.play(sound.scrape, i == meIdx ? 0.3 : 0.15 * (1 - d / 400), pan);
                scrapeCooldown = now + (i == meIdx ? 250 : 600);
            }
        }
        if (s.phase == PH_COUNTDOWN) {
            int sec = (int) Math.ceil(s.timerMs / 1000.0);
            if (sec != lastCountdownSec && sec >= 1 && sec <= 3) sound.play(sound.beep, 0.8);
            lastCountdownSec = sec;
        } else {
            lastCountdownSec = -1;
        }
        if (s.phase != lastPhase) {
            if (s.phase == PH_RACING) {
                sound.play(sound.beepGo, 0.9);
                sound.play(sound.cheer, 0.7 + crowd * 0.3);
            } else if (s.phase == PH_RESULTS) {
                sound.play(sound.applause, 0.8);
                sound.play(sound.wolfWhistle, 0.5);
            }
            lastPhase = s.phase;
        }
        if (me == null) return;
        if (s.phase == PH_RACING && me.finished && !wasFinished) {
            sound.play(sound.airHorn, 0.7);
            sound.play(sound.applause, 1.0);
            sound.play(sound.whistle, 0.6);
            sound.play(sound.cheer, 0.9);
        }
        wasFinished = me.finished;
        if (s.phase == PH_RACING && me.lap != lastLapSound) {
            if (lastLapSound > 0) {
                sound.play(sound.cheer, 0.5 + crowd * 0.5);
                sound.play(sound.applause, 0.4 + crowd * 0.5);
            }
            lastLapSound = me.lap;
        }
        if (s.phase == PH_LOBBY) lastLapSound = 1;
        // the crowd reacts when you pass the spectators
        if (s.phase == PH_RACING && now > crowdCooldown && sc.nearStand(mx, my, 380)) {
            float[][] fx = { sound.whistle, sound.wolfWhistle, sound.cheer, sound.cheer, sound.applause };
            sound.play(fx[rnd.nextInt(fx.length)], 0.45 + rnd.nextDouble() * 0.35);
            crowdCooldown = now + 700 + rnd.nextInt(1400);
        }
    }

    private int lastLapSound = 1;

    // ------------------------------------------------------------------ world drawing

    /** Interpolation used when the map is turned (driver view). */
    static Object SMOOTH_TURN = RenderingHints.VALUE_INTERPOLATION_BILINEAR;

    private static final Color SKIN_TONE = new Color(198, 134, 66);
    private static final Color DARK = new Color(30, 30, 34);

    private static void drawCar(Graphics2D g, PlayerView p, double x, double y, double angle, boolean me) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        if (me) {
            g.setColor(new Color(255, 255, 255, 70));
            g.fillOval(-30, -30, 60, 60);
        }
        g.rotate(angle);
        Color body = CAR_COLORS[p.id % CAR_COLORS.length];
        switch (p.vehicle) {
            case CYCLE -> drawCycle(g, body);
            case MOTORBIKE -> drawMotorbike(g, body);
            case RICKSHAW -> drawRickshaw(g, body);
            default -> drawCarBody(g, body, p.id);
        }
        g.setTransform(saved);
    }

    /** Name tags above the vehicles: mine first, then the closest ones; tags that would overlap are skipped. */
    private void drawLabels(Graphics2D g, Snapshot s, double[] sx, double[] sy, int meIdx, int w, int h) {
        Integer[] order = new Integer[s.players.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        double mx = meIdx >= 0 ? sx[meIdx] : w / 2.0, my = meIdx >= 0 ? sy[meIdx] : h / 2.0;
        java.util.Arrays.sort(order, (a, b) -> a == meIdx ? -1 : b == meIdx ? 1
                : Double.compare(Math.hypot(sx[a] - mx, sy[a] - my), Math.hypot(sx[b] - mx, sy[b] - my)));
        g.setFont(FONT_NAME);
        FontMetrics fm = g.getFontMetrics();
        List<Rectangle> used = new ArrayList<>();
        for (int i : order) {
            if (sx[i] < -40 || sy[i] < -40 || sx[i] > w + 40 || sy[i] > h + 40) continue;
            PlayerView p = s.players[i];
            String label = p.ai ? p.name + " (AI)" : p.name;
            int tw = fm.stringWidth(label);
            Rectangle r = new Rectangle((int) sx[i] - tw / 2 - 5, (int) sy[i] - 46, tw + 10, 18);
            if (used.stream().anyMatch(r::intersects)) continue;
            used.add(r);
            g.setColor(new Color(12, 16, 26, 170));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
            g.setColor(i == meIdx ? Color.YELLOW : p.ai ? AI_BLUE : Color.WHITE);
            g.drawString(label, r.x + 5, r.y + 14);
        }
    }

    /** Drawn facing +x, centred on the origin. Also used by the start window. */
    static void drawVehicleShape(Graphics2D g, int vehicle, Color body) {
        switch (vehicle) {
            case CYCLE -> drawCycle(g, body);
            case MOTORBIKE -> drawMotorbike(g, body);
            case RICKSHAW -> drawRickshaw(g, body);
            default -> drawCarBody(g, body, 0);
        }
    }

    private static void drawCarBody(Graphics2D g, Color body, int id) {
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(-17, -8, 40, 22, 8, 8);           // shadow
        g.setColor(new Color(20, 20, 20));
        g.fillRect(-15, -13, 9, 4); g.fillRect(-15, 9, 9, 4); // wheels
        g.fillRect(8, -13, 9, 4);   g.fillRect(8, 9, 9, 4);
        g.setColor(body);
        g.fillRoundRect(-20, -11, 40, 22, 8, 8);           // body
        g.setColor(new Color(30, 40, 60));
        g.fillRoundRect(2, -8, 9, 16, 4, 4);               // windshield
        g.setColor(id % CAR_COLORS.length == 9 ? new Color(30, 30, 30, 160) : new Color(255, 255, 255, 160));
        g.fillRect(-14, -2, 14, 4);                        // stripe
    }

    private static void drawMotorbike(Graphics2D g, Color body) {
        g.setColor(new Color(0, 0, 0, 80));
        g.fillRoundRect(-15, -4, 36, 13, 8, 8);            // shadow
        g.setColor(DARK);
        g.fillRoundRect(-19, -3, 11, 6, 5, 5);             // rear wheel
        g.fillRoundRect(9, -3, 11, 6, 5, 5);               // front wheel
        g.setColor(body);
        g.fillRoundRect(-12, -5, 24, 10, 7, 7);            // tank and body
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(50, 50, 55));
        g.drawLine(8, -9, 8, 9);                           // handlebar
        g.setColor(body.darker());
        g.fillOval(-9, -9, 12, 18);                        // rider's jacket
        g.setColor(SKIN_TONE);
        g.fillOval(6, -11, 4, 4);
        g.fillOval(6, 7, 4, 4);                            // hands
        g.setColor(Color.WHITE);
        g.fillOval(-6, -6, 12, 12);                        // helmet
        g.setColor(body);
        g.fillRect(-6, -1, 6, 2);                          // helmet stripe
        g.setColor(new Color(40, 50, 70));
        g.fillArc(-6, -6, 12, 12, -55, 110);               // visor
        g.setColor(new Color(255, 240, 150));
        g.fillOval(18, -2, 4, 4);                          // headlight
        g.setStroke(new BasicStroke(1));
    }

    private static void drawCycle(Graphics2D g, Color body) {
        g.setColor(new Color(0, 0, 0, 70));
        g.fillRoundRect(-15, -3, 36, 10, 6, 6);            // shadow
        g.setColor(DARK);
        g.fillRoundRect(-20, -2, 13, 4, 4, 4);             // rear wheel
        g.fillRoundRect(8, -2, 13, 4, 4, 4);               // front wheel
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(body);
        g.drawLine(-13, 0, 11, 0);                         // frame
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(60, 60, 65));
        g.drawLine(9, -7, 9, 7);                           // handlebar
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(SKIN_TONE);
        g.drawLine(-2, -6, 9, -7);                         // arms
        g.drawLine(-2, 6, 9, 7);
        g.setColor(body.brighter());
        g.fillOval(-9, -8, 11, 16);                        // rider's shirt
        g.setColor(SKIN_TONE);
        g.fillOval(-6, -4, 8, 8);                          // head
        g.setColor(new Color(25, 20, 15));
        g.fillOval(-7, -4, 7, 8);                          // hair
        g.setStroke(new BasicStroke(1));
    }

    /** Dhaka cycle-rickshaw: colourful hood over the passenger seat, the puller in front. */
    private static void drawRickshaw(Graphics2D g, Color body) {
        g.setColor(new Color(0, 0, 0, 80));
        g.fillRoundRect(-24, -13, 54, 30, 12, 12);         // shadow
        g.setColor(DARK);
        g.fillRect(-19, -18, 13, 4); g.fillRect(-19, 14, 13, 4);   // rear wheels
        g.fillRoundRect(17, -2, 12, 4, 3, 3);              // front wheel
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(90, 60, 40));
        g.drawLine(-4, 0, 19, 0);                          // chassis
        g.drawLine(-13, -15, -13, 15);                     // rear axle
        g.setColor(body);
        g.fillRoundRect(-28, -15, 27, 30, 18, 18);         // folding hood
        g.setColor(body.darker());
        g.fillRoundRect(-28, -15, 9, 30, 9, 9);            // back of the hood
        g.setColor(new Color(255, 255, 255, 170));
        for (int k = -9; k <= 9; k += 6) g.fillOval(-16, k - 2, 4, 4);   // painted flowers
        Color[] fringe = { new Color(250, 200, 40), new Color(30, 150, 80), new Color(220, 40, 50) };
        for (int k = -14; k < 14; k += 4) {                // tassels on the front edge
            g.setColor(fringe[Math.floorMod(k / 4, 3)]);
            g.fillPolygon(new int[] { -2, -2, 2 }, new int[] { k, k + 4, k + 2 }, 3);
        }
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(60, 60, 65));
        g.drawLine(19, -7, 19, 7);                         // handlebar
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(SKIN_TONE);
        g.drawLine(10, -6, 19, -7);                        // puller's arms
        g.drawLine(10, 6, 19, 7);
        g.setColor(new Color(240, 240, 230));
        g.fillOval(4, -8, 11, 16);                         // vest
        g.setColor(SKIN_TONE);
        g.fillOval(7, -4, 8, 8);                           // head
        g.setColor(new Color(200, 60, 40));
        g.fillOval(6, -4, 7, 8);                           // gamchha towel on the head
        g.setStroke(new BasicStroke(1));
    }

    /** Glowing line across the road at the next checkpoint the player must drive through. */
    private void drawNextGate(Graphics2D g, PlayerView me, int cx, int cy) {
        int i = me.nextCp * Track.CP_STEP;
        double nx = -track.ty[i], ny = track.tx[i], hw = track.half[i] - 10;
        float pulse = (float) (0.55 + 0.45 * Math.sin(System.nanoTime() / 1.5e8));
        Color c = me.nextCp == 0 ? Color.WHITE : new Color(255, 220, 40);
        g.setStroke(new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (90 + 120 * pulse)));
        g.drawLine((int) (track.xs[i] - nx * hw - cx), (int) (track.ys[i] - ny * hw - cy),
                (int) (track.xs[i] + nx * hw - cx), (int) (track.ys[i] + ny * hw - cy));
        g.setStroke(new BasicStroke(1));
    }

    /** Yellow arrow next to my vehicle showing which way the road goes. Returns true if driving the wrong way. */
    private boolean drawDirectionArrow(Graphics2D g, PlayerView me, double x, double y, double angle,
                                       double sx, double sy, double rot, boolean racing) {
        int best = track.nearestSample(x, y);
        int ahead = (best + 8) % track.samples;
        double dir = Math.atan2(track.ys[ahead] - y, track.xs[ahead] - x) + rot;
        AffineTransform saved = g.getTransform();
        g.translate(sx + Math.cos(dir) * 58, sy + Math.sin(dir) * 58);
        g.rotate(dir);
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(16, 0); arrow.lineTo(-8, -13); arrow.lineTo(-3, 0); arrow.lineTo(-8, 13); arrow.closePath();
        g.setColor(new Color(0, 0, 0, 120));
        g.translate(2, 2); g.fill(arrow); g.translate(-2, -2);
        g.setColor(new Color(255, 220, 40));
        g.fill(arrow);
        g.setTransform(saved);

        double moveX = Math.cos(angle) * Math.signum(me.speed), moveY = Math.sin(angle) * Math.signum(me.speed);
        return racing && Math.abs(me.speed) > 60 && moveX * track.tx[best] + moveY * track.ty[best] < -0.4;
    }

    private void drawMinimap(Graphics2D g, Scenery sc, Snapshot s, double[] px, double[] py, int w) {
        int mw = sc.minimap.getWidth(), mh = sc.minimap.getHeight();
        int mx = w - mw - 12, my = 12;
        double k = sc.miniScale;
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(mx - 6, my - 6, mw + 12, mh + 12, 10, 10);
        g.drawImage(sc.minimap, mx, my, null);
        PlayerView me = s.find(myId);
        if (me != null && !me.finished && (s.phase == PH_RACING || s.phase == PH_COUNTDOWN)) {
            int i = me.nextCp * Track.CP_STEP;
            g.setColor(new Color(255, 220, 40));
            g.fillRect(mx + (int) (track.xs[i] * k) - 3, my + (int) (track.ys[i] * k) - 3, 6, 6);
        }
        for (int i = s.players.length - 1; i >= 0; i--) {
            int dx = mx + (int) (px[i] * k), dy = my + (int) (py[i] * k);
            boolean mine = s.players[i].id == myId;
            int r = mine ? 5 : 4;
            g.setColor(CAR_COLORS[s.players[i].id % CAR_COLORS.length]);
            g.fillOval(dx - r, dy - r, 2 * r, 2 * r);
            g.setColor(mine ? Color.WHITE : Color.BLACK);
            g.drawOval(dx - r, dy - r, 2 * r, 2 * r);
        }
    }

    // ------------------------------------------------------------------ HUD

    private static final Color LABEL_GRAY = new Color(165, 182, 200);
    private static final Color AI_BLUE = new Color(175, 210, 255);
    private static final Color GOOD = new Color(100, 240, 130);

    private void drawHud(Graphics2D g, Snapshot s, int meIdx, int w, int h) {
        PlayerView me = meIdx >= 0 ? s.players[meIdx] : null;
        int n = s.players.length;

        // status card, top-left: labels on the left, big values on the right, sized to fit
        if (me != null) {
            boolean racing = s.phase == PH_RACING || s.phase == PH_RESULTS;
            String[] labels = { "LAP", "POSITION", "SPEED", "TIME" };
            String[] values = {
                    racing ? me.lap + " / " + LAPS : "-",
                    racing ? me.place + " / " + n : "-",
                    Math.round(Math.abs(me.speed) * 0.4) + " km/h",
                    s.phase == PH_RACING ? formatTime(me.finished ? me.finishMs : s.timerMs) : "-:--.--" };
            int pad = 16, rowH = 32;
            int labelW = maxWidth(g, FONT_LABEL, labels);
            int valueW = Math.max(maxWidth(g, FONT_VALUE, values), maxWidth(g, FONT_VALUE, "0:00.00", "000 km/h"));
            int bw = pad + labelW + 18 + valueW + pad, bh = pad + rowH * labels.length - 6 + pad / 2;
            panel(g, 14, 14, bw, bh);
            for (int i = 0; i < labels.length; i++) {
                int base = 14 + pad + 22 + i * rowH;
                g.setFont(FONT_LABEL);
                g.setColor(LABEL_GRAY);
                g.drawString(labels[i], 14 + pad, base - 3);
                g.setFont(FONT_VALUE);
                g.setColor(Color.WHITE);
                g.drawString(values[i], 14 + bw - pad - g.getFontMetrics().stringWidth(values[i]), base);
            }
        }

        // race order, bottom-left, sized to the longest name
        boolean lobby = s.phase == PH_LOBBY;
        Font listFont = n > 10 ? FONT_NAME : FONT_LIST;
        int rowH = n > 10 ? 20 : 24, pad = 12, dot = 12;
        String[] rows = new String[n], tags = new String[n];
        for (int i = 0; i < n; i++) {
            PlayerView p = s.players[i];
            rows[i] = (lobby ? "" : p.place + ".  ") + p.name + (p.id == myId ? "  (you)" : p.ai ? "  (AI)" : "");
            tags[i] = lobby && !p.ai ? (p.ready ? "READY" : "not ready") : "";
        }
        int textW = maxWidth(g, listFont, rows), tagW = maxWidth(g, FONT_LABEL, tags);
        int bw = pad + dot + 10 + textW + (tagW > 0 ? 16 + tagW : 0) + pad, bh = pad + n * rowH + pad / 2;
        int bx = 14, by = h - bh - 14;
        panel(g, bx, by, bw, bh);
        for (int i = 0; i < n; i++) {
            PlayerView p = s.players[i];
            int y = by + pad + i * rowH + rowH - 7;
            g.setColor(CAR_COLORS[p.id % CAR_COLORS.length]);
            g.fillOval(bx + pad, y - dot + 1, dot, dot);
            g.setFont(listFont);
            g.setColor(p.id == myId ? Color.YELLOW : p.ai ? AI_BLUE : Color.WHITE);
            g.drawString(rows[i], bx + pad + dot + 10, y);
            if (!tags[i].isEmpty()) {
                g.setFont(FONT_LABEL);
                g.setColor(p.ready ? GOOD : LABEL_GRAY);
                g.drawString(tags[i], bx + bw - pad - g.getFontMetrics().stringWidth(tags[i]), y);
            }
        }

        // key help, bottom centre
        String help = VIEW_NAMES[viewMode] + ":  " + (viewMode == 2 ? "arrows = directions on the map"
                : "\u2191 drive  \u2193 brake  \u2190 \u2192 steer  (brake before bends!)")
                + "    \u2022    C  change view    \u2022    H  horn    \u2022    M  sound " + (sound.isMuted() ? "off" : "on")
                + "    \u2022    Esc  quit";
        int helpW = textWidth(g, FONT_SMALL, help) + 32, listRight = bx + bw + 14;
        int helpX = Math.max(w / 2, listRight + helpW / 2);
        if (helpX + helpW / 2 > w - 10) helpX = w / 2;     // no room beside the list: centre it anyway
        pill(g, help, FONT_SMALL, LABEL_GRAY, helpX, h - 16);

        switch (s.phase) {
            case PH_LOBBY -> drawLobby(g, s, w, h);
            case PH_COUNTDOWN -> {
                int secs = (int) Math.ceil(s.timerMs / 1000.0);
                centerText(g, String.valueOf(Math.max(1, secs)), FONT_HUGE, Color.WHITE, w / 2, h / 2 - 40);
            }
            case PH_RACING -> {
                if (s.timerMs < 1000) centerText(g, "GO!", FONT_HUGE, GOOD, w / 2, h / 2 - 40);
                if (me != null && me.finished) {
                    textBox(g, w / 2, 76, List.of(new Line("FINISHED  P" + me.place + "   " + formatTime(me.finishMs), FONT_TITLE, Color.YELLOW)));
                }
            }
            case PH_RESULTS -> drawResults(g, s, w, h);
            default -> { }
        }
    }

    /** Progress bar to the finish flag, lap banners and the wrong-way warning. */
    private void drawRaceGuidance(Graphics2D g, Snapshot s, PlayerView me, boolean wrongWay, int w, int h) {
        if (s.phase == PH_LOBBY) { lastLap = 1; return; }
        int total = 1 + LAPS * track.checkpoints;
        int bw = Math.min(560, w - 620), bx = (w - bw) / 2, by = 22;
        if (bw > 120) {
            panel(g, bx - 16, by - 10, bw + 60, 44);
            g.setColor(new Color(255, 255, 255, 60));
            g.fillRoundRect(bx, by + 8, bw, 8, 8, 8);
            for (int lap = 1; lap < LAPS; lap++) {        // lap separators
                int lx = bx + (int) ((1 + lap * track.checkpoints) / (double) total * bw);
                g.fillRect(lx - 1, by + 4, 2, 16);
            }
            for (int row = 0; row < 3; row++) {           // checkered finish flag
                for (int col = 0; col < 2; col++) {
                    g.setColor((row + col) % 2 == 0 ? Color.WHITE : Color.BLACK);
                    g.fillRect(bx + bw + 12 + col * 7, by + 2 + row * 7, 7, 7);
                }
            }
            for (int i = s.players.length - 1; i >= 0; i--) {
                PlayerView p = s.players[i];
                double f = p.finished ? 1 : Math.min(1, p.cpCount / (double) total);
                int dx = bx + (int) (f * bw);
                boolean mine = p.id == myId;
                g.setColor(CAR_COLORS[p.id % CAR_COLORS.length]);
                g.fillOval(dx - 7, by + 5, 14, 14);
                g.setColor(mine ? Color.WHITE : Color.BLACK);
                g.setStroke(new BasicStroke(mine ? 3 : 1));
                g.drawOval(dx - 7, by + 5, 14, 14);
                g.setStroke(new BasicStroke(1));
            }
        }

        long now = System.currentTimeMillis();
        if (s.phase == PH_RACING && me.lap != lastLap) {
            banner = me.lap == LAPS ? "FINAL LAP!" : "LAP " + me.lap + " / " + LAPS;
            bannerUntil = now + 2500;
        }
        lastLap = me.lap;
        if (s.phase == PH_COUNTDOWN) {
            textBox(g, w / 2, h / 2 + 120, List.of(new Line("Race " + LAPS + " laps - follow the yellow arrow!", FONT_HUD, Color.YELLOW),
                    new Line(viewMode == 2 ? "Arrows = directions on the map"
                            : "\u2191 drive   \u2193 brake   \u2190 \u2192 steer   -   brake before bends, the fence slows you down!", FONT_SMALL, Color.WHITE)));
        }
        if (banner != null && now < bannerUntil && !me.finished) {
            centerText(g, banner, FONT_BIG, me.lap == LAPS ? new Color(255, 120, 60) : Color.WHITE, w / 2, h / 2 - 90);
        }
        if (wrongWay && (now / 300) % 2 == 0) {
            centerText(g, "WRONG WAY!", FONT_BIG, new Color(255, 70, 70), w / 2, h / 2 + 110);
        }
    }

    private void drawLobby(Graphics2D g, Snapshot s, int w, int h) {
        int ready = 0, people = 0, ai = 0;
        for (PlayerView p : s.players) {
            if (p.ai) { ai++; continue; }
            people++;
            if (p.ready) ready++;
        }
        PlayerView me = s.find(myId);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(track.name, FONT_TITLE, Color.WHITE));
        lines.add(new Line(people + (people == 1 ? " player" : " players") + (ai > 0 ? "  +  " + ai + " AI drivers" : ""), FONT_SMALL, LABEL_GRAY));
        lines.add(new Line("GOAL: be the first to drive " + LAPS + " laps!", FONT_HUD, Color.WHITE));
        if (me != null && !me.ready) {
            lines.add(new Line("Press ENTER when you are ready", FONT_HUD, blink ? Color.YELLOW : new Color(255, 240, 160)));
        } else {
            lines.add(new Line("You are READY  -  press ENTER to cancel", FONT_HUD, GOOD));
        }
        lines.add(new Line(ready + " / " + people + " ready  -  the race starts when everyone is ready", FONT_SMALL, Color.WHITE));
        if (hostInfo != null) lines.add(new Line(hostInfo, FONT_SMALL, new Color(140, 215, 255)));
        textBox(g, w / 2, 26, lines);
    }

    private void drawResults(Graphics2D g, Snapshot s, int w, int h) {
        int n = s.players.length;
        PlayerView winner = n > 0 && s.players[0].finished ? s.players[0] : null;
        String headline = winner == null ? "RACE OVER" : winner.id == myId ? "YOU WIN!" : winner.name + " WINS!";
        Font rowFont = n > 8 ? FONT_LIST : FONT_HUD;
        int rowH = n > 8 ? 24 : 30, pad = 24;
        String[] names = new String[n], times = new String[n];
        for (int i = 0; i < n; i++) {
            PlayerView p = s.players[i];
            names[i] = p.place + ".   " + p.name + (p.ai ? "  (AI)" : "");
            times[i] = p.finished ? formatTime(p.finishMs) : "DNF";
        }
        String footer = "Back to the lobby in " + (int) Math.ceil(s.timerMs / 1000.0) + "s";
        int tableW = 22 + maxWidth(g, rowFont, names) + 40 + maxWidth(g, rowFont, times);
        int bw = 2 * pad + Math.max(Math.max(tableW, textWidth(g, FONT_BIG, headline)), textWidth(g, FONT_SMALL, footer));
        int bh = pad + 60 + 16 + n * rowH + 40;
        int bx = (w - bw) / 2, by = Math.max(10, (h - bh) / 2);
        panel(g, bx, by, bw, bh);
        centerText(g, headline, FONT_BIG, winner != null ? CAR_COLORS[winner.id % CAR_COLORS.length].brighter() : Color.WHITE, w / 2, by + pad + 46);
        int tx = (w - tableW) / 2;
        g.setFont(rowFont);
        for (int i = 0; i < n; i++) {
            PlayerView p = s.players[i];
            int y = by + pad + 60 + 16 + i * rowH + rowH - 8;
            g.setColor(CAR_COLORS[p.id % CAR_COLORS.length]);
            g.fillOval(tx, y - 12, 12, 12);
            g.setColor(p.id == myId ? Color.YELLOW : p.ai ? AI_BLUE : Color.WHITE);
            g.drawString(names[i], tx + 22, y);
            g.drawString(times[i], tx + tableW - g.getFontMetrics().stringWidth(times[i]), y);
        }
        centerText(g, footer, FONT_SMALL, LABEL_GRAY, w / 2, by + bh - 16);
    }

    // ------------------------------------------------------------------ drawing helpers

    /** One line of text in a box. */
    private record Line(String text, Font font, Color color) { }

    /** A box sized to fit its lines, centred at cx, starting at top. Returns the bottom edge. */
    private static int textBox(Graphics2D g, int cx, int top, List<Line> lines) {
        int pad = 18, gap = 8, maxW = 0, total = 0;
        for (Line l : lines) {
            FontMetrics fm = g.getFontMetrics(l.font());
            maxW = Math.max(maxW, fm.stringWidth(l.text()));
            total += fm.getAscent() + fm.getDescent();
        }
        int bw = maxW + 2 * pad, bh = total + gap * (lines.size() - 1) + 2 * pad;
        panel(g, cx - bw / 2, top, bw, bh);
        int y = top + pad;
        for (Line l : lines) {
            FontMetrics fm = g.getFontMetrics(l.font());
            centerText(g, l.text(), l.font(), l.color(), cx, y + fm.getAscent());
            y += fm.getAscent() + fm.getDescent() + gap;
        }
        return top + bh;
    }

    /** A small rounded label sized to its text, centred at cx with its bottom at bottom. */
    private static void pill(Graphics2D g, String text, Font font, Color color, int cx, int bottom) {
        FontMetrics fm = g.getFontMetrics(font);
        int bw = fm.stringWidth(text) + 32, bh = fm.getAscent() + fm.getDescent() + 12;
        panel(g, cx - bw / 2, bottom - bh, bw, bh);
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, cx - fm.stringWidth(text) / 2, bottom - bh + 6 + fm.getAscent());
    }

    private static int textWidth(Graphics2D g, Font font, String text) {
        return g.getFontMetrics(font).stringWidth(text);
    }

    private static int maxWidth(Graphics2D g, Font font, String... texts) {
        int m = 0;
        for (String t : texts) m = Math.max(m, g.getFontMetrics(font).stringWidth(t));
        return m;
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(12, 16, 26, 205));
        g.fillRoundRect(x, y, w, h, 16, 16);
        g.setColor(new Color(255, 255, 255, 38));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, w, h, 16, 16);
        g.setStroke(new BasicStroke(1));
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

    // ------------------------------------------------------------------ snapshot data

    static final class PlayerView {
        int id, lap, place, finishMs, nextCp, cpCount;
        String name;
        float x, y, angle, speed;
        boolean finished, ready, ai;
        int vehicle, events;
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
            if (n < 0 || n > MAX_CARS) throw new IOException("bad car count " + n);
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
                p.ready = in.readBoolean();
                p.nextCp = in.readByte();
                p.cpCount = in.readShort();
                p.ai = in.readBoolean();
                p.vehicle = Math.floorMod(in.readByte(), VEHICLE_NAMES.length);
                p.events = in.readByte();
                s.players[i] = p;
            }
            s.recvNanos = System.nanoTime();
            return s;
        }
    }
}
