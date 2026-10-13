# Product Requirements Document (PRD): Aplikasi Android Keymapper untuk PXN V9 Gen 2 (Target: Game BUSSID)

## 1. Ringkasan Proyek
Saya membutuhkan aplikasi Android (Keymapper/Touch Emulator) yang memungkinkan penggunaan *steering wheel* PXN V9 Gen 2 untuk bermain game Bus Simulator Indonesia (BUSSID). Aplikasi ini harus bisa menerjemahkan input *hardware* fisik (USB/Bluetooth HID) menjadi simulasi sentuhan (*touch events*) di layar (Overlay) secara real-time, tanpa delay.

## 2. Analisis Layout Layar (Berdasarkan Referensi Layar BUSSID)
Aplikasi harus menyediakan antarmuka *overlay* untuk menempatkan *widget mapping* di atas layout game berikut:
*   **Kiri Bawah:** Area kemudi virtual (Virtual Steering Wheel).
*   **Kanan Bawah:** Pedal Gas dan Rem.
*   **Kanan Tengah:** UI H-Shifter (Gigi R, 1, 2, 3, 4, 5, 6).
*   **Kiri & Atas:** Tombol aksi (Lampu, sein, klakson 1/2/3, pintu, wiper, kamera, dll).

## 3. Fitur Utama & Spesifikasi Teknis (Core Mechanics)

### A. Konfigurasi Input "Slider" untuk Steering Wheel (Setir)
*   **Mekanisme:** Game BUSSID menggunakan rotasi kemudi virtual. Aplikasi harus membaca input *Axis* dari setir PXN (nilai minimum ke maksimum, misal -1.0 hingga 1.0) dan menerjemahkannya menggunakan widget **"Slider/Rotational Swipe"**.
*   **Aksi Layar:** Saat setir fisik diputar ke kiri, aplikasi melakukan simulasi sentuhan dan geseran (*drag*) ke arah kiri pada area setir virtual di layar, begitu juga sebaliknya. Sensitivitas (Deadzone dan Linearity) harus bisa diatur.

### B. Konfigurasi Input "Slider / Node Dragging" untuk H-Shifter
*   **Mekanisme:** UI Shifter pada BUSSID mengharuskan pemain menggeser *node* (titik tengah) ke slot gigi (R, 1, 2, 3, 4, 5, 6). 
*   **Aksi Layar:** Shifter pada PXN V9 dideteksi sebagai tombol (Button) diskrit untuk masing-masing posisi gigi. Aplikasi harus memiliki widget **"H-Shifter Macro"** atau mekanisme *Slider* posisi.
*   **Logika Translasi:** Ketika fisik shifter masuk ke "Gigi 1", aplikasi melakukan simulasi *touch down* di titik tengah shifter layar, lalu secara instan melakukan *drag/slide* ke koordinat posisi "Gigi 1" di layar dan *touch up*. Jika dikembalikan ke netral, *drag* kembali ke tengah.

### C. Konfigurasi Pedal (Gas, Rem, Kopling)
*   **Mekanisme:** Pedal mengirimkan data *Axis* (seperti trigger). 
*   **Aksi Layar:** Menggunakan widget **"Continuous Touch"**. Saat pedal ditekan melewatin *threshold* tertentu, aplikasi melakukan simulasi *hold touch* (tekan tahan) pada area pedal gas/rem di layar kanan bawah. Dilepas = *touch up*.

### D. Konfigurasi Tombol Standar (Tap)
*   Mapping 1-to-1 dari tombol di roda kemudi PXN (A, B, X, Y, L, R, D-pad) menjadi sentuhan tunggal (*Single Tap*) pada ikon di layar (sein, lampu, pintu, klakson).

## 4. Persyaratan Sistem & Aksesibilitas
*   **Input Parsing:** Aplikasi harus bisa membaca Raw HID Data (InputDevice API Android) dari PXN V9 Gen 2 (baik via OTG maupun Bluetooth).
*   **Touch Injection:** Karena aplikasi harus menyentuh layar di luar aplikasinya sendiri (di atas game BUSSID), aplikasi HARUS mengimplementasikan **Android Accessibility Service API** (untuk non-root gesture injection) atau **InputManager/Uinput** (jika menargetkan device Root). Preferensi utama adalah tanpa root (Accessibility).
*   **Overlay UI:** Menggunakan `SYSTEM_ALERT_WINDOW` (Display over other apps) untuk menampilkan antarmuka pengaturan *mapping* saat game berjalan.

## 5. User Interface (UI) Aplikasi
*   **Menu Utama:** Status koneksi PXN V9, daftar profil game (Profile: BUSSID).
*   **Mode Edit (Overlay):** Munculkan *floating button*. Jika diklik, layar menjadi transparan. Pengguna bisa menambahkan widget (Tap, Slider Setir, Slider Shifter, Pedal) dan menariknya (*drag & drop*) ke posisi UI game.
*   **Kalibrasi:** Menu untuk mengatur *Deadzone* setir dan titik tengah/ujung dari slider Shifter.

## 6. Deliverables yang Diharapkan
Tolong buatkan arsitektur kode awal untuk aplikasi Android ini menggunakan Kotlin, dengan fokus pada:
1.  Implementasi `AccessibilityService` untuk *dispatch gesture* (simulasi sentuhan *slider* dan *tap*).
2.  Cara menangkap *InputDevice Event* dari gamepad/steering wheel di *background/overlay* Android.