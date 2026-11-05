#!/bin/bash
set -e

echo "=== Building QuestRookie Android App ==="
cd "$(dirname "$0")"

# Build in debug mode for emulator
./gradlew assembleDebug --console=plain

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "=== Launching Emulator ==="

# Create and start emulator if it doesn't exist (one-time setup)
if [ ! -f $HOME/.android/avd/test_device.avd ]; then
    echo "Creating test emulator..."
    emulator -create-test-device \
        --name "QuestRookie Test" \
        --device pixel7pro \
        --api 34 \
        --storage 2G

fi

# Start emulator in background (suppress output until ready)
emulator -avd test_device.avd &
EMULATOR_PID=$!

echo "Waiting for emulator to boot..."
sleep 60

# Install the app on the running emulator
adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk

if [ $? -ne 0 ]; then
    echo "Install failed! Checking device..."
    adb devices
    exit 1
fi

echo ""
echo "=== App Installed Successfully ==="
adb shell pm list packages | grep questrookie

# Keep terminal open to see app in action
echo ""
echo "App is now running. Open the QuestRookie app on your emulator."
read -p "Press Enter to close this window..."