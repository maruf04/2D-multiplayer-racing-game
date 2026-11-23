package racer;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GridLayout;
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
 *   java -jar racer.jar                         -> launcher window (host or join)
 *   java -jar racer.jar server [port]           -> dedicated headless server
 *   java -jar racer.jar host [port] [name]      -> host + play, no dialog
 *   java -jar racer.jar join <ip> [port] [name] -> join, no dialog
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase() : "";
        switch (mode) {
            case "server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_PORT;
                new GameServer(port).start();
                System.out.println("[server] LAN address(es): " + lanAddresses() + "  - press Ctrl+C to stop");
            }
            case "host" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_PORT;
                String name = args.length > 2 ? args[2] : System.getProperty("user.name", "Host");
                SwingUtilities.invokeLater(() -> launch(true, "localhost", port, name));
            }
            case "join" -> {
                if (args.length < 2) {
                    System.err.println("usage: join <server-ip> [port] [name]");
                    System.exit(1);
                }
                int port = args.length > 2 ? Integer.parseInt(args[2]) : Protocol.DEFAULT_PORT;
                String name = args.length > 3 ? args[3] : System.getProperty("user.name", "Player");
                SwingUtilities.invokeLater(() -> launch(false, args[1], port, name));
            }
            default -> SwingUtilities.invokeLater(Main::showLauncher);
        }
    }

    private static void showLauncher() {
        JTextField name = new JTextField(System.getProperty("user.name", "Player"));
        JTextField address = new JTextField("localhost");
        JTextField port = new JTextField(String.valueOf(Protocol.DEFAULT_PORT));
        JRadioButton host = new JRadioButton("Host a game (others join you)", true);
        JRadioButton join = new JRadioButton("Join a game");
        ButtonGroup group = new ButtonGroup();
        group.add(host);
        group.add(join);
        address.setEnabled(false);
        host.addActionListener(e -> address.setEnabled(false));
        join.addActionListener(e -> address.setEnabled(true));

        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        p.add(new JLabel("Your name:"));
        p.add(name);
        p.add(host);
        p.add(join);
        p.add(new JLabel("Server IP address (when joining):"));
        p.add(address);
        p.add(new JLabel("TCP port:"));
        p.add(port);

        int res = JOptionPane.showConfirmDialog(null, p, "Net Racer - up to " + Protocol.MAX_PLAYERS + " players",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) System.exit(0);

        int portNum;
        try {
            portNum = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(null, "Port must be a number.");
            showLauncher();
            return;
        }
        launch(host.isSelected(), host.isSelected() ? "localhost" : address.getText().trim(), portNum, name.getText().trim());
    }

    /** Starts the (optional) local server and connects, off the UI thread. Must be called on the EDT. */
    private static void launch(boolean hosting, String address, int port, String name) {
        GameClient client = new GameClient(hosting ? "Friends join at " + lanAddresses() + "  port " + port : null);
        new Thread(() -> {
            GameServer server = null;
            try {
                if (hosting) {
                    server = new GameServer(port);
                    server.start();
                }
                client.connect(address, port, name);
            } catch (Exception e) {
                if (server != null) server.stop();
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null, (hosting ? "Could not host: " : "Could not join: ") + msg,
                            "Net Racer", JOptionPane.ERROR_MESSAGE);
                    showLauncher();
                });
                return;
            }
            GameServer finalServer = server;
            SwingUtilities.invokeLater(() -> openWindow(client, finalServer));
        }, "connect").start();
    }

    private static void openWindow(GameClient client, GameServer server) {
        JFrame frame = new JFrame(server != null ? "Net Racer (hosting)" : "Net Racer");
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
        frame.setVisible(true);
        client.requestFocusInWindow();
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
