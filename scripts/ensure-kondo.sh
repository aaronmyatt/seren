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
# clj-kondo releases use "macos" (not "darwin") and "linux".
# See: https://github.com/clj-kondo/clj-kondo/releases
RAW_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$RAW_OS" in
  darwin) OS="macos" ;;
  linux)  OS="linux" ;;
  *)      echo "Unsupported OS: $RAW_OS"; exit 1 ;;
esac

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64)        ARCH="amd64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
esac

# Fetch latest release URL from GitHub
VERSION=$(curl -sL "https://api.github.com/repos/clj-kondo/clj-kondo/releases/latest" | grep '"tag_name"' | sed -E 's/.*"v([^"]+)".*/\1/')

if [ -z "$VERSION" ]; then
  echo "Failed to detect latest clj-kondo version from GitHub API."
  exit 1
fi

URL="https://github.com/clj-kondo/clj-kondo/releases/download/v${VERSION}/clj-kondo-${VERSION}-${OS}-${ARCH}.zip"

echo "Downloading clj-kondo v${VERSION} for ${OS}-${ARCH}..."
echo "URL: $URL"
curl -fSL "$URL" -o "$INSTALL_DIR/clj-kondo.zip"

# Verify we got a real zip file (not an HTML error page)
# See: https://en.wikipedia.org/wiki/ZIP_(file_format)#Local_file_header
if ! file "$INSTALL_DIR/clj-kondo.zip" | grep -q "Zip archive"; then
  echo "Downloaded file is not a valid zip archive. Check the URL above."
  rm -f "$INSTALL_DIR/clj-kondo.zip"
  exit 1
fi

unzip -o "$INSTALL_DIR/clj-kondo.zip" -d "$INSTALL_DIR"
rm "$INSTALL_DIR/clj-kondo.zip"
chmod +x "$INSTALL_DIR/clj-kondo"

echo "clj-kondo installed to $INSTALL_DIR/clj-kondo"
echo "Add to PATH: export PATH=\"\$PWD/$INSTALL_DIR:\$PATH\""
