#!/usr/bin/env bash
# Cut an Android release: bump the version, build the signed APK, tag vX.Y.Z,
# push, and publish a GitHub Release with the APK attached.
#   scripts/release.sh 1.1.0 ["Optional release notes"]     (PRERELEASE=1 marks it as a pre-release)
# Needs: the release key (~/.config/tally/release.jks, TALLY_* in ~/.gradle/gradle.properties) and `gh` logged in.
set -euo pipefail
VERSION="${1:?usage: scripts/release.sh X.Y.Z [notes]}"
NOTES="${2:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must look like 1.2.3"; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
GRADLE_FILE=android/app/build.gradle.kts
TAG="v$VERSION"

[[ -z "$(git status --porcelain)" ]] || { echo "commit or stash your changes first"; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "$TAG already exists"; exit 1; }
grep -q TALLY_STORE_FILE ~/.gradle/gradle.properties || { echo "release key not configured (see CLAUDE.md)"; exit 1; }

# versionCode must go up with every release, or phones refuse the update
CODE=$(( $(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE") + 1 ))
sed -i "s/versionCode = [0-9]*/versionCode = $CODE/; s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" "$GRADLE_FILE"
echo "Building $VERSION (versionCode $CODE)…"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
( cd android && ./gradlew --console=plain assembleRelease )
APK_SRC=android/app/build/outputs/apk/release/app-release.apk
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner"
"$APKSIGNER" verify --print-certs "$APK_SRC" | grep -q "CN=Tally" || { echo "APK is not signed with the release key"; git checkout "$GRADLE_FILE"; exit 1; }
OUT="android/app/build/Tally-$VERSION.apk"
cp "$APK_SRC" "$OUT"

git add "$GRADLE_FILE"
git commit -q -m "Release the Android app $VERSION" -m "versionCode $CODE."
git tag -a "$TAG" -m "Tally $VERSION"
git push -q origin HEAD "$TAG"

BODY="${NOTES:+$NOTES

}**Install:** download \`Tally-$VERSION.apk\` below on your phone and open it (allow installing from this source once). It updates over the previous version and keeps your data. Android 10 or newer."
gh release create "$TAG" "$OUT" --title "Tally $VERSION" --notes "$BODY" $([[ -n "${PRERELEASE:-}" ]] && echo --prerelease || echo --latest)
echo "Published: $(gh release view "$TAG" --json url -q .url)"
