package racer;

/**
 * Shared constants for the TCP protocol.
 *
 * Every message starts with a one-byte type, followed by its fields written
 * with DataOutputStream (big-endian ints/floats, modified-UTF-8 strings).
 *
 * Client -> Server
 *   C_HELLO  : int version, UTF name, byte vehicle  (first message, once)
 *   C_INPUT  : byte inputBits (IN_* flags)          (sent whenever keys or steering mode change)
 *   C_READY  : (no payload)                         (toggle ready in the lobby; race starts when all humans are ready)
 *
 * Server -> Client
 *   S_WELCOME: byte playerId, int sceneSeed, UTF trackId
 *   S_REJECT : UTF reason                           (connection is then closed)
 *   S_STATE  : byte phase, int timerMs, byte hostId, byte count,
 *              count x [byte id, UTF name, float x, float y, float angle,
 *                       float speed, byte lap, byte place, boolean finished,
 *                       int finishMs, boolean ready, byte nextCheckpoint,
 *                       short checkpointsPassed, boolean ai, byte vehicle, byte events]
 *              Cars are sent in race order (leader first).
 */
public final class Protocol {
    private Protocol() {}

    public static final int VERSION = 5;
    public static final int DEFAULT_PORT = 5555;
    /** Total vehicles on the grid (people + AI). 4 lanes x 4 rows. */
    public static final int MAX_CARS = 16;
    public static final int DEFAULT_AI = 3;
    public static final int LAPS = 3;

    public static final int TICK_RATE = 60;   // server physics steps per second
    public static final int SEND_EVERY = 2;   // broadcast state every N ticks (30 Hz)

    // client -> server
    public static final byte C_HELLO = 1;
    public static final byte C_INPUT = 2;
    public static final byte C_READY = 3;

    // server -> client
    public static final byte S_WELCOME = 10;
    public static final byte S_REJECT = 11;
    public static final byte S_STATE = 12;

    // input bits
    public static final int IN_UP = 1;
    public static final int IN_DOWN = 2;
    public static final int IN_LEFT = 4;
    public static final int IN_RIGHT = 8;
    /** Set = arcade steering: arrows are screen directions (up = drive up the screen). */
    public static final int IN_ARCADE = 16;
    /** Set = steering assist: when no steering key is held, the vehicle follows the curve of the road. */
    public static final int IN_ASSIST = 32;

    // vehicles (all have the same speed)
    public static final int CYCLE = 0, MOTORBIKE = 1, CAR = 2, RICKSHAW = 3;
    public static final String[] VEHICLE_NAMES = { "Cycle", "Motorbike", "Car", "Rickshaw" };

    // per-car events since the last update (for sounds)
    public static final int EV_HIT = 1;     // bumped into another vehicle
    public static final int EV_FENCE = 2;   // scraped the fence

    // game phases
    public static final byte PH_LOBBY = 0;
    public static final byte PH_COUNTDOWN = 1;
    public static final byte PH_RACING = 2;
    public static final byte PH_RESULTS = 3;
}
