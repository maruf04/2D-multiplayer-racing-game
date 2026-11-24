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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;

import static racer.Protocol.*;

/**
 * Authoritative TCP game server. Clients only send their key state; the server
 * runs all physics, AI drivers and lap counting at 60 Hz and broadcasts the world at 30 Hz.
 */
public final class GameServer {
    // car physics (pixels, seconds)
    private static final double ACCEL = 360, BRAKE = 720, REVERSE = 260, ROLL = 140;
    private static final double MAX_SPEED = 520, MAX_REVERSE = 160;
    private static final double ASSIST_RATE = 0.9;
    /** Tyre grip: the sideways acceleration a vehicle can take. Faster = wider turns, so brake before bends. */
    private static final double GRIP = 560;
    /** Speed lost per second when cornering right at the grip limit. */
    private static final double SCRUB = 260;
    private static final double TURN_RATE = 2.9, ARCADE_TURN_RATE = 4.0, AI_TURN_RATE = 3.4, CAR_RADIUS = 18;
    /** Fences: a vehicle's center can't get closer than this to the road edge. */
    private static final double FENCE_MARGIN = 14;

    private static final long COUNTDOWN_MS = 3000, RESULTS_MS = 12000, FINISH_GRACE_MS = 30000;
    private static final String[] AI_NAMES = { "Rafi", "Nadia", "Tanvir", "Sadia", "Arif", "Mitu", "Shuvo", "Puja",
            "Imran", "Nabila", "Sakib", "Tisha", "Fahim", "Riya", "Jamal", "Lima" };

    private final int requestedPort;
    private volatile int port;
    private Lan.Responder responder;
    private final int aiTarget;
    private final String mapId;
    private final int sceneSeed = new Random().nextInt();
    private final Random rng = new Random();
    private final Track track;
    private final Car[] slots = new Car[MAX_CARS];
    private final Object lock = new Object();
    private volatile boolean running;
    private ServerSocket serverSocket;

    // game state, guarded by lock
    private byte phase = PH_LOBBY;
    private long phaseStart = System.nanoTime();
    private long firstFinish = -1;
    private int finishedCount;

    public GameServer(int port, int aiCars, String mapId) throws IOException {
        this.requestedPort = port;
        this.port = port;
        this.aiTarget = Math.max(0, Math.min(MAX_CARS - 1, aiCars));
        this.mapId = mapId;
        this.track = new Track(MapData.load(mapId));
    }

    public void start() throws IOException {
        // use the requested port, or the next free one (the players find the game on the Wi-Fi anyway)
        for (int p = requestedPort; ; p++) {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            try {
                serverSocket.bind(new InetSocketAddress(p));
                port = p;
                break;
            } catch (IOException e) {
                serverSocket.close();
                if (p >= requestedPort + 9) throw new IOException("TCP ports " + requestedPort + "-" + p + " are all in use");
            }
        }
        try {
            responder = Lan.Responder.start(this::discoveryAnswer);
        } catch (IOException e) {
            log("Wi-Fi game list is off (UDP port " + Lan.DISCOVERY_PORT + " busy): players can still join by IP");
        }
        running = true;
        startThread("accept", this::acceptLoop);
        startThread("game-loop", this::gameLoop);
        log("Listening on TCP port " + port + " - track: " + track.name + " (up to " + MAX_CARS + " vehicles, " + aiTarget + " AI)");
    }

    /** The TCP port the server really uses. */
    public int port() {
        return port;
    }

    /** What players looking for games on the Wi-Fi see. */
    private byte[] discoveryAnswer() {
        synchronized (lock) {
            int host = hostId(), ais = 0;
            for (Car c : slots) if (c != null && c.ai) ais++;
            return Lan.answer(port, host >= 0 ? slots[host].name : "Host", track.name, humanCount(), ais, phase);
        }
    }

    public void stop() {
        running = false;
        if (responder != null) responder.stop();
        try { serverSocket.close(); } catch (IOException ignored) { }
        synchronized (lock) {
            for (Car c : slots) if (c != null) c.close();
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
        Car p = null;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            if (in.readByte() != C_HELLO) throw new IOException("expected HELLO");
            int version = in.readInt();
            String name = cleanName(in.readUTF());
            // older versions don't send a vehicle; they get a clear "version mismatch" below
            int vehicle = version == VERSION ? Math.floorMod(in.readByte(), VEHICLE_NAMES.length) : CAR;

            String reject = null;
            synchronized (lock) {
                if (version != VERSION) {
                    reject = "Version mismatch: server is v" + VERSION + ", you are v" + version + ". Update the game.";
                } else if (phase != PH_LOBBY) {
                    reject = "A race is in progress. Try again when it ends.";
                } else {
                    int slot = freeSlot();
                    if (slot < 0) slot = removeOneAi();       // people take an AI driver's place
                    if (slot < 0) {
                        reject = "Server is full (" + MAX_CARS + " players).";
                    } else {
                        if (name.isEmpty()) name = "Player " + (slot + 1);
                        p = new Car(slot, s, name, vehicle);
                        slots[slot] = p;
                        resetCar(p, slot);
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
            log(p.name + " joined from " + s.getRemoteSocketAddress() + " on a " + VEHICLE_NAMES[vehicle].toLowerCase());
            s.setSoTimeout(0);

            while (running) {
                byte type = in.readByte();
                switch (type) {
                    case C_INPUT -> p.input = in.readByte();
                    case C_READY -> toggleReady(p);
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

    private void remove(Car p) {
        synchronized (lock) {
            if (slots[p.id] != p) return;
            slots[p.id] = null;
        }
        p.close();
        log(p.name + " left");
    }

    private void broadcast(byte[] msg) {
        for (Car c : slots) if (c != null && !c.ai) c.send(msg);
    }

    // ------------------------------------------------------------------ AI roster

    /** In the lobby keep the AI count at the host's choice, leaving room for people. */
    private void syncAi() {
        int humans = humanCount(), ais = 0;
        for (Car c : slots) if (c != null && c.ai) ais++;
        int want = humans == 0 ? 0 : Math.min(aiTarget, MAX_CARS - humans);
        while (ais > want && removeOneAi() >= 0) ais--;
        while (ais < want) {
            int slot = freeSlot();
            if (slot < 0) break;
            Car ai = new Car(slot, pickAiName(), leastUsedVehicle());
            slots[slot] = ai;
            resetCar(ai, slot);
            ais++;
        }
    }

    private int removeOneAi() {
        for (int i = MAX_CARS - 1; i >= 0; i--) {
            if (slots[i] != null && slots[i].ai) {
                slots[i] = null;
                return i;
            }
        }
        return -1;
    }

    /** AI drivers pick the vehicle type that is least used on the grid, so there is always a good mix. */
    private int leastUsedVehicle() {
        int[] used = new int[VEHICLE_NAMES.length];
        for (Car c : slots) if (c != null) used[c.vehicle]++;
        int min = Integer.MAX_VALUE;
        for (int u : used) min = Math.min(min, u);
        List<Integer> choices = new ArrayList<>();
        for (int v = 0; v < used.length; v++) if (used[v] == min) choices.add(v);
        return choices.get(rng.nextInt(choices.size()));
    }

    private String pickAiName() {
        List<String> names = new ArrayList<>(List.of(AI_NAMES));
        for (Car c : slots) if (c != null) names.remove(c.name);
        return names.isEmpty() ? "Driver" : names.get(rng.nextInt(names.size()));
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
        if (humanCount() == 0 && phase != PH_LOBBY) {
            setPhase(PH_LOBBY, now);
            for (Car c : slots) if (c != null) resetCar(c, c.id);
        }
        long elapsedMs = (now - phaseStart) / 1_000_000L;
        switch (phase) {
            case PH_LOBBY -> {
                syncAi();
                // free practice for people while waiting; AI cars wait on the grid
                for (Car c : slots) if (c != null && !c.ai) drive(c, dt);
                collideCars();
                boolean allReady = true;
                for (Car c : slots) if (c != null && !c.ai && !c.ready) allReady = false;
                if (allReady && humanCount() > 0) startCountdown(now);
            }
            case PH_COUNTDOWN -> {
                if (elapsedMs >= COUNTDOWN_MS) setPhase(PH_RACING, now);
            }
            case PH_RACING -> {
                for (Car c : slots) {
                    if (c == null) continue;
                    double ox = c.x, oy = c.y;
                    if (c.ai) driveAi(c, dt); else drive(c, dt);
                    if (!c.finished) checkGates(c, ox, oy, elapsedMs, now);
                }
                collideCars();
                boolean humansDone = true, allDone = true;
                for (Car c : slots) {
                    if (c == null || c.finished) continue;
                    allDone = false;
                    if (!c.ai) humansDone = false;
                }
                boolean graceOver = firstFinish >= 0 && (now - firstFinish) / 1_000_000L >= FINISH_GRACE_MS;
                if (allDone || humansDone || graceOver) setPhase(PH_RESULTS, now);
            }
            case PH_RESULTS -> {
                for (Car c : slots) {
                    if (c == null) continue;
                    if (!c.ai) drive(c, dt);
                    else if (c.finished) coast(c, dt);
                    else driveAi(c, dt);
                }
                collideCars();
                if (elapsedMs >= RESULTS_MS) {
                    setPhase(PH_LOBBY, now);
                    for (Car c : slots) if (c != null) resetCar(c, c.id);
                }
            }
            default -> { }
        }
    }

    private void setPhase(byte newPhase, long now) {
        phase = newPhase;
        phaseStart = now;
        log("Phase -> " + phaseName(newPhase));
    }

    private void toggleReady(Car p) {
        synchronized (lock) {
            if (phase != PH_LOBBY) return;
            p.ready = !p.ready;
            log(p.name + (p.ready ? " is ready" : " is not ready"));
        }
    }

    /** Every car gets a random spot on the grid ("queue on the lanes randomly"). */
    private void startCountdown(long now) {
        List<Car> cars = new ArrayList<>();
        for (Car c : slots) if (c != null) cars.add(c);
        List<Integer> spots = new ArrayList<>();
        for (int i = 0; i < cars.size(); i++) spots.add(i);
        Collections.shuffle(spots, rng);
        for (int i = 0; i < cars.size(); i++) {
            Car c = cars.get(i);
            resetCar(c, spots.get(i));
            if (!c.ai) c.ready = false;
        }
        finishedCount = 0;
        firstFinish = -1;
        setPhase(PH_COUNTDOWN, now);
    }

    private void resetCar(Car c, int gridSpot) {
        double[] g = track.gridSlot(gridSpot);
        c.x = g[0];
        c.y = g[1];
        c.angle = g[2];
        c.speed = 0;
        c.lapsDone = -1;   // becomes 0 when the start line is first crossed
        c.nextCp = 0;
        c.cpCount = 0;
        c.finished = false;
        c.finishMs = 0;
        c.trackIdx = track.nearestSample(c.x, c.y);
        double[] near = track.nearestOnCenter(c.x, c.y);
        c.laneOffset = (c.x - near[0]) * -track.ty[c.trackIdx] + (c.y - near[1]) * track.tx[c.trackIdx];
        c.laneTimer = 3 + rng.nextDouble() * 3;
        c.stuckTime = 0;
        c.reverseTime = 0;
    }

    /** Human driving from key input. */
    private void drive(Car p, double dt) {
        p.angleBefore = p.angle;
        p.stunTime = Math.max(0, p.stunTime - dt);
        int in = p.input;
        boolean up = (in & IN_UP) != 0, down = (in & IN_DOWN) != 0;
        int sx = ((in & IN_RIGHT) != 0 ? 1 : 0) - ((in & IN_LEFT) != 0 ? 1 : 0);

        if ((in & IN_ARCADE) != 0) {
            // Arcade: arrows are screen directions. The car turns to face them and drives there.
            int sy = (down ? 1 : 0) - (up ? 1 : 0);
            if (sx == 0 && sy == 0) {
                roll(p, dt);
            } else {
                double diff = Math.IEEEremainder(Math.atan2(sy, sx) - p.angle, 2 * Math.PI);
                double maxTurn = ARCADE_TURN_RATE * dt;
                p.angle += Math.max(-maxTurn, Math.min(maxTurn, diff));
                if (Math.abs(diff) < 1.6 || p.speed < 0) p.speed += ACCEL * (p.stunTime > 0 ? 0.3 : 1) * dt;   // roughly facing it: go
                else p.speed = Math.max(0, p.speed - BRAKE * dt);                // turning around: slow down
            }
        } else {
            // Classic: up/down = throttle/brake, left/right steer relative to the car.
            if (up) p.speed += ACCEL * (p.stunTime > 0 ? 0.3 : 1) * dt;
            if (down) p.speed -= (p.speed > 0 ? BRAKE : REVERSE) * dt;
            if (!up && !down) roll(p, dt);
            double grip = Math.min(1, Math.abs(p.speed) / 90);
            p.angle += sx * TURN_RATE * grip * Math.signum(p.speed) * dt;
            if ((in & IN_ASSIST) != 0 && sx == 0 && p.speed > 60) {
                // light steering help: gently turns you toward the direction of the road (you still steer the bends)
                int ahead = (followRoad(p) + 3) % track.samples;
                double diff = Math.IEEEremainder(Math.atan2(track.ty[ahead], track.tx[ahead]) - p.angle, 2 * Math.PI);
                if (Math.abs(diff) < 1.0) {
                    double max = ASSIST_RATE * dt;
                    p.angle += Math.max(-max, Math.min(max, diff));
                }
            }
        }
        move(p, dt);
    }

    /** Keeps c.trackIdx at the race-line sample nearest the vehicle (searching near the last one). */
    private int followRoad(Car c) {
        int best = c.trackIdx;
        double bd = Double.MAX_VALUE;
        for (int k = -6; k <= 14; k++) {
            int i = Math.floorMod(c.trackIdx + k, track.samples);
            double d = (track.xs[i] - c.x) * (track.xs[i] - c.x) + (track.ys[i] - c.y) * (track.ys[i] - c.y);
            if (d < bd) { bd = d; best = i; }
        }
        if (bd > 300 * 300) best = track.nearestSample(c.x, c.y);
        c.trackIdx = best;
        return best;
    }

    /** AI: if a vehicle is just ahead in my lane, move to the freest lane instead of crashing into it. */
    private void avoidTraffic(Car c, int idx) {
        double fx = track.tx[idx], fy = track.ty[idx], nx = -fy, ny = fx;
        double myLat = (c.x - track.xs[idx]) * nx + (c.y - track.ys[idx]) * ny;
        boolean blocked = false;
        double[] laneScore = new double[Track.LANES];
        for (Car o : slots) {
            if (o == null || o == c) continue;
            double dx = o.x - c.x, dy = o.y - c.y, along = dx * fx + dy * fy;
            if (along < 0 || along > 160) continue;
            double lat = (o.x - track.xs[idx]) * nx + (o.y - track.ys[idx]) * ny;
            if (Math.abs(lat - myLat) < 34 && o.speed < c.speed + 20) blocked = true;
            for (int l = 0; l < Track.LANES; l++) {
                double laneLat = (l - (Track.LANES - 1) / 2.0) * Track.LANE_WIDTH;
                if (Math.abs(lat - laneLat) < 40) laneScore[l] += 1 + (160 - along) / 80;
            }
        }
        if (!blocked) return;
        int bestLane = 0;
        for (int l = 1; l < Track.LANES; l++) {
            double laneLat = (l - (Track.LANES - 1) / 2.0) * Track.LANE_WIDTH, bestLat = (bestLane - (Track.LANES - 1) / 2.0) * Track.LANE_WIDTH;
            double a = laneScore[l] + Math.abs(laneLat - myLat) / 200, b = laneScore[bestLane] + Math.abs(bestLat - myLat) / 200;
            if (a < b) bestLane = l;
        }
        c.laneOffset = (bestLane - (Track.LANES - 1) / 2.0) * Track.LANE_WIDTH;
        c.laneTimer = 2 + rng.nextDouble() * 2;
    }

    /** AI driver: follows a lane along the race line and slows down for bends. */
    private void driveAi(Car c, double dt) {
        c.angleBefore = c.angle;
        int best = followRoad(c);

        // switch lanes now and then (overtaking feel)
        c.laneTimer -= dt;
        if (c.laneTimer <= 0) {
            c.laneTimer = 2 + rng.nextDouble() * 4;
            c.laneOffset = (rng.nextInt(Track.LANES) - (Track.LANES - 1) / 2.0) * Track.LANE_WIDTH;
        }

        avoidTraffic(c, best);
        int ahead = (best + 4 + (int) (Math.max(0, c.speed) / 150)) % track.samples;
        double room = Math.min(track.half[ahead], track.half[best]) - 48;
        double lane = Math.max(-room, Math.min(room, c.laneOffset));
        double nx = -track.ty[ahead], ny = track.tx[ahead];
        double tx = track.xs[ahead] + nx * lane, ty = track.ys[ahead] + ny * lane;
        double diff = Math.IEEEremainder(Math.atan2(ty - c.y, tx - c.x) - c.angle, 2 * Math.PI);

        if (c.reverseTime > 0) {                 // un-stick: back away from whatever is in front
            c.reverseTime -= dt;
            c.speed = Math.max(-120, c.speed - BRAKE * dt);
            c.angle -= Math.signum(diff) * 1.5 * dt;
            move(c, dt);
            return;
        }

        double maxTurn = AI_TURN_RATE * dt;
        c.angle += Math.max(-maxTurn, Math.min(maxTurn, diff));

        // fastest speed the coming bends allow, braking in time (same grip as everybody)
        double target = MAX_SPEED;
        for (int k = 0; k <= 36; k += 4) {
            int a = (best + k) % track.samples, b = (best + k + 8) % track.samples;
            double bend = Math.abs(Math.IEEEremainder(
                    Math.atan2(track.ty[b], track.tx[b]) - Math.atan2(track.ty[a], track.tx[a]), 2 * Math.PI));
            double radius = 8 * 24.0 / Math.max(bend, 1e-3);
            double vmax = Math.sqrt(GRIP * radius) * (0.88 + 0.1 * c.skill);
            target = Math.min(target, Math.sqrt(vmax * vmax + 2 * BRAKE * 0.6 * k * 24.0));
        }
        if (Math.abs(diff) > 0.7) target = Math.min(target, 160);
        if (c.speed < target) c.speed += ACCEL * dt;
        else c.speed = Math.max(target, c.speed - BRAKE * 0.6 * dt);

        if (phase == PH_RACING && c.speed < 30) {
            c.stuckTime += dt;
            if (c.stuckTime > 1.2) { c.reverseTime = 0.7; c.stuckTime = 0; }
        } else {
            c.stuckTime = 0;
        }
        move(c, dt);
    }

    private void coast(Car c, double dt) {
        c.angleBefore = c.angle;
        roll(c, dt);
        move(c, dt);
    }

    private static void roll(Car p, double dt) {
        double roll = ROLL * dt;
        p.speed = Math.abs(p.speed) <= roll ? 0 : p.speed - Math.signum(p.speed) * roll;
    }

    /** Integrates position and keeps the car between the fences. */
    private void move(Car p, double dt) {
        // grip: at speed a vehicle cannot turn as sharply; cornering hard scrubs off speed
        double turned = Math.IEEEremainder(p.angle - p.angleBefore, 2 * Math.PI);
        double limit = GRIP / Math.max(Math.abs(p.speed), 1) * dt;
        if (Math.abs(turned) > limit) {
            turned = Math.signum(turned) * limit;
            p.angle = p.angleBefore + turned;
        }
        double use = Math.abs(turned) / limit;
        if (use > 0.7 && Math.abs(p.speed) > 150) p.speed -= Math.signum(p.speed) * SCRUB * (use - 0.7) / 0.3 * dt;
        p.angleBefore = p.angle;
        p.angle = Math.IEEEremainder(p.angle, 2 * Math.PI);
        if (p.speed > MAX_SPEED) p.speed = MAX_SPEED;
        if (p.speed < -MAX_REVERSE) p.speed = -MAX_REVERSE;
        p.x += Math.cos(p.angle) * p.speed * dt;
        p.y += Math.sin(p.angle) * p.speed * dt;
        fence(p);
    }

    /** The fences on both sides of the road: cars can never leave it. */
    private void fence(Car p) {
        double[] near = track.nearestOnCenter(p.x, p.y);
        double d = near[2];
        double limit = track.halfAt((int) near[3], near[4]) - FENCE_MARGIN;
        if (d <= limit) return;
        double nx = (p.x - near[0]) / d, ny = (p.y - near[1]) / d;     // points out of the road
        p.x = near[0] + nx * limit;
        p.y = near[1] + ny * limit;
        double vx = Math.cos(p.angle) * p.speed, vy = Math.sin(p.angle) * p.speed;
        double into = vx * nx + vy * ny;                                 // speed going into the fence
        double before = Math.abs(p.speed);
        if (into > 0) {
            // the speed into the fence is lost, and the harder the hit, the more of the rest goes too
            double hit = into / Math.max(1, before);                    // 0 = glancing, 1 = head-on
            vx -= into * nx;
            vy -= into * ny;
            double along = Math.hypot(vx, vy) * (1 - 0.75 * hit);
            if (along > 5) {
                p.angle = p.speed >= 0 ? Math.atan2(vy, vx) : Math.atan2(-vy, -vx);   // slide along the fence
                p.speed = Math.signum(p.speed) * along;
            } else {
                p.speed = 0;
            }
            if (into > 140) {
                p.events |= EV_HIT;
                p.stunTime = Math.min(0.8, into / 400);                  // shaken: no full power for a moment
            }
        }
        p.speed *= 0.97;                                                 // scraping friction
        if (before > 110) p.events |= EV_FENCE;
    }

    private void collideCars() {
        double minDist = CAR_RADIUS * 2;
        for (int i = 0; i < MAX_CARS; i++) {
            Car a = slots[i];
            if (a == null) continue;
            for (int j = i + 1; j < MAX_CARS; j++) {
                Car b = slots[j];
                if (b == null) continue;
                double dx = b.x - a.x, dy = b.y - a.y;
                double d = Math.hypot(dx, dy);
                if (d >= minDist || d == 0) continue;
                double push = (minDist - d) / 2, nx = dx / d, ny = dy / d;
                double impact = Math.abs((Math.cos(a.angle) * a.speed - Math.cos(b.angle) * b.speed) * nx
                        + (Math.sin(a.angle) * a.speed - Math.sin(b.angle) * b.speed) * ny);
                if (impact > 70) { a.events |= EV_HIT; b.events |= EV_HIT; }
                a.x -= nx * push; a.y -= ny * push;
                b.x += nx * push; b.y += ny * push;
                a.speed *= 0.9;
                b.speed *= 0.9;
                fence(a);
                fence(b);
            }
        }
    }

    private void checkGates(Car p, double ox, double oy, long raceMs, long now) {
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
        p.nextCp = (p.nextCp + 1) % track.checkpoints;
    }

    private byte[] buildState() {
        List<Car> order = new ArrayList<>();
        for (Car c : slots) if (c != null) order.add(c);
        order.sort(Comparator
                .comparing((Car c) -> !c.finished)
                .thenComparingInt(c -> c.finished ? c.finishMs : 0)
                .thenComparingInt(c -> -c.cpCount)
                .thenComparingDouble(c -> track.distanceToGate(c.nextCp, c.x, c.y)));

        long elapsedMs = (System.nanoTime() - phaseStart) / 1_000_000L;
        int timer = switch (phase) {
            case PH_COUNTDOWN -> (int) Math.max(0, COUNTDOWN_MS - elapsedMs);
            case PH_RACING -> (int) elapsedMs;
            case PH_RESULTS -> (int) Math.max(0, RESULTS_MS - elapsedMs);
            default -> 0;
        };

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + order.size() * 56);
            DataOutputStream o = new DataOutputStream(bos);
            o.writeByte(S_STATE);
            o.writeByte(phase);
            o.writeInt(timer);
            o.writeByte(hostId());
            o.writeByte(order.size());
            int place = 1;
            for (Car c : order) {
                o.writeByte(c.id);
                o.writeUTF(c.name);
                o.writeFloat((float) c.x);
                o.writeFloat((float) c.y);
                o.writeFloat((float) c.angle);
                o.writeFloat((float) c.speed);
                o.writeByte(Math.max(1, Math.min(LAPS, c.lapsDone + 1)));
                o.writeByte(place++);
                o.writeBoolean(c.finished);
                o.writeInt(c.finishMs);
                o.writeBoolean(c.ready);
                o.writeByte(c.nextCp);
                o.writeShort(c.cpCount);
                o.writeBoolean(c.ai);
                o.writeByte(c.vehicle);
                o.writeByte(c.events);
                c.events = 0;
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // cannot happen with a byte array
        }
    }

    // ------------------------------------------------------------------ helpers

    private int freeSlot() {
        for (int i = 0; i < MAX_CARS; i++) if (slots[i] == null) return i;
        return -1;
    }

    private int humanCount() {
        int n = 0;
        for (Car c : slots) if (c != null && !c.ai) n++;
        return n;
    }

    /** The host is the connected person with the lowest slot. */
    private int hostId() {
        for (Car c : slots) if (c != null && !c.ai) return c.id;
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
        new Thread(r, name).start();
    }

    private static void log(String msg) {
        System.out.println("[server] " + msg);
    }

    /** A car on the grid: either a connected person (socket + sender thread) or an AI driver. */
    private final class Car {
        final int id;
        final boolean ai;
        final String name;
        final Socket socket;
        final DataOutputStream out;
        final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(16);
        final Thread sender;
        volatile int input;
        volatile boolean closed;

        final int vehicle;
        double x, y, angle, speed, angleBefore, stunTime;
        int lapsDone, nextCp, cpCount, finishMs, events;
        boolean finished, ready;

        // AI state
        final double skill;
        int trackIdx;
        double laneOffset, laneTimer, stuckTime, reverseTime;

        /** A connected person. */
        Car(int id, Socket socket, String name, int vehicle) throws IOException {
            this.id = id;
            this.ai = false;
            this.vehicle = vehicle;
            this.socket = socket;
            this.name = name;
            this.skill = 1;
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            ByteArrayOutputStream welcome = new ByteArrayOutputStream();
            DataOutputStream w = new DataOutputStream(welcome);
            w.writeByte(S_WELCOME);
            w.writeByte(id);
            w.writeInt(sceneSeed);
            w.writeUTF(mapId);
            queue.add(welcome.toByteArray());
            sender = new Thread(this::sendLoop, "sender-" + id);
            sender.setDaemon(true);
            sender.start();
        }

        /** An AI driver. */
        Car(int id, String name, int vehicle) {
            this.id = id;
            this.ai = true;
            this.vehicle = vehicle;
            this.name = name;
            this.socket = null;
            this.out = null;
            this.sender = null;
            this.skill = 0.93 + rng.nextDouble() * 0.07;   // some AI drivers are faster than others
            this.ready = true;
        }

        void send(byte[] msg) {
            if (closed || ai) return;
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
            if (ai) return;
            sender.interrupt();
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
