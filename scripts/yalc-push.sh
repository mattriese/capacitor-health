#!/usr/bin/env bash
#
# Stamps PluginBuildInfo (Android + iOS) with the current git short hash,
# builds the plugin, and pushes it via yalc. Reverts the stamp after push
# so git stays clean.
#
# Usage: bash scripts/yalc-push.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGIN_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_BUILD_INFO="$PLUGIN_ROOT/android/src/main/java/app/capgo/plugin/health/PluginBuildInfo.kt"
IOS_BUILD_INFO="$PLUGIN_ROOT/ios/Sources/HealthPlugin/PluginBuildInfo.swift"

cd "$PLUGIN_ROOT"

# Get current git short hash
BUILD_ID=$(git rev-parse --short HEAD)
echo "==> Stamping BUILD_ID = $BUILD_ID"

# Stamp Android (Kotlin)
sed -i '' "s/const val BUILD_ID = \".*\"/const val BUILD_ID = \"$BUILD_ID\"/" "$ANDROID_BUILD_INFO"

# Stamp iOS (Swift)
sed -i '' "s/static let BUILD_ID: String = \".*\"/static let BUILD_ID: String = \"$BUILD_ID\"/" "$IOS_BUILD_INFO"

# Ensure the stamp is reverted on any exit path so the working tree stays clean.
cleanup() {
    sed -i '' "s/const val BUILD_ID = \".*\"/const val BUILD_ID = \"dev\"/" "$ANDROID_BUILD_INFO"
    sed -i '' "s/static let BUILD_ID: String = \".*\"/static let BUILD_ID: String = \"dev\"/" "$IOS_BUILD_INFO"
}
trap cleanup EXIT

if command -v java >/dev/null 2>&1; then
    echo "==> Compiling Android Kotlin..."
    cd "$PLUGIN_ROOT/android"
    ./gradlew compileDebugKotlin --quiet
    cd "$PLUGIN_ROOT"
else
    echo "==> Java runtime not found; skipping Android Kotlin compilation."
fi

echo "==> Building TypeScript..."
npm run build

echo "==> Pushing to yalc..."
npx yalc push --force

echo ""
echo "==> Done! Plugin pushed with build ID: $BUILD_ID"
echo "    Run 'npm run plugin:sync' in flow-ionic to complete the update."
