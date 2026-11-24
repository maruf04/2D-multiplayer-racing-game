package racer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Wi-Fi / local network support, so nobody has to type an IP address:
 *  - lists this computer's networks, with the Wi-Fi name when the system can tell it
 *  - the host answers "who is hosting?" questions (UDP broadcast on port DISCOVERY_PORT)
 *  - players who want to join ask that question every second and get a list of games on the same Wi-Fi.
 */
final class Lan {
    static final int DISCOVERY_PORT = 5550;
    private static final byte[] ASK = "BDRACE?".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ANSWER = "BDRACE!".getBytes(StandardCharsets.US_ASCII);

    private Lan() { }

    /** One network connection of this computer (usually the Wi-Fi). */
    record Network(String wifiName, String device, InetAddress address, InetAddress broadcast, boolean wifi, boolean virtual) {
        /** What the dropdown shows, e.g. "SBH-404   (Wi-Fi, 192.168.0.200)". */
        @Override
        public String toString() {
            String ip = address.getHostAddress();
            if (wifi) return (wifiName != null ? wifiName : "Wi-Fi " + device) + "   (Wi-Fi, " + ip + ")";
            if (virtual) return "Virtual network " + device + "   (" + ip + ")";
            return "Network cable " + device + "   (" + ip + ")";
        }

        /** Short name for messages, e.g. "SBH-404" or "the Wi-Fi". */
        String shortName() {
            return wifiName != null ? wifiName : wifi ? "the Wi-Fi" : "network " + device;
        }
    }

    /** A game found on the network. */
    record Game(InetAddress address, int port, int version, String host, String track, int people, int ai, int phase, long seen) {
        boolean compatible() { return version == Protocol.VERSION; }

        String key() { return address.getHostAddress() + ":" + port; }

        @Override
        public String toString() {
            String who = people == 1 ? "1 player" : people + " players";
            if (ai > 0) who += " + " + ai + " AI";
            String place = track.contains(",") ? track.substring(0, track.indexOf(',')) : track;
            String s = host + "  •  " + place + "  •  " + who;
            if (!compatible()) s += "  •  different game version";
            else if (phase != Protocol.PH_LOBBY) s += "  •  racing now, wait";
            return s;
        }
    }

    // ------------------------------------------------------------------ this computer's networks

    /** Networks that are switched on, Wi-Fi first, virtual ones last. */
    static List<Network> networks() {
        Map<String, String> names = wifiNames();
        List<Network> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String dev = ni.getName(), disp = ni.getDisplayName() == null ? dev : ni.getDisplayName();
                String ssid = names.containsKey(dev) ? names.get(dev) : names.get(disp);
                boolean wifi = ssid != null || looksLikeWifi(dev, disp);
                if (ssid == null && wifi) ssid = names.get("*");      // the only Wi-Fi name the system reported
                boolean virtual = !wifi && (ni.isVirtual() || looksVirtual(dev, disp));
                String shown = !disp.equals(dev) && disp.length() <= 40 ? disp : dev;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        list.add(new Network(ssid, shown, ia.getAddress(), ia.getBroadcast(), wifi, virtual));
                    }
                }
            }
        } catch (IOException ignored) { }
        list.sort(Comparator.comparing((Network n) -> !n.wifi()).thenComparing(Network::virtual));
        return list;
    }

    private static boolean looksLikeWifi(String dev, String disp) {
        String d = disp.toLowerCase(Locale.ROOT);
        return dev.startsWith("wl") || d.contains("wi-fi") || d.contains("wifi") || d.contains("wireless")
                || d.contains("wlan") || d.contains("802.11");
    }

    private static boolean looksVirtual(String dev, String disp) {
        String d = disp.toLowerCase(Locale.ROOT);
        for (String p : new String[] { "docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "tun", "tap", "zt", "tailscale", "utun", "lxc", "lxd" }) {
            if (dev.startsWith(p)) return true;
        }
        for (String w : new String[] { "virtual", "vmware", "virtualbox", "hyper-v", "vpn", "tap-", "tunnel", "bluetooth", "loopback" }) {
            if (d.contains(w)) return true;
        }
        return false;
    }

    private static volatile Map<String, String> wifiNameCache;

    /** Device (or adapter description) -> Wi-Fi name, asked from the operating system. Best effort. */
    static Map<String, String> wifiNames() {
        Map<String, String> cached = wifiNameCache;
        if (cached != null) return cached;
        Map<String, String> map = new HashMap<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String desc = null, name = null;
            for (String line : run("netsh", "wlan", "show", "interfaces")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                if (key.equalsIgnoreCase("Name")) { name = value; desc = null; }
                else if (key.toLowerCase(Locale.ROOT).startsWith("descr")) desc = value;
                else if (key.equals("SSID") && !value.isEmpty()) {
                    if (desc != null) map.put(desc, value);
                    if (name != null) map.put(name, value);
                    map.put("*", value);
                }
            }
        } else if (os.contains("mac")) {
            for (String dev : new String[] { "en0", "en1" }) {
                for (String line : run("networksetup", "-getairportnetwork", dev)) {
                    int colon = line.indexOf(':');
                    if (line.startsWith("Current") && colon > 0) {
                        map.put(dev, line.substring(colon + 1).trim());
                        map.put("*", line.substring(colon + 1).trim());
                    }
                }
            }
        } else {
            for (String line : run("nmcli", "-t", "-f", "DEVICE,TYPE,STATE,CONNECTION", "device")) {
                String[] f = line.split("(?<!\\\\):");
                if (f.length >= 4 && f[1].equals("wifi") && f[2].startsWith("connected") && !f[3].isEmpty()) {
                    String ssid = f[3].replace("\\:", ":").replace("\\\\", "\\");
                    map.put(f[0], ssid);
                    map.put("*", ssid);
                }
            }
            if (map.isEmpty()) {
                for (String line : run("iwgetid", "-r")) {
                    if (!line.isBlank()) map.put("*", line.trim());
                }
            }
        }
        wifiNameCache = map;
        return map;
    }

    /** Runs a small system command and returns its output (empty if it is missing or too slow). */
    private static List<String> run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return List.of();
            }
            String enc = System.getProperty("native.encoding", Charset.defaultCharset().name());
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName(enc)))) {
                for (String line; (line = r.readLine()) != null; ) lines.add(line);
            }
            return lines;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ host side

    /** Builds the answer the host sends to players looking for games. */
    static byte[] answer(int port, String host, String track, int people, int ai, int phase) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bos);
            o.write(ANSWER);
            o.writeInt(Protocol.VERSION);
            o.writeInt(port);
            o.writeUTF(host);
            o.writeUTF(track);
            o.writeByte(people);
            o.writeByte(ai);
            o.writeByte(phase);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs on the host: answers every "who is hosting?" question on the network. */
    static final class Responder implements Runnable {
        private final Supplier<byte[]> info;
        private final DatagramSocket socket;
        private volatile boolean running = true;

        private Responder(Supplier<byte[]> info) throws IOException {
            this.info = info;
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
        }

        static Responder start(Supplier<byte[]> info) throws IOException {
            Responder r = new Responder(info);
            Thread t = new Thread(r, "lan-responder");
            t.setDaemon(true);
            t.start();
            return r;
        }

        @Override
        public void run() {
            byte[] buf = new byte[64];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    if (startsWith(p.getData(), p.getLength(), ASK)) {
                        byte[] reply = info.get();
                        socket.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
                    }
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }

        void stop() {
            running = false;
            socket.close();
        }
    }

    // ------------------------------------------------------------------ joining side

    /** Runs while the start window is in JOIN mode: keeps a fresh list of the games on the chosen network. */
    static final class Finder {
        private final Consumer<List<Game>> listener;
        private final Map<String, Game> found = new ConcurrentHashMap<>();
        private volatile Network network;
        private volatile boolean running;

        Finder(Consumer<List<Game>> listener) {
            this.listener = listener;
        }

        synchronized void start(Network n) {
            setNetwork(n);
            if (running) return;
            running = true;
            Thread t = new Thread(this::loop, "lan-finder");
            t.setDaemon(true);
            t.start();
        }

        void setNetwork(Network n) {
            if (Objects.equals(n, network)) return;
            network = n;
            found.clear();
        }

        void stop() {
            running = false;
        }

        private void loop() {
            DatagramSocket socket = null;
            Network bound = null;
            byte[] buf = new byte[512];
            while (running) {
                try {
                    Network n = network;
                    if (socket == null || !Objects.equals(n, bound)) {
                        if (socket != null) socket.close();
                        socket = new DatagramSocket(new InetSocketAddress(n != null ? n.address() : null, 0));
                        socket.setBroadcast(true);
                        socket.setSoTimeout(200);
                        bound = n;
                    }
                    for (InetAddress target : targets(n)) {
                        try {
                            socket.send(new DatagramPacket(ASK, ASK.length, target, DISCOVERY_PORT));
                        } catch (IOException ignored) { }   // that address is not reachable from here
                    }
                    long end = System.currentTimeMillis() + 1100;
                    while (running && System.currentTimeMillis() < end) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            socket.receive(p);
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        Game g = parse(p);
                        if (g != null) found.put(g.key(), g);
                    }
                    long now = System.currentTimeMillis();
                    found.values().removeIf(g -> now - g.seen() > 4000);
                    if (running) listener.accept(withoutDuplicates(new ArrayList<>(found.values())));
                } catch (IOException e) {
                    if (socket != null) socket.close();
                    socket = null;
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }
            if (socket != null) socket.close();
        }

        private static Set<InetAddress> targets(Network n) {
            Set<InetAddress> t = new LinkedHashSet<>();
            if (n != null && n.broadcast() != null) t.add(n.broadcast());
            try {
                t.add(InetAddress.getByName("255.255.255.255"));
            } catch (IOException ignored) { }
            t.add(InetAddress.getLoopbackAddress());   // a game hosted on this same computer
            return t;
        }

        private static Game parse(DatagramPacket p) {
            if (!startsWith(p.getData(), p.getLength(), ANSWER)) return null;
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(p.getData(), ANSWER.length, p.getLength() - ANSWER.length));
                int version = in.readInt(), port = in.readInt();
                String host = in.readUTF(), track = in.readUTF();
                int people = in.readByte(), ai = in.readByte(), phase = in.readByte();
                return new Game(p.getAddress(), port, version, host, track, people, ai, phase, System.currentTimeMillis());
            } catch (IOException e) {
                return null;
            }
        }

        /** The same game answers on the Wi-Fi address and on "this computer"; keep the Wi-Fi one. */
        private static List<Game> withoutDuplicates(List<Game> games) {
            List<Game> out = new ArrayList<>();
            for (Game g : games) {
                boolean twin = g.address().isLoopbackAddress() && games.stream().anyMatch(o -> !o.address().isLoopbackAddress()
                        && o.port() == g.port() && o.host().equals(g.host()) && o.track().equals(g.track()));
                if (!twin) out.add(g);
            }
            out.sort(Comparator.comparing(Game::host).thenComparing(Game::key));
            return out;
        }
    }

    private static boolean startsWith(byte[] data, int length, byte[] prefix) {
        return length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }
}
