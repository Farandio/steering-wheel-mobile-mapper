"""
BUSSID Full Steering, Pedals & H-Shifter Mouse-Drag Macro Mapper
Author: Antigravity

FITUR SPESIAL H-SHIFTER MOUSE DRAG UNTUK BUSSID:
1. Logika Translasi H-Shifter Fisik (PXN V9 / Logitech / Thrustmaster Shifter):
   - Di shifter fisik, posisi gigi (R, 1, 2, 3, 4, 5, 6) dideteksi sebagai tombol (discrete buttons).
   - Saat Anda menggeser shifter fisik ke "Gigi 1", script secara kilat (instant stroke):
     * Memindahkan mouse ke posisi knob kuning (Netral).
     * Melakukan `mouseDown()`.
     * Melakukan `drag` instan ke slot target "Gigi 1" di layar BUSSID.
     * Melakukan `mouseUp()`.
   - Saat shifter fisik dipindahkan ke posisi Netral (atau gigi dilepas):
     * Knob ditarik kembali dari slot gigi ke garis tengah (Netral).
2. Auto-Detect Tombol Shifter Fisik:
   - Di tab "🕹️ H-Shifter BUSSID", Anda cukup klik [ 🎯 Deteksi Gigi ] lalu masukkan tuas shifter ke Gigi 1/2/3/dst untuk mapping instan!
3. Pengatur Posisi Panel Shifter:
   - Disediakan tombol panah geser [ ⬆️ ⬇️ ⬅️ ➡️ ] dan tombol [ 🧪 TEST DRAG SHIFTER ] untuk memastikan knob kuning langsung berpindah ke gigi yang dipilih!
"""

import sys
import os
import json
import time
import math
import threading
import ctypes
from ctypes import wintypes
import tkinter as tk
from tkinter import ttk, messagebox
import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.001

user32 = ctypes.windll.user32
winmm = ctypes.windll.winmm

def inject_hardware_key(vk_code, scan_code, is_down):
    flags = 0 if is_down else 0x0002
    user32.keybd_event(vk_code, scan_code, flags, 0)

# Joystick WinMM
class JOYINFOEX(ctypes.Structure):
    _fields_ = [
        ('dwSize', wintypes.DWORD),
        ('dwFlags', wintypes.DWORD),
        ('dwXpos', wintypes.DWORD),
        ('dwYpos', wintypes.DWORD),
        ('dwZpos', wintypes.DWORD),
        ('dwRpos', wintypes.DWORD),
        ('dwUpos', wintypes.DWORD),
        ('dwVpos', wintypes.DWORD),
        ('dwButtons', wintypes.DWORD),
        ('dwButtonNumber', wintypes.DWORD),
        ('dwPOV', wintypes.DWORD),
        ('dwReserved1', wintypes.DWORD),
        ('dwReserved2', wintypes.DWORD)
    ]

class JOYCAPSW(ctypes.Structure):
    _fields_ = [
        ('wMid', wintypes.WORD),
        ('wPid', wintypes.WORD),
        ('szPname', wintypes.WCHAR * 32),
        ('wXmin', wintypes.UINT),
        ('wXmax', wintypes.UINT),
        ('wYmin', wintypes.UINT),
        ('wYmax', wintypes.UINT),
        ('wZmin', wintypes.UINT),
        ('wZmax', wintypes.UINT),
        ('wNumButtons', wintypes.UINT),
        ('wPeriodMin', wintypes.UINT),
        ('wPeriodMax', wintypes.UINT),
        ('wRmin', wintypes.UINT),
        ('wRmax', wintypes.UINT),
        ('wUmin', wintypes.UINT),
        ('wUmax', wintypes.UINT),
        ('wVmin', wintypes.UINT),
        ('wVmax', wintypes.UINT),
        ('wCaps', wintypes.UINT),
        ('wMaxAxes', wintypes.UINT),
        ('wNumAxes', wintypes.UINT),
        ('wMaxButtons', wintypes.UINT),
        ('szRegKey', wintypes.WCHAR * 32),
        ('szOEMVxD', wintypes.WCHAR * 260)
    ]

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config_mapping.json")
AXIS_LIST = ["X", "Y", "Z", "R", "U", "V"]

class FullBussidWheelShifterApp:
    def __init__(self, root):
        self.root = root
        self.root.title("🏎️ BUSSID Wheel, Pedals & H-Shifter Drag Mapper")
        self.root.geometry("880x760")
        self.root.configure(bg="#151520")

        self.connected_device_id = None
        self.running = True
        self.active_buttons = set()
        self.prev_buttons = set()
        self.axis_values = {a: 32767 for a in AXIS_LIST}
        self.raw_axis_baselines = {a: 32767 for a in AXIS_LIST}
        self.pedal_pressed_states = {"gas": False, "brake": False, "clutch": False}
        self.detecting_pedal_type = None

        # Posisi Setir BUSSID
        self.steer_config = {
            "center_x": 220,
            "center_y": 800,
            "radius": 135,
            "max_angle": 360,
        }

        # Posisi & Layout H-Shifter BUSSID (Berdasarkan Screenshot 1920x1080)
        # Panel Shifter: Pusat netral di sekitar X=1765, Y=660
        self.shifter_config = {
            "center_x": 1765,     # Posisi Netral tengah
            "center_y": 660,
            "y_top": 615,        # Posisi baris atas (Gigi 1, 3, 5)
            "y_bottom": 710,     # Posisi baris bawah (Gigi R, 2, 4, 6)
            "x_col_R": 1580,     # Kolom R
            "x_col_1_2": 1675,   # Kolom 1 & 2
            "x_col_3_4": 1765,   # Kolom 3 & 4
            "x_col_5_6": 1855,   # Kolom 5 & 6
            # Tombol fisik setir yang terhubung ke masing-masing gigi:
            "slots": {
                "gear_1": {"btn": "12", "name": "Gigi 1"},
                "gear_2": {"btn": "13", "name": "Gigi 2"},
                "gear_3": {"btn": "14", "name": "Gigi 3"},
                "gear_4": {"btn": "15", "name": "Gigi 4"},
                "gear_5": {"btn": "16", "name": "Gigi 5"},
                "gear_6": {"btn": "17", "name": "Gigi 6"},
                "gear_r": {"btn": "18", "name": "Gigi R (Mundur)"}
            }
        }

        self.calib = {
            "steer": {"axis": "X", "center": 32767, "min": 0, "max": 65535, "deadzone": 900},
            "gas": {"axis": "Y", "rest": 65535, "pressed": 0, "threshold": 0.15, "key": "w"},
            "brake": {"axis": "R", "rest": 65535, "pressed": 0, "threshold": 0.15, "key": "s"},
            "clutch": {"axis": "Z", "rest": 65535, "pressed": 0, "threshold": 0.15, "key": "shift"}
        }

        self.mappings = {
            "btn_0": {"name": "Klakson", "type": "key", "target": "h"},
            "btn_1": {"name": "Lampu Utama", "type": "key", "target": "l"},
            "btn_2": {"name": "Hazard", "type": "key", "target": "j"},
            "btn_3": {"name": "Wiper", "type": "key", "target": "p"},
            "btn_4": {"name": "Handbrake", "type": "key", "target": "space"},
        }

        self.is_mouse_down = False
        self.current_gear = "N"
        self.shifter_lock = threading.Lock()
        self.is_shifting_active = False

        self.load_config()
        self.create_ui()

        # Polling worker
        self.poll_thread = threading.Thread(target=self.poll_controller_loop, daemon=True)
        self.poll_thread.start()

        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r") as f:
                    data = json.load(f)
                    if "calib" in data:
                        for k, v in data["calib"].items():
                            if k in self.calib:
                                self.calib[k].update(v)
                    if "steer_config" in data:
                        self.steer_config.update(data["steer_config"])
                    if "shifter_config" in data:
                        self.shifter_config.update(data["shifter_config"])
                    if "mappings" in data:
                        self.mappings = data["mappings"]
            except Exception as e:
                print(f"Error load config: {e}")

    def save_config(self):
        for p_id in ["gas", "brake", "clutch"]:
            if hasattr(self, 'pedal_widgets') and p_id in self.pedal_widgets:
                self.calib[p_id]["key"] = self.pedal_widgets[p_id]["ent_key"].get().strip().lower()

        if hasattr(self, 'scale_radius'):
            self.steer_config["radius"] = int(self.scale_radius.get())
            self.calib["steer"]["deadzone"] = int(self.scale_deadzone.get())

        data = {
            "calib": self.calib,
            "steer_config": self.steer_config,
            "shifter_config": self.shifter_config,
            "mappings": self.mappings
        }
        try:
            with open(CONFIG_FILE, "w") as f:
                json.dump(data, f, indent=4)
            messagebox.showinfo("Sukses", "Semua pengaturan (Setir, Pedal, dan H-Shifter) berhasil disimpan!")
        except Exception as e:
            messagebox.showerror("Error", f"Gagal simpan: {e}")

    def create_ui(self):
        top_bar = tk.Frame(self.root, bg="#20202e", padx=12, pady=10)
        top_bar.pack(fill=tk.X)

        tk.Label(top_bar, text="🕹️ BUSSID STEERING & H-SHIFTER DRAG MAPPER", 
                 font=("Segoe UI", 12, "bold"), fg="#06d6a0", bg="#20202e").pack(side=tk.LEFT)

        tk.Button(top_bar, text="🔄 Scan Devices", command=self.scan_devices,
                  bg="#4f5d75", fg="white", relief=tk.FLAT, padx=8).pack(side=tk.RIGHT, padx=5)

        # Device selection
        dev_row = tk.Frame(self.root, bg="#151520", padx=15, pady=6)
        dev_row.pack(fill=tk.X)

        tk.Label(dev_row, text="Steering Wheel & Shifter:", font=("Segoe UI", 10), fg="#a0c4ff", bg="#151520").pack(side=tk.LEFT)
        self.combo_devices = ttk.Combobox(dev_row, state="readonly", width=42)
        self.combo_devices.pack(side=tk.LEFT, padx=10)
        self.combo_devices.bind("<<ComboboxSelected>>", self.on_device_selected)

        self.lbl_status = tk.Label(dev_row, text="Mencari...", font=("Segoe UI", 10, "bold"), fg="#f77f00", bg="#151520")
        self.lbl_status.pack(side=tk.LEFT, padx=5)

        # Tabs
        nb = ttk.Notebook(self.root)
        nb.pack(fill=tk.BOTH, expand=True, padx=12, pady=6)

        # Tab 1: H-SHIFTER BUSSID (FITUR UTAMA BARU)
        self.tab_shifter = tk.Frame(nb, bg="#1e1e2d", padx=12, pady=10)
        nb.add(self.tab_shifter, text="🕹️ H-Shifter BUSSID (Drag Knob)")
        self.create_shifter_tab()

        # Tab 2: Setir & Posisi
        self.tab_steer = tk.Frame(nb, bg="#1e1e2d", padx=12, pady=10)
        nb.add(self.tab_steer, text="🎯 Setir (Kemudi)")
        self.create_steer_tab()

        # Tab 3: Pedal Gas & Rem
        self.tab_pedals = tk.Frame(nb, bg="#1e1e2d", padx=12, pady=10)
        nb.add(self.tab_pedals, text="🚦 Gas, Rem & Kopling")
        self.create_pedals_tab()

        # Tab 4: Tombol Fisik
        self.tab_buttons = tk.Frame(nb, bg="#1e1e2d", padx=12, pady=10)
        nb.add(self.tab_buttons, text="🎮 Tombol Fisik")
        self.create_buttons_tab()

        # Bottom Bar
        bot_bar = tk.Frame(self.root, bg="#151520", padx=15, pady=8)
        bot_bar.pack(fill=tk.X)

        self.btn_minimize_play = tk.Button(bot_bar, text="🎮 MINIMIZE KE BACKGROUND (UNTUK MAIN DI GAME)", 
                                           font=("Segoe UI", 11, "bold"), bg="#06d6a0", fg="#111", 
                                           relief=tk.FLAT, padx=16, pady=8, command=self.minimize_to_background)
        self.btn_minimize_play.pack(side=tk.LEFT)

        self.btn_save = tk.Button(bot_bar, text="💾 Simpan Pengaturan", 
                                  font=("Segoe UI", 11, "bold"), bg="#3a86ff", fg="white", 
                                  relief=tk.FLAT, padx=14, pady=8, command=self.save_config)
        self.btn_save.pack(side=tk.RIGHT)

        self.scan_devices()

    # ================= TAB H-SHIFTER =================
    def create_shifter_tab(self):
        desc = tk.Label(self.tab_shifter, 
                        text="📌 LOGIKA H-SHIFTER DRAG MOUSE BUSSID:\n"
                             "• Setiap kali tuas shifter fisik dimasukkan ke Gigi (R, 1, 2, 3, 4, 5, 6), mouse otomatis melakukan\n"
                             "  klik & drag pada knob kuning ke slot gigi tersebut di layar game BUSSID!\n"
                             "• Saat tuas dinetralkan, knob ditarik kembali ke posisi tengah (Netral).",
                        font=("Segoe UI", 9, "bold"), fg="#06d6a0", bg="#1e1e2d", justify=tk.LEFT)
        desc.pack(anchor=tk.W, pady=(0, 4))

        # Status Posisi Gigi Saat Ini
        status_box = tk.Frame(self.tab_shifter, bg="#151520", padx=10, pady=5)
        status_box.pack(fill=tk.X, pady=3)

        tk.Label(status_box, text="Posisi Gigi Aktif Saat Ini:", font=("Segoe UI", 10, "bold"), fg="#edf2f4", bg="#151520").pack(side=tk.LEFT)
        self.lbl_current_gear = tk.Label(status_box, text="[ NETRAL - N ]", font=("Segoe UI", 11, "bold"), fg="#fca311", bg="#151520")
        self.lbl_current_gear.pack(side=tk.LEFT, padx=10)

        # Table Mapping Gigi ke Tombol Shifter Fisik
        map_box = tk.LabelFrame(self.tab_shifter, text="Pengaturan Tombol Fisik untuk Tiap Gigi", 
                                font=("Segoe UI", 9, "bold"), fg="#fca311", bg="#1e1e2d", padx=8, pady=4)
        map_box.pack(fill=tk.BOTH, expand=True, pady=4)

        columns = ("gear_id", "gear_name", "btn_num", "target_coords")
        self.tree_shifter = ttk.Treeview(map_box, columns=columns, show="headings", height=7)
        self.tree_shifter.heading("gear_id", text="Slot Gigi")
        self.tree_shifter.heading("gear_name", text="Nama Gigi")
        self.tree_shifter.heading("btn_num", text="Tombol Shifter Fisik")
        self.tree_shifter.heading("target_coords", text="Koordinat Layar (X, Y)")

        self.tree_shifter.column("gear_id", width=80)
        self.tree_shifter.column("gear_name", width=140)
        self.tree_shifter.column("btn_num", width=140)
        self.tree_shifter.column("target_coords", width=150)
        self.tree_shifter.pack(fill=tk.BOTH, expand=True, pady=2)

        # Form Edit / Detect Tombol Shifter
        form_row = tk.Frame(map_box, bg="#1e1e2d")
        form_row.pack(fill=tk.X, pady=3)

        tk.Label(form_row, text="Pilih Gigi:", fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT)
        self.combo_gear_select = ttk.Combobox(form_row, values=["gear_1", "gear_2", "gear_3", "gear_4", "gear_5", "gear_6", "gear_r"], width=8, state="readonly")
        self.combo_gear_select.current(0)
        self.combo_gear_select.pack(side=tk.LEFT, padx=4)

        tk.Label(form_row, text="Tombol Shifter:", fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT, padx=(6, 2))
        self.ent_gear_btn = tk.Entry(form_row, width=6)
        self.ent_gear_btn.insert(0, "12")
        self.ent_gear_btn.pack(side=tk.LEFT, padx=3)

        tk.Button(form_row, text="🎯 Deteksi Gigi", command=self.detect_gear_button, 
                  bg="#fb5607", fg="white", relief=tk.FLAT, padx=6).pack(side=tk.LEFT, padx=4)

        tk.Button(form_row, text="💾 Pasang ke Gigi", command=self.apply_gear_mapping, 
                  bg="#06d6a0", fg="#111", font=("Segoe UI", 9, "bold"), relief=tk.FLAT, padx=8).pack(side=tk.LEFT, padx=4)

        # Tombol Uji Nyata Drag Shifter
        tk.Button(form_row, text="🧪 TEST DRAG GIGI INI", command=self.test_drag_selected_gear, 
                  bg="#3a86ff", fg="white", font=("Segoe UI", 9, "bold"), relief=tk.FLAT, padx=8).pack(side=tk.LEFT, padx=8)

        # Visual Shifter Overlay yang dapat di-drag langsung dengan mouse
        pos_box = tk.LabelFrame(self.tab_shifter, text="🎮 Posisikan Shifter BUSSID dengan Drag Mouse di Layar", 
                                font=("Segoe UI", 9, "bold"), fg="#fca311", bg="#1e1e2d", padx=8, pady=6)
        pos_box.pack(fill=tk.X, pady=4)

        info_lbl = tk.Label(pos_box, text="💡 Klik tombol 'Buka Kotak Pengatur Shifter' di bawah ini.\n"
                                          "Akan muncul jendela overlay transparan dengan pola H-Shifter (R, 1, 2, 3, 4, 5, 6).\n"
                                          "Klik & TAHAN DRAG kotak tersebut tepat di atas H-Shifter BUSSID di layar game Anda!",
                            font=("Segoe UI", 9), fg="#06d6a0", bg="#1e1e2d", justify=tk.LEFT)
        info_lbl.pack(anchor=tk.W, pady=(0, 4))

        p_row = tk.Frame(pos_box, bg="#1e1e2d")
        p_row.pack(fill=tk.X, pady=2)

        self.lbl_shifter_coords = tk.Label(p_row, text=f"Pusat Netral: X={self.shifter_config['center_x']} px, Y={self.shifter_config['center_y']} px", 
                                           font=("Consolas", 10, "bold"), fg="#00FF66", bg="#151520", padx=10, pady=4)
        self.lbl_shifter_coords.pack(side=tk.LEFT)

        self.btn_toggle_shifter_overlay = tk.Button(p_row, text="🪟 Buka Kotak Pengatur Shifter (Drag di Layar)", 
                                                    bg="#fb5607", fg="white", font=("Segoe UI", 9, "bold"), 
                                                    relief=tk.FLAT, padx=10, pady=4, command=self.toggle_shifter_overlay)
        self.btn_toggle_shifter_overlay.pack(side=tk.LEFT, padx=8)

        tk.Button(p_row, text="RESET POSISI", bg="#4f5d75", fg="white", relief=tk.FLAT, padx=8, pady=4, 
                  command=self.reset_shifter_pos).pack(side=tk.LEFT, padx=4)

        self.shifter_overlay_win = None
        self.refresh_shifter_table()

    def toggle_shifter_overlay(self):
        if self.shifter_overlay_win and tk.Toplevel.winfo_exists(self.shifter_overlay_win):
            self.shifter_overlay_win.destroy()
            self.shifter_overlay_win = None
            self.btn_toggle_shifter_overlay.config(text="🪟 Buka Kotak Pengatur Shifter (Drag di Layar)", bg="#fb5607")
            return

        self.btn_toggle_shifter_overlay.config(text="❌ Tutup Kotak Pengatur Shifter", bg="#ef233c")

        # Ukuran window overlay shifter: lebar 340, tinggi 160
        # Posisi dihitung dari center_x, center_y
        w = 340
        h = 160
        left = max(0, self.shifter_config["center_x"] - w // 2)
        top = max(0, self.shifter_config["center_y"] - h // 2)

        ow = tk.Toplevel(self.root)
        self.shifter_overlay_win = ow
        ow.overrideredirect(True)
        ow.attributes("-topmost", True)
        ow.geometry(f"{w}x{h}+{left}+{top}")
        ow.configure(bg="#2b2d42")

        # Canvas untuk visualisasi H-Pattern Shifter
        cv = tk.Canvas(ow, width=w, height=h, bg="#1a1a24", highlightthickness=2, highlightbackground="#00FF66")
        cv.pack(fill=tk.BOTH, expand=True)

        # Draw H-Pattern Shifter
        # Center Netral rel: (w//2, h//2) -> (170, 80)
        cx, cy = w // 2, h // 2
        col_r_rel = cx - (self.shifter_config["center_x"] - self.shifter_config["x_col_R"])
        col_12_rel = cx - (self.shifter_config["center_x"] - self.shifter_config["x_col_1_2"])
        col_34_rel = cx
        col_56_rel = cx + (self.shifter_config["x_col_5_6"] - self.shifter_config["center_x"])
        y_top_rel = cy - (self.shifter_config["center_y"] - self.shifter_config["y_top"])
        y_bot_rel = cy + (self.shifter_config["y_bottom"] - self.shifter_config["center_y"])

        # Title bar di dalam canvas
        cv.create_rectangle(0, 0, w, 24, fill="#06d6a0", outline="")
        cv.create_text(w // 2, 12, text="[TAHAN & GESER/DRAG KOTAK INI KE SHIFTER BUSSID]", fill="#111", font=("Segoe UI", 8, "bold"))

        # Garis H horizontal Netral
        cv.create_line(col_r_rel, cy, col_56_rel, cy, fill="#fca311", width=4)
        # Garis Kolom R (ke bawah)
        cv.create_line(col_r_rel, cy, col_r_rel, y_bot_rel, fill="#fca311", width=4)
        # Garis Kolom 1-2
        cv.create_line(col_12_rel, y_top_rel, col_12_rel, y_bot_rel, fill="#fca311", width=4)
        # Garis Kolom 3-4
        cv.create_line(col_34_rel, y_top_rel, col_34_rel, y_bot_rel, fill="#fca311", width=4)
        # Garis Kolom 5-6
        cv.create_line(col_56_rel, y_top_rel, col_56_rel, y_bot_rel, fill="#fca311", width=4)

        # Bulatan slot gigi
        slots_rel = [
            (col_r_rel, y_bot_rel, "R", "#ef233c"),
            (col_12_rel, y_top_rel, "1", "#3a86ff"),
            (col_12_rel, y_bot_rel, "2", "#3a86ff"),
            (col_34_rel, y_top_rel, "3", "#3a86ff"),
            (col_34_rel, y_bot_rel, "4", "#3a86ff"),
            (col_56_rel, y_top_rel, "5", "#3a86ff"),
            (col_56_rel, y_bot_rel, "6", "#3a86ff"),
            (cx, cy, "N (Kuning)", "#ffbe0b")
        ]
        for sx, sy, txt, clr in slots_rel:
            rad = 12 if "N" not in txt else 14
            cv.create_oval(sx - rad, sy - rad, sx + rad, sy + rad, fill=clr, outline="white", width=1)
            cv.create_text(sx, sy, text=txt[0], fill="white" if "N" not in txt else "black", font=("Segoe UI", 9, "bold"))

        # Binding Drag Window
        self._drag_data = {"x": 0, "y": 0}

        def on_press(event):
            self._drag_data["x"] = event.x
            self._drag_data["y"] = event.y

        def on_drag(event):
            dx = event.x - self._drag_data["x"]
            dy = event.y - self._drag_data["y"]
            nx = ow.winfo_x() + dx
            ny = ow.winfo_y() + dy
            ow.geometry(f"+{nx}+{ny}")

            # Update koordinat shifter center
            new_cx = nx + w // 2
            new_cy = ny + h // 2
            shift_dx = new_cx - self.shifter_config["center_x"]
            shift_dy = new_cy - self.shifter_config["center_y"]

            self.shifter_config["center_x"] = new_cx
            self.shifter_config["center_y"] = new_cy
            self.shifter_config["y_top"] += shift_dy
            self.shifter_config["y_bottom"] += shift_dy
            self.shifter_config["x_col_R"] += shift_dx
            self.shifter_config["x_col_1_2"] += shift_dx
            self.shifter_config["x_col_3_4"] += shift_dx
            self.shifter_config["x_col_5_6"] += shift_dx

            self.lbl_shifter_coords.config(text=f"Pusat Netral: X={new_cx} px, Y={new_cy} px")
            self.refresh_shifter_table()

        cv.bind("<ButtonPress-1>", on_press)
        cv.bind("<B1-Motion>", on_drag)

    def get_gear_coordinates(self, gear_key):
        sc = self.shifter_config
        cols = {
            "gear_r": (sc["x_col_R"], sc["y_bottom"]),
            "gear_1": (sc["x_col_1_2"], sc["y_top"]),
            "gear_2": (sc["x_col_1_2"], sc["y_bottom"]),
            "gear_3": (sc["x_col_3_4"], sc["y_top"]),
            "gear_4": (sc["x_col_3_4"], sc["y_bottom"]),
            "gear_5": (sc["x_col_5_6"], sc["y_top"]),
            "gear_6": (sc["x_col_5_6"], sc["y_bottom"]),
            "N": (sc["center_x"], sc["center_y"])
        }
        return cols.get(gear_key, (sc["center_x"], sc["center_y"]))

    def refresh_shifter_table(self):
        for item in self.tree_shifter.get_children():
            self.tree_shifter.delete(item)
        for g_id, data in self.shifter_config["slots"].items():
            coords = self.get_gear_coordinates(g_id)
            self.tree_shifter.insert("", tk.END, iid=g_id, values=(
                g_id,
                data.get("name", ""),
                f"Tombol {data.get('btn', '')}",
                f"({coords[0]}, {coords[1]})"
            ))

    def detect_gear_button(self):
        if not self.active_buttons:
            messagebox.showinfo("Deteksi", "Masukkan tuas shifter fisik ke gigi yang diinginkan.")
            return
        btn = list(self.active_buttons)[0]
        self.ent_gear_btn.delete(0, tk.END)
        self.ent_gear_btn.insert(0, str(btn))

    def apply_gear_mapping(self):
        g_id = self.combo_gear_select.get()
        btn_val = self.ent_gear_btn.get().strip()
        if g_id in self.shifter_config["slots"]:
            self.shifter_config["slots"][g_id]["btn"] = btn_val
            self.refresh_shifter_table()
            messagebox.showinfo("Sukses", f"{self.shifter_config['slots'][g_id]['name']} dipasangkan ke Tombol {btn_val}!")

    def test_drag_selected_gear(self):
        g_id = self.combo_gear_select.get()
        threading.Thread(target=lambda: self.execute_shifter_drag(g_id), daemon=True).start()

    def reset_shifter_pos(self):
        self.shifter_config["center_x"] = 1765
        self.shifter_config["center_y"] = 660
        self.shifter_config["y_top"] = 615
        self.shifter_config["y_bottom"] = 710
        self.shifter_config["x_col_R"] = 1580
        self.shifter_config["x_col_1_2"] = 1675
        self.shifter_config["x_col_3_4"] = 1765
        self.shifter_config["x_col_5_6"] = 1855
        self.lbl_shifter_coords.config(text=f"Pusat Netral: X=1765 px, Y=660 px")
        if self.shifter_overlay_win and tk.Toplevel.winfo_exists(self.shifter_overlay_win):
            w = 340
            h = 160
            left = max(0, 1765 - w // 2)
            top = max(0, 660 - h // 2)
            self.shifter_overlay_win.geometry(f"{w}x{h}+{left}+{top}")
        self.refresh_shifter_table()

    # ================= EKSEKUSI DRAG SHIFTER MOUSE =================
    def smooth_drag_line(self, x1, y1, x2, y2, steps=10, delay=0.015):
        for i in range(1, steps + 1):
            t = i / steps
            cx = int(x1 + (x2 - x1) * t)
            cy = int(y1 + (y2 - y1) * t)
            pyautogui.moveTo(cx, cy)
            time.sleep(delay)

    def execute_shifter_drag(self, target_gear_key):
        with self.shifter_lock:
            if self.current_gear == target_gear_key:
                return

            self.is_shifting_active = True

            # 1. Jika setir sedang ditahan (sedang belok), lepaskan klik mouse setir terlebih dahulu
            was_steering = self.is_mouse_down
            if was_steering:
                pyautogui.mouseUp()
                self.is_mouse_down = False
                time.sleep(0.02)

            # Dapatkan koordinat asal knob saat ini dan koordinat target
            src_x, src_y = self.get_gear_coordinates(self.current_gear)
            dst_x, dst_y = self.get_gear_coordinates(target_gear_key)

            # 2. Posisikan mouse tepat di knob kuning
            pyautogui.moveTo(src_x, src_y)
            time.sleep(0.04)

            # 3. Tekan dan tahan tombol mouse kiri (Hold knob)
            pyautogui.mouseDown()
            time.sleep(0.06)

            # 4. Lakukan drag bertahap (smooth stroke)
            neut_y = self.shifter_config["center_y"]
            if src_x != dst_x:
                if src_y != neut_y:
                    self.smooth_drag_line(src_x, src_y, src_x, neut_y, steps=6, delay=0.012)
                self.smooth_drag_line(src_x, neut_y, dst_x, neut_y, steps=8, delay=0.012)
                if dst_y != neut_y:
                    self.smooth_drag_line(dst_x, neut_y, dst_x, dst_y, steps=6, delay=0.012)
            else:
                self.smooth_drag_line(src_x, src_y, dst_x, dst_y, steps=8, delay=0.012)

            # Berikan waktu agar BUSSID meregistrasi gigi sudah masuk ke slot
            time.sleep(0.06)

            # 5. Lepaskan klik mouse (Drop knob)
            pyautogui.mouseUp()
            time.sleep(0.03)
            self.current_gear = target_gear_key

            # Update label status gigi di antarmuka
            name = target_gear_key.upper() if target_gear_key == "N" else self.shifter_config["slots"].get(target_gear_key, {}).get("name", target_gear_key)
            self.root.after(0, lambda: self.lbl_current_gear.config(text=f"[ {name} ]", fg="#00FF66" if target_gear_key != "N" else "#fca311"))

            # Selesai shifting
            self.is_shifting_active = False

    # ================= TAB SETIR =================
    def create_steer_tab(self):
        desc = tk.Label(self.tab_steer, 
                        text="📌 PENGATURAN KEMUDI SETIR BUSSID:\n"
                             "• Menggunakan kontrol native yang langsung memutar setir BUSSID.\n"
                             "• Gunakan tombol panah di bawah untuk mengepaskan koordinat setir jika diperlukan.",
                        font=("Segoe UI", 9, "bold"), fg="#06d6a0", bg="#1e1e2d", justify=tk.LEFT)
        desc.pack(anchor=tk.W, pady=(0, 4))

        # Visual Steer Overlay yang bisa di-drag langsung dengan mouse
        nav_box = tk.LabelFrame(self.tab_steer, text="🎮 Posisikan Lingkaran Setir BUSSID dengan Drag Mouse di Layar", 
                                font=("Segoe UI", 9, "bold"), fg="#fca311", bg="#1e1e2d", padx=10, pady=6)
        nav_box.pack(fill=tk.X, pady=4)

        info_lbl = tk.Label(nav_box, text="💡 Klik 'Buka Lingkaran Setir (Drag di Layar)'.\n"
                                          "Akan muncul jendela lingkaran transparan. Geser & DRAG tepat ke atas setir BUSSID Anda!",
                            font=("Segoe UI", 9), fg="#06d6a0", bg="#1e1e2d", justify=tk.LEFT)
        info_lbl.pack(anchor=tk.W, pady=(0, 4))

        coord_row = tk.Frame(nav_box, bg="#1e1e2d")
        coord_row.pack(fill=tk.X, pady=2)

        self.lbl_coords = tk.Label(coord_row, text=f"Pusat: X={self.steer_config['center_x']} px, Y={self.steer_config['center_y']} px | Radius={self.steer_config['radius']} px", 
                                   font=("Consolas", 10, "bold"), fg="#00FF66", bg="#151520", padx=10, pady=4)
        self.lbl_coords.pack(side=tk.LEFT)

        self.btn_toggle_steer_overlay = tk.Button(coord_row, text="⭕ Buka Lingkaran Setir (Drag di Layar)", 
                                                  bg="#fb5607", fg="white", font=("Segoe UI", 9, "bold"), 
                                                  relief=tk.FLAT, padx=10, pady=4, command=self.toggle_steer_overlay)
        self.btn_toggle_steer_overlay.pack(side=tk.LEFT, padx=8)

        tk.Button(coord_row, text="RESET POSISI", bg="#4f5d75", fg="white", relief=tk.FLAT, padx=8, pady=4, 
                  command=self.reset_steer_pos).pack(side=tk.LEFT, padx=4)

        self.steer_overlay_win = None

        size_box = tk.LabelFrame(self.tab_steer, text="Radius & Deadzone Setir", 
                                font=("Segoe UI", 9, "bold"), fg="#fca311", bg="#1e1e2d", padx=10, pady=6)
        size_box.pack(fill=tk.X, pady=4)

        r1 = tk.Frame(size_box, bg="#1e1e2d")
        r1.pack(fill=tk.X, pady=2)
        tk.Label(r1, text="Ukuran Radius Lingkaran:", width=22, anchor=tk.W, fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT)
        self.scale_radius = tk.Scale(r1, from_=80, to=260, orient=tk.HORIZONTAL, length=280,
                                     bg="#1e1e2d", fg="white", highlightthickness=0, command=self.on_radius_change)
        self.scale_radius.set(self.steer_config.get("radius", 135))
        self.scale_radius.pack(side=tk.LEFT, padx=5)

        r2 = tk.Frame(size_box, bg="#1e1e2d")
        r2.pack(fill=tk.X, pady=2)
        tk.Label(r2, text="Deadzone Titik Tengah:", width=22, anchor=tk.W, fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT)
        self.scale_deadzone = tk.Scale(r2, from_=0, to=5000, orient=tk.HORIZONTAL, length=280,
                                       bg="#1e1e2d", fg="white", highlightthickness=0)
        self.scale_deadzone.set(self.calib["steer"].get("deadzone", 900))
        self.scale_deadzone.pack(side=tk.LEFT, padx=5)

        self.steer_canvas = tk.Canvas(self.tab_steer, width=500, height=45, bg="#111", highlightthickness=1, highlightbackground="#444")
        self.steer_canvas.pack(fill=tk.X, pady=3)
        self.steer_indicator = self.steer_canvas.create_rectangle(245, 6, 255, 39, fill="#06d6a0", outline="")
        self.steer_center_line = self.steer_canvas.create_line(250, 0, 250, 45, fill="#ef233c", width=2)

        self.lbl_steer_status = tk.Label(self.tab_steer, text="Raw: 32767 | Derajat: 0.0° | Touch: Lurus", 
                                         font=("Segoe UI", 9, "bold"), fg="#06d6a0", bg="#1e1e2d")
        self.lbl_steer_status.pack(anchor=tk.W, pady=2)

    def toggle_steer_overlay(self):
        if self.steer_overlay_win and tk.Toplevel.winfo_exists(self.steer_overlay_win):
            self.steer_overlay_win.destroy()
            self.steer_overlay_win = None
            self.btn_toggle_steer_overlay.config(text="⭕ Buka Lingkaran Setir (Drag di Layar)", bg="#fb5607")
            return

        self.btn_toggle_steer_overlay.config(text="❌ Tutup Lingkaran Setir", bg="#ef233c")

        r = self.steer_config["radius"]
        size = (r + 20) * 2
        cx = self.steer_config["center_x"]
        cy = self.steer_config["center_y"]
        left = max(0, cx - size // 2)
        top = max(0, cy - size // 2)

        ow = tk.Toplevel(self.root)
        self.steer_overlay_win = ow
        ow.overrideredirect(True)
        ow.attributes("-topmost", True)
        ow.geometry(f"{size}x{size}+{left}+{top}")
        ow.configure(bg="#2b2d42")

        cv = tk.Canvas(ow, width=size, height=size, bg="#151520", highlightthickness=2, highlightbackground="#ef233c")
        cv.pack(fill=tk.BOTH, expand=True)
        self.steer_overlay_canvas = cv

        self.redraw_steer_overlay(cv, size, r)

        drag_state = {"x": 0, "y": 0}

        def on_press(event):
            drag_state["x"] = event.x
            drag_state["y"] = event.y

        def on_drag(event):
            dx = event.x - drag_state["x"]
            dy = event.y - drag_state["y"]
            nx = ow.winfo_x() + dx
            ny = ow.winfo_y() + dy
            ow.geometry(f"+{nx}+{ny}")

            # Update steer center
            new_cx = nx + size // 2
            new_cy = ny + size // 2
            self.steer_config["center_x"] = new_cx
            self.steer_config["center_y"] = new_cy
            self.lbl_coords.config(text=f"Pusat: X={new_cx} px, Y={new_cy} px | Radius={self.steer_config['radius']} px")

        cv.bind("<ButtonPress-1>", on_press)
        cv.bind("<B1-Motion>", on_drag)

    def redraw_steer_overlay(self, cv, size, r):
        cv.delete("all")
        mid = size // 2
        # Title bar
        cv.create_rectangle(0, 0, size, 22, fill="#ef233c", outline="")
        cv.create_text(mid, 11, text="[TAHAN & DRAG LINGKARAN KE SETIR BUSSID]", fill="white", font=("Segoe UI", 8, "bold"))

        # Lingkaran luar setir
        cv.create_oval(mid - r, mid - r, mid + r, mid + r, outline="#00FF66", width=4)
        # Crosshair tengah
        cv.create_line(mid - 25, mid, mid + 25, mid, fill="#ffbe0b", width=3)
        cv.create_line(mid, mid - 25, mid, mid + 25, fill="#ffbe0b", width=3)
        cv.create_oval(mid - 6, mid - 6, mid + 6, mid + 6, fill="#ef233c", outline="white", width=1)
        cv.create_text(mid, mid + 20, text="PUSAT SETIR", fill="#00FF66", font=("Segoe UI", 8, "bold"))

    def reset_steer_pos(self):
        self.steer_config["center_x"] = 220
        self.steer_config["center_y"] = 800
        self.steer_config["radius"] = 135
        self.scale_radius.set(135)
        self.lbl_coords.config(text=f"Pusat: X=220 px, Y=800 px | Radius=135 px")
        if self.steer_overlay_win and tk.Toplevel.winfo_exists(self.steer_overlay_win):
            r = 135
            size = (r + 20) * 2
            left = max(0, 220 - size // 2)
            top = max(0, 800 - size // 2)
            self.steer_overlay_win.geometry(f"{size}x{size}+{left}+{top}")
            self.redraw_steer_overlay(self.steer_overlay_canvas, size, r)

    def on_radius_change(self, val):
        r = int(val)
        self.steer_config["radius"] = r
        self.lbl_coords.config(text=f"Pusat: X={self.steer_config['center_x']} px, Y={self.steer_config['center_y']} px | Radius={r} px")
        if self.steer_overlay_win and tk.Toplevel.winfo_exists(self.steer_overlay_win):
            size = (r + 20) * 2
            cx = self.steer_config["center_x"]
            cy = self.steer_config["center_y"]
            left = max(0, cx - size // 2)
            top = max(0, cy - size // 2)
            self.steer_overlay_win.geometry(f"{size}x{size}+{left}+{top}")
            self.redraw_steer_overlay(self.steer_overlay_canvas, size, r)

    # ================= TAB PEDAL =================
    def create_pedals_tab(self):
        tk.Label(self.tab_pedals, text="Pedal Gas & Rem Otomatis Mengirim Input ke Game:", 
                 font=("Segoe UI", 10, "bold"), fg="#fca311", bg="#1e1e2d").pack(anchor=tk.W, pady=(0, 4))

        raw_box = tk.LabelFrame(self.tab_pedals, text="Nilai Raw Semua Axis:", 
                                fg="#06d6a0", bg="#1e1e2d", padx=8, pady=3)
        raw_box.pack(fill=tk.X, pady=2)

        self.lbl_all_axes = tk.Label(raw_box, text="X: 0 | Y: 0 | Z: 0 | R: 0 | U: 0 | V: 0", 
                                     font=("Consolas", 10, "bold"), fg="#edf2f4", bg="#151520", pady=3)
        self.lbl_all_axes.pack(fill=tk.X)

        pedal_frame = tk.Frame(self.tab_pedals, bg="#1e1e2d")
        pedal_frame.pack(fill=tk.BOTH, expand=True, pady=4)

        self.pedal_widgets = {}
        pedal_configs = [
            ("gas", "🟢 PEDAL GAS (Throttle)", "#06d6a0", "w"),
            ("brake", "🔴 PEDAL REM (Brake)", "#ef233c", "s"),
            ("clutch", "🔵 PEDAL KOPLING (Clutch)", "#3a86ff", "shift")
        ]

        for p_id, p_name, p_color, default_key in pedal_configs:
            box = tk.LabelFrame(pedal_frame, text=p_name, font=("Segoe UI", 9, "bold"), 
                                fg=p_color, bg="#1e1e2d", padx=8, pady=4)
            box.pack(fill=tk.X, pady=3)

            r1 = tk.Frame(box, bg="#1e1e2d")
            r1.pack(fill=tk.X, pady=2)

            pb = ttk.Progressbar(r1, orient=tk.HORIZONTAL, length=300, mode='determinate')
            pb.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 8))

            lbl_pct = tk.Label(r1, text="0%", font=("Segoe UI", 10, "bold"), fg=p_color, bg="#1e1e2d", width=6)
            lbl_pct.pack(side=tk.RIGHT)

            r2 = tk.Frame(box, bg="#1e1e2d")
            r2.pack(fill=tk.X, pady=2)

            tk.Label(r2, text="Axis:", fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT)
            cb_axis = ttk.Combobox(r2, values=AXIS_LIST, width=4, state="readonly")
            cb_axis.set(self.calib[p_id]["axis"])
            cb_axis.pack(side=tk.LEFT, padx=3)

            btn_detect = tk.Button(r2, text="⚡ Auto-Detect", 
                                   command=lambda pid=p_id: self.start_detect_pedal(pid),
                                   bg="#4f5d75", fg="white", relief=tk.FLAT, padx=6)
            btn_detect.pack(side=tk.LEFT, padx=3)

            tk.Button(r2, text="Set Lepas", 
                      command=lambda pid=p_id: self.set_pedal_point(pid, "rest"),
                      bg="#3d5a80", fg="white", relief=tk.FLAT, padx=4).pack(side=tk.LEFT, padx=2)

            tk.Button(r2, text="Set Injak", 
                      command=lambda pid=p_id: self.set_pedal_point(pid, "pressed"),
                      bg="#3d5a80", fg="white", relief=tk.FLAT, padx=4).pack(side=tk.LEFT, padx=2)

            tk.Label(r2, text="Key Game:", fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT, padx=(6, 2))
            ent_key = tk.Entry(r2, width=6)
            ent_key.insert(0, self.calib[p_id].get("key", default_key))
            ent_key.pack(side=tk.LEFT, padx=2)

            self.pedal_widgets[p_id] = {
                "pb": pb,
                "lbl_pct": lbl_pct,
                "cb_axis": cb_axis,
                "ent_key": ent_key,
                "btn_detect": btn_detect
            }

            cb_axis.bind("<<ComboboxSelected>>", lambda e, pid=p_id: self.calib[pid].update({"axis": self.pedal_widgets[pid]["cb_axis"].get()}))
            ent_key.bind("<KeyRelease>", lambda e, pid=p_id: self.calib[pid].update({"key": self.pedal_widgets[pid]["ent_key"].get().strip().lower()}))

    def set_pedal_point(self, p_id, point_type):
        axis = self.calib[p_id]["axis"]
        cur_val = self.axis_values.get(axis, 32767)
        self.calib[p_id][point_type] = cur_val
        messagebox.showinfo("Kalibrasi Pedal", f"{p_id.upper()} {point_type} disimpan: {cur_val}")

    def start_detect_pedal(self, p_id):
        self.detecting_pedal_type = p_id
        self.raw_axis_baselines = dict(self.axis_values)
        btn = self.pedal_widgets[p_id]["btn_detect"]
        btn.config(text="⏳ Injak Sekarang!", bg="#e76f51")
        self.root.after(100, self.check_pedal_detection_tick, 0)

    def check_pedal_detection_tick(self, count):
        if not self.detecting_pedal_type:
            return
        p_id = self.detecting_pedal_type
        max_diff = 0
        detected_axis = None
        for a in AXIS_LIST:
            diff = abs(self.axis_values[a] - self.raw_axis_baselines[a])
            if diff > max_diff:
                max_diff = diff
                detected_axis = a

        if max_diff > 4000:
            self.calib[p_id]["axis"] = detected_axis
            self.pedal_widgets[p_id]["cb_axis"].set(detected_axis)
            self.calib[p_id]["rest"] = self.raw_axis_baselines[detected_axis]
            self.calib[p_id]["pressed"] = self.axis_values[detected_axis]
            
            btn = self.pedal_widgets[p_id]["btn_detect"]
            btn.config(text="✅ Terdeteksi!", bg="#06d6a0")
            self.detecting_pedal_type = None
            messagebox.showinfo("Auto Detect", f"Pedal {p_id.upper()} terdeteksi di Axis {detected_axis}!")
            self.root.after(1500, lambda: btn.config(text="⚡ Auto-Detect", bg="#4f5d75"))
            return

        if count < 35:
            self.root.after(100, self.check_pedal_detection_tick, count + 1)
        else:
            btn = self.pedal_widgets[p_id]["btn_detect"]
            btn.config(text="❌ Gagal", bg="#ef233c")
            self.detecting_pedal_type = None
            self.root.after(1500, lambda: btn.config(text="⚡ Auto-Detect", bg="#4f5d75"))

    # ================= TAB TOMBOL =================
    def create_buttons_tab(self):
        columns = ("btn_id", "label", "target", "status")
        self.tree_map = ttk.Treeview(self.tab_buttons, columns=columns, show="headings", height=8)
        self.tree_map.heading("btn_id", text="ID Tombol Setir")
        self.tree_map.heading("label", text="Nama Fungsi")
        self.tree_map.heading("target", text="Target Key Game")
        self.tree_map.heading("status", text="Triggered")

        self.tree_map.column("btn_id", width=120)
        self.tree_map.column("label", width=150)
        self.tree_map.column("target", width=120)
        self.tree_map.column("status", width=90)
        self.tree_map.pack(fill=tk.BOTH, expand=True, pady=4)

        add_box = tk.LabelFrame(self.tab_buttons, text="➕ Tambah / Edit Tombol", 
                                font=("Segoe UI", 9, "bold"), fg="#06d6a0", bg="#1e1e2d", padx=8, pady=6)
        add_box.pack(fill=tk.X, pady=4)

        r1 = tk.Frame(add_box, bg="#1e1e2d")
        r1.pack(fill=tk.X, pady=2)

        tk.Label(r1, text="Nomor Tombol:", width=12, anchor=tk.W, fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT)
        self.ent_btn_num = tk.Entry(r1, width=8)
        self.ent_btn_num.insert(0, "0")
        self.ent_btn_num.pack(side=tk.LEFT, padx=4)

        tk.Button(r1, text="🎯 Deteksi Tekan", command=self.detect_pressed_button, 
                  bg="#fb5607", fg="white", relief=tk.FLAT, padx=6).pack(side=tk.LEFT, padx=4)

        tk.Label(r1, text="Nama Fungsi:", width=12, anchor=tk.E, fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT, padx=4)
        self.ent_btn_label = tk.Entry(r1, width=15)
        self.ent_btn_label.insert(0, "Klakson / Handbrake")
        self.ent_btn_label.pack(side=tk.LEFT, padx=4)

        tk.Label(r1, text="Key Game:", width=10, anchor=tk.E, fg="#edf2f4", bg="#1e1e2d").pack(side=tk.LEFT, padx=4)
        self.ent_target_val = tk.Entry(r1, width=8)
        self.ent_target_val.insert(0, "space")
        self.ent_target_val.pack(side=tk.LEFT, padx=4)

        r2 = tk.Frame(add_box, bg="#1e1e2d")
        r2.pack(fill=tk.X, pady=4)

        tk.Button(r2, text="➕ Tambahkan Mapping", command=self.add_mapping,
                  bg="#06d6a0", fg="#111", font=("Segoe UI", 9, "bold"), relief=tk.FLAT, padx=10, pady=3).pack(side=tk.LEFT, padx=4)

        tk.Button(r2, text="🗑️ Hapus Terpilih", command=self.delete_mapping,
                  bg="#e63946", fg="white", relief=tk.FLAT, padx=10, pady=3).pack(side=tk.LEFT, padx=4)

        self.lbl_active_btns = tk.Label(self.tab_buttons, text="Tekan tombol pada steering wheel...", 
                                        font=("Segoe UI", 10), fg="#06d6a0", bg="#1e1e2d", pady=2)
        self.lbl_active_btns.pack(anchor=tk.W)

        self.refresh_mappings_table()

    def refresh_mappings_table(self):
        for item in self.tree_map.get_children():
            self.tree_map.delete(item)
        for btn_key, val in self.mappings.items():
            btn_idx = btn_key.replace("btn_", "Tombol ")
            self.tree_map.insert("", tk.END, iid=btn_key, values=(
                btn_idx,
                val.get("name", ""),
                val.get("target", ""),
                "RELEASED"
            ))

    def detect_pressed_button(self):
        if not self.active_buttons:
            messagebox.showinfo("Deteksi", "Tekan tombol fisik di setir Anda.")
            return
        btn = list(self.active_buttons)[0]
        self.ent_btn_num.delete(0, tk.END)
        self.ent_btn_num.insert(0, str(btn))

    def add_mapping(self):
        btn_input = self.ent_btn_num.get().strip().lower()
        label = self.ent_btn_label.get().strip()
        target = self.ent_target_val.get().strip().lower()

        if not btn_input or not target:
            messagebox.showwarning("Peringatan", "Input tombol dan Target Key tidak boleh kosong!")
            return

        key_id = f"btn_{btn_input}" if not btn_input.startswith("btn_") else btn_input
        self.mappings[key_id] = {
            "name": label or f"Button {btn_input}",
            "type": "key",
            "target": target
        }
        self.refresh_mappings_table()
        messagebox.showinfo("Berhasil", f"Mapping untuk '{btn_input}' berhasil ditambahkan!")

    def delete_mapping(self):
        selected = self.tree_map.selection()
        if not selected:
            return
        for item in selected:
            if item in self.mappings:
                del self.mappings[item]
        self.refresh_mappings_table()

    def scan_devices(self):
        self.combo_devices['values'] = []
        num = winmm.joyGetNumDevs()
        devices = []
        for i in range(num):
            caps = JOYCAPSW()
            res = winmm.joyGetDevCapsW(i, ctypes.byref(caps), ctypes.sizeof(caps))
            if res == 0:
                devices.append(f"ID {i}: {caps.szPname}")

        if devices:
            self.combo_devices['values'] = devices
            self.combo_devices.current(0)
            self.on_device_selected(None)
        else:
            self.lbl_status.config(text="Tidak ada Steering Wheel terdeteksi!", fg="#ef233c")

    def on_device_selected(self, event):
        val = self.combo_devices.get()
        if val:
            dev_id = int(val.split(":")[0].replace("ID ", ""))
            self.connected_device_id = dev_id
            caps = JOYCAPSW()
            res = winmm.joyGetDevCapsW(dev_id, ctypes.byref(caps), ctypes.sizeof(caps))
            if res == 0:
                self.lbl_status.config(text=f"Terkoneksi ({caps.szPname})", fg="#06d6a0")

    def minimize_to_background(self):
        self.root.iconify()
        messagebox.showinfo("Mode Main Aktif", "Aplikasi berjalan di BACKGROUND!\nKlik 1x di game BUSSID untuk mulai bermain.")

    # ================= EKSEKUSI TOUCH DRAG SETIR =================
    def execute_touch_steering(self, norm, angle_deg):
        # Jika shifter sedang melakukan drag gigi, tahan setir sejenak agar kursor tidak berebutan
        if self.is_shifting_active:
            return

        cx = self.steer_config["center_x"]
        cy = self.steer_config["center_y"]
        r = self.steer_config["radius"]

        theta_rad = math.radians(angle_deg - 90)
        screen_x = cx + int(r * math.cos(theta_rad))
        screen_y = cy + int(r * math.sin(theta_rad))

        is_steering = abs(norm) > 0.02

        if is_steering:
            if not self.is_mouse_down:
                pyautogui.moveTo(screen_x, screen_y)
                pyautogui.mouseDown()
                self.is_mouse_down = True
            else:
                pyautogui.moveTo(screen_x, screen_y)

            self.lbl_steer_status.config(text=f"Raw: {self.axis_values.get(self.calib['steer']['axis'], 0)} | Derajat: {angle_deg:.1f}° | Touch: HOLD & DRAG (AKTIF)", fg="#00FF66")
        else:
            if self.is_mouse_down:
                pyautogui.moveTo(screen_x, screen_y)
                pyautogui.mouseUp()
                self.is_mouse_down = False

            self.lbl_steer_status.config(text=f"Raw: {self.axis_values.get(self.calib['steer']['axis'], 0)} | Derajat: 0.0° | Touch: LURUS (RELEASED)", fg="#06d6a0")

    # ================= POLLING CONTROLLER =================
    def poll_controller_loop(self):
        joy_info = JOYINFOEX()
        joy_info.dwSize = ctypes.sizeof(JOYINFOEX)
        joy_info.dwFlags = 0xFF

        while self.running:
            if self.connected_device_id is not None:
                res = winmm.joyGetPosEx(self.connected_device_id, ctypes.byref(joy_info))
                if res == 0:
                    self.axis_values["X"] = joy_info.dwXpos
                    self.axis_values["Y"] = joy_info.dwYpos
                    self.axis_values["Z"] = joy_info.dwZpos
                    self.axis_values["R"] = joy_info.dwRpos
                    self.axis_values["U"] = joy_info.dwUpos
                    self.axis_values["V"] = joy_info.dwVpos

                    btns = set()
                    raw_btn = joy_info.dwButtons
                    for b in range(32):
                        if raw_btn & (1 << b):
                            btns.add(b)
                    self.active_buttons = btns

                    # Process Pedals, H-Shifter & Buttons
                    self.process_pedals()
                    self.process_shifter()
                    self.process_mappings()

                    self.root.after(10, self.update_steer_and_gui)
            time.sleep(0.01)

    def calculate_pedal_pct(self, p_id):
        p = self.calib[p_id]
        axis = p["axis"]
        cur = self.axis_values.get(axis, 32767)
        rest = p.get("rest", 65535)
        pressed = p.get("pressed", 0)

        total_range = pressed - rest
        if total_range == 0:
            return 0.0
        pct = (cur - rest) / total_range
        return max(0.0, min(1.0, pct))

    def process_pedals(self):
        key_map = {'w': (0x57, 0x11), 's': (0x53, 0x1F), 'shift': (0x10, 0x2A)}
        for p_id in ["gas", "brake", "clutch"]:
            pct = self.calculate_pedal_pct(p_id)
            threshold = self.calib[p_id].get("threshold", 0.15)
            key = self.calib[p_id].get("key", "").strip()

            is_pressed = pct >= threshold
            was_pressed = self.pedal_pressed_states[p_id]

            if is_pressed and not was_pressed:
                self.pedal_pressed_states[p_id] = True
                if key in key_map:
                    vk, sc = key_map[key]
                    inject_hardware_key(vk, sc, is_down=True)

            elif not is_pressed and was_pressed:
                self.pedal_pressed_states[p_id] = False
                if key in key_map:
                    vk, sc = key_map[key]
                    inject_hardware_key(vk, sc, is_down=False)

    # Deteksi Input H-Shifter Fisik
    def process_shifter(self):
        detected_gear = None
        for g_id, data in self.shifter_config["slots"].items():
            btn_str = str(data.get("btn", "")).strip()
            if btn_str.isdigit():
                btn_num = int(btn_str)
                if btn_num in self.active_buttons:
                    detected_gear = g_id
                    break

        if detected_gear:
            if self.current_gear != detected_gear:
                threading.Thread(target=lambda: self.execute_shifter_drag(detected_gear), daemon=True).start()
        else:
            # Jika semua tombol shifter dilepas -> Netral (N)
            if self.current_gear != "N":
                threading.Thread(target=lambda: self.execute_shifter_drag("N"), daemon=True).start()

    def process_mappings(self):
        pressed = self.active_buttons - self.prev_buttons
        released = self.prev_buttons - self.active_buttons

        for b in pressed:
            str_b = str(b)
            for key_id in [f"btn_{b}", str_b]:
                if key_id in self.mappings:
                    target_key = self.mappings[key_id].get("target", "")
                    if target_key:
                        vk = user32.VkKeyScanW(ord(target_key[0])) & 0xFF
                        sc = user32.MapVirtualKeyW(vk, 0)
                        inject_hardware_key(vk, sc, is_down=True)
                    self.update_tree_status(key_id, "PRESSED")

        for b in released:
            str_b = str(b)
            for key_id in [f"btn_{b}", str_b]:
                if key_id in self.mappings:
                    target_key = self.mappings[key_id].get("target", "")
                    if target_key:
                        vk = user32.VkKeyScanW(ord(target_key[0])) & 0xFF
                        sc = user32.MapVirtualKeyW(vk, 0)
                        inject_hardware_key(vk, sc, is_down=False)
                    self.update_tree_status(key_id, "RELEASED")

        self.prev_buttons = set(self.active_buttons)

    def update_tree_status(self, key_id, status):
        try:
            if self.tree_map.exists(key_id):
                vals = list(self.tree_map.item(key_id, "values"))
                vals[3] = status
                self.tree_map.item(key_id, values=vals)
        except Exception:
            pass

    def update_steer_and_gui(self):
        axes_str = " | ".join([f"{a}: {self.axis_values[a]}" for a in AXIS_LIST])
        self.lbl_all_axes.config(text=axes_str)

        for p_id in ["gas", "brake", "clutch"]:
            pct = self.calculate_pedal_pct(p_id)
            pct_100 = int(pct * 100)
            self.pedal_widgets[p_id]["pb"]["value"] = pct_100
            self.pedal_widgets[p_id]["lbl_pct"].config(text=f"{pct_100}%")

        s = self.calib["steer"]
        raw_steer = self.axis_values.get(s["axis"], 32767)
        c = s["center"]
        dz = int(self.scale_deadzone.get())
        diff = raw_steer - c

        if abs(diff) < dz:
            norm = 0.0
        elif diff > 0:
            max_r = max(1, s["max"] - c)
            norm = min(1.0, (diff - dz) / max_r)
        else:
            min_r = max(1, c - s["min"])
            norm = max(-1.0, (diff + dz) / min_r)

        max_deg = self.steer_config.get("max_angle", 360)
        angle_deg = norm * max_deg

        canvas_w = self.steer_canvas.winfo_width() or 500
        center_x = canvas_w // 2
        cur_x = center_x + int(norm * (center_x - 10))
        self.steer_canvas.coords(self.steer_indicator, cur_x - 8, 6, cur_x + 8, 39)

        # Eksekusi Touch Steering
        self.execute_touch_steering(norm, angle_deg)

        if self.active_buttons:
            btn_str = ", ".join([f"Tombol {b}" for b in sorted(self.active_buttons)])
            self.lbl_active_btns.config(text=f"Sedang Ditekan: {btn_str}", fg="#ff006e")
        else:
            self.lbl_active_btns.config(text="Tidak ada tombol ditekan", fg="#06d6a0")

    def on_close(self):
        self.running = False
        try:
            pyautogui.mouseUp()
        except Exception:
            pass
        self.root.destroy()
        sys.exit(0)

if __name__ == "__main__":
    root = tk.Tk()
    app = FullBussidWheelShifterApp(root)
    root.mainloop()
