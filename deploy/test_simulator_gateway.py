import importlib.util
import pathlib
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request


spec = importlib.util.spec_from_file_location('gateway', pathlib.Path(__file__).with_name('simulator-gateway.py'))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.clock = [1000.0]
        self.state = gateway.AccessState('tester', 'test-password', clock=lambda: self.clock[0], ttl=60)
        self.server = gateway.create_server(('127.0.0.1', 0), self.state, 'https://example.test:8446')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, path, data=None, headers=None):
        request = urllib.request.Request(self.base + path, data=data, headers=headers or {})
        try:
            response = self.opener.open(request, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        self.addCleanup(response.close)
        return response

    def login(self, password='test-password', origin='https://example.test:8446'):
        return self.request('/_gateway/login', urllib.parse.urlencode({'username': 'tester', 'password': password}).encode(), {'Origin': origin, 'Content-Type': 'application/x-www-form-urlencoded'})

    def test_login_cookie_and_authorization(self):
        self.assertEqual(self.request('/_gateway/verify').status, 303)
        self.assertEqual(self.login('wrong').status, 401)
        result = self.login()
        self.assertEqual(result.status, 303)
        cookie = result.headers['Set-Cookie']
        for flag in ('Secure', 'HttpOnly', 'SameSite=Strict', 'Path=/'):
            self.assertIn(flag, cookie)
        self.assertNotIn('test-password', cookie)
        self.assertEqual(self.request('/_gateway/verify', headers={'Cookie': cookie.split(';')[0]}).status, 204)
        self.clock[0] += 61
        self.assertEqual(self.request('/_gateway/verify', headers={'Cookie': cookie.split(';')[0]}).status, 303)

    def test_origin_logout_and_rate_limit(self):
        self.assertEqual(self.login(origin='https://foreign.test').status, 403)
        self.assertEqual(self.login(origin='').status, 403)
        cookie = self.login().headers['Set-Cookie'].split(';')[0]
        result = self.request('/_gateway/logout', b'', {'Origin': 'https://example.test:8446', 'Cookie': cookie})
        self.assertEqual(result.status, 303)
        self.assertEqual(self.request('/_gateway/verify', headers={'Cookie': cookie}).status, 303)
        for _ in range(10):
            self.assertEqual(self.login('wrong').status, 401)
        self.assertEqual(self.login().status, 429)
        self.clock[0] += 301
        self.assertEqual(self.login().status, 303)

    def test_login_page_has_no_http_auth_challenge(self):
        result = self.request('/_gateway/login')
        self.assertEqual(result.status, 200)
        self.assertIsNone(result.headers['WWW-Authenticate'])
        self.assertIn('no-store', result.headers['Cache-Control'])
        self.assertEqual(result.headers['Referrer-Policy'], 'same-origin')
        self.assertIn(b'type="password"', result.read())
        self.assertEqual(self.request('/missing').status, 404)
        self.assertEqual(self.request('/_gateway/login', b'x' * 4097, {'Origin': 'https://example.test:8446'}).status, 413)


if __name__ == '__main__':
    unittest.main()
