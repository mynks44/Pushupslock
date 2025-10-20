# PushUpLock (Android App)

PushUpLock is a **self-discipline utility** that locks distracting apps (like Instagram) and forces you to **do real physical push-ups** to earn screen time.

---

## ✨ Features

✅ **App Locking with Physical Challenge**
- Select any installed app (Instagram, YouTube, etc.)
- When opened, it **blocks access with an overlay** until you *complete a push-up* in front of the camera.

✅ **Pose Detection using ML Kit**
- Uses **CameraX + Google ML Kit Pose Detection** to count push-up reps.

✅ **Time-Based Unlocks**
- Each successful push-up **grants 10 minutes** of usage time (configurable).
- A **foreground countdown** runs while the user is inside the app.
- When time runs out → **App locks again automatically**.

✅ **Accessibility-Based App Monitoring**
- The app runs silently using an **Accessibility Service** to detect when a locked app is opened.

---

## 🚀 How It Works

| Action | Result |
|--------|--------|
| User opens locked app (e.g., Instagram) | Overlay appears: *"Do one push-up to unlock."* |
| User clicks **Start Push-Ups** | Camera opens and counts a push-up via pose detection |
| Push-up successful | App grants +10 minutes and unblocks the app |
| Timer expires | Overlay returns and blocks again |

---

## 🛠️ Setup & Requirements

| Requirement | Details |
|-------------|---------|
| Minimum Android | **Android 8.0 (API 26)** |
| Camera Permission | Required for pose detection |
| Overlay Permission | Required to block apps |
| Accessibility Service | Required to detect foreground app |

---

## 📦 Tech Stack

| Component | Library |
|-----------|----------------------|
| Pose Detection | Google ML Kit: `com.google.mlkit:pose-detection` |
| Camera | CameraX |
| UI | XML + Compose (optional) |
| Storage | SharedPreferences (Gson) |
| Background Logic | Foreground Service + Accessibility Service |

---

## ✅ Current TODOs / Future Enhancements

- [ ] Add settings for **custom push-ups per minute** ratio
- [ ] Allow **multiple push-up reps** before unlock
- [ ] Add **UI stats dashboard** (total push-ups done)
- [ ] Add **streak system / gamification**
- [ ] Add **vibration + sound feedback** on unlock

---

## 📸 Demo (coming soon)

> GIF or screenshots to be added later.

---

## ✍️ Author

Built with pain and discipline by **[YOUR NAME]**

---

