"""Loopback-only form authentication for a Caddy-protected QA simulator.

Keep this service behind HTTPS and Caddy's same-origin guard. The credentials
file uses Username: / Password: lines and must be readable only by its owner.
This gateway does not replace the simulator's platform account authorization.
"""
import argparse
import hashlib
import hmac
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import pathlib
import secrets
import threading
import time
import urllib.parse


COOKIE = '__Host-dongying_simulator'
LOGIN_PATH = '/_gateway/login'
PAGE = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>设备模拟器访问验证</title><style>
body{font-family:system-ui,sans-serif;background:#eef2f6;color:#172338;margin:0}
main{max-width:380px;margin:12vh auto;padding:32px;background:white;border-radius:12px}
h1{font-size:24px}label{display:block;margin-top:20px}input,button{box-sizing:border-box;width:100%;padding:12px;margin-top:8px;font:inherit;border:1px solid #bac4d0;border-radius:6px}
button{background:#1768ce;color:white;cursor:pointer;border:0;margin-top:24px}p{line-height:1.6;color:#546276}.error{color:#b3261e}
</style><main><h1>设备模拟器访问验证</h1><p>请输入服务器部署时提供的访问账号。进入后，仍需登录原平台账号。</p>
<p class="error">MESSAGE</p><form method="post" action="/_gateway/login">
<label>访问账号<input name="username" autocomplete="username" required></label>
<label>访问密码<input name="password" type="password" autocomplete="current-password" required></label>
<button type="submit">进入模拟器</button></form></main></html>'''


class AccessState:
    def __init__(self, username, password, clock=time.monotonic, ttl=8 * 3600):
        self.username = hashlib.sha256(username.encode()).digest()
        self.password = hashlib.sha256(password.encode()).digest()
        self.clock, self.ttl = clock, ttl
        self.sessions, self.failures = {}, {}
        self.lock = threading.Lock()

    def login(self, username, password, client):
        with self.lock:
            now = self.clock()
            self.sessions = {token: expiry for token, expiry in self.sessions.items() if expiry > now}
            self.failures = {key: [stamp for stamp in stamps if stamp > now - 300]
                             for key, stamps in self.failures.items() if any(stamp > now - 300 for stamp in stamps)}
            attempts = self.failures.get(client, [])
            if len(attempts) >= 10 or (client not in self.failures and len(self.failures) >= 4096):
                return 429, None
            user_ok = hmac.compare_digest(self.username, hashlib.sha256(username.encode()).digest())
            pass_ok = hmac.compare_digest(self.password, hashlib.sha256(password.encode()).digest())
            if not (user_ok and pass_ok):
                self.failures[client] = attempts + [now]
                return 401, None
            if len(self.sessions) >= 2048:
                return 503, None
            self.failures.pop(client, None)
            token = secrets.token_urlsafe(32)
            self.sessions[token] = now + self.ttl
            return 303, token

    def valid(self, token):
        with self.lock:
            return self.sessions.get(token, 0) > self.clock()

    def logout(self, token):
        with self.lock:
            self.sessions.pop(token, None)


def create_server(address, state, origin):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            # Never log submitted credentials, cookies, or query strings.
            pass

        def respond(self, status, body=b'', **headers):
            self.send_response(status)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            # Same-origin form POSTs must retain their Origin; no-referrer makes
            # Chromium navigation requests send Origin: null.
            self.send_header('Referrer-Policy', 'same-origin')
            self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
            for name, value in headers.items():
                self.send_header(name.replace('_', '-'), value)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def token(self):
            cookies = SimpleCookie()
            try:
                cookies.load(self.headers.get('Cookie', ''))
                return cookies[COOKIE].value if COOKIE in cookies else ''
            except Exception:
                return ''

        def do_GET(self):
            path = urllib.parse.urlsplit(self.path).path
            if path == LOGIN_PATH:
                self.respond(200, PAGE.replace('MESSAGE', '').encode())
            elif path == '/_gateway/verify':
                if state.valid(self.token()):
                    self.respond(204)
                else:
                    self.respond(303, Location=LOGIN_PATH)
            else:
                self.respond(404)

        def do_POST(self):
            if self.headers.get('Origin') != origin:
                self.respond(403)
                return
            path = urllib.parse.urlsplit(self.path).path
            if path == '/_gateway/logout':
                state.logout(self.token())
                self.respond(303, Location=LOGIN_PATH, Set_Cookie=f'{COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0')
                return
            if path != LOGIN_PATH:
                self.respond(404)
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
            except ValueError:
                self.respond(400)
                return
            if length < 0 or length > 4096:
                self.respond(413)
                return
            if self.headers.get_content_type() != 'application/x-www-form-urlencoded':
                self.respond(415)
                return
            self.connection.settimeout(10)
            try:
                values = urllib.parse.parse_qs(self.rfile.read(length).decode('utf-8'), max_num_fields=8)
            except (ValueError, UnicodeError, TimeoutError):
                self.respond(400)
                return
            client = self.headers.get('X-Gateway-Client', self.client_address[0])
            status, token = state.login(values.get('username', [''])[0], values.get('password', [''])[0], client)
            if token:
                self.respond(303, Location='/', Set_Cookie=f'{COOKIE}={token}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age={state.ttl}')
            else:
                message = '尝试次数过多，请五分钟后再试。' if status == 429 else '访问账号或密码不正确。'
                self.respond(status, PAGE.replace('MESSAGE', message).encode())

    return ThreadingHTTPServer(address, Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials', required=True)
    parser.add_argument('--origin', required=True)
    parser.add_argument('--port', type=int, default=8770)
    args = parser.parse_args()
    origin = urllib.parse.urlsplit(args.origin)
    if origin.scheme != 'https' or not origin.netloc or origin.path or origin.query or origin.fragment or origin.username:
        parser.error('--origin must be a plain HTTPS origin')
    values = dict(line.split(': ', 1) for line in pathlib.Path(args.credentials).read_text().splitlines() if ': ' in line)
    state = AccessState(values['Username'], values['Password'])
    create_server(('127.0.0.1', args.port), state, args.origin).serve_forever()


if __name__ == '__main__':
    main()
