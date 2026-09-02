# Capstone Student App 🎓

A mobile app for students to view assignments, photograph their completed
worksheets, extract each answer box from the page, grade them **on the device**
with a local vision model, and upload the result.

Two Gradle modules:

- **`:app`** — screens, networking, on-device grading (LiteRT-LM).
- **`:extractor`** — ArUco registration and cropping (OpenCV). Given a photo of a
  printed worksheet and its layout, it returns one rectified PNG per answer box.

> **Full local setup lives in `HANDOFF.md` §3** — Postgres, the Node server,
> seeding an assignment, pushing the model, and getting a test image onto the
> device. This README is the short version.

## 🚀 How to Run the Project

### 1. Prerequisites
- **Android Studio** (Ladybug or newer recommended).
- **JDK 21** to run Gradle (Android Studio bundles one). The modules *compile* to
  Java 17 bytecode — that is the `sourceCompatibility`, not the JDK you need.
- **A physical arm64 Android phone**, or an **arm64 emulator image**.
  `extractor/build.gradle.kts` sets `abiFilters += "arm64-v8a"`, so the OpenCV
  native library is not in the APK on a standard x86_64 emulator and scanning
  will fail. Check with `adb shell getprop ro.product.cpu.abi`.
- **The model file.** It is not in the repo (`*.litertlm` is gitignored — the
  files are 570 MB to 1.7 GB). Obtain it separately and push it:
  ```
  adb shell mkdir -p /data/local/tmp/llm
  adb push LLaVA-OneVision-0.5B.litertlm /data/local/tmp/llm/
  ```
  See `HANDOFF.md` §3.6 for which model to use.

### 2. Setup
1. Open Android Studio.
2. Select **File > Open** and choose the `Capstone_Android` folder (the Gradle
   project is named `capstone`).
3. Wait for the green bar at the bottom to finish "Gradle Sync".

### 3. Connect to your Backend

The app reaches the server over **USB, not Wi-Fi**. `adb reverse` opens a tunnel
so that `localhost` *on the phone* resolves to `localhost` *on your laptop*:

```
adb reverse tcp:3000 tcp:3000
```

Run that once the phone is attached and the server is up. Confirm it with
`adb reverse --list`, which should print `tcp:3000 tcp:3000`.

> ⚠️ **Do not change `BASE_URL`.** It must stay `http://localhost:3000/api/`.
> It is no longer a constant in `AppContainer.kt` — it is
> `BuildConfig.BASE_URL`, set per build type in `app/build.gradle.kts`, and
> `AppContainer` just reads it. Putting your laptop's LAN IP there breaks the
> USB path and is not needed: with `adb reverse` running, `localhost` is
> already correct. There is no same-Wi-Fi requirement at all.

**The forward drops silently, and nothing warns you** — the app just fails to
connect, which looks exactly like the server being down. It is gone after
unplugging the cable, after a device reboot, and any time the adb server
restarts, which a Run or `installDebug` from Android Studio can trigger. So:
**re-run `adb reverse tcp:3000 tcp:3000` after every replug and after every
install.** It is safe to run repeatedly, and `adb reverse --list` tells you
whether you actually need to.

### 4. Click Run!
- Select your device in the top toolbar.
- Click the **Green Play Button** (Run).
- Then re-run the `adb reverse` command, because the install just cleared it.

---

## 🛠 Project Structure (Where is the code?)

**`:app`**

- **`di/AppContainer.kt`**: The "Heart" of the app. Sets up Retrofit (network),
  DataStore, the repositories, the model provider and the grader. The server URL
  comes from `BuildConfig.BASE_URL` — **do not hardcode an IP here.**
- **`data/remote/`**: `ApiService.kt` (the list of all URLs the app talks to) and
  the DTOs, including `LayoutModels.kt` — the printed-page geometry served by the
  backend.
- **`data/repository/`**: Login, registration, assignment fetch, submission
  upload and grade upload.
- **`data/local/`**: `LocalModelProvider.kt` (one LiteRT-LM engine per process),
  `LocalGradingService.kt` (prompt, retry, JSON parsing) and `ModelSpec.kt` (the
  model registry).
- **`domain/worksheet/`**: `QuestionResolver.kt` — joins each cropped answer box
  to the question it belongs to. `WorksheetSession.kt` — hands crops from the
  scan screen to the grading screen.
- **`domain/grading/`**: `WorksheetGrader.kt` grades box by box;
  `WorksheetGrade.kt` merges the per-box results into the single row the server
  stores.
- **`util/ImagePrep.kt`**: EXIF orientation, downscale, PNG encode.
- **`ui/screens/`**:
    - `LoginScreen.kt`: The login page.
    - `HomeScreen.kt`: List of pending/completed assignments.
    - `ScanScreen.kt`: Picks a photo with the **system photo picker** and runs
      extraction on it. It does **not** take the photo itself — you use the
      phone's own camera app first, because registration needs four sharp corner
      markers and the stock camera has the framing and focus aids this screen
      does not. (A CameraX `CameraPreview` composable is still in the file but
      has no caller.)
    - `WorksheetGradingScreen.kt`: Runs the model over each crop, then uploads.
    - `ModelTestScreen.kt`: **Temporary** debug harness for probing the engine.
      Not part of the student flow.
    - `SubmitScreen.kt`: **Orphaned.** The `submit/{assignmentId}` route was
      replaced by `grade/{assignmentId}`; nothing navigates here any more.
- **`MainActivity.kt`**: The "Router" that decides which screen to show first.

**`:extractor`** — `Registration.kt` (ArUco detection + homography),
`PageExtractor.kt` (the rectified crop), `LayoutValidator.kt` (refuses a layout
that cannot produce sensible crops), `Layout.kt`, `ExtractionResult.kt`.
`extractor/NOTES.md` explains what was and was not ported from the Python
original — but see `HANDOFF.md` §8: it names a `mobile_Extract/` folder that has
since been deleted.

---

## 📡 APIs Used (Backend Specs)

All eight methods declared in `ApiService.kt`, against `BASE_URL`:

| Action | Method | Path | Auth? |
| :--- | :--- | :--- | :--- |
| Login | POST | `/auth/login` | No |
| Register | POST | `/auth/register` | No |
| List Assignments | GET | `/assignments` | Yes |
| Get Assignment Detail | GET | `/assignments/:id` | Yes |
| Submit Worksheet | POST | `/submissions` | Yes (Multipart) |
| List My Submissions | GET | `/submissions/me` | Yes |
| Get One Submission | GET | `/submissions/me/:id` | Yes |
| Upload Grade | POST | `/submissions/:id/grade` | Yes |

The detail endpoint is the important one: it serves the model answer, the rubric
and the **page layout**, which is what makes on-device grading possible. Grades
are read off `/submissions/me` (each submission carries its grades) — there is no
client method for `GET /grades/me`.

---

## 🔒 Security Note
The app allows **HTTP** (cleartext) traffic via `network_security_config.xml`, so
it can talk to the development server without an SSL certificate. With
`adb reverse` that traffic never leaves the USB cable — it is not exposed on any
network — but the config is a development setting and must not ship as-is.
