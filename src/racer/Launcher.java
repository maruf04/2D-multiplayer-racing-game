package racer;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The start window: big, colourful and easy to read. Pick a track, a vehicle, then host or join. */
final class Launcher extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final Color YELLOW = new Color(255, 205, 40);
    private static final Font LABEL = new Font(Font.SANS_SERIF, Font.BOLD, 19);
    private static final Font FIELD = new Font(Font.SANS_SERIF, Font.PLAIN, 21);

    /** What the player chose. */
    record Choice(boolean host, String address, int port, String name, int aiCars, String mapId, int vehicle, Lan.Network network) { }

    private static final String SEARCHING = "Looking for games on this Wi-Fi...";
    private static final String TYPE_IP = "Type the host's IP address myself...";
    private static final String NO_WIFI = "No Wi-Fi found - you can still race on this computer";

    private final JTextField name = field(System.getProperty("user.name", "Player"), 11);
    private final JTextField ip = field("192.168.0.", 11);
    private final JSpinner ai = new JSpinner(new SpinnerNumberModel(Protocol.DEFAULT_AI, 0, Protocol.MAX_CARS - 1, 1));
    private final JComboBox<Object> wifiBox = combo();
    private final DefaultComboBoxModel<Object> games = new DefaultComboBoxModel<>(new Object[] { SEARCHING, TYPE_IP });
    private final JComboBox<Object> gameBox = combo();
    private final FancyButton hostBtn = new FancyButton("HOST A GAME", YELLOW, Color.BLACK, 19);
    private final FancyButton joinBtn = new FancyButton("JOIN A GAME", YELLOW, Color.BLACK, 19);
    private final FancyButton start = new FancyButton("START RACING  \u25B6", new Color(40, 190, 90), Color.WHITE, 27);
    private final FancyButton prevTrack = new FancyButton("\u25C0", YELLOW, Color.BLACK, 24);
    private final FancyButton nextTrack = new FancyButton("\u25B6", YELLOW, Color.BLACK, 24);
    private final JLabel status = new JLabel(" ", SwingConstants.LEFT);
    private final JLabel aiLabel = label("AI cars");
    private final JLabel gameLabel = label("Game");
    private final JLabel ipLabel = label("Host IP");
    private final JPanel aiRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JPanel form = new JPanel(new GridBagLayout());
    private final VehicleCard[] cards = new VehicleCard[4];
    private final TrackPreview preview = new TrackPreview();
    private final List<MapData.Info> tracks = MapData.list();
    private final Lan.Finder finder = new Lan.Finder(list -> SwingUtilities.invokeLater(() -> showGames(list)));
    private int trackIndex;
    private int vehicle = Protocol.CAR;
    private boolean hosting = true;

    Launcher(Consumer<Choice> onStart) {
        super("Bangladesh City Racing");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Background bg = new Background();
        bg.setLayout(new GridBagLayout());
        setContentPane(bg);

        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0; form.add(label("Your name"), c);
        c.gridx = 1; c.gridwidth = 3; form.add(name, c); c.gridwidth = 1;
        c.gridx = 1; c.gridy = 1; c.gridwidth = 2; form.add(hostBtn, c);
        c.gridx = 3; c.gridwidth = 1; form.add(joinBtn, c);
        c.gridx = 0; c.gridy = 2; form.add(label("Wi-Fi"), c);
        wifiBox.addItem("Finding your Wi-Fi...");
        c.gridx = 1; c.gridwidth = 3; form.add(wifiBox, c); c.gridwidth = 1;

        aiRow.setOpaque(false);
        ai.setFont(FIELD);
        ((JSpinner.DefaultEditor) ai.getEditor()).getTextField().setColumns(2);
        aiRow.add(ai);
        JLabel aiHint = new JLabel("computer drivers join the race");
        aiHint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        aiHint.setForeground(new Color(200, 220, 235));
        aiRow.add(aiHint);
        c.gridx = 0; c.gridy = 3; form.add(aiLabel, c);
        c.gridx = 1; c.gridwidth = 3; form.add(aiRow, c); c.gridwidth = 1;
        gameBox.setModel(games);
        c.gridx = 0; c.gridy = 4; form.add(gameLabel, c);
        c.gridx = 1; c.gridwidth = 3; form.add(gameBox, c); c.gridwidth = 1;
        c.gridx = 0; c.gridy = 5; form.add(ipLabel, c);
        c.gridx = 1; c.gridwidth = 3; form.add(ip, c); c.gridwidth = 1;

        c.gridx = 0; c.gridy = 6; form.add(label("Vehicle"), c);
        JPanel cardRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        cardRow.setOpaque(false);
        for (int v = 0; v < cards.length; v++) {
            cards[v] = new VehicleCard(v);
            cardRow.add(cards[v]);
        }
        c.gridx = 1; c.gridwidth = 3; form.add(cardRow, c); c.gridwidth = 1;

        c.gridx = 0; c.gridy = 7; c.gridwidth = 4; c.insets = new Insets(16, 6, 4, 6);
        start.setPreferredSize(new Dimension(470, 60));
        form.add(start, c);
        c.gridy = 8; c.insets = new Insets(4, 6, 4, 6);
        status.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        status.setForeground(Color.WHITE);
        status.setPreferredSize(new Dimension(470, 26));
        form.add(status, c);

        JPanel trackBox = new JPanel(new GridBagLayout());
        trackBox.setOpaque(false);
        GridBagConstraints t = new GridBagConstraints();
        t.gridx = 0; t.gridy = 0; t.gridwidth = 3; t.fill = GridBagConstraints.BOTH;
        preview.setPreferredSize(new Dimension(420, 450));
        preview.setMinimumSize(new Dimension(420, 450));
        trackBox.add(preview, t);
        t.gridy = 1; t.gridwidth = 1; t.insets = new Insets(10, 4, 0, 4); t.fill = GridBagConstraints.NONE;
        prevTrack.setPreferredSize(new Dimension(90, 52));
        nextTrack.setPreferredSize(new Dimension(90, 52));
        trackBox.add(prevTrack, t);
        t.gridx = 1; t.weightx = 1;
        JLabel count = new JLabel(tracks.size() + " tracks across Bangladesh", SwingConstants.CENTER);
        count.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        count.setForeground(new Color(220, 240, 255));
        trackBox.add(count, t);
        t.gridx = 2; t.weightx = 0;
        trackBox.add(nextTrack, t);

        // everything centred on the screen: title on top, form left, track right
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        GridBagConstraints k = new GridBagConstraints();
        k.gridx = 0; k.gridy = 0; k.gridwidth = 2; k.anchor = GridBagConstraints.WEST; k.insets = new Insets(0, 6, 14, 0);
        content.add(new Title(), k);
        k.gridy = 1; k.gridwidth = 1; k.anchor = GridBagConstraints.NORTHWEST; k.insets = new Insets(0, 0, 0, 0);
        content.add(form, k);
        k.gridx = 1; k.anchor = GridBagConstraints.NORTH; k.insets = new Insets(0, 40, 0, 0);
        content.add(trackBox, k);
        bg.add(content, new GridBagConstraints());

        hostBtn.addActionListener(e -> setMode(true));
        joinBtn.addActionListener(e -> setMode(false));
        prevTrack.addActionListener(e -> selectTrack(trackIndex - 1));
        nextTrack.addActionListener(e -> selectTrack(trackIndex + 1));
        gameBox.addActionListener(e -> updateRows());
        wifiBox.addActionListener(e -> {
            if (!hosting && wifiBox.getSelectedItem() instanceof Lan.Network n) finder.start(n);
        });
        start.addActionListener(e -> startPressed(onStart));
        setMode(true);
        selectVehicle(Protocol.CAR);
        selectTrack(0);
        loadNetworks();
        setSize(1180, 760);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(null);
        setExtendedState(MAXIMIZED_BOTH);
    }

    /** Asks the system for the Wi-Fi name in the background (it can take a second). */
    private void loadNetworks() {
        Thread t = new Thread(() -> {
            List<Lan.Network> nets = Lan.networks();
            SwingUtilities.invokeLater(() -> {
                wifiBox.removeAllItems();
                for (Lan.Network n : nets) wifiBox.addItem(n);
                if (nets.isEmpty()) wifiBox.addItem(NO_WIFI);
                wifiBox.setSelectedIndex(0);
                if (!hosting && !nets.isEmpty()) finder.start(nets.get(0));
            });
        }, "wifi-names");
        t.setDaemon(true);
        t.start();
    }

    private Lan.Network network() {
        return wifiBox.getSelectedItem() instanceof Lan.Network n ? n : null;
    }

    private void startPressed(Consumer<Choice> onStart) {
        String player = name.getText().trim();
        if (hosting) {
            if (tracks.isEmpty()) {
                showError("No tracks found - run compile.sh again");
                return;
            }
            setBusy(true);
            onStart.accept(new Choice(true, "localhost", Protocol.DEFAULT_PORT, player, (Integer) ai.getValue(),
                    tracks.get(trackIndex).id(), vehicle, network()));
            return;
        }
        Object sel = gameBox.getSelectedItem();
        if (sel instanceof Lan.Game g) {
            if (!g.compatible()) {
                showError("That game is a different version - both players need the same version");
                return;
            }
            setBusy(true);
            onStart.accept(new Choice(false, g.address().getHostAddress(), g.port(), player, 0, "", vehicle, network()));
        } else if (TYPE_IP.equals(sel)) {
            String text = ip.getText().trim();
            int port = Protocol.DEFAULT_PORT;
            int colon = text.lastIndexOf(':');
            try {
                if (colon > 0) {
                    port = Integer.parseInt(text.substring(colon + 1).trim());
                    text = text.substring(0, colon).trim();
                }
            } catch (NumberFormatException ex) {
                showError("Write the address like 192.168.0.5 or 192.168.0.5:5555");
                return;
            }
            if (text.isEmpty() || text.endsWith(".")) {
                showError("Type the full IP address shown in the host's lobby");
                return;
            }
            setBusy(true);
            onStart.accept(new Choice(false, text, port, player, 0, "", vehicle, network()));
        } else {
            showError("No game found on this Wi-Fi yet - ask a friend to click HOST A GAME");
        }
    }

    /** New list of games from the network search. */
    private void showGames(List<Lan.Game> found) {
        if (hosting || gameBox.isPopupVisible()) return;       // don't change an open list under the mouse
        List<Object> items = new ArrayList<>(found);
        if (found.isEmpty()) items.add(SEARCHING);
        items.add(TYPE_IP);
        boolean same = items.size() == games.getSize();
        for (int i = 0; same && i < items.size(); i++) same = items.get(i).toString().equals(games.getElementAt(i).toString());
        if (same) return;
        Object sel = gameBox.getSelectedItem();
        String key = sel instanceof Lan.Game g ? g.key() : null;
        games.removeAllElements();
        for (Object o : items) games.addElement(o);
        Object pick = found.isEmpty() ? SEARCHING : found.get(0);
        if (TYPE_IP.equals(sel)) pick = TYPE_IP;
        for (Lan.Game g : found) if (g.key().equals(key)) pick = g;
        games.setSelectedItem(pick);
        updateRows();
    }

    private void updateRows() {
        aiLabel.setVisible(hosting);
        aiRow.setVisible(hosting);
        gameLabel.setVisible(!hosting);
        gameBox.setVisible(!hosting);
        boolean manual = !hosting && TYPE_IP.equals(gameBox.getSelectedItem());
        ipLabel.setVisible(manual);
        ip.setVisible(manual);
        form.revalidate();
        form.repaint();
    }

    void setMode(boolean host) {
        hosting = host;
        hostBtn.selected = host;
        joinBtn.selected = !host;
        prevTrack.setEnabled(host);
        nextTrack.setEnabled(host);
        if (host) finder.stop();
        else if (network() != null) finder.start(network());
        updateRows();
        preview.repaint();
        repaint();
    }

    @Override
    public void dispose() {
        finder.stop();
        super.dispose();
    }

    private void selectTrack(int i) {
        if (tracks.isEmpty()) return;
        trackIndex = Math.floorMod(i, tracks.size());
        preview.repaint();
    }

    private void selectVehicle(int v) {
        vehicle = v;
        for (VehicleCard card : cards) card.repaint();
    }

    void setBusy(boolean busy) {
        start.setEnabled(!busy);
        status.setForeground(Color.WHITE);
        status.setText(busy ? "Connecting and building the map..." : " ");
    }

    void showError(String msg) {
        start.setEnabled(true);
        status.setForeground(new Color(255, 120, 110));
        status.setText(msg);
    }

    private static JComboBox<Object> combo() {
        JComboBox<Object> box = new JComboBox<>();
        box.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        box.setPreferredSize(new Dimension(500, 42));
        box.setMaximumRowCount(10);
        return box;
    }

    private static JTextField field(String text, int columns) {
        JTextField f = new JTextField(text, columns);
        f.setFont(FIELD);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 120), 2),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        return f;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(LABEL);
        l.setForeground(Color.WHITE);
        return l;
    }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** A big clickable card showing one vehicle. */
    private final class VehicleCard extends JComponent {
        private static final long serialVersionUID = 1L;
        final int type;

        VehicleCard(int type) {
            this.type = type;
            setPreferredSize(new Dimension(104, 92));
            setMinimumSize(getPreferredSize());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(Protocol.VEHICLE_NAMES[type] + " - all vehicles have the same speed");
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { selectVehicle(VehicleCard.this.type); }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            boolean on = vehicle == type;
            g.setColor(on ? new Color(255, 205, 40, 235) : new Color(255, 255, 255, 40));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.setColor(on ? Color.WHITE : new Color(255, 255, 255, 120));
            g.setStroke(new BasicStroke(on ? 3 : 2));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
            AffineTransform saved = g.getTransform();
            g.translate(getWidth() / 2.0, 38);
            double k = type == Protocol.RICKSHAW ? 1.4 : 1.7;
            g.scale(k, k);
            GameClient.drawVehicleShape(g, type, on ? new Color(220, 40, 50) : new Color(40, 120, 235));
            g.setTransform(saved);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.setColor(on ? Color.BLACK : Color.WHITE);
            String text = Protocol.VEHICLE_NAMES[type];
            g.drawString(text, (getWidth() - g.getFontMetrics().stringWidth(text)) / 2, getHeight() - 10);
            g.dispose();
        }
    }

    /** Shows the selected track: its landmark, the circuit, name and city. */
    private final class TrackPreview extends JComponent {
        private static final long serialVersionUID = 1L;
        private final Map<String, BufferedImage> cache = new HashMap<>();

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(0, 0, 0, 110));
            g.fillRoundRect(0, 0, w, h, 24, 24);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            g.setColor(YELLOW);
            g.drawString(hosting ? "CHOOSE THE TRACK" : "THE HOST CHOOSES THE TRACK", 20, 30);
            if (tracks.isEmpty()) {
                g.setColor(Color.WHITE);
                g.drawString("No tracks found - run compile.sh", 20, 70);
                g.dispose();
                return;
            }
            MapData.Info info = tracks.get(trackIndex);
            int maxW = w - 40, maxH = h - 130;
            BufferedImage img = maxW < 40 || maxH < 40 ? null
                    : cache.computeIfAbsent(info.id() + "@" + maxW + "x" + maxH, key -> render(info.id(), maxW, maxH));
            if (img != null) {
                if (!hosting) {
                    g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
                }
                g.drawImage(img, (w - img.getWidth()) / 2, 44 + (h - 130 - img.getHeight()) / 2, null);
                g.setComposite(java.awt.AlphaComposite.SrcOver);
            }
            if (hosting) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
                g.setColor(Color.WHITE);
                String n = info.name();
                g.drawString(n, (w - g.getFontMetrics().stringWidth(n)) / 2, h - 50);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
                g.setColor(new Color(200, 225, 245));
                String cty = info.city() + "  •  " + (trackIndex + 1) + " / " + tracks.size();
                g.drawString(cty, (w - g.getFontMetrics().stringWidth(cty)) / 2, h - 20);
            } else {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
                g.setColor(Color.WHITE);
                String n = "You will race on the host's track";
                g.drawString(n, (w - g.getFontMetrics().stringWidth(n)) / 2, h - 36);
            }
            g.dispose();
        }

        private BufferedImage render(String id, int maxW, int maxH) {
            MapData m;
            try {
                m = MapData.load(id);
            } catch (Exception e) {
                return null;
            }
            double k = Math.min(maxW / (double) m.worldW, maxH / (double) m.worldH);
            BufferedImage img = new BufferedImage((int) (m.worldW * k) + 2, (int) (m.worldH * k) + 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            smooth(g);
            g.scale(k, k);
            Color[] fill = { new Color(60, 150, 230), new Color(0, 0, 0, 0), new Color(170, 170, 178),
                    new Color(80, 170, 80), new Color(40, 120, 50), new Color(110, 180, 90) };
            Path2D road = path(m.center);
            for (int r = 0; r < m.rings.length; r++) {
                if (m.ringRole[r] == MapData.ISLAND) continue;
                g.setColor(fill[m.ringRole[r]]);
                g.fill(path(m.rings[r]));
            }
            g.setStroke(new BasicStroke((float) (2 * Track.HALF_WIDTH + 60), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 255, 255, 50));
            g.draw(road);
            g.setStroke(new BasicStroke((float) (2 * Track.HALF_WIDTH), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(45, 45, 52));
            g.draw(road);
            g.setStroke(new BasicStroke((float) (6 / k), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                    new float[] { (float) (12 / k), (float) (12 / k) }, 0));
            g.setColor(new Color(255, 255, 255, 170));
            g.draw(road);
            g.setColor(Color.WHITE);                       // start line
            int sx = m.center[0], sy = m.center[1];
            g.fillOval((int) (sx - 9 / k), (int) (sy - 9 / k), (int) (18 / k), (int) (18 / k));
            g.dispose();
            return img;
        }

        private Path2D path(int[] pts) {
            Path2D p = new Path2D.Double();
            p.moveTo(pts[0], pts[1]);
            for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
            p.closePath();
            return p;
        }
    }

    /** Rounded, coloured button that is easy to see. */
    private static final class FancyButton extends JButton {
        private static final long serialVersionUID = 1L;
        final Color on, text;
        boolean selected = true;

        FancyButton(String label, Color on, Color text, int size) {
            super(label);
            this.on = on;
            this.text = text;
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(190, 48));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            Color fill = !isEnabled() ? new Color(90, 90, 90, 160) : selected ? on : new Color(255, 255, 255, 40);
            if (getModel().isRollover() && isEnabled()) fill = fill.brighter();
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRoundRect(3, 4, getWidth() - 4, getHeight() - 4, 18, 18);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 4, getHeight() - 4, 18, 18);
            if (!selected) {
                g.setColor(new Color(255, 255, 255, 140));
                g.setStroke(new BasicStroke(2));
                g.drawRoundRect(1, 1, getWidth() - 6, getHeight() - 6, 18, 18);
            }
            g.setFont(getFont());
            g.setColor(selected && isEnabled() ? text : Color.WHITE);
            int tw = g.getFontMetrics().stringWidth(getText());
            g.drawString(getText(), (getWidth() - 4 - tw) / 2, (getHeight() - 4) / 2 + g.getFontMetrics().getAscent() / 2 - 3);
            g.dispose();
        }
    }

    /** The big title. */
    private static final class Title extends JComponent {
        private static final long serialVersionUID = 1L;

        Title() {
            setPreferredSize(new Dimension(700, 140));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 60));
            g.setColor(new Color(0, 0, 0, 120));
            g.drawString("BANGLADESH", 4, 60);
            g.setColor(Color.WHITE);
            g.drawString("BANGLADESH", 0, 56);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 42));
            g.setColor(new Color(0, 0, 0, 120));
            g.drawString("CITY RACING", 4, 106);
            g.setColor(YELLOW);
            g.drawString("CITY RACING", 0, 102);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            g.setColor(new Color(220, 240, 255));
            g.drawString("Race your friends on the same Wi-Fi  \u2022  up to " + Protocol.MAX_CARS + " racers  \u2022  AI drivers  \u2022  "
                    + Protocol.LAPS + " laps", 2, 132);
            g.dispose();
        }
    }

    /** Gradient background with the key help at the bottom. */
    private static final class Background extends JPanel {
        private static final long serialVersionUID = 1L;

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), h = getHeight();
            smooth(g);
            g.setPaint(new GradientPaint(0, 0, new Color(10, 18, 48), 0, h, new Color(8, 96, 112)));
            g.fillRect(0, 0, w, h);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g.setColor(new Color(200, 220, 235));
            String help = "\u2191 drive   \u2193 brake   \u2190 \u2192 steer   \u2022   C change view   \u2022   H horn   \u2022   Enter ready   \u2022   M sound   \u2022   Esc quit";
            g.drawString(help, (w - g.getFontMetrics().stringWidth(help)) / 2, h - 18);
            g.dispose();
        }
    }
}
