# QuestRookie

A sleek sideloading browser and library manager for Meta Quest devices. Connect to your device via Wi-Fi ADB or USB, browse game catalogs, download APKs with a single tap, and manage your installed apps — all in one polished interface designed entirely for Quest.

## Features

- **Sleek & Functional UI** – Designed from the ground up for VR/Quest devices, not ported from mobile.
- **Multi-source Catalogs** – Browse curated game lists from SideQuest, F-Droid, GitHub Apps, and custom mirror servers (Rookie protocol).
- **Wi-Fi ADB & USB Debugging** – Connect to your Quest device wirelessly or via USB with automatic reconnection support.
- **Download Manager** – Streamlined one-tap APK downloads with progress tracking.
- **Library Management** – View installed apps, uninstall, and manage sideloaded content directly from the app.
- **Custom Sources** – Add any Rookie-compatible mirror server as a custom source.

## Getting Started

### Building

1. Clone the repository:
   ```bash
   git clone https://github.com/EillotBappo/QuestRookie.git
   cd QuestRookie
   ```

2. Open in Android Studio and build.

3. Or use the included script:
   ```bash
   ./build_questrookie.sh
   ```

### Running on Quest

Connect your Quest device via Wi-Fi ADB or USB, then run/install the APK. The app will auto-detect connected devices and allow sideloading directly from the UI.

## Project Structure

- `app/` – Main Android application module
- `docs/` – Documentation and assets
- `apk/` – Built APK outputs

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

## License

[Add your license here]
