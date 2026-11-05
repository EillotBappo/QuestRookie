#!/bin/bash
set -e

echo "🔨 Building QuestRookie Android App..."

# Clean previous builds
./gradlew clean || true

# Release build: not debuggable, so Compose runs compiled (debug builds stall the UI). Signed with the debug key.
./gradlew assembleRelease --console plain 2>&1 | tee build.log

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Build successful!"
    echo "📦 APK location: app/build/outputs/apk/release/app-release.apk"
    
    # Also show file info
    if [ -f "./app/build/outputs/apk/release/app-release.apk" ]; then
        ls -lh ./app/build/outputs/apk/release/app-release.apk
    fi
else
    echo ""
    echo "❌ Build failed. Check build.log for details."
    tail -100 build.log
    exit 1
fi
