#!/bin/sh
set -eu

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

OUTPUT_DIR="${ROOT}/output"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

copy_build_artifacts() {
  echo "[INFO] copying $1/ -> output/"
  cp -R "$1/." "$OUTPUT_DIR/"
}

if [ -f package.json ]; then
  echo "[INFO] Node frontend detected"

  if [ -f package-lock.json ]; then
    npm ci
  elif [ -f yarn.lock ]; then
    yarn install --frozen-lockfile
  elif [ -f pnpm-lock.yaml ]; then
    npm install -g pnpm
    pnpm install --frozen-lockfile
  else
    npm install
  fi

  npm run build

  if [ -d dist ]; then
    copy_build_artifacts dist
  elif [ -d build ]; then
    copy_build_artifacts build
  elif [ -d out ]; then
    copy_build_artifacts out
  else
    echo "[ERROR] build succeeded but no dist/, build/, or out/ directory was found" >&2
    exit 1
  fi
else
  echo "[WARN] package.json not found — preparing empty output/ for deploy metadata"
  touch "${OUTPUT_DIR}/.keep"
fi

echo "[INFO] build complete"
find "$OUTPUT_DIR" -maxdepth 2 | sed 's/^/- /'
