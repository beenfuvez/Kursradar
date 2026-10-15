#!/usr/bin/env python3
"""Kursradar v4.1 – lokaler Server mit Live-Daten von Yahoo Finance.

Start:  python3 server.py   →  http://localhost:8765
Nur Python-Standardbibliothek nötig.
"""
import http.cookiejar
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.request
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

VERSION = "4.1"
PORT = int(os.environ.get("PORT", "8765"))
ROOT = os.path.dirname(os.path.abspath(__file__))
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
CACHE_TTL = 20  # Sekunden

_cache = {}
_cache_lock = threading.Lock()
_jar = http.cookiejar.CookieJar()
_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_jar))
_crumb = {"value": None, "ts": 0}


def _get(url, timeout=12):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with _opener.open(req, timeout=timeout) as r:
        return r.read()


def cached(key, fn, ttl=CACHE_TTL):
    now = time.time()
    with _cache_lock:
        hit = _cache.get(key)
        if hit and now - hit[0] < ttl:
            return hit[1]
    val = fn()
    with _cache_lock:
        _cache[key] = (now, val)
    return val


def get_crumb():
    if _crumb["value"] and time.time() - _crumb["ts"] < 3600:
        return _crumb["value"]
    try:
        _get("https://fc.yahoo.com", timeout=8)
    except Exception:
        pass  # liefert 404, setzt aber das Cookie
    crumb = _get("https://query2.finance.yahoo.com/v1/test/getcrumb").decode().strip()
    if not crumb or "<" in crumb:
        raise RuntimeError("Kein Crumb erhalten")
    _crumb.update(value=crumb, ts=time.time())
    return crumb


def chart(symbol, rng, interval):
    q = urllib.parse.urlencode({"range": rng, "interval": interval,
                                "events": "div", "includePrePost": "false"})
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(symbol)}?{q}"
    return json.loads(_get(url))


def search(q):
    qs = urllib.parse.urlencode({"q": q, "quotesCount": 10, "newsCount": 0, "lang": "de-DE", "region": "DE"})
    j = json.loads(_get(f"https://query2.finance.yahoo.com/v1/finance/search?{qs}"))
    keep = ("symbol", "shortname", "longname", "quoteType", "typeDisp", "exchDisp")
    return {"quotes": [{k: x.get(k) for k in keep} for x in j.get("quotes", []) if x.get("symbol")]}


# Nachrichten-Feeds (nur diese festen Adressen werden abgerufen)
FEEDS = {
    "wirtschaft": "https://www.tagesschau.de/wirtschaft/index~rss2.xml",
    "finanzen": "https://www.tagesschau.de/wirtschaft/finanzen/index~rss2.xml",
}


def news(q):
    qs = urllib.parse.urlencode({"q": q, "quotesCount": 0, "newsCount": 8, "lang": "de-DE", "region": "DE"})
    j = json.loads(_get(f"https://query2.finance.yahoo.com/v1/finance/search?{qs}"))
    keep = ("title", "publisher", "link", "providerPublishTime", "relatedTickers")
    return {"news": [{k: x.get(k) for k in keep} for x in j.get("news", []) if x.get("title")]}


def feed(fid):
    return {"xml": _get(FEEDS[fid]).decode("utf-8", "replace")}


def data_dir():
    """Depot-Daten liegen im Benutzerordner, damit sie neue Versionen überleben."""
    d = os.environ.get("KURSRADAR_DATA")
    if not d:
        if os.name == "nt":
            d = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "Kursradar")
        else:
            d = os.path.join(os.path.expanduser("~"), ".kursradar")
    os.makedirs(d, exist_ok=True)
    return d


def portfolio_path():
    return os.path.join(data_dir(), "portfolio.json")


def read_portfolio():
    try:
        with open(portfolio_path(), encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        data = {"positions": []}
        seed = os.path.join(ROOT, "depot-import.json")
        if os.path.exists(seed):  # erster Start: mitgeliefertes Depot übernehmen
            with open(seed, encoding="utf-8") as f:
                data = {"positions": json.load(f).get("positions", [])}
            write_portfolio(data)
    data["path"] = portfolio_path()
    return data


def write_portfolio(data):
    path = portfolio_path()
    if os.path.exists(path):  # vorherigen Stand als Sicherung behalten
        try:
            os.replace(path, path.replace(".json", ".bak.json"))
        except OSError:
            pass
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "saved": time.strftime("%Y-%m-%d %H:%M:%S"),
                   "positions": data["positions"], "journal": data.get("journal") or []},
                  f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
    return path


def quote(symbols):
    crumb = get_crumb()
    q = urllib.parse.urlencode({"symbols": symbols, "crumb": crumb})
    return json.loads(_get(f"https://query2.finance.yahoo.com/v7/finance/quote?{q}"))


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_message(self, fmt, *args):
        if "/api/" not in (args[0] if args else ""):
            super().log_message(fmt, *args)

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _host_ok(self):
        # Schutz gegen fremde Webseiten (DNS-Rebinding): nur localhost zulassen
        host = (self.headers.get("Host") or "").split(":")[0]
        return host in ("localhost", "127.0.0.1")

    def do_PUT(self):
        u = urllib.parse.urlparse(self.path)
        if u.path != "/api/portfolio":
            return self._json(404, {"error": "unbekannt"})
        # Eigener Header erzwingt eine CORS-Vorabprüfung → andere Webseiten können nicht schreiben
        if not self._host_ok() or self.headers.get("X-Kursradar") != "1":
            return self._json(403, {"error": "nicht erlaubt"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
            if n > 2_000_000:
                return self._json(413, {"error": "zu groß"})
            data = json.loads(self.rfile.read(n).decode("utf-8"))
            if not isinstance(data, dict) or not isinstance(data.get("positions"), list):
                return self._json(400, {"error": "positions fehlt"})
            return self._json(200, {"ok": True, "path": write_portfolio(data)})
        except Exception as e:
            return self._json(500, {"error": str(e)})

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path.startswith("/api/") and not self._host_ok():
            return self._json(403, {"error": "nicht erlaubt"})
        p = urllib.parse.parse_qs(u.query)
        arg = lambda k, d="": (p.get(k) or [d])[0]
        try:
            if u.path == "/api/ping":
                return self._json(200, {"ok": True, "source": "Yahoo Finance"})
            if u.path == "/api/chart":
                sym, rng, iv = arg("symbol"), arg("range", "2y"), arg("interval", "1d")
                if not sym:
                    return self._json(400, {"error": "symbol fehlt"})
                return self._json(200, cached(("c", sym, rng, iv), lambda: chart(sym, rng, iv)))
            if u.path == "/api/portfolio":
                return self._json(200, read_portfolio())
            if u.path == "/api/search":
                q = arg("q").strip()[:60]
                return self._json(200, cached(("s", q), lambda: search(q), ttl=300) if q else {"quotes": []})
            if u.path == "/api/news":
                q = arg("q").strip()[:60]
                return self._json(200, cached(("n", q), lambda: news(q), ttl=300) if q else {"news": []})
            if u.path == "/api/feed":
                fid = arg("id")
                if fid not in FEEDS:
                    return self._json(400, {"error": "unbekannter Feed"})
                return self._json(200, cached(("f", fid), lambda: feed(fid), ttl=600))
            if u.path == "/api/quote":
                syms = arg("symbols")
                return self._json(200, cached(("q", syms), lambda: quote(syms), ttl=60))
            if u.path in ("/", "/index.html"):
                with open(os.path.join(ROOT, "index.html"), "rb") as f:
                    body = b"<!doctype html>\n<html lang=\"de\"><head><meta charset=\"utf-8\">" \
                           b"<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">" \
                           b"</head><body>" + f.read() + b"</body></html>"
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                return self.wfile.write(body)
        except Exception as e:  # Upstream-Fehler an den Client weiterreichen
            return self._json(502, {"error": str(e)})
        return super().do_GET()


class Server(ThreadingHTTPServer):
    # Unter Windows würde SO_REUSEADDR einen zweiten Server auf demselben Port zulassen
    allow_reuse_address = os.name != "nt"
    daemon_threads = True


if __name__ == "__main__":
    url = f"http://localhost:{PORT}"
    open_browser = "--no-browser" not in sys.argv
    try:
        srv = Server(("127.0.0.1", PORT), Handler)
    except OSError:
        # Port belegt: Kursradar läuft vermutlich schon → nur den Browser öffnen
        print(f"Port {PORT} ist bereits belegt – Kursradar läuft wohl schon. Öffne {url}")
        if open_browser:
            webbrowser.open(url)
        time.sleep(3)
        sys.exit(0)
    print(f"Kursradar v{VERSION} läuft auf {url}")
    print(f"Depot-Datei: {portfolio_path()}")
    print("Dieses Fenster offen lassen, solange du die App nutzt. Beenden: Fenster schließen oder Strg+C.")
    if open_browser:
        threading.Timer(1.0, webbrowser.open, args=(url,)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)
