# Bangladesh City Racing

Top-down multiplayer racing game in plain Java (no external libraries).
Race around famous places in all eight divisions of Bangladesh. Every track is built from real
OpenStreetMap data (© OpenStreetMap contributors, ODbL): the landmark, the bridges, the streets and the buildings around it.

| Track | City | Inside the circuit |
|---|---|---|
| Dhanmondi Lake | Dhaka | lake with 10 real bridges |
| Hatirjheel | Dhaka | lake with bridges |
| Sher-e-Bangla Stadium | Mirpur, Dhaka | cricket stadium packed with fans |
| Foy's Lake | Chattogram | lake with many arms |
| Tilagarh Eco Park | Sylhet | forest |
| University of Rajshahi | Rajshahi | campus |
| Khulna University | Khulna | campus with KU Lake |
| Durga Sagar | Barishal | big dighi with an island |
| Chiklir Beel | Rangpur | long lake |
| BAU Botanical Garden | Mymensingh | garden |

- Online multiplayer over **TCP** – up to **16 racers** (people + AI drivers)
- Choose your vehicle: **cycle, motorbike, car or rickshaw** (all have the same speed)
- Laps run **anti-clockwise**; the fenced road has **4 lanes** and **narrows to 2 lanes** in three places
- Walkway with people walking, grandstands with cheering fans, food carts, speed boats
- Stereo sound **generated in real time** (no sound files): engines that rise with speed through the gears,
  pedal sounds, horns and bells, crashes, and surroundings that change with where you are –
  water by the lake, traffic and horns near the city, birds near the trees, the crowd roaring from the grandstands
- Runs on low-end PCs: software 2D rendering, 512 MB memory cap

## Quick start
| | Linux | Windows |
|---|---|---|
| Compile | `./compile.sh` | (done automatically by `run.bat`) |
| Play | `./run.sh` | double-click `run.bat` |

Both scripts install Java (JDK 17+) automatically if it is missing.

Everybody connects to the **same Wi-Fi**. In the start window pick your **vehicle**, then:
- **HOST A GAME** – choose the track and the number of AI drivers.
- **JOIN A GAME** – the games hosted on your Wi-Fi appear in the **Game** list by themselves (no IP address to type).
  If a network blocks this (some office / campus Wi-Fi), choose *"Type the host's IP address myself..."*.

On Windows, when the firewall asks about Java, click **Allow** (private networks) so friends can see and join your game.

## How a race works
1. **Lobby** – free practice. Everyone presses **Enter** when ready; the race starts when all players are ready.
   Vehicles are placed on the 4-lane grid in random order.
2. **Race** – be the first to drive **3 laps**. Follow the **yellow arrow** and drive through the **glowing gate**;
   the bar at the top shows everyone's progress to the finish flag. "WRONG WAY!" flashes if you drive backwards.
3. **Results** – winner screen, then back to the lobby (others get 30 s to finish after the winner).

Command-line shortcuts (same for `run.bat`):
```
./run.sh tracks                                         # list the track names
./run.sh host [port] [name] [aiCars] [track] [vehicle]  # e.g. ./run.sh host 5555 Maruf 5 hatirjheel rickshaw
./run.sh join <ip> [port] [name] [vehicle]
./run.sh server [port] [aiCars] [track]                 # dedicated server, no window
```
Ports: the game uses **TCP 5555** (or the next free port) and **UDP 5550** to find games on the Wi-Fi.

## Controls
| Key | Action |
|---|---|
| ↑ / W | Drive forward |
| ↓ / S | Brake / reverse – **brake before bends**: at high speed you can't turn sharply, and hitting the fence slows you down a lot |
| ← → / A D | Steer the vehicle left / right (a light steering help points you along the road) |
| C | Change view: **Map view** (still map, default) → **Turning camera** (forward is always up) → **Arrow keys** (each arrow is a screen direction) |
| H | Horn (car, motorbike) or bell (rickshaw, cycle) |
| Enter | Ready / not ready (lobby) |
| M | Sound on / off |
| Esc | Quit |

## Code
- `src/racer/Protocol.java` – message types and constants
- `src/racer/GameServer.java` – authoritative server: physics, fences, AI drivers, laps, ranking (60 Hz, broadcasts at 30 Hz)
- `src/racer/GameClient.java` – game screen: vehicles, HUD, input, snapshot interpolation, sound triggers
- `src/racer/Scenery.java` – map rendering (city, landmark, road, stands) and animated life (walkers, fans, speed boats)
- `src/racer/SoundEngine.java` – real-time synthesized vehicle, crowd and effect audio
- `src/racer/Launcher.java` – start window (vehicle, track, Wi-Fi and game list)
- `src/racer/Lan.java` – finds games on the same Wi-Fi (UDP broadcast) and reads the Wi-Fi name
- `src/racer/Track.java` – shared track geometry: race line, road width, checkpoints, starting grid
- `src/racer/MapData.java` – loads a track file; `src/racer/maps/*.map` are the generated tracks

## Adding or rebuilding tracks
Tracks are listed in `tools/maps.json` (id, name, city, type and the OpenStreetMap object the circuit goes around).
```
pip install shapely numpy
python3 tools/make_maps.py fetch      # download OpenStreetMap data (cached in tools/osm/)
python3 tools/make_maps.py build      # write src/racer/maps/*.map
./compile.sh
```
