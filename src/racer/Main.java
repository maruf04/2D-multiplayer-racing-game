package racer;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entry point.
 *   run.sh                                              -> start window (host or join)
 *   run.sh server [port] [aiCars] [track]               -> dedicated headless server
 *   run.sh host [port] [name] [aiCars] [track] [vehicle] -> host + play, no start window
 *   run.sh join <ip> [port] [name] [vehicle]            -> join, no start window
 *   run.sh tracks                                       -> list the track names
 * vehicle = cycle | motorbike | car | rickshaw
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase() : "";
        switch (mode) {
            case "tracks" -> {
                for (MapData.Info i : MapData.list()) System.out.println(i.id() + "  -  " + i.title());
            }
            case "server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_PORT;
                int ai = args.length > 2 ? Integer.parseInt(args[2]) : Protocol.DEFAULT_AI;
                String track = args.length > 3 ? args[3] : MapData.defaultId();
                new GameServer(port, ai, track).start();
                System.out.println("[server] LAN address(es): " + lanAddresses() + "  - press Ctrl+C to stop");
            }
            case "host" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_PORT;
                String name = args.length > 2 ? args[2] : System.getProperty("user.name", "Host");
                int ai = args.length > 3 ? Integer.parseInt(args[3]) : Protocol.DEFAULT_AI;
                String track = args.length > 4 ? args[4] : MapData.defaultId();
                int vehicle = args.length > 5 ? vehicle(args[5]) : Protocol.CAR;
                launch(new Launcher.Choice(true, "localhost", port, name, ai, track, vehicle, null), null);
            }
            case "join" -> {
                if (args.length < 2) {
                    System.err.println("usage: join <server-ip> [port] [name]");
                    System.exit(1);
                }
                int port = args.length > 2 ? Integer.parseInt(args[2]) : Protocol.DEFAULT_PORT;
                String name = args.length > 3 ? args[3] : System.getProperty("user.name", "Player");
                int vehicle = args.length > 4 ? vehicle(args[4]) : Protocol.CAR;
                launch(new Launcher.Choice(false, args[1], port, name, 0, "", vehicle, null), null);
            }
            default -> SwingUtilities.invokeLater(Main::showLauncher);
        }
    }

    private static int vehicle(String name) {
        for (int i = 0; i < Protocol.VEHICLE_NAMES.length; i++) {
            if (Protocol.VEHICLE_NAMES[i].equalsIgnoreCase(name)) return i;
        }
        return Protocol.CAR;
    }

    private static void showLauncher() {
        Launcher[] holder = new Launcher[1];
        holder[0] = new Launcher(choice -> launch(choice, holder[0]));
        holder[0].setVisible(true);
    }

    /** Starts the (optional) local server and connects, off the UI thread. */
    private static void launch(Launcher.Choice c, Launcher launcher) {
        GameClient client = new GameClient(null);
        new Thread(() -> {
            GameServer server = null;
            try {
                if (c.host()) {
                    server = new GameServer(c.port(), c.aiCars(), c.mapId());
                    server.start();
                    client.setHostInfo(hostInfo(c.network(), server.port()));
                }
                client.connect(c.address(), server != null ? server.port() : c.port(), c.name(), c.vehicle());
            } catch (Exception e) {
                if (server != null) server.stop();
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                String text = (c.host() ? "Could not host: " : "Could not join: ") + msg;
                SwingUtilities.invokeLater(() -> {
                    if (launcher != null) {
                        launcher.showError(text);
                    } else {
                        JOptionPane.showMessageDialog(null, text, "Bangladesh City Racing", JOptionPane.ERROR_MESSAGE);
                        System.exit(1);
                    }
                });
                return;
            }
            GameServer finalServer = server;
            SwingUtilities.invokeLater(() -> {
                if (launcher != null) launcher.dispose();
                openWindow(client, finalServer);
            });
        }, "connect").start();
    }

    private static void openWindow(GameClient client, GameServer server) {
        JFrame frame = new JFrame(server != null ? "Bangladesh City Racing (hosting)" : "Bangladesh City Racing");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                client.close();
                if (server != null) server.stop();
                System.exit(0);
            }
        });
        frame.add(client);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);   // full screen size, still a normal window
        frame.setVisible(true);
        client.requestFocusInWindow();
    }

    /** Lobby line for the host: friends on the same Wi-Fi find the game by themselves. */
    static String hostInfo(Lan.Network net, int port) {
        if (net != null) {
            return "Friends on " + net.shortName() + " will see this game under JOIN A GAME   (or type "
                    + net.address().getHostAddress() + (port == Protocol.DEFAULT_PORT ? "" : ":" + port) + ")";
        }
        return "Friends join at " + lanAddresses() + (port == Protocol.DEFAULT_PORT ? "" : "  port " + port);
    }

    static String lanAddresses() {
        List<String> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) list.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return list.isEmpty() ? "localhost" : String.join(", ", list);
    }
}
