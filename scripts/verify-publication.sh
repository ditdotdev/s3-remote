#!/usr/bin/env bash
# Copyright Dit 2026
# SPDX-License-Identifier: BUSL-1.1

#
# Verifies that the Maven publication meets the Maven Central requirements
# (https://central.sonatype.org/publish/requirements/) without uploading
# anything: it publishes to a throwaway local repository and inspects the
# result. Run on every pull request so a build change cannot silently produce
# a publication that Central would reject at release time.
#
# Usage: scripts/verify-publication.sh <artifactId> [<artifactId>...]
#
# Signatures are checked only when a signing key is configured (the
# ORG_GRADLE_PROJECT_signingInMemoryKey environment variable); pull request
# builds have no key and publish unsigned.
#
set -euo pipefail

[[ $# -ge 1 ]] || { echo "usage: $0 <artifactId> [<artifactId>...]"; exit 2; }

GROUP_PATH="dev/dit"
VERSION="0.0.0-verify"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$ROOT/build/verify-m2"
# Gradle is a native Windows program under Git Bash and cannot resolve a
# POSIX-style /c/... path, so hand it a mixed-style (C:/...) path there.
REPO_ARG="$REPO"
if command -v cygpath >/dev/null 2>&1; then REPO_ARG="$(cygpath -m "$REPO")"; fi

rm -rf "$REPO"
(cd "$ROOT" && ./gradlew --quiet publishToMavenLocal "-Pversion=$VERSION" "-Dmaven.repo.local=$REPO_ARG")

failures=0
fail() { echo "  FAIL: $1"; failures=$((failures + 1)); }
pass() { echo "  ok:   $1"; }

for artifact in "$@"; do
  dir="$REPO/$GROUP_PATH/$artifact/$VERSION"
  base="$dir/$artifact-$VERSION"
  echo "== dev.dit:$artifact =="

  files=("$base.jar" "$base-sources.jar" "$base-javadoc.jar" "$base.pom")
  for f in "${files[@]}"; do
    if [[ -s "$f" ]]; then pass "${f##*/}"; else fail "missing ${f##*/}"; fi
  done

  pom="$base.pom"
  if [[ -f "$pom" ]]; then
    # One XPath-ish check per element Central requires in every POM.
    flat="$(tr -d '\n' < "$pom")"
    for element in name description url; do
      # Direct child of <project>: must appear before the first nested block.
      if grep -qE "^  <$element>[^<]+</$element>" "$pom"; then pass "pom <$element>"; else fail "pom <$element> missing"; fi
    done
    for block in "licenses.*<license>.*<name>[^<]+</name>.*<url>[^<]+</url>" \
                 "developers.*<developer>.*<name>[^<]+</name>" \
                 "scm.*<connection>[^<]+</connection>.*<url>[^<]+</url>"; do
      label="${block%%.*}"
      if grep -qE "<$block" <<<"$flat"; then pass "pom <$label>"; else fail "pom <$label> incomplete"; fi
    done
  fi

  if [[ -n "${ORG_GRADLE_PROJECT_signingInMemoryKey:-}" ]]; then
    for f in "${files[@]}"; do
      if [[ -s "$f.asc" ]]; then pass "${f##*/}.asc"; else fail "missing ${f##*/}.asc"; fi
    done
  else
    echo "  skip: signatures (no signing key configured)"
  fi
done

if [[ $failures -gt 0 ]]; then
  echo "Publication does not meet Maven Central requirements: $failures problem(s)."
  exit 1
fi
echo "Publication meets Maven Central requirements."
