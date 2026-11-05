#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "=== Building QuestRookie Android App ==="
./gradlew assembleDebug --console=plain

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "=== Launching Emulator ==="

if [ ! -f $HOME/.android/avd/test_device.avd ]; then
    echo "Creating test emulator..."
    emulator -create-test-device \
        --name "QuestRookie Test" \
        --device pixel7pro \
        --api 34 \
        --storage 2G

fi

emulator -avd test_device.avd &
sleep 60

echo "Installing app..."
adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk

echo ""
echo "=== App Installed ==="
adb shell pm list packages | grep questrookie
read -p "Press Enter to continue..."
