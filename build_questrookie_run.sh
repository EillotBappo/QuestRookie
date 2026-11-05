#!/bin/bash
set -e

echo "🚀 Running QuestRookie on Android Emulator..."

# First, build if needed
if [ ! -f "./app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo "Building first..."
    ./gradlew assembleDebug --console plain > build.log 2>&1
    
    if [ $? -ne 0 ]; then
        echo "❌ Build failed. Check build.log for details."
        tail -50 build.log
        exit 1
    fi
fi

# Find the emulator or create one
if command -v emulator &> /dev/null; then
    AVD_NAME="QuestRookie_AVD"
    
    # List available AVDs and try to use an existing one, or create a new one
    if avdmanager list avd 2>/dev/null | grep -q "$AVD_NAME"; then
        echo "Using existing AVD: $AVD_NAME"
        emulator -avd "$AVD_NAME" -no-audio -no-window &
    else
        echo "📱 Creating new Android Virtual Device..."
        
        # Create a simple AVD configuration file
        mkdir -p ~/.android/avds
        
        cat > ~/.android/avds/$AVD_NAME.avd/config.ini << 'AVDEOF'
[hw]
battery=on

[skin]
path=/sdcard/skins/nexus-6-portrait-v32.png
type=nexus-6-portrait-v32

[boot]
target=29

[emu]
kernel=system.img
ram=4096

[paths]
data=/sdcard/data
AVDEOF
        
        # Start emulator with our custom config
        emulator -no-audio -no-window -no-boot-anim -sdk 29 &
    fi
    
    # Wait for emulator to start and initialize ADB
    echo "⏳ Waiting for emulator to be ready..."
    sleep 15
    
    # Check if adb is working with the emulator
    if ! adb devices | grep -q device; then
        echo "❌ Emulator failed to connect via ADB"
        exit 1
    fi
    
    # Get the emulator's device ID
    DEVICE_ID=$(adb devices | grep 'device' | head -1 | cut -f1)
    
    if [ -z "$DEVICE_ID" ]; then
        echo "❌ Could not find connected device"
        exit 1
    fi
    
    echo "📱 Connected to: $DEVICE_ID"
    
    # Clear existing apps with the same package name
    adb -s "$DEVICE_ID" uninstall com.questrookie || true
    
    # Install the APK
    echo "⬇️ Installing QuestRookie..."
    adb -s "$DEVICE_ID" install -r ./app/build/outputs/apk/debug/app-debug.apk
    
    if [ $? -ne 0 ]; then
        echo "❌ Failed to install APK"
        exit 1
    fi
    
    # Verify installation
    INSTALLED=$(adb -s "$DEVICE_ID" shell pm list packages | grep com.questrookie)
    if [ -z "$INSTALLED" ]; then
        echo "❌ Package not found after installation"
        exit 1
    fi
    
    echo ""
    echo "✅ QuestRookie installed successfully!"
    echo "📍 Package: $INSTALLED"
    
    # Try to launch the app
    echo "🎮 Launching QuestRookie..."
    adb -s "$DEVICE_ID" shell am start-foreground-activity --nostop com.questrookie.MainActivity
    
    # Keep the script running so we can see what happens in the emulator
    echo ""
    echo "✅ App launched! The emulator window should be opening automatically."
    echo "Press Ctrl+C to exit..."
    
    # Monitor for a few seconds to show app is running
    sleep 5
    
    adb -s "$DEVICE_ID" shell dumpsys activity activities | grep com.questrookie || true
    
else
    echo "Error: 'emulator' not found in PATH. Install Android SDK command line tools."
fi
