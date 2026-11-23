package racer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;

import static racer.Protocol.*;

/**
 * Authoritative TCP game server. Clients only send their key state; the server
 * runs all physics and lap counting at 60 Hz and broadcasts the world at 30 Hz.
 */
public final class GameServer {
    // car physics (pixels, seconds)
    private static final double ACCEL = 360, BRAKE = 720, REVERSE = 260, ROLL = 140;
    private static final double MAX_ON_TRACK = 520, MAX_OFF_TRACK = 190, MAX_REVERSE = 160;
    private static final double TURN_RATE = 2.7, ARCADE_TURN_RATE = 4.0, CAR_RADIUS = 18;

    private static final long COUNTDOWN_MS = 3000, RESULTS_MS = 10000, FINISH_GRACE_MS = 30000;

    private final int port;
    private final Track track = new Track();
    private final Player[] slots = new Player[MAX_PLAYERS];
    private final Object lock = new Object();
    private volatile boolean running;
    private ServerSocket serverSocket;

    // game state, guarded by lock
    private byte phase = PH_LOBBY;
    private long phaseStart = System.nanoTime();
    private long firstFinish = -1;
    private int finishedCount;

    public GameServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        startThread("accept", this::acceptLoop);
        startThread("game-loop", this::gameLoop);
        log("Listening on TCP port " + port + " (max " + MAX_PLAYERS + " players)");
    }

    public void stop() {
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) { }
        synchronized (lock) {
            for (Player p : slots) if (p != null) p.close();
        }
    }

    // ------------------------------------------------------------------ networking

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                startThread("client-" + s.getRemoteSocketAddress(), () -> handleClient(s));
            } catch (IOException e) {
                if (running) log("Accept failed: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket s) {
        Player p = null;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            if (in.readByte() != C_HELLO) throw new IOException("expected HELLO");
            int version = in.readInt();
            String name = cleanName(in.readUTF());

            String reject = null;
            synchronized (lock) {
                if (version != VERSION) {
                    reject = "Version mismatch: server is v" + VERSION + ", you are v" + version + ".";
                } else if (phase != PH_LOBBY) {
                    reject = "A race is in progress. Try again when it ends.";
                } else {
                    int slot = freeSlot();
                    if (slot < 0) {
                        reject = "Server is full (" + MAX_PLAYERS + "/" + MAX_PLAYERS + " players).";
                    } else {
                        if (name.isEmpty()) name = "Player " + (slot + 1);
                        p = new Player(slot, s, name);
                        slots[slot] = p;
                        resetCar(p);
                    }
                }
            }
            if (reject != null) {
                DataOutputStream o = new DataOutputStream(s.getOutputStream());
                o.writeByte(S_REJECT);
                o.writeUTF(reject);
                o.flush();
                log("Rejected " + s.getRemoteSocketAddress() + ": " + reject);
                s.close();
                return;
            }
            log(p.name + " joined from " + s.getRemoteSocketAddress() + " as player " + (p.id + 1));
            s.setSoTimeout(0);

            while (running) {
                byte type = in.readByte();
                switch (type) {
                    case C_INPUT -> p.input = in.readByte();
                    case C_START -> requestStart(p);
                    default -> throw new IOException("unknown message type " + type);
                }
            }
        } catch (IOException e) {
            // client disconnected or sent garbage
        } finally {
            if (p != null) remove(p);
            else try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void remove(Player p) {
        synchronized (lock) {
            if (slots[p.id] != p) return;
            slots[p.id] = null;
        }
        p.close();
        log(p.name + " left");
    }

    private void broadcast(byte[] msg) {
        for (Player p : slots) if (p != null) p.send(msg);
    }

    // ------------------------------------------------------------------ game logic

    private void gameLoop() {
        final long tickNanos = 1_000_000_000L / TICK_RATE;
        final double dt = 1.0 / TICK_RATE;
        long next = System.nanoTime();
        long tick = 0;
        while (running) {
            synchronized (lock) {
                update(System.nanoTime(), dt);
                if (tick++ % SEND_EVERY == 0) broadcast(buildState());
            }
            next += tickNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) LockSupport.parkNanos(sleep);
            else if (sleep < -100_000_000L) next = System.nanoTime(); // fell far behind, resync
        }
    }

    private void update(long now, double dt) {
        if (playerCount() == 0 && phase != PH_LOBBY) {
            setPhase(PH_LOBBY, now);
            return;
        }
        long elapsedMs = (now - phaseStart) / 1_000_000L;
        switch (phase) {
            case PH_LOBBY -> {
                // free practice while waiting
                for (Player p : slots) if (p != null) drive(p, dt);
                collideCars();
            }
            case PH_COUNTDOWN -> {
                if (elapsedMs >= COUNTDOWN_MS) setPhase(PH_RACING, now);
            }
            case PH_RACING -> {
                for (Player p : slots) {
                    if (p == null) continue;
                    double ox = p.x, oy = p.y;
                    drive(p, dt);
                    if (!p.finished) checkGates(p, ox, oy, elapsedMs, now);
                }
                collideCars();
                boolean allDone = true;
                for (Player p : slots) if (p != null && !p.finished) allDone = false;
                boolean graceOver = firstFinish >= 0 && (now - firstFinish) / 1_000_000L >= FINISH_GRACE_MS;
                if (allDone || graceOver) setPhase(PH_RESULTS, now);
            }
            case PH_RESULTS -> {
                for (Player p : slots) if (p != null) drive(p, dt);
                collideCars();
                if (elapsedMs >= RESULTS_MS) {
                    setPhase(PH_LOBBY, now);
                    for (Player p : slots) if (p != null) resetCar(p);
                }
            }
            default -> { }
        }
    }

    private void setPhase(byte newPhase, long now) {
        phase = newPhase;
        phaseStart = now;
        if (newPhase != PH_LOBBY) log("Phase -> " + phaseName(newPhase));
    }

    private void requestStart(Player p) {
        synchronized (lock) {
            if (phase != PH_LOBBY || hostId() != p.id) return;
            for (Player q : slots) if (q != null) resetCar(q);
            finishedCount = 0;
            firstFinish = -1;
            setPhase(PH_COUNTDOWN, System.nanoTime());
        }
    }

    private void resetCar(Player p) {
        double[] g = track.gridSlot(p.id);
        p.x = g[0];
        p.y = g[1];
        p.angle = g[2];
        p.speed = 0;
        p.lapsDone = -1;   // becomes 0 when the start line is first crossed
        p.nextCp = 0;
        p.cpCount = 0;
        p.finished = false;
        p.finishMs = 0;
    }

    private void drive(Player p, double dt) {
        int in = p.input;
        boolean up = (in & IN_UP) != 0, down = (in & IN_DOWN) != 0;
        int sx = ((in & IN_RIGHT) != 0 ? 1 : 0) - ((in & IN_LEFT) != 0 ? 1 : 0);
        double max = track.onTrack(p.x, p.y) ? MAX_ON_TRACK : MAX_OFF_TRACK;

        if ((in & IN_ARCADE) != 0) {
            // Arcade: arrows are screen directions. The car turns to face them and drives there.
            int sy = (down ? 1 : 0) - (up ? 1 : 0);
            if (sx == 0 && sy == 0) {
                roll(p, dt);
            } else {
                double diff = Math.IEEEremainder(Math.atan2(sy, sx) - p.angle, 2 * Math.PI);
                double maxTurn = ARCADE_TURN_RATE * dt;
                p.angle += Math.max(-maxTurn, Math.min(maxTurn, diff));
                if (Math.abs(diff) < 1.6 || p.speed < 0) p.speed += ACCEL * dt;   // roughly facing it: go
                else p.speed = Math.max(0, p.speed - BRAKE * dt);                // turning around: slow down
            }
        } else {
            // Classic: up/down = throttle/brake, left/right steer relative to the car.
            if (up) p.speed += ACCEL * dt;
            if (down) p.speed -= (p.speed > 0 ? BRAKE : REVERSE) * dt;
            if (!up && !down) roll(p, dt);
            double grip = Math.min(1, Math.abs(p.speed) / 120);
            p.angle += sx * TURN_RATE * grip * Math.signum(p.speed) * dt;
        }
        p.angle = Math.IEEEremainder(p.angle, 2 * Math.PI);
        if (p.speed > max) p.speed = max + (p.speed - max) * Math.exp(-3 * dt); // grass slows you down
        if (p.speed < -MAX_REVERSE) p.speed = -MAX_REVERSE;

        double ox = p.x, oy = p.y;
        p.x += Math.cos(p.angle) * p.speed * dt;
        p.y += Math.sin(p.angle) * p.speed * dt;

        // the lake and the world border act as walls
        double r = CAR_RADIUS;
        if (track.inLake(p.x, p.y) && !track.inLake(ox, oy)) { // (if pushed in by another car, let it drive out)
            p.x = ox;
            p.y = oy;
            p.speed *= -0.3;
        }
        if (p.x < r || p.x > Track.WORLD_W - r || p.y < r || p.y > Track.WORLD_H - r) {
            p.x = Math.max(r, Math.min(Track.WORLD_W - r, p.x));
            p.y = Math.max(r, Math.min(Track.WORLD_H - r, p.y));
            p.speed *= -0.3;
        }
    }

    private static void roll(Player p, double dt) {
        double roll = ROLL * dt;
        p.speed = Math.abs(p.speed) <= roll ? 0 : p.speed - Math.signum(p.speed) * roll;
    }

    private void collideCars() {
        double minDist = CAR_RADIUS * 2;
        for (int i = 0; i < MAX_PLAYERS; i++) {
            Player a = slots[i];
            if (a == null) continue;
            for (int j = i + 1; j < MAX_PLAYERS; j++) {
                Player b = slots[j];
                if (b == null) continue;
                double dx = b.x - a.x, dy = b.y - a.y;
                double d = Math.hypot(dx, dy);
                if (d >= minDist || d == 0) continue;
                double push = (minDist - d) / 2, nx = dx / d, ny = dy / d;
                a.x -= nx * push; a.y -= ny * push;
                b.x += nx * push; b.y += ny * push;
                a.speed *= 0.85;
                b.speed *= 0.85;
            }
        }
    }

    private void checkGates(Player p, double ox, double oy, long raceMs, long now) {
        if (!track.crossedGate(p.nextCp, ox, oy, p.x, p.y)) return;
        p.cpCount++;
        if (p.nextCp == 0) {
            p.lapsDone++;
            if (p.lapsDone >= LAPS) {
                p.finished = true;
                p.finishMs = (int) raceMs;
                finishedCount++;
                if (firstFinish < 0) firstFinish = now;
                log(p.name + " finished P" + finishedCount + " in " + raceMs + " ms");
            }
        }
        p.nextCp = (p.nextCp + 1) % Track.CHECKPOINTS;
    }

    private byte[] buildState() {
        List<Player> order = new ArrayList<>();
        for (Player p : slots) if (p != null) order.add(p);
        order.sort(Comparator
                .comparing((Player p) -> !p.finished)
                .thenComparingInt(p -> p.finished ? p.finishMs : 0)
                .thenComparingInt(p -> -p.cpCount)
                .thenComparingDouble(p -> track.distanceToGate(p.nextCp, p.x, p.y)));

        long elapsedMs = (System.nanoTime() - phaseStart) / 1_000_000L;
        int timer = switch (phase) {
            case PH_COUNTDOWN -> (int) Math.max(0, COUNTDOWN_MS - elapsedMs);
            case PH_RACING -> (int) elapsedMs;
            case PH_RESULTS -> (int) Math.max(0, RESULTS_MS - elapsedMs);
            default -> 0;
        };

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + order.size() * 48);
            DataOutputStream o = new DataOutputStream(bos);
            o.writeByte(S_STATE);
            o.writeByte(phase);
            o.writeInt(timer);
            o.writeByte(hostId());
            o.writeByte(order.size());
            int place = 1;
            for (Player p : order) {
                o.writeByte(p.id);
                o.writeUTF(p.name);
                o.writeFloat((float) p.x);
                o.writeFloat((float) p.y);
                o.writeFloat((float) p.angle);
                o.writeFloat((float) p.speed);
                o.writeByte(Math.max(1, Math.min(LAPS, p.lapsDone + 1)));
                o.writeByte(place++);
                o.writeBoolean(p.finished);
                o.writeInt(p.finishMs);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // cannot happen with a byte array
        }
    }

    // ------------------------------------------------------------------ helpers

    private int freeSlot() {
        for (int i = 0; i < MAX_PLAYERS; i++) if (slots[i] == null) return i;
        return -1;
    }

    private int playerCount() {
        int n = 0;
        for (Player p : slots) if (p != null) n++;
        return n;
    }

    /** The host (who may start races) is the connected player with the lowest slot. */
    private int hostId() {
        for (Player p : slots) if (p != null) return p.id;
        return -1;
    }

    private static String cleanName(String n) {
        n = n.replaceAll("[\\p{Cntrl}]", "").trim();
        return n.length() > 12 ? n.substring(0, 12) : n;
    }

    private static String phaseName(byte ph) {
        return switch (ph) {
            case PH_LOBBY -> "LOBBY";
            case PH_COUNTDOWN -> "COUNTDOWN";
            case PH_RACING -> "RACING";
            default -> "RESULTS";
        };
    }

    private static void startThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.start();
    }

    private static void log(String msg) {
        System.out.println("[server] " + msg);
    }

    /** One connected player: socket, car state, and a sender thread so slow clients never stall the game loop. */
    private final class Player {
        final int id;
        final Socket socket;
        final String name;
        final DataOutputStream out;
        final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(16);
        final Thread sender;
        volatile int input;
        volatile boolean closed;

        double x, y, angle, speed;
        int lapsDone, nextCp, cpCount, finishMs;
        boolean finished;

        Player(int id, Socket socket, String name) throws IOException {
            this.id = id;
            this.socket = socket;
            this.name = name;
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            queue.add(new byte[] { S_WELCOME, (byte) id });
            sender = new Thread(this::sendLoop, "sender-" + id);
            sender.setDaemon(true);
            sender.start();
        }

        void send(byte[] msg) {
            if (closed) return;
            if (!queue.offer(msg)) { // client is lagging: drop the oldest state
                queue.poll();
                queue.offer(msg);
            }
        }

        private void sendLoop() {
            try {
                while (!closed) {
                    byte[] msg = queue.take();
                    out.write(msg);
                    if (queue.isEmpty()) out.flush();
                }
            } catch (InterruptedException | IOException e) {
                // fall through to removal
            }
            remove(this);
        }

        void close() {
            closed = true;
            sender.interrupt();
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
