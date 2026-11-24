#!/usr/bin/env bash
# =============================================================================
#  Net Racer - run script (Linux)
#  Runs the compiled bytecode in ./out. If Java 17+ is missing it is installed
#  first; if the game is not compiled yet, compile.sh is run automatically.
#
#  Usage:
#    ./run.sh                          launcher window (host or join a game)
#    ./run.sh host [port] [name]       host and play straight away
#    ./run.sh join <ip> [port] [name]  join a friend's game
#    ./run.sh server [port]            dedicated server, no window
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

MIN_JAVA=17

java_major() {
    local out
    out="$("$1" -version 2>&1 | head -n1)" || { echo 0; return; }
    local v
    v="$(echo "$out" | grep -oE '[0-9]+(\.[0-9]+)*' | head -n1)"
    [[ -z "$v" ]] && { echo 0; return; }
    local major="${v%%.*}"
    [[ "$major" == "1" ]] && major="$(echo "$v" | cut -d. -f2)"
    echo "$major"
}

install_java() {
    echo ">> Java $MIN_JAVA+ not found. Installing it now (administrator password may be asked)..."
    local SUDO=""
    [[ "$(id -u)" -ne 0 ]] && SUDO="sudo"
    # Full JDK (not the "headless" runtime) so the game window and compiler both work.
    if command -v apt-get >/dev/null 2>&1; then
        $SUDO apt-get update
        $SUDO apt-get install -y openjdk-21-jdk || $SUDO apt-get install -y openjdk-17-jdk || $SUDO apt-get install -y default-jdk
    elif command -v dnf >/dev/null 2>&1; then
        $SUDO dnf install -y java-21-openjdk-devel || $SUDO dnf install -y java-17-openjdk-devel
    elif command -v yum >/dev/null 2>&1; then
        $SUDO yum install -y java-21-openjdk-devel || $SUDO yum install -y java-17-openjdk-devel
    elif command -v pacman >/dev/null 2>&1; then
        $SUDO pacman -Sy --noconfirm jdk21-openjdk || $SUDO pacman -Sy --noconfirm jdk17-openjdk
    elif command -v zypper >/dev/null 2>&1; then
        $SUDO zypper --non-interactive install java-21-openjdk-devel || $SUDO zypper --non-interactive install java-17-openjdk-devel
    elif command -v apk >/dev/null 2>&1; then
        $SUDO apk add openjdk21 || $SUDO apk add openjdk17
    else
        echo "!! Could not detect a package manager. Please install Java $MIN_JAVA or newer manually:"
        echo "   https://adoptium.net/temurin/releases/"
        exit 1
    fi
    hash -r
}

if ! command -v java >/dev/null 2>&1 || [[ "$(java_major java)" -lt "$MIN_JAVA" ]]; then
    install_java
    if ! command -v java >/dev/null 2>&1 || [[ "$(java_major java)" -lt "$MIN_JAVA" ]]; then
        echo "!! Java installation failed or an older java is still first on PATH."
        exit 1
    fi
fi

if [[ ! -f out/racer/Main.class || ! -f out/racer/maps/index.txt || src/racer -nt out/racer ]]; then
    echo ">> Game is not compiled yet - running compile.sh"
    ./compile.sh
fi

# Low-resource settings: 512 MB max heap (fine for 4 GB machines), simple single-threaded GC.
JVM_OPTS=(-Xms128m -Xmx512m -XX:+UseSerialGC)
if [[ "${1:-}" == "server" ]]; then
    JVM_OPTS=(-Xms32m -Xmx96m -XX:+UseSerialGC -Djava.awt.headless=true)
fi

exec java "${JVM_OPTS[@]}" -cp out racer.Main "$@"
