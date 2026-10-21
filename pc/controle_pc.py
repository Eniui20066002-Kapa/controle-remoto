#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Controle Remoto do Celular - lado do PC.

Conecta no app "Controle Remoto" do celular (mesma rede Wi-Fi/LAN), mostra a tela
do celular numa janela e envia toques, rolagem, teclas e botoes de navegacao.

Precisa de: Python 3 + Pillow (pip install pillow). O iniciar.bat instala sozinho.
"""
import io
import json
import math
import os
import queue
import socket
import struct
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk

try:
    from PIL import Image, ImageTk
except ImportError:
    print("Falta a biblioteca Pillow. Rode:  python -m pip install pillow")
    input("Aperte Enter para sair...")
    sys.exit(1)

APP_TITLE = "Controle Remoto do Celular"
PORT = 8765
T_FRAME, T_TEXT, T_CMD = 1, 2, 10
SETTINGS = os.path.join(os.path.expanduser("~"), ".controle_remoto.json")

RESAMPLE = getattr(getattr(Image, "Resampling", Image), "BILINEAR")

QUALITY = {
    "Leve (rede fraca)": (640, 40, 8),
    "Normal": (960, 55, 8),
    "Alta": (1280, 75, 10),
}


# ----------------------------------------------------------------- protocolo
def send_frame(sock, ftype, payload):
    sock.sendall(struct.pack(">IB", len(payload), ftype) + payload)


def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("conexao fechada")
        buf += chunk
    return bytes(buf)


def recv_frame(sock):
    length, ftype = struct.unpack(">IB", recv_exact(sock, 5))
    if length > 30_000_000:
        raise ConnectionError("quadro grande demais")
    return ftype, (recv_exact(sock, length) if length else b"")


class Remote:
    """Conexao com o celular. A leitura roda numa thread; guarda so o quadro mais novo."""

    def __init__(self):
        self.sock = None
        self.alive = False
        self.send_lock = threading.Lock()
        self.latest = None
        self.latest_lock = threading.Lock()
        self.frames = 0
        self.on_text = None
        self.on_close = None

    def connect(self, ip, pin, port=PORT, timeout=6.0):
        s = socket.create_connection((ip, port), timeout=timeout)
        try:
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.settimeout(10)
            send_frame(s, T_CMD, ("AUTH " + pin).encode("utf-8"))
            ftype, payload = recv_frame(s)
            msg = payload.decode("utf-8", "replace")
            if ftype != T_TEXT or msg != "OK":
                raise ConnectionError(msg[4:] if msg.startswith("ERR ") else msg)
            s.settimeout(None)
        except Exception:
            s.close()
            raise
        self.sock = s
        self.alive = True
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        err = "Conexao encerrada."
        try:
            while True:
                ftype, payload = recv_frame(self.sock)
                if ftype == T_FRAME:
                    with self.latest_lock:
                        self.latest = payload
                    self.frames += 1
                elif ftype == T_TEXT and self.on_text:
                    self.on_text(payload.decode("utf-8", "replace"))
        except Exception as e:
            if self.alive:
                err = "Conexao perdida (%s)." % e
        intentional = not self.alive
        self.alive = False
        try:
            self.sock.close()
        except Exception:
            pass
        if not intentional and self.on_close:
            self.on_close(err)

    def take_frame(self):
        with self.latest_lock:
            data, self.latest = self.latest, None
        return data

    def send(self, text):
        if not self.alive:
            return
        try:
            with self.send_lock:
                send_frame(self.sock, T_CMD, text.encode("utf-8"))
        except Exception:
            try:
                self.sock.close()  # a thread de leitura percebe e avisa
            except Exception:
                pass

    def close(self):
        self.alive = False
        try:
            self.sock.close()
        except Exception:
            pass


# ------------------------------------------------------------------ utilidades
def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def scan_lan(port=PORT):
    """Procura na rede (/24) quem tem a porta do app aberta."""
    base = ".".join(local_ip().split(".")[:3])
    found = []

    def probe(ip):
        try:
            with socket.create_connection((ip, port), timeout=0.5):
                found.append(ip)
        except OSError:
            pass

    threads = [threading.Thread(target=probe, args=("%s.%d" % (base, i),)) for i in range(1, 255)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return sorted(found)


def load_settings():
    try:
        with open(SETTINGS, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_settings(d):
    try:
        with open(SETTINGS, "w", encoding="utf-8") as f:
            json.dump(d, f)
    except Exception:
        pass


# ------------------------------------------------------------------------ GUI
class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("460x330")
        self.minsize(420, 300)
        self.ui_q = queue.Queue()
        self.remote = None
        self.cur_img = None
        self.photo = None
        self.geo = (0, 0, 1, 1)
        self.press = None
        self.last_wheel = 0.0
        self.fps_last = (time.time(), 0)
        self.settings = load_settings()
        self.connect_frame = None
        self.remote_frame = None
        self.protocol("WM_DELETE_WINDOW", self.on_close_window)
        self.bind("<Key>", self.on_key)
        self.bind("<Control-v>", self.on_paste)
        self.build_connect()
        self.after(30, self.poll)

    # fila de tarefas vindas de outras threads
    def post(self, fn):
        self.ui_q.put(fn)

    def poll(self):
        try:
            while True:
                self.ui_q.get_nowait()()
        except queue.Empty:
            pass
        if self.remote and self.remote.alive:
            data = self.remote.take_frame()
            if data is not None:
                self.show_frame(data)
            now = time.time()
            if now - self.fps_last[0] >= 1.0:
                n = self.remote.frames
                fps = (n - self.fps_last[1]) / (now - self.fps_last[0])
                self.fps_text.set("%.0f quadros/s" % fps)
                self.fps_last = (now, n)
        self.after(15, self.poll)

    # ---------------------------------------------------------- tela de conexao
    def build_connect(self):
        if self.remote_frame is not None:
            self.remote_frame.destroy()
            self.remote_frame = None
        self.title(APP_TITLE)
        self.geometry("460x330")
        f = ttk.Frame(self, padding=18)
        f.pack(fill="both", expand=True)
        self.connect_frame = f

        ttk.Label(f, text="Conectar no celular", font=("Segoe UI", 15, "bold")).grid(
            row=0, column=0, columnspan=2, sticky="w", pady=(0, 4))
        ttk.Label(f, text="Abra o app no celular, toque em \"Iniciar compartilhamento\"\n"
                          "e digite aqui o IP e o PIN que aparecem na tela dele.",
                  justify="left").grid(row=1, column=0, columnspan=2, sticky="w", pady=(0, 12))

        ttk.Label(f, text="IP do celular").grid(row=2, column=0, sticky="w")
        self.ip_var = tk.StringVar(value=self.settings.get("ip", ""))
        ip_entry = ttk.Entry(f, textvariable=self.ip_var, width=22)
        ip_entry.grid(row=3, column=0, sticky="we", pady=(0, 8))
        ttk.Button(f, text="Procurar na rede", command=self.do_scan).grid(row=3, column=1, padx=(8, 0))

        ttk.Label(f, text="PIN (6 numeros)").grid(row=4, column=0, sticky="w")
        self.pin_var = tk.StringVar()
        pin_entry = ttk.Entry(f, textvariable=self.pin_var, width=22)
        pin_entry.grid(row=5, column=0, sticky="we", pady=(0, 12))

        self.btn_connect = ttk.Button(f, text="Conectar", command=self.do_connect)
        self.btn_connect.grid(row=6, column=0, sticky="w")
        self.msg_var = tk.StringVar()
        ttk.Label(f, textvariable=self.msg_var, foreground="#b00020", wraplength=420,
                  justify="left").grid(row=7, column=0, columnspan=2, sticky="w", pady=(12, 0))
        f.columnconfigure(0, weight=1)

        pin_entry.bind("<Return>", lambda e: self.do_connect())
        ip_entry.bind("<Return>", lambda e: pin_entry.focus_set())
        (pin_entry if self.ip_var.get() else ip_entry).focus_set()

    def do_scan(self):
        self.msg_var.set("Procurando na rede...")

        def work():
            found = scan_lan()

            def done():
                if found:
                    self.ip_var.set(found[0])
                    extra = "" if len(found) == 1 else " (outros: %s)" % ", ".join(found[1:])
                    self.msg_var.set("Encontrado: %s%s. Agora digite o PIN." % (found[0], extra))
                else:
                    self.msg_var.set("Nao achei nenhum celular. Confira se o compartilhamento esta "
                                     "iniciado e se os dois estao no mesmo Wi-Fi.")
            self.post(done)
        threading.Thread(target=work, daemon=True).start()

    def do_connect(self):
        ip = self.ip_var.get().strip()
        pin = self.pin_var.get().replace(" ", "").strip()
        if not ip or not pin:
            self.msg_var.set("Preencha o IP e o PIN.")
            return
        self.btn_connect.state(["disabled"])
        self.msg_var.set("Conectando...")

        def work():
            r = Remote()
            try:
                r.connect(ip, pin)
            except Exception as e:
                err = str(e) or e.__class__.__name__

                def fail():
                    self.btn_connect.state(["!disabled"])
                    self.msg_var.set("Nao deu certo: %s" % err)
                self.post(fail)
                return
            self.post(lambda: self.start_remote(r, ip))
        threading.Thread(target=work, daemon=True).start()

    # ----------------------------------------------------------- tela de controle
    def start_remote(self, remote, ip):
        self.settings["ip"] = ip
        save_settings(self.settings)
        self.remote = remote
        remote.on_text = lambda t: self.post(lambda: self.on_remote_text(t))
        remote.on_close = lambda err: self.post(lambda: self.on_remote_closed(err))
        if self.connect_frame is not None:
            self.connect_frame.destroy()
            self.connect_frame = None
        self.build_remote(ip)
        self.apply_quality()

    def build_remote(self, ip):
        self.title("%s - %s" % (APP_TITLE, ip))
        self.geometry("560x900")
        self.minsize(360, 500)
        f = ttk.Frame(self)
        f.pack(fill="both", expand=True)
        self.remote_frame = f

        bar = ttk.Frame(f, padding=(4, 4))
        bar.pack(fill="x")
        for label, cmd in (("Voltar", "BACK"), ("Inicio", "HOME"), ("Recentes", "RECENTS"),
                           ("Notificacoes", "NOTIF"), ("Config. rapidas", "QS"),
                           ("Acordar tela", "WAKE"), ("Atualizar", "REFRESH")):
            ttk.Button(bar, text=label, width=max(6, len(label)),
                       command=lambda c=cmd: self.send(c)).pack(side="left", padx=2)

        bar2 = ttk.Frame(f, padding=(4, 0, 4, 4))
        bar2.pack(fill="x")
        ttk.Label(bar2, text="Qualidade:").pack(side="left", padx=(2, 4))
        self.q_var = tk.StringVar(value="Normal")
        cb = ttk.Combobox(bar2, textvariable=self.q_var, values=list(QUALITY), state="readonly", width=16)
        cb.pack(side="left")
        cb.bind("<<ComboboxSelected>>", lambda e: (self.apply_quality(), self.canvas.focus_set()))
        ttk.Button(bar2, text="Desconectar", command=self.disconnect).pack(side="right", padx=2)

        self.canvas = tk.Canvas(f, bg="#101010", highlightthickness=0, cursor="hand2")
        self.canvas.pack(fill="both", expand=True)
        self.img_item = self.canvas.create_image(0, 0, anchor="nw")
        self.hint_item = self.canvas.create_text(
            10, 10, anchor="nw", fill="#888", text="Aguardando a imagem do celular...")
        self.canvas.bind("<Configure>", lambda e: self.redraw())
        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<ButtonRelease-1>", self.on_release)
        self.canvas.bind("<ButtonRelease-3>", lambda e: self.send("BACK"))
        self.canvas.bind("<ButtonRelease-2>", lambda e: self.send("HOME"))
        self.canvas.bind("<MouseWheel>", self.on_wheel)
        self.canvas.bind("<Button-4>", lambda e: self.wheel(e, +1))
        self.canvas.bind("<Button-5>", lambda e: self.wheel(e, -1))

        tbar = ttk.Frame(f, padding=(4, 4))
        tbar.pack(fill="x")
        self.text_var = tk.StringVar()
        te = ttk.Entry(tbar, textvariable=self.text_var)
        te.pack(side="left", fill="x", expand=True)
        te.bind("<Return>", lambda e: self.send_text())
        ttk.Button(tbar, text="Enviar texto", command=self.send_text).pack(side="left", padx=(4, 0))

        self.fps_text = tk.StringVar(value="")
        self.status_text = tk.StringVar(
            value="Esquerdo: toque | segurar: toque longo | arrastar: deslizar/rolar | "
                  "roda: rolar | direito: Voltar | meio: Inicio | Esc: Voltar")
        st = ttk.Frame(f, padding=(6, 0, 6, 4))
        st.pack(fill="x")
        ttk.Label(st, textvariable=self.status_text, foreground="#555", wraplength=520).pack(side="left")
        ttk.Label(st, textvariable=self.fps_text, foreground="#555").pack(side="right")
        self.canvas.focus_set()

    def on_remote_text(self, text):
        if text.startswith("WARN "):
            self.status_text.set("Aviso do celular: " + text[5:])

    def on_remote_closed(self, err):
        self.remote = None
        self.cur_img = None
        self.build_connect()
        self.msg_var.set(err)

    def disconnect(self):
        if self.remote:
            self.remote.close()
            self.remote = None
        self.cur_img = None
        self.build_connect()

    def on_close_window(self):
        if self.remote:
            self.remote.close()
        self.destroy()

    # ------------------------------------------------------------------- imagem
    def show_frame(self, data):
        try:
            img = Image.open(io.BytesIO(data))
            img.load()
        except Exception:
            return
        self.cur_img = img
        self.redraw()

    def redraw(self):
        if self.cur_img is None or self.remote_frame is None:
            return
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        if cw < 10 or ch < 10:
            return
        iw, ih = self.cur_img.size
        s = min(cw / iw, ch / ih)
        dw, dh = max(1, int(iw * s)), max(1, int(ih * s))
        self.photo = ImageTk.PhotoImage(self.cur_img.resize((dw, dh), RESAMPLE))
        ox, oy = (cw - dw) // 2, (ch - dh) // 2
        self.geo = (ox, oy, dw, dh)
        self.canvas.itemconfig(self.img_item, image=self.photo)
        self.canvas.coords(self.img_item, ox, oy)
        self.canvas.itemconfig(self.hint_item, state="hidden")

    def norm(self, x, y, clamp=False):
        ox, oy, dw, dh = self.geo
        nx, ny = (x - ox) / dw, (y - oy) / dh
        if clamp:
            return min(1.0, max(0.0, nx)), min(1.0, max(0.0, ny))
        if 0 <= nx <= 1 and 0 <= ny <= 1:
            return nx, ny
        return None

    # -------------------------------------------------------------------- envio
    def send(self, cmd):
        if self.remote:
            self.remote.send(cmd)

    def apply_quality(self):
        size, q, fps = QUALITY.get(self.q_var.get(), QUALITY["Normal"])
        self.send("CFG %d %d %d" % (size, q, fps))

    def send_text(self):
        t = self.text_var.get()
        if t:
            self.send("TEXT " + t)
            self.text_var.set("")
        self.canvas.focus_set()

    # ------------------------------------------------------------------- mouse
    def on_press(self, e):
        self.canvas.focus_set()
        self.press = (e.x, e.y, time.time())

    def on_release(self, e):
        if not self.press:
            return
        x0, y0, t0 = self.press
        self.press = None
        p0 = self.norm(x0, y0)
        if p0 is None:
            return
        dt = time.time() - t0
        if math.hypot(e.x - x0, e.y - y0) < 10:
            self.send("%s %.5f %.5f" % ("LONG" if dt > 0.6 else "TAP", p0[0], p0[1]))
        else:
            p1 = self.norm(e.x, e.y, clamp=True)
            dur = int(min(max(dt * 1000, 120), 2000))
            self.send("SWIPE %.5f %.5f %.5f %.5f %d" % (p0[0], p0[1], p1[0], p1[1], dur))

    def on_wheel(self, e):
        self.wheel(e, 1 if e.delta > 0 else -1)

    def wheel(self, e, sign):
        now = time.time()
        if now - self.last_wheel < 0.15:
            return
        self.last_wheel = now
        p = self.norm(e.x, e.y) or (0.5, 0.5)
        self.send("SCROLL %.5f %.5f %s" % (p[0], p[1], "up" if sign > 0 else "down"))

    # ------------------------------------------------------------------ teclado
    def typing_in_entry(self):
        w = self.focus_get()
        return isinstance(w, (tk.Entry, ttk.Entry, ttk.Combobox))

    def on_key(self, e):
        if self.remote is None or self.typing_in_entry():
            return
        if e.keysym == "Escape":
            self.send("BACK")
        elif e.keysym == "BackSpace":
            self.send("BKSP")
        elif e.keysym in ("Return", "KP_Enter"):
            self.send("ENTER")
        elif e.char and e.char.isprintable() and not (e.state & 0x4):
            self.send("TEXT " + e.char)

    def on_paste(self, e):
        if self.remote is None or self.typing_in_entry():
            return
        try:
            t = self.clipboard_get()
        except Exception:
            return
        if t:
            self.send("TEXT " + t)


def main():
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    App().mainloop()


if __name__ == "__main__":
    main()
