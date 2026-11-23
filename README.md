# Net Racer

Top-down multiplayer racing game in plain Java (no external libraries).
The circuit runs around **Dhanmondi Lake, Dhaka**: the lake's real shape comes from
OpenStreetMap (© OpenStreetMap contributors, ODbL), with the race track along its shore.
Up to **4 players** race over **TCP**; runs on low-end PCs (software 2D rendering, 256 MB heap).

## Quick start
| | Linux | Windows |
|---|---|---|
| Compile | `./compile.sh` | (done automatically by `run.bat`) |
| Play | `./run.sh` | double-click `run.bat` |

Both scripts install Java (JDK 17+) automatically if it is missing.

One player chooses **Host**; the others choose **Join** and type the host's IP
(shown in the lobby). The host presses **Enter** to start a 3-lap race.

Command-line shortcuts (same for `run.bat`):
```
./run.sh host [port] [name]
./run.sh join <ip> [port] [name]
./run.sh server [port]        # dedicated server, no window
```
Default port: **5555/TCP** — allow it through the host's firewall.

## Controls
| Key | Action |
|---|---|
| Arrows / WASD | Drive. **Arcade steering** (default): each arrow is a screen direction, so ↑ drives up the screen and ← drives left (diagonals work too). |
| C | Switch to **Classic steering**: ↑ throttle, ↓ brake/reverse, ←/→ turn relative to the car |
| Enter | Start the race (host only) |
| Esc | Quit |

The lake is a wall; grass slows you down. Pass every checkpoint in order to count a lap.

## Code
- `src/racer/Protocol.java` – message types and constants
- `src/racer/GameServer.java` – authoritative server: physics, laps, ranking (60 Hz, broadcasts at 30 Hz)
- `src/racer/GameClient.java` – Swing renderer + input, interpolates server snapshots
- `src/racer/Track.java` – shared track geometry, checkpoints, lake collision
- `src/racer/LakeData.java` – generated Dhanmondi Lake outline + racing line (world pixels, 2 px = 1 m)
- `src/racer/Main.java` – launcher (host / join / server)
