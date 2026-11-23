#!/usr/bin/env bash
# =============================================================================
#  Net Racer - compile script (Linux)
#  Compiles src/racer/*.java into Java bytecode in ./out
#  If a Java compiler (JDK 17 or newer) is missing, it is installed first.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

MIN_JAVA=17

# Prints the major version of a java/javac binary (e.g. 21), or 0 if absent.
java_major() {
    local out
    out="$("$1" -version 2>&1 | head -n1)" || { echo 0; return; }
    # handles "javac 21.0.2", "openjdk version \"17.0.9\"", "java version \"1.8.0\""
    local v
    v="$(echo "$out" | grep -oE '[0-9]+(\.[0-9]+)*' | head -n1)"
    [[ -z "$v" ]] && { echo 0; return; }
    local major="${v%%.*}"
    [[ "$major" == "1" ]] && major="$(echo "$v" | cut -d. -f2)"
    echo "$major"
}

install_jdk() {
    echo ">> JDK $MIN_JAVA+ not found. Installing it now (administrator password may be asked)..."
    local SUDO=""
    [[ "$(id -u)" -ne 0 ]] && SUDO="sudo"

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
        echo "!! Could not detect a package manager. Please install JDK $MIN_JAVA or newer manually:"
        echo "   https://adoptium.net/temurin/releases/"
        exit 1
    fi
    hash -r
}

if ! command -v javac >/dev/null 2>&1 || [[ "$(java_major javac)" -lt "$MIN_JAVA" ]]; then
    install_jdk
    if ! command -v javac >/dev/null 2>&1 || [[ "$(java_major javac)" -lt "$MIN_JAVA" ]]; then
        echo "!! JDK installation failed or an older javac is still first on PATH."
        exit 1
    fi
fi

echo ">> Using $(javac -version 2>&1)"
rm -rf out
mkdir -p out
javac --release "$MIN_JAVA" -encoding UTF-8 -d out src/racer/*.java
echo ">> Compiled successfully into ./out"
echo ">> Start the game with: ./run.sh"
