# TextNow Blaster

An Android app that automates sending messages through TextNow using the Accessibility Service API.

---

## How It Works

The app uses Android's Accessibility Service to control TextNow's UI — the same mechanism used by apps like Automate or Tasker. It opens TextNow for each recipient, fills in the message, and taps Send, with a configurable delay between each.

---

## Setup Instructions

### 1. Build the App

Requirements:
- Android Studio Hedgehog (2023.1) or newer
- Android SDK 34
- JDK 17

Steps:
1. Open Android Studio → "Open an existing project" → select this folder
2. Let Gradle sync finish
3. Plug in your Android phone (USB debugging enabled)
4. Press Run (▶) or Build → Generate Signed APK

### 2. Install on Your Phone

Either run directly from Android Studio, or:
```
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Enable the Accessibility Service

This is required — without it the app cannot control TextNow's UI.

1. Open the **TextNow Blaster** app
2. Tap **"Open Accessibility Settings"** at the bottom
3. In the list, find **"TextNow Blaster Automation"** and tap it
4. Toggle it **ON** and confirm
5. Return to the app — the status at the top should turn green

### 4. Prepare Your Phone Numbers File

Create a plain `.txt` file with one phone number per line:

```
6045551234
7785559876
6045550001
```

- Numbers can include formatting (dashes, spaces, brackets) — the app strips them
- Numbers shorter than 7 digits are skipped
- Save the file to your phone's storage or Google Drive

---

## Using the App

1. **Load Numbers** — tap "Load Numbers from File" and pick your `.txt` file
2. **Type your message** in the message box
3. **Set delay** — drag the slider (5–60 seconds between each send)
4. **Tap Start Sending** — confirm the dialog
5. **Leave your phone alone** while it runs — don't interact with the screen
6. Tap **Stop** at any time to cancel

The progress bar and counter update in real time.

---

## Troubleshooting

**"Send button not found" / messages not sending**

TextNow's UI layout varies by version. The app tries multiple strategies to find UI elements (resource IDs, hint text, content descriptions). If it fails:
- Make sure TextNow is updated to the latest version
- Check Android's logcat filtered by tag `TNBlaster` for details
- Increase the delay slider — slower = more reliable

**App stops controlling TextNow after a while**

Some Android manufacturers (Samsung, Xiaomi, Huawei) aggressively kill background services. Go to:
- Settings → Battery → App → TextNow Blaster → set to "Unrestricted" / disable battery optimization

**TextNow opens but goes to wrong screen**

The `sms:` URI scheme may not be registered by TextNow on your device. The app falls back to opening TextNow's home screen. In that case you may need to manually navigate to a new message before starting — or file an issue if you'd like navigation automation added.

---

## File Structure

```
app/src/main/java/com/textnowblaster/
  MainActivity.kt                  — UI, file loading, start/stop
  TextNowAccessibilityService.kt   — Accessibility automation engine

app/src/main/res/
  layout/activity_main.xml         — Main screen layout
  xml/accessibility_service_config.xml  — Accessibility service config
  values/strings.xml, themes.xml

AndroidManifest.xml
```

---

## Notes

- This app does not send SMS directly — it automates TextNow's own UI
- Respect TextNow's Terms of Service regarding bulk messaging
- Use reasonable delays (15+ seconds recommended) to avoid triggering rate limits
- The app works entirely on-device; no data leaves your phone
