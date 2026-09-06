"""Helpers and the HTTP contract of the Trade Republic auth sidecar.

Everything that touches Trade Republic itself is stubbed: the WAF token
fetch (headless Chromium) is replaced by a coroutine and httpx is pointed at
a MockTransport, so these tests run in the CI image without a browser or
network access. What is covered is everything that is ours: phone
normalisation and masking, the Set-Cookie fallback parsing, refresh-token
rotation, and the request validation that keeps `processId`/`tan` out of the
upstream path.
"""

import base64
import json
import unittest
from unittest import mock

import httpx
from fastapi.testclient import TestClient

import main
from main import (
    app,
    cookie_names,
    cookie_value,
    generate_device_info,
    mask_phone,
    normalise_phone,
    tr_headers,
)


class PhoneHelpersTest(unittest.TestCase):
    def test_french_national_prefix_becomes_e164(self):
        self.assertEqual(normalise_phone("0612345678"), "+33612345678")

    def test_international_numbers_are_kept_and_trimmed(self):
        self.assertEqual(normalise_phone("  +4915112345678 "), "+4915112345678")

    def test_mask_keeps_only_prefix_and_last_two_digits(self):
        self.assertEqual(mask_phone("+33612345678"), "+33****78")

    def test_mask_hides_short_numbers_entirely(self):
        for short in ("", "12", "123456"):
            self.assertEqual(mask_phone(short), "****", short)


class HeaderHelpersTest(unittest.TestCase):
    def test_device_info_is_base64_json_with_a_stable_device_id(self):
        decoded = json.loads(base64.b64decode(generate_device_info()))
        self.assertEqual(list(decoded), ["stableDeviceId"])
        self.assertEqual(len(decoded["stableDeviceId"]), 128)  # sha512 hex

    def test_waf_token_header_is_only_sent_when_a_token_exists(self):
        self.assertNotIn("x-aws-waf-token", tr_headers(None))
        self.assertEqual(tr_headers("tok")["x-aws-waf-token"], "tok")

    def test_headers_identify_the_web_platform(self):
        headers = tr_headers(None)
        self.assertEqual(headers["x-tr-platform"], "web")
        self.assertEqual(headers["Origin"], main.TR_APP)

    def test_cookie_names_lists_every_set_cookie_header(self):
        headers = httpx.Headers([
            ("set-cookie", "tr_session=abc; Path=/; Secure; HttpOnly"),
            ("set-cookie", "tr_refresh=def; Path=/"),
            ("set-cookie", "malformed"),
        ])
        self.assertEqual(cookie_names(headers), ["tr_session", "tr_refresh"])


class CookieValueTest(unittest.TestCase):
    """`cookie_value` is the hand-rolled fallback behind httpx's cookie jar."""

    def response(self, *set_cookie: str) -> httpx.Response:
        return httpx.Response(
            200,
            headers=[("set-cookie", value) for value in set_cookie],
            request=httpx.Request("POST", "https://api.traderepublic.com/api/v1/auth/web/login/p/1234"),
        )

    def test_value_comes_from_the_parsed_jar_when_httpx_kept_it(self):
        self.assertEqual(cookie_value(self.response("tr_session=abc; Path=/"), "tr_session"), "abc")

    def test_value_falls_back_to_the_raw_header_when_the_jar_dropped_it(self):
        # A Domain attribute that does not match the request host makes httpx's jar
        # discard the cookie; TR's session must still be read, whatever its case.
        resp = self.response("TR_SESSION=abc; Domain=other.example; Secure")
        self.assertIsNone(resp.cookies.get("tr_session"))
        self.assertEqual(cookie_value(resp, "tr_session"), "abc")

    def test_an_attribute_that_starts_with_the_name_is_not_a_value(self):
        self.assertIsNone(cookie_value(self.response("other=1; tr_session=not-a-cookie"), "tr_session"))

    def test_missing_cookie_is_none(self):
        self.assertIsNone(cookie_value(self.response(), "tr_session"))


def _no_waf_token():
    async def stub():
        return None
    return stub


# Captured before any patch: `main.httpx` is this very module, so patching
# `main.httpx.AsyncClient` also replaces the name the mock itself constructs.
_RealAsyncClient = httpx.AsyncClient


class _MockedTr:
    """Route httpx.AsyncClient calls made by main to a MockTransport."""

    def __init__(self, handler):
        self.handler = handler
        self.requests: list[httpx.Request] = []

    def __call__(self, **kwargs):
        def record(request):
            self.requests.append(request)
            return self.handler(request)
        kwargs.pop("transport", None)
        return _RealAsyncClient(transport=httpx.MockTransport(record), **kwargs)


class HttpContractTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_health(self):
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})

    def test_initiate_returns_process_id(self):
        def handler(request):
            self.assertEqual(request.url.path, "/api/v1/auth/web/login")
            self.assertEqual(json.loads(request.content)["phoneNumber"], "+33612345678")
            return httpx.Response(200, json={"processId": "p-1"})

        mocked = _MockedTr(handler)
        with mock.patch.object(main, "get_waf_token", _no_waf_token()), \
                mock.patch.object(main.httpx, "AsyncClient", mocked):
            response = self.client.post("/initiate", json={"phoneNumber": "0612345678", "pin": "1234"})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"processId": "p-1"})

    def test_initiate_relays_trade_republic_rejections(self):
        mocked = _MockedTr(lambda request: httpx.Response(401, text="bad pin"))
        with mock.patch.object(main, "get_waf_token", _no_waf_token()), \
                mock.patch.object(main.httpx, "AsyncClient", mocked):
            response = self.client.post("/initiate", json={"phoneNumber": "0612345678", "pin": "0000"})

        self.assertEqual(response.status_code, 401)
        self.assertIn("bad pin", response.json()["detail"])

    def test_complete_extracts_session_and_refresh_cookies(self):
        def handler(request):
            self.assertEqual(request.url.path, "/api/v1/auth/web/login/p-1/123456")
            return httpx.Response(200, headers=[
                ("set-cookie", "tr_session=sess-token; Path=/; Secure; HttpOnly"),
                ("set-cookie", "tr_refresh=refresh-token; Path=/; Secure; HttpOnly"),
            ])

        mocked = _MockedTr(handler)
        with mock.patch.object(main, "get_waf_token", _no_waf_token()), \
                mock.patch.object(main.httpx, "AsyncClient", mocked):
            response = self.client.post("/complete", json={"processId": "p-1", "tan": "123456"})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(),
                         {"sessionToken": "sess-token", "refreshToken": "refresh-token"})

    def test_complete_reads_the_session_from_the_raw_header_when_the_jar_drops_it(self):
        mocked = _MockedTr(lambda request: httpx.Response(200, headers=[
            ("set-cookie", "tr_session=sess-token; Domain=other.example; Secure"),
        ]))
        with mock.patch.object(main, "get_waf_token", _no_waf_token()), \
                mock.patch.object(main.httpx, "AsyncClient", mocked):
            response = self.client.post("/complete", json={"processId": "p-1", "tan": "1234"})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"sessionToken": "sess-token", "refreshToken": None})

    def test_complete_without_session_cookie_is_a_bad_gateway(self):
        mocked = _MockedTr(lambda request: httpx.Response(200, json={}))
        with mock.patch.object(main, "get_waf_token", _no_waf_token()), \
                mock.patch.object(main.httpx, "AsyncClient", mocked):
            response = self.client.post("/complete", json={"processId": "p-2", "tan": "000000"})

        self.assertEqual(response.status_code, 502)
        self.assertIn("tr_session", response.json()["detail"])

    def test_complete_relays_trade_republic_status_and_error_body(self):
        mocked = _MockedTr(lambda request: httpx.Response(
            401, json={"errors": [{"errorCode": "VALIDATION_CODE_INVALID"}]}))
        with mock.patch.object(main, "get_waf_token", _no_waf_token()), \
                mock.patch.object(main.httpx, "AsyncClient", mocked):
            response = self.client.post("/complete", json={"processId": "p-1", "tan": "0000"})

        self.assertEqual(response.status_code, 401)
        self.assertIn("VALIDATION_CODE_INVALID", response.json()["detail"])

    def test_refresh_falls_back_to_the_submitted_refresh_token(self):
        def handler(request):
            self.assertEqual(request.url.path, "/api/v1/auth/web/refresh")
            self.assertIn("tr_refresh=old", request.headers.get("cookie", ""))
            return httpx.Response(200, headers=[("set-cookie", "tr_session=new-sess; Path=/")])

        mocked = _MockedTr(handler)
        with mock.patch.object(main.httpx, "AsyncClient", mocked):
            response = self.client.post("/refresh", json={"refreshToken": "old"})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"sessionToken": "new-sess", "refreshToken": "old"})

    def test_refresh_returns_a_rotated_refresh_token(self):
        mocked = _MockedTr(lambda request: httpx.Response(200, headers=[
            ("set-cookie", "tr_session=new-sess; Path=/"),
            ("set-cookie", "tr_refresh=new-ref; Path=/"),
        ]))
        with mock.patch.object(main.httpx, "AsyncClient", mocked):
            response = self.client.post("/refresh", json={"refreshToken": "old"})

        self.assertEqual(response.json(), {"sessionToken": "new-sess", "refreshToken": "new-ref"})

    def test_refresh_relays_a_rejection_and_needs_a_session_cookie(self):
        rejected = _MockedTr(lambda request: httpx.Response(401, text="expired"))
        with mock.patch.object(main.httpx, "AsyncClient", rejected):
            self.assertEqual(self.client.post("/refresh", json={"refreshToken": "old"}).status_code, 401)

        empty = _MockedTr(lambda request: httpx.Response(200))
        with mock.patch.object(main.httpx, "AsyncClient", empty):
            self.assertEqual(self.client.post("/refresh", json={"refreshToken": "old"}).status_code, 502)


class RequestValidationTest(unittest.TestCase):
    """`processId` and `tan` are interpolated into the TR path: bound them here."""

    def setUp(self):
        self.client = TestClient(app)
        self.mocked = _MockedTr(lambda request: httpx.Response(200))

    def post(self, path, body):
        with mock.patch.object(main, "get_waf_token", _no_waf_token()), \
                mock.patch.object(main.httpx, "AsyncClient", self.mocked):
            return self.client.post(path, json=body)

    def test_a_malformed_tan_never_reaches_trade_republic(self):
        response = self.post("/complete", {"processId": "p-1", "tan": "1234?x=1#"})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "VALIDATION_CODE_INVALID")
        self.assertEqual(self.mocked.requests, [])

    def test_a_process_id_with_path_characters_never_reaches_trade_republic(self):
        response = self.post("/complete", {"processId": "../v1/other", "tan": "1234"})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")
        self.assertEqual(self.mocked.requests, [])

    def test_unexpected_fields_are_refused(self):
        response = self.post("/initiate", {"phoneNumber": "0612345678", "pin": "1234", "x": 1})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")
        self.assertEqual(self.mocked.requests, [])

    def test_an_empty_phone_number_is_refused_before_the_browser_starts(self):
        browser = mock.AsyncMock(return_value=None)
        with mock.patch.object(main, "get_waf_token", browser), \
                mock.patch.object(main.httpx, "AsyncClient", self.mocked):
            response = self.client.post("/initiate", json={"phoneNumber": "", "pin": "1234"})

        self.assertEqual(response.status_code, 400)
        browser.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
