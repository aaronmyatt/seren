#!/usr/bin/env bash

# Ensures the Clojure CLI (clojure/clj) is available. Installs locally if not found.
# Requires Java (JDK 8+) as a prerequisite.
# Uses the official Clojure posix installer with a local --prefix.
# See: https://clojure.org/guides/install_clojure

set -euo pipefail

INSTALL_DIR="${CLJ_INSTALL_DIR:-.clojure-tools}"

if command -v clojure &>/dev/null; then
  echo "Clojure CLI already installed: $(clojure --version 2>&1 | head -1)"
  exit 0
fi

# Check if we already installed locally in a previous run
if [ -x "$INSTALL_DIR/bin/clojure" ]; then
  echo "Clojure CLI found at $INSTALL_DIR/bin/clojure"
  echo "Add to PATH: export PATH=\"\$PWD/$INSTALL_DIR/bin:\$PATH\""
  exit 0
fi

# Java is required — check before attempting install
# See: https://clojure.org/guides/install_clojure#_prerequisites
if ! command -v java &>/dev/null; then
  echo "Error: Java (JDK 8+) is required but not found on PATH."
  echo ""
  echo "Install Java first, then re-run this script:"
  echo "  macOS:    brew install openjdk"
  echo "  Ubuntu:   sudo apt install default-jdk"
  echo "  Fedora:   sudo dnf install java-21-openjdk"
  echo "  Generic:  https://adoptium.net/temurin/releases/"
  exit 1
fi

echo "Clojure CLI not found. Installing to $INSTALL_DIR ..."
mkdir -p "$INSTALL_DIR"

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

# Download the official Clojure posix installer
# This works on both macOS and Linux.
# See: https://github.com/clojure/brew-install/releases
echo "Downloading Clojure CLI installer..."
curl -sL https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh \
  -o "$TMPDIR/install.sh"
chmod +x "$TMPDIR/install.sh"

# Install to local prefix — no sudo required
# Creates: <prefix>/bin/clojure, <prefix>/bin/clj, <prefix>/lib/clojure/
"$TMPDIR/install.sh" --prefix "$PWD/$INSTALL_DIR"

echo "Clojure CLI installed to $INSTALL_DIR/"

# Export PATH for the current process tree
export PATH="$PWD/$INSTALL_DIR/bin:$PATH"
echo "Added $INSTALL_DIR/bin to PATH for this session."
echo ""
echo "To make it permanent, add to your shell profile:"
echo "  export PATH=\"\$PWD/$INSTALL_DIR/bin:\$PATH\""
