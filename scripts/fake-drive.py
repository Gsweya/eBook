#!/usr/bin/env python3
"""A stand-in for the bits of Google's APIs that GBook sync uses, for testing sync between two desktop copies
without a Google account:

    scripts/fake-drive.py 8765 &
    GBOOK_GOOGLE_API=http://127.0.0.1:8765 GBOOK_GOOGLE_CLIENT_ID=test GBOOK_DATA_DIR=/tmp/a gbook

Files live in memory. Any token is accepted; a data dir is "signed in" when its sync/state.json has
"enabled": true and sync/token holds any text.
"""
import json
import re
import sys
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

files = {}  # id -> {"name", "version", "data"}
sessions = {}  # resumable upload id -> metadata
lock = threading.Lock()


def meta(fid):
    f = files[fid]
    return {"id": fid, "name": f["name"], "version": str(f["version"]), "size": str(len(f["data"]))}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("fake-drive: %s %s\n" % (self.command, self.path.split("?")[0]))

    def send(self, code, body=b"", ctype="application/json", headers=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def route(self, method):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        path = u.path
        if method == "POST" and self.headers.get("X-HTTP-Method-Override"):
            method = self.headers["X-HTTP-Method-Override"]
        with lock:
            if path == "/token":
                self.body()
                return self.send(200, {"access_token": "fake", "expires_in": 3600})
            if path in ("/revoke",):
                self.body()
                return self.send(200, {})
            if path == "/v1/userinfo":
                return self.send(200, {"email": "reader@example.com"})
            if path == "/drive/v3/files" and method == "GET":
                return self.send(200, {"files": [meta(i) for i in files]})
            m = re.fullmatch(r"/drive/v3/files/([^/]+)", path)
            if m and method == "GET":
                fid = m.group(1)
                if fid not in files:
                    return self.send(404, {"error": {"message": "File not found"}})
                return self.send(200, files[fid]["data"], "application/octet-stream")
            if m and method == "DELETE":
                files.pop(m.group(1), None)
                return self.send(204)
            if path == "/upload/drive/v3/files" and method == "POST":
                kind = q.get("uploadType")
                data = self.body()
                if kind == "multipart":
                    boundary = re.search(r"boundary=(\S+)", self.headers["Content-Type"]).group(1).encode()
                    parts = [p for p in data.split(b"--" + boundary) if p.strip() not in (b"", b"--")]
                    md = json.loads(parts[0].split(b"\r\n\r\n", 1)[1].strip())
                    content = parts[1].split(b"\r\n\r\n", 1)[1][:-2]
                    fid = uuid.uuid4().hex[:16]
                    files[fid] = {"name": md["name"], "version": 1, "data": content}
                    return self.send(200, meta(fid))
                if kind == "resumable":
                    sid = uuid.uuid4().hex
                    sessions[sid] = json.loads(data)
                    host = self.headers["Host"]
                    return self.send(200, {}, headers={"Location": f"http://{host}/upload/session/{sid}"})
            m = re.fullmatch(r"/upload/drive/v3/files/([^/]+)", path)
            if m and method == "PATCH":
                fid = m.group(1)
                if fid not in files:
                    return self.send(404, {"error": {"message": "File not found"}})
                files[fid]["data"] = self.body()
                files[fid]["version"] += 1
                return self.send(200, meta(fid))
            m = re.fullmatch(r"/upload/session/([0-9a-f]+)", path)
            if m and method == "PUT":
                md = sessions.pop(m.group(1))
                fid = uuid.uuid4().hex[:16]
                files[fid] = {"name": md["name"], "version": 1, "data": self.body()}
                return self.send(200, meta(fid))
        self.send(404, {"error": {"message": f"No fake for {method} {path}"}})

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_PUT(self):
        self.route("PUT")

    def do_PATCH(self):
        self.route("PATCH")

    def do_DELETE(self):
        self.route("DELETE")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
