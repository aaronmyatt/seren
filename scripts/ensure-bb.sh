#!/usr/bin/env bash

# Ensures babashka (bb) is available. Installs it locally if not found on PATH.
# Uses the official babashka install script with a project-local install dir.
# See: https://github.com/babashka/babashka#installation

set -euo pipefail

INSTALL_DIR="${BB_INSTALL_DIR:-.bb/bin}"

if command -v bb &>/dev/null; then
  echo "babashka already installed: $(bb --version)"
  exit 0
fi

# Check if we already installed locally in a previous run
if [ -x "$INSTALL_DIR/bb" ]; then
  echo "babashka found at $INSTALL_DIR/bb: $("$INSTALL_DIR/bb" --version)"
  echo "Add to PATH: export PATH=\"\$PWD/$INSTALL_DIR:\$PATH\""
  exit 0
fi

echo "babashka not found. Installing to $INSTALL_DIR ..."
mkdir -p "$INSTALL_DIR"

# Detect OS and architecture
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "$OS" in
  darwin|linux) ;;
  *) echo "Error: Unsupported OS '$OS'"; exit 1 ;;
esac

case "$ARCH" in
  x86_64|amd64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
  *) echo "Error: Unsupported architecture '$ARCH'"; exit 1 ;;
esac

# Fetch latest version from GitHub API
# See: https://docs.github.com/en/rest/releases/releases#get-the-latest-release
LATEST=$(curl -sL https://api.github.com/repos/babashka/babashka/releases/latest \
  | grep '"tag_name"' | head -1 | sed 's/.*"v\(.*\)".*/\1/')

if [ -z "$LATEST" ]; then
  echo "Error: Could not determine latest babashka version"
  exit 1
fi

echo "Downloading babashka v${LATEST} for ${OS}/${ARCH} ..."

# Construct download URL
# Release naming: babashka-<version>-<os>-<arch>.tar.gz
# See: https://github.com/babashka/babashka/releases
FILENAME="babashka-${LATEST}-${OS}-${ARCH}.tar.gz"

# Static builds use "-static" suffix on linux amd64
if [ "$OS" = "linux" ] && [ "$ARCH" = "amd64" ]; then
  FILENAME="babashka-${LATEST}-${OS}-${ARCH}-static.tar.gz"
fi

URL="https://github.com/babashka/babashka/releases/download/v${LATEST}/${FILENAME}"

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

curl -sL "$URL" -o "$TMPDIR/$FILENAME"
tar -xzf "$TMPDIR/$FILENAME" -C "$INSTALL_DIR"
chmod +x "$INSTALL_DIR/bb"

echo "babashka v${LATEST} installed to $INSTALL_DIR/bb"

# Export PATH for the current process tree so subsequent npm scripts can use bb
export PATH="$PWD/$INSTALL_DIR:$PATH"
echo "Added $INSTALL_DIR to PATH for this session."
echo ""
echo "To make it permanent, add to your shell profile:"
echo "  export PATH=\"\$PWD/$INSTALL_DIR:\$PATH\""
