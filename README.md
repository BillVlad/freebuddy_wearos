# FreeBuds WearOS Controller

A lightweight, native, and highly optimized WearOS application for controlling noise cancellation (ANC) and monitoring battery life on **HUAWEI FreeBuds 5i** directly from your smart watch.

This project is a standalone port inspired by the reverse-engineered Huawei Bluetooth Classic protocol.

This project is a written with AI (specially Gemini)... Ya, imma dumb for coding for Android (I will try to learn Android development, so...)

---

## 🌟 Features

- **Bidirectional State Sync:** Displays real-time battery status (Left, Right, Case) and actively synchronizes the ANC mode button highlight with the physical touch controls on your earbuds.
- **Physical Bezel Scrolling:** Smoothly scroll through the interface using the rotating physical bezel or digital touch ring (fully hardware-accelerated, zero-lag dispatching).
- **Compact WearOS Tile (Widget):** Quickly switch ANC modes in one tap directly from the watch home screen. Executed asynchronously in the background using extremely efficient `LoadAction` without blinking or launching any foreground activities.
- **Battery & Performance Optimized:** Implements buffered SPP (Serial Port Profile) socket streams and lightweight reactive state mutations to minimize CPU and battery usage on weak dual-core smartwatch processors.
- **Pure Dark Theme:** Pure black background tailored for OLED displays to maximize battery life.

---

## 📚 Credits & Attributions

This project wouldn't be possible without the incredible reverse-engineering and development efforts of the open-source community:

- **Original Repository & Protocol Reverse Engineering:** [TheLastGimbus/freebuddy](https://github.com/TheLastGimbus/FreeBuddy)  — the foundational project that decoded the proprietary Huawei Bluetooth Classic protocol.
- **FreeBuds 5i Reference Fork:** [wtshelyx/freebuddy_5i](https://github.com/wtshelyx/freebuddy_5i) — used as a codebase reference to adapt the protocol commands and parameters specifically for the FreeBuds 5i.

---

## 🛠️ How to Install & Run

1. Pair your **HUAWEI FreeBuds 5i** in the system Bluetooth settings of your WearOS watch.
2. Clone this repository and open it in **Android Studio**.
3. Connect your watch to your PC via **ADB over Wi-Fi** (Wireless Debugging).
4. Switch the **Build Variant** in Android Studio to `release` (the project is pre-configured to sign release builds using the auto-generated debug key for frictionless local testing with full R8 compiler optimizations).
5. Press **Run** (green triangle) to install and launch.

---

## 📝 License

This project is open-source and distributed under the MIT License.
