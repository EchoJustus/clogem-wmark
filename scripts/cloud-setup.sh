#!/bin/bash
# SPDX-FileCopyrightText: 2026 The clogem-wmark authors
# SPDX-License-Identifier: EPL-2.0
# Provision a Claude Code cloud environment (or any Ubuntu 24.04 box) for wmark.
# Paste into the cloud environment's "Setup script" field; it runs as root
# before the session starts and the result is cached (keep it under ~5 min).
#
# The environment also needs:
#   Network access: Custom = the Trusted defaults plus  clojars.org  repo.clojars.org
#                   (malli, http-kit and graal-build-time are published on Clojars)
#   Environment variables (optional):
#     JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
set -uo pipefail

arch=$(dpkg --print-architecture)

apt_part() {
  apt-get update -qq &&
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
    openjdk-25-jdk-headless ffmpeg fonts-dejavu-core >/dev/null &&
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

apt_part & a=$!
bb_part & b=$!
clojure_part & c=$!
fail=0
wait $a || { echo "apt install failed"; fail=1; }
wait $b || { echo "babashka install failed"; fail=1; }
wait $c || { echo "clojure CLI install failed (bb clojure still works)"; }

java -version 2>&1 | head -1
command -v bb >/dev/null && bb --version
command -v clojure >/dev/null && clojure --version
exit $fail
