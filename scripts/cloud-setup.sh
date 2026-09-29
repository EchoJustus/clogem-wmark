#!/bin/bash
# SPDX-FileCopyrightText: 2026 The clogem-wmark authors
# SPDX-License-Identifier: EPL-2.0
# Provision a Claude Code cloud environment (or any Ubuntu 24.04 box) for wmark.
# Paste into the cloud environment's "Setup script" field; it runs as root
# before the session starts and the result is cached (keep it under ~5 min).
#
# The environment also needs:
#   Network access: Custom = the Trusted defaults plus  clojars.org  repo.clojars.org
#                   (malli, http-kit and graal-build-time are published on Clojars);
#                   bb kernel-dart also fetches pub.dev packages
#   Environment variables (optional):
#     JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
set -uo pipefail

arch=$(dpkg --print-architecture)

apt_part() {
  apt-get update -qq &&
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
    openjdk-25-jdk-headless ffmpeg fonts-dejavu-core unzip >/dev/null &&
  update-alternatives --set java "/usr/lib/jvm/java-25-openjdk-${arch}/bin/java" &&
  { update-alternatives --set javac "/usr/lib/jvm/java-25-openjdk-${arch}/bin/javac" 2>/dev/null || true; }
}
bb_part() {        # Babashka: the task runner (bb test, bb lint, ...)
  curl -fsSL https://raw.githubusercontent.com/babashka/babashka/master/install | bash >/dev/null
}
clojure_part() {   # Clojure CLI, for clojure -M/-T directly (bb clojure works without it)
  curl -fsSL -o /tmp/clojure-install.sh https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh &&
  bash /tmp/clojure-install.sh >/dev/null
}

dart_part() {      # the Dart SDK that kernel/dart and CI pin, for bb kernel-dart (unpacked below)
  local v=3.13.4 d sum
  case "$arch" in
    amd64) d=x64;   sum=6487a10df5eab890d746d14a55f4c70bec3c1c0633f51804eb504cbc0fc395bb ;;
    arm64) d=arm64; sum=1d545609bdf9da6fb5e68fbd96a599e2836e44ee64379991bd4493ee764d2fdb ;;
    *) return 1 ;;
  esac
  curl -fsSL -o /tmp/dartsdk.zip \
    "https://storage.googleapis.com/dart-archive/channels/stable/release/$v/sdk/dartsdk-linux-$d-release.zip" &&
  echo "$sum  /tmp/dartsdk.zip" | sha256sum -c --quiet
}

apt_part & a=$!
bb_part & b=$!
clojure_part & c=$!
dart_part & d=$!
fail=0
wait $a || { echo "apt install failed"; fail=1; }
wait $b || { echo "babashka install failed"; fail=1; }
wait $c || { echo "clojure CLI install failed (bb clojure still works)"; }
if wait $d && rm -rf /opt/dart-sdk && unzip -q /tmp/dartsdk.zip -d /opt; then
  ln -sf /opt/dart-sdk/bin/dart /usr/local/bin/dart
else
  echo "Dart SDK install failed (only bb kernel-dart needs it)"
fi
rm -f /tmp/dartsdk.zip

# bb's built-in `clojure` (deps.clj) downloads the Clojure tools jar with its
# own trust store, which a TLS-inspecting egress proxy rejects (PKIX). Give it
# the CLI's copy of the same version, so `bb lint`/`bb test` work offline.
if command -v clojure >/dev/null; then
  v=$(clojure --version 2>/dev/null | awk '{print $NF}')
  if [ -n "$v" ] && ls /usr/local/lib/clojure/libexec/*.jar >/dev/null 2>&1; then
    mkdir -p "$HOME/.deps.clj/$v/ClojureTools" &&
    cp /usr/local/lib/clojure/libexec/*.jar "$HOME/.deps.clj/$v/ClojureTools/"
  fi
fi

java -version 2>&1 | head -1
command -v bb >/dev/null && bb --version
command -v clojure >/dev/null && clojure --version
command -v dart >/dev/null && dart --version
exit $fail
