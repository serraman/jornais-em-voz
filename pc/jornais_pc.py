"""
Jornais em Voz - PC

Lê jornais em PDF com o Gemini e cria um ficheiro ".jornal.json" por jornal,
para enviar para o telemóvel (por exemplo pelo WhatsApp) e abrir na app
Jornais em Voz, que prepara o áudio para o Android Auto.
"""
import base64
import concurrent.futures as cf
import datetime as dt
import hashlib
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request

import pymupdf as fitz  # PyMuPDF

APP_NAME = "Jornais em Voz PC"
DEFAULT_MODEL = "gemini-3.5-flash-lite"
BASE = "https://generativelanguage.googleapis.com/v1beta"
PARALLEL = 3
FALLBACK_PREFS = [
    "gemini-3.6-flash", "gemini-flash-lite-latest", "gemini-3.5-flash-lite",
    "gemini-flash-latest", "gemini-2.5-flash-lite", "gemini-3.8-flash",
]


# ---------------------------------------------------------------- pastas e definições

def app_dir():
    base = os.environ.get("APPDATA") or os.path.expanduser("~/.config")
    d = os.path.join(base, "JornaisEmVoz")
    os.makedirs(d, exist_ok=True)
    return d


def cache_dir():
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~/.cache")
    d = os.path.join(base, "JornaisEmVoz", "paginas")
    os.makedirs(d, exist_ok=True)
    return d


def downloads_dir():
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                                r"Software\Microsoft\Windows\CurrentVersion\Explorer\Shell Folders") as k:
                return winreg.QueryValueEx(k, "{374DE290-123F-4565-9164-39C4925E467B}")[0]
        except Exception:
            pass
    return os.path.join(os.path.expanduser("~"), "Downloads")


def default_out_dir():
    return os.path.join(downloads_dir(), "Jornais em Voz")


def load_config():
    try:
        with open(os.path.join(app_dir(), "config.json"), encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:
        cfg = {}
    cfg.setdefault("apiKey", "")
    cfg.setdefault("model", DEFAULT_MODEL)
    cfg.setdefault("outDir", default_out_dir())
    return cfg


def save_config(cfg):
    with open(os.path.join(app_dir(), "config.json"), "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


# ---------------------------------------------------------------- nome do jornal

def name_from_file(name):
    """'(20261117-PT) Público_LX_261117_101020.pdf' -> 'Público'"""
    base = os.path.splitext(os.path.basename(name))[0]
    base = re.sub(r"\([^)]*\)", " ", base)
    base = re.sub(r"^[_\s-]*\d{8}-[A-Za-z]{2}_*", " ", base)
    words = []
    for t in [t for t in re.split(r"[_\s]+", base) if t.strip()]:
        is_code = any(c.isdigit() for c in t) or (len(t) <= 3 and t == t.upper() and any(c.isalpha() for c in t))
        if is_code:
            if words:
                break
            continue
        words.append(t)
    return " ".join(words).replace("-", " ").strip() or "Jornal"


# ---------------------------------------------------------------- Gemini

class FatalError(Exception):
    """Erro que não vale a pena repetir (chave inválida, modelo inexistente, limite diário)."""


class BadRequest(Exception):
    pass


PROMPT = """Esta imagem é a página {page} de {total} de um jornal português.
Extrai TODAS as notícias e artigos de texto desta página. Transcreve o texto INTEGRAL de cada artigo, palavra a palavra, sem resumir nem cortar. Junta as palavras partidas por hífen no fim da linha. Segue a ordem de leitura correcta das colunas.
Ignora: publicidade, cabeçalhos e rodapés da página, legendas de fotografias, assinaturas de autores, programação de TV, meteorologia, passatempos, tabelas de resultados e índices.
Uma caixa lateral com título próprio é um artigo separado.
Para cada artigo devolve:
- "tipo": "noticia", "opiniao", "breve" ou "chamada" (chamada = destaque de primeira página ou índice que remete para outra página do jornal);
- "seccao": a secção do jornal (ex.: Sociedade, Política, Desporto), se estiver visível, senão "";
- "titulo": o título do artigo ("" se o texto é continuação da página anterior e não tem título);
- "entrada": o subtítulo ou parágrafo de entrada em destaque, se existir, senão "";
- "texto": o corpo integral do artigo (sem repetir o título nem a entrada);
- "continua_da_anterior": true se o texto começa a meio de uma frase, continuando um artigo da página anterior;
- "continua_na_seguinte": true se o texto acaba a meio e continua noutra página.
Indica também "nome_jornal" se o nome do jornal aparecer na página, senão "".
Responde só com JSON neste formato:
{{"nome_jornal":"","artigos":[{{"tipo":"noticia","seccao":"","titulo":"","entrada":"","texto":"","continua_da_anterior":false,"continua_na_seguinte":false}}]}}"""


def schema():
    s = {"type": "STRING"}
    b = {"type": "BOOLEAN"}
    art = {
        "type": "OBJECT",
        "properties": {
            "tipo": {"type": "STRING", "enum": ["noticia", "opiniao", "breve", "chamada"]},
            "seccao": s, "titulo": s, "entrada": s, "texto": s,
            "continua_da_anterior": b, "continua_na_seguinte": b,
        },
        "required": ["tipo", "titulo", "texto"],
    }
    return {
        "type": "OBJECT",
        "properties": {"nome_jornal": s, "artigos": {"type": "ARRAY", "items": art}},
        "required": ["artigos"],
    }


def parse_result(raw):
    t = raw.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1].rsplit("```", 1)[0].strip()
    a, b = t.find("{"), t.rfind("}")
    if a < 0 or b <= a:
        raise ValueError("Resposta do Gemini sem JSON")
    return json.loads(t[a:b + 1])


class Gemini:
    def __init__(self, key, model, on_status=None):
        self.key = key
        self.model = model
        self.on_status = on_status or (lambda m: None)
        self.use_thinking = True
        self.fallback = None

    def _open(self, url, data=None):
        req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
        req.add_header("x-goog-api-key", self.key)
        if data:
            req.add_header("Content-Type", "application/json; charset=utf-8")
        return urllib.request.urlopen(req, timeout=180)

    def list_models(self):
        try:
            with self._open(BASE + "/models?pageSize=200") as r:
                data = json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise FatalError(self._describe(e.code, e.read().decode("utf-8", "replace")))
        out = []
        for m in data.get("models", []):
            if "generateContent" in m.get("supportedGenerationMethods", []):
                out.append(m.get("name", "").replace("models/", ""))
        return sorted(out)

    def pick_fallback(self):
        try:
            models = self.list_models()
        except Exception:
            return
        for m in FALLBACK_PREFS:
            if m != self.model and m in models:
                self.fallback = m
                return
        for m in models:
            if m != self.model and "flash" in m and not any(x in m for x in ("image", "tts", "live", "audio")):
                self.fallback = m
                return

    def _describe(self, code, text):
        if code in (401, 403):
            return "A chave da API Gemini não é válida ou não tem permissão."
        if code == 404:
            return f"O modelo '{self.model}' não existe. Escolha outro nas Definições."
        return f"Erro {code} do Gemini: {self._short(text)}"

    @staticmethod
    def _short(text):
        try:
            return json.loads(text)["error"]["message"][:300]
        except Exception:
            return text[:300]

    def _post(self, body, label):
        last = "erro desconhecido"
        busy = timeouts = 0
        for attempt in range(8):
            use = self.fallback if (busy >= 2 and self.fallback and busy % 2 == 0) else self.model
            url = f"{BASE}/models/{use}:generateContent"
            try:
                with self._open(url, json.dumps(body).encode("utf-8")) as r:
                    return json.loads(r.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                text = e.read().decode("utf-8", "replace")
                code = e.code
                if code == 429:
                    if "perday" in text.lower() or "per day" in text.lower():
                        raise FatalError(f"Atingiu o limite diário gratuito do modelo {self.model}. "
                                         "Tente amanhã ou escolha outro modelo.")
                    m = re.search(r'"retryDelay"\s*:\s*"(\d+)', text)
                    secs = int(m.group(1)) if m else 30 * (attempt + 1)
                    self.on_status(f"{label}: limite por minuto do Gemini, a aguardar {secs + 2} s")
                    time.sleep(secs + 2)
                    last = "limite por minuto"
                elif code >= 500:
                    busy += 1
                    nxt = f" com {self.fallback}" if (busy >= 2 and self.fallback and busy % 2 == 0) else ""
                    self.on_status(f"{label}: Gemini ocupado ({code}), nova tentativa{nxt}")
                    time.sleep(2 if nxt else 8 * min(busy, 4))
                    last = f"serviço do Gemini indisponível ({code})"
                elif code == 400:
                    if "api key" in text.lower() or "api_key" in text.lower():
                        raise FatalError("A chave da API Gemini não é válida.")
                    raise BadRequest(self._short(text))
                else:
                    raise FatalError(self._describe(code, text))
            except (TimeoutError, OSError) as e:
                if "timed out" in str(e).lower() or isinstance(e, TimeoutError):
                    timeouts += 1
                    self.on_status(f"{label}: o Gemini demorou demasiado, nova tentativa")
                    last = "o Gemini não respondeu em 3 minutos"
                    if timeouts >= 2:
                        break
                else:
                    last = str(e)
                    self.on_status(f"{label}: erro de rede ({e}), nova tentativa")
                    time.sleep(5 * (attempt + 1))
        raise IOError(f"Falhou após várias tentativas: {last}")

    def _generate(self, parts, sch, label):
        cfg = {"responseMimeType": "application/json", "maxOutputTokens": 32768}
        if sch:
            cfg["responseSchema"] = sch
        if self.use_thinking:
            cfg["thinkingConfig"] = {"thinkingLevel": "low"}
        body = {"contents": [{"role": "user", "parts": parts}], "generationConfig": cfg}
        resp = self._post(body, label)
        cands = resp.get("candidates") or []
        if not cands:
            raise IOError("Resposta vazia do Gemini")
        out = cands[0].get("content", {}).get("parts", [])
        return "".join(p.get("text", "") for p in out if not p.get("thought"))

    def analyze_page(self, jpeg, page, total):
        parts = [
            {"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(jpeg).decode("ascii")}},
            {"text": PROMPT.format(page=page, total=total)},
        ]
        label = f"pág. {page}"
        try:
            try:
                raw = self._generate(parts, schema(), label)
            except BadRequest as e:
                if self.use_thinking and "think" in str(e).lower():
                    self.use_thinking = False
                    raw = self._generate(parts, schema(), label)
                else:
                    raise
        except BadRequest:
            try:
                raw = self._generate(parts, None, label)
            except BadRequest as e2:
                raise FatalError(f"O Gemini recusou o pedido: {e2}")
        parse_result(raw)  # valida
        return raw


# ---------------------------------------------------------------- PDF

def render_page(doc, i, width=1800):
    page = doc[i]
    zoom = width / page.rect.width
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
    try:
        return pix.tobytes("jpg", jpg_quality=85)
    except TypeError:
        return pix.tobytes("jpg")


def file_hash(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def merge_pages(raw_pages):
    """raw_pages: lista (por página) de texto JSON do Gemini ou None. Devolve (artigos, nome, lidas)."""
    merged = []
    pending = None
    paper_name = ""
    read = 0
    for i, raw in enumerate(raw_pages):
        try:
            res = parse_result(raw) if raw else None
        except Exception:
            res = None
        if res is None:
            pending = None
            continue
        read += 1
        if not paper_name and (res.get("nome_jornal") or "").strip():
            paper_name = res["nome_jornal"].strip()
        for a in res.get("artigos", []) or []:
            tipo = a.get("tipo", "noticia")
            titulo = (a.get("titulo") or "").strip()
            texto = (a.get("texto") or "").strip()
            cont_prev = bool(a.get("continua_da_anterior"))
            cont_next = bool(a.get("continua_na_seguinte"))
            if tipo == "chamada":
                continue
            if cont_prev and merged and (not titulo or pending is not None):
                target = pending or merged[-1]
                target["text"] = target["text"].rstrip() + "\n" + texto
                if not cont_next:
                    pending = None
                continue
            if len(texto) < 80:
                continue
            if not titulo:
                titulo = " ".join(texto.split()[:10]) + "…"
            art = {
                "page": i + 1,
                "section": (a.get("seccao") or "").strip(),
                "title": titulo,
                "lead": (a.get("entrada") or "").strip(),
                "text": texto,
            }
            merged.append(art)
            pending = art if cont_next else None
    return merged, paper_name, read


def safe_filename(s):
    return re.sub(r'[\\/:*?"<>|]+', "", s).strip() or "Jornal"


class Job:
    """Processa um PDF. Pode ser parado; as páginas já lidas ficam guardadas (cache)."""

    def __init__(self, pdf_path, cfg, on_update):
        self.pdf = pdf_path
        self.cfg = cfg
        self.on_update = on_update  # on_update(job)
        self.name = name_from_file(pdf_path)
        self.status = "Na fila"
        self.total = 0
        self.done = 0
        self.stop = threading.Event()
        self.output = None
        self.error = None
        self.finished = False

    def set(self, status):
        self.status = status
        self.on_update(self)

    def run(self, gemini_factory=None):
        try:
            self._run(gemini_factory)
        except FatalError as e:
            self.error = str(e)
            self.set("Erro: " + str(e))
        except Exception as e:
            self.error = str(e)
            self.set("Erro: " + str(e))
        finally:
            self.finished = True
            self.on_update(self)

    def _run(self, gemini_factory):
        cfg = self.cfg
        if not cfg.get("apiKey"):
            raise FatalError("Falta a chave da API Gemini (botão Definições).")
        self.set("A abrir o PDF…")
        doc = fitz.open(self.pdf)
        n = doc.page_count
        self.total = n
        cdir = os.path.join(cache_dir(), file_hash(self.pdf))
        os.makedirs(cdir, exist_ok=True)

        def cache_file(i):
            return os.path.join(cdir, f"{i}.json")

        todo = [i for i in range(n) if not os.path.exists(cache_file(i))]
        self.done = n - len(todo)
        g = (gemini_factory or (lambda: Gemini(cfg["apiKey"], cfg.get("model") or DEFAULT_MODEL)))()
        g.on_status = lambda m: self.set(m)
        if todo:
            self.set("A preparar o Gemini…")
            g.pick_fallback()

        fatal = []
        lock = threading.Lock()
        pace = {"t": 0.0}
        render_lock = threading.Lock()

        def work(i):
            if self.stop.is_set() or fatal:
                return
            with render_lock:
                jpeg = render_page(doc, i)
            with lock:
                wait = 2.5 - (time.time() - pace["t"])
                if wait > 0:
                    time.sleep(wait)
                pace["t"] = time.time()
            if self.stop.is_set() or fatal:
                return
            try:
                raw = g.analyze_page(jpeg, i + 1, n)
            except FatalError as e:
                fatal.append(e)
                return
            except Exception:
                return
            with open(cache_file(i), "w", encoding="utf-8") as f:
                f.write(raw)
            with lock:
                self.done += 1
            self.set(f"A ler páginas com o Gemini ({self.done} de {n})")

        if todo:
            self.set(f"A ler páginas com o Gemini ({self.done} de {n})")
            ex = cf.ThreadPoolExecutor(max_workers=PARALLEL)
            futures = [ex.submit(work, i) for i in todo]
            while not all(f.done() for f in futures):
                if self.stop.is_set():
                    break
                time.sleep(0.5)
            ex.shutdown(wait=False, cancel_futures=True)
        doc.close() if not todo or all(f.done() for f in futures) else None
        if fatal and not self.stop.is_set():
            raise fatal[0]

        raws = []
        for i in range(n):
            p = cache_file(i)
            raws.append(open(p, encoding="utf-8").read() if os.path.exists(p) else None)
        arts, gname, read = merge_pages(raws)
        if not arts:
            raise FatalError("Não foram encontradas notícias" + (" (parado antes de ler páginas)." if self.stop.is_set() else "."))

        today = dt.date.today()
        package = {
            "formato": "jornais-em-voz",
            "versao": 1,
            "nome": self.name if self.name != "Jornal" else (gname or "Jornal"),
            "data": today.isoformat(),
            "criado": int(time.time() * 1000),
            "paginas_lidas": read,
            "total_paginas": n,
            "artigos": arts,
        }
        out_dir = cfg.get("outDir") or default_out_dir()
        os.makedirs(out_dir, exist_ok=True)
        fname = f"{safe_filename(package['nome'])} {today.strftime('%d-%m-%Y')}.jornal.json"
        self.output = os.path.join(out_dir, fname)
        with open(self.output, "w", encoding="utf-8") as f:
            json.dump(package, f, ensure_ascii=False)
        extra = f" ({read} de {n} páginas lidas)" if read < n else ""
        self.set(f"Pronto: {len(arts)} notícias{extra}")


# ---------------------------------------------------------------- interface

def run_gui():
    import tkinter as tk
    from tkinter import filedialog, messagebox, simpledialog, ttk

    cfg = load_config()
    root = tk.Tk()
    root.title(APP_NAME)
    root.geometry("760x480")
    try:
        root.tk.call("tk", "scaling", 1.3)
    except Exception:
        pass

    jobs = []
    queue = []
    worker = {"t": None}

    top = ttk.Frame(root, padding=10)
    top.pack(fill="x")
    ttk.Button(top, text="Escolher jornais (PDF)…", command=lambda: choose()).pack(side="left")
    ttk.Button(top, text="Parar e guardar o que já leu", command=lambda: stop_current()).pack(side="left", padx=6)
    ttk.Button(top, text="Abrir pasta dos ficheiros prontos", command=lambda: open_out()).pack(side="left")
    ttk.Button(top, text="Definições", command=lambda: settings()).pack(side="right")

    info = ttk.Label(root, padding=(10, 0), wraplength=730, justify="left", text=(
        "1. Descarregue os PDFs no WhatsApp do PC e escolha-os aqui.\n"
        "2. Quando estiverem prontos, envie os ficheiros \".jornal.json\" para si próprio no WhatsApp.\n"
        "3. No telemóvel, toque no ficheiro no WhatsApp e escolha \"Jornais em Voz\"."))
    info.pack(fill="x")

    cols = ("jornal", "estado")
    tree = ttk.Treeview(root, columns=cols, show="headings", height=12)
    tree.heading("jornal", text="Jornal")
    tree.heading("estado", text="Estado")
    tree.column("jornal", width=200)
    tree.column("estado", width=520)
    tree.pack(fill="both", expand=True, padx=10, pady=10)

    def refresh(job):
        def ui():
            iid = str(id(job))
            vals = (job.name, job.status)
            if tree.exists(iid):
                tree.item(iid, values=vals)
            else:
                tree.insert("", "end", iid=iid, values=vals)
        root.after(0, ui)

    def loop():
        while queue:
            job = queue.pop(0)
            job.run()
            if job.output:
                root.after(0, lambda j=job: on_done(j))
        worker["t"] = None

    def on_done(job):
        if not any(not j.finished for j in jobs):
            if messagebox.askyesno(APP_NAME, "Jornais prontos. Abrir a pasta para os enviar pelo WhatsApp?"):
                open_out(job.output)

    def choose():
        if not cfg.get("apiKey"):
            settings()
            if not cfg.get("apiKey"):
                return
        files = filedialog.askopenfilenames(
            title="Escolher jornais", initialdir=downloads_dir(),
            filetypes=[("Jornais em PDF", "*.pdf")])
        for f in files:
            job = Job(f, cfg, refresh)
            jobs.append(job)
            queue.append(job)
            refresh(job)
        if queue and worker["t"] is None:
            worker["t"] = threading.Thread(target=loop, daemon=True)
            worker["t"].start()

    def stop_current():
        for j in jobs:
            if not j.finished and j.status != "Na fila":
                j.stop.set()
                j.set("A parar e a guardar as páginas já lidas…")
                return
        messagebox.showinfo(APP_NAME, "Não há nenhum jornal a ser lido.")

    def open_out(select=None):
        d = cfg.get("outDir") or default_out_dir()
        os.makedirs(d, exist_ok=True)
        if sys.platform == "win32":
            import subprocess
            if select and os.path.exists(select):
                subprocess.Popen(["explorer", "/select,", os.path.normpath(select)])
            else:
                os.startfile(d)

    def settings():
        key = simpledialog.askstring(APP_NAME, "Chave da API Gemini (começa por AIza…):",
                                     initialvalue=cfg.get("apiKey", ""), parent=root)
        if key is None:
            return
        cfg["apiKey"] = key.strip()
        model = simpledialog.askstring(APP_NAME, "Modelo Gemini:",
                                       initialvalue=cfg.get("model") or DEFAULT_MODEL, parent=root)
        if model:
            cfg["model"] = model.strip()
        save_config(cfg)
        if cfg["apiKey"]:
            try:
                models = Gemini(cfg["apiKey"], cfg["model"]).list_models()
                ok = cfg["model"] in models
                messagebox.showinfo(APP_NAME, "Chave válida." + (
                    "" if ok else f"\n\nAtenção: o modelo {cfg['model']} não está disponível. "
                                  "Modelos 'flash' disponíveis:\n" +
                                  "\n".join(m for m in models if "flash" in m)))
            except Exception as e:
                messagebox.showerror(APP_NAME, f"Não foi possível validar a chave:\n{e}")

    if os.environ.get("JEV_SMOKE"):
        root.after(2000, root.destroy)  # teste automático da janela
    root.mainloop()


if __name__ == "__main__":
    run_gui()
