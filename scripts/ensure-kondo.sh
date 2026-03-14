#!/usr/bin/env bash

# Ensures clj-kondo (Clojure linter) is available. Installs locally if not found.
# clj-kondo catches unbalanced parentheses, arity errors, unused vars, and more
# — all without running the code.
#
# See: https://github.com/clj-kondo/clj-kondo
# See: https://github.com/clj-kondo/clj-kondo/blob/master/doc/install.md

set -euo pipefail

INSTALL_DIR="${KONDO_INSTALL_DIR:-.clj-kondo-bin}"

if command -v clj-kondo &>/dev/null; then
  echo "clj-kondo already installed: $(clj-kondo --version 2>&1)"
  exit 0
fi

if [ -x "$INSTALL_DIR/clj-kondo" ]; then
  echo "clj-kondo found at $INSTALL_DIR/clj-kondo"
  echo "Add to PATH: export PATH=\"\$PWD/$INSTALL_DIR:\$PATH\""
  exit 0
fi

echo "clj-kondo not found. Installing to $INSTALL_DIR ..."

mkdir -p "$INSTALL_DIR"

# Detect platform
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64)  ARCH="amd64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
esac

# Fetch latest release URL from GitHub
VERSION=$(curl -sL "https://api.github.com/repos/clj-kondo/clj-kondo/releases/latest" | grep '"tag_name"' | sed -E 's/.*"v([^"]+)".*/\1/')
URL="https://github.com/clj-kondo/clj-kondo/releases/download/v${VERSION}/clj-kondo-${VERSION}-${OS}-${ARCH}.zip"

echo "Downloading clj-kondo v${VERSION} for ${OS}-${ARCH}..."
curl -sL "$URL" -o "$INSTALL_DIR/clj-kondo.zip"
unzip -o "$INSTALL_DIR/clj-kondo.zip" -d "$INSTALL_DIR"
rm "$INSTALL_DIR/clj-kondo.zip"
chmod +x "$INSTALL_DIR/clj-kondo"

echo "clj-kondo installed to $INSTALL_DIR/clj-kondo"
echo "Add to PATH: export PATH=\"\$PWD/$INSTALL_DIR:\$PATH\""
