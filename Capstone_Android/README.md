# Capstone Student App 🎓

This is a mobile app for students to view assignments, scan their completed worksheets using the phone camera, and submit them for grading.

## 🚀 How to Run the Project

### 1. Prerequisites
- **Android Studio** (Ladybug or newer recommended).
- **Physical Android Phone** or **Emulator**.
- **Java 17** (comes with Android Studio).

### 2. Setup
1. Open Android Studio.
2. Select **File > Open** and choose the `capstone` folder.
3. Wait for the green bar at the bottom to finish "Gradle Sync".

### 3. Connect to your Backend
If you are running the backend on your laptop and the app on a **real phone**:
1. Find your laptop's IP address (e.g., `192.168.1.5`).
2. Open `app/src/main/java/com/example/capstone/di/AppContainer.kt`.
3. Change `BASE_URL` to: `http://YOUR_IP:3000/api/`.
4. Ensure both your phone and laptop are on the **same Wi-Fi**.

### 4. Click Run!
- Select your device in the top toolbar.
- Click the **Green Play Button** (Run).

---

## 🛠 Project Structure (Where is the code?)

- **`di/AppContainer.kt`**: The "Heart" of the app. It sets up Retrofit (network), the Database, and the Repositories. Update your **Server IP** here.
- **`data/remote/`**: Contains `ApiService.kt` (the list of all URLs the app talks to) and Models (what the data looks like).
- **`data/repository/`**: Contains the logic for login, registration, and uploading files.
- **`ui/screens/`**: This is where the screens live:
    - `LoginScreen.kt`: The login page.
    - `ScanScreen.kt`: The **Camera code** for taking photos.
    - `SubmitScreen.kt`: Shows the photo and handles the upload button.
    - `HomeScreen.kt`: List of pending/completed assignments.
- **`MainActivity.kt`**: The "Router" that decides which screen to show first.

---

## 📡 APIs Used (Backend Specs)

| Action | Method | Path | Auth? |
| :--- | :--- | :--- | :--- |
| Login | POST | `/auth/login` | No |
| Register | POST | `/auth/register` | No |
| List Assignments | GET | `/assignments` | Yes |
| Get Assignment Detail | GET | `/assignments/:id` | Yes |
| Submit Worksheet | POST | `/submissions` | Yes (Multipart) |
| View Grades | GET | `/submissions/me` | Yes |

---

## 🔒 Security Note
The app is configured to allow **HTTP** (cleartext) traffic via `network_security_config.xml` so you can test on your local network without needing expensive SSL certificates.
