# QuestRookie 🥕

**A free, open-source storefront and manager for Meta Quest headsets.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

QuestRookie is a companion app that lets you browse, install, manage, and launch games and apps on your Meta Quest (1/2/3/Pro) — with or without Wi-Fi Calling. It aggregates titles from SideQuest, OculusDB, and any custom mirror sources you add, so you can discover and sideload everything in one place.

---

## ✨ Features

- **Game Store** — Browse hundreds of VR games and apps across multiple built-in catalogs (SideQuest, OculusDB) and user-configured mirrors (vrhub, etc.).
- **Multi-source aggregation** — Add extra mirror sources with a single URL or JSON config. All sources appear side-by-side; if one fails the others still load.
- **Smart library & search** — Filter by installed / updates / favourites, sort by name, popularity, rating, size, last played, etc.
- **One-tap install & update** — Install, reinstall, or update any title from within the app. Bulk-update all pending updates at once.
- **Offline mode** — Works without Wi-Fi Calling; your cached game list stays usable until you refresh.
- **Wireless ADB** — Connect to your Quest over Wi‑Fi directly from the app (no PC needed).
- **Package installer** — Install APKs, OBBs, and split packages via USB or wireless.
- **App management** — Launch, uninstall, cache-clear, force-stop, and view permissions/data for any sideloaded package.
- **Tweak & tweaklet support** — Apply system tweaks (e.g. 90 Hz on Quest 2) and per-app tweaks at install time.
- **Shortcuts & home screen widgets** — One-tap shortcuts to launch games or open specific screens from your Quest's home launcher.
- **Catalogs & mirrors** — Built-in catalogs auto-update; custom mirror sources let you point at your own vrhub-server, VRStore server, or any compatible index.
- **Favourites & adult filtering** — Mark titles as favourites and optionally hide adult content.
- **Screenshots & trailers** — View screenshots from OculusDB data; embedded YouTube trailers play directly inside the game detail screen.
- **Dark theme / Material You** — Polished Material 3 UI with adaptive theming, shimmer loading cards, and focus-friendly navigation (great for keyboard/D-pad).

---

## 🚀 Getting Started

### 1. Install QuestRookie on your Quest

Download the latest APK from the [Releases page](https://github.com/ethancloete/QuestRookie/releases). Sideload it onto your headset via SideQuest, `adb install`, or by side-loading directly in the app.

> **Tip:** If you're new to sideloading, use SideQuest's "Side-load App" feature or run `adb install questrookie.apk` from a computer connected to your Quest over USB.

### 2. Connect to your headset

- **Over USB:** Plug in via USB-C. The app auto-detects the device.
- **Over Wi‑Fi (Wireless ADB):** Enable Developer Mode on your Quest, enable Wireless ADB, then tap "Connect" in QuestRookie's connection screen and follow the pairing flow. No PC needed.

### 3. Download a game list

On first launch you'll see an empty store. Tap **Download game list** to fetch titles from SideQuest, OculusDB, and any configured mirror sources. This may take a minute on slow connections.

Once loaded, browse categories or use search to find what you want.

### 4. Install a game

1. Tap a game card to open its detail page.
2. Press **Install**. The download queue handles the rest — APK + OBBs are fetched and installed automatically.
3. Return to the Store or Library at any time; queued jobs continue in the background.

### 5. Manage your apps

Open the **Library** tab to see all sideloaded titles. You can:
- Launch, uninstall, cache-clear, or force-stop any app.
- View package details (permissions, data usage).
- Apply tweaks at install time for compatible packages.

---

## 🔌 Adding Custom Mirror Sources

QuestRookie supports custom mirror sources beyond the built-in catalogs. To add one:

1. Open the Store → tap **⋮** → **Sources**.
2. Click **Add a source**.
3. Enter:
   - **Name:** A label for your source (e.g., `MyServer`).
   - **Config URL or JSON:** Points to either:
     - A vrhub-server config at `http://<host>:39457/config.json`
     - A VRStore-compatible index-v1.json link
   - **API Key** (optional): If your source requires authentication.

Once added, games from that source appear alongside the built-in catalogs. Sources are independent — if one fails to load, the others still display normally.

---

## 🛠️ Developer Mode & Wireless ADB on Quest

To connect wirelessly:

1. Open **Settings** → **Devices** → **Meta Quest**.
2. Enable **Developer Mode** (you may need to create a developer account at [developer.oculus.com](https://developer.oculus.com)).
3. Go back and enable **Wireless ADB**.
4. In QuestRookie, go to the connection screen and tap **Connect over Wi‑Fi**. Follow the on-screen pairing instructions.

---

## 📁 Data Storage

QuestRookie stores its game lists, cache, and configuration in:
/storage/emulated/0/Android/data/com.questrookie/files/

- `games.json` — Cached game list (JSON)
- `cache/` — Downloaded thumbnails & screenshots
- `tweaks/` — Per-app tweak configurations

You can back up this directory to preserve your library between installs.

---

## ⚙️ Configuration Files

### Config JSON (`config.json`)
Used when adding a custom mirror source:
```json
{
  "name": "My Mirror",
  "base_url": "http://192.168.1.50:39457/",
  "api_key": "",
  "mirrors": ["http://mirror1.example.com/"]
}
Index JSON (index-v1.json)
Used for VRStore-compatible sources:

json
{
  "packages": [
    {
      "name": "GameName",
      "version": "1.0.0",
      "url": "http://example.com/game.apk"
    }
  ]
}
```
🔄 Updates
QuestRookie checks for updates on the OculusDB catalog and displays an Update badge next to any installed game that has a newer version available. You can:

Update individual games from their detail page.
Tap Update all in the Store header to update every pending title at once.
📝 Permissions & Runtime Requests
QuestRookie requests these permissions at runtime (where applicable):

POST_NOTIFICATIONS — Show install progress / completion notifications.
READ_PHONE_STATE — Detect headset USB/Wi-Fi connection state.
INTERNET — Download game lists, assets, and packages.

Key Components
Component	Purpose
AppState	Central state holder — games list, queue, favourites, current device
Catalogs.kt	Defines built-in catalogs (SideQuest, OculusDB) with JSON parsing
StoreLogic.kt	Core game data model (Game, Details) + filtering/sorting logic
Installer.kt	Multi-stage install: download APK → push to device → install OBBs → launch
Queue.kt	Background job queue for parallel downloads and installs
Tweaks.kt	Applies system tweaks (90Hz on Quest 2, etc.) per-package at install time
🛠️ Building from Source
Prerequisites
Android Studio (latest stable)
JDK 17+
Gradle (bundled with Android Studio)
Steps
bash
# Clone the repository
git clone https://github.com/ethancloete/QuestRookie.git
cd QuestRookie

# Open in Android Studio and build
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK (needs signing config)
The generated APK will be at: app/build/outputs/apk/debug/app-debug.apk

📦 Dependencies
QuestRookie uses these major libraries:

Library	Purpose
Jetpack Compose	Declarative UI framework
Material 3 (MaterialComponents)	Theming, components, design tokens
Kotlin Coroutines	Asynchronous operations
OkHttp / Kotlinx Serialization	HTTP requests & JSON parsing
Activity Compose	Activity lifecycle integration
See app/build.gradle.kts for the full dependency list.

🤝 Contributing
Contributions are welcome! Here's how:

Fork this repository and create a feature branch (git checkout -b feature/my-feature).
Commit your changes with clear messages.
Pull request against the main branch.
Code Style
Use Kotlin idioms and follow Kotlin coding conventions.
Compose functions should be small, focused, and testable.
Add comments for complex logic; inline docs for public APIs.
📄 License
QuestRookie is released under the GNU General Public License v3.0. See LICENSE for details.

Copyright (C) 2025 Ethan Cloete & Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
🙏 Acknowledgements
SideQuest — Game catalog source
OculusDB — Store data (descriptions, screenshots, ratings)
VRStore — Mirror source compatibility
vrhub — Mirror source compatibility
📞 Support & Community
GitHub Issues: Report bugs or request features
Discord / Forum: Join the community for help, tips, and discussions. (Link coming soon)
Made with ❤️ for the VR sideloading community.
