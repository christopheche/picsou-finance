"""HTTP contract, pending-credential lifecycle and log hygiene of the DEGIRO sidecar.

The pending entry holds a plaintext password and Java trusts /portfolio's payload
outright, so these are the two places a quiet bug costs the most: a password that
outlives its TTL, or a reshaped DEGIRO response that comes back as an empty
portfolio and wipes the last good holdings.
"""

import json
import time
import unittest
from unittest.mock import patch

import httpx
from fastapi import HTTPException
from fastapi.testclient import TestClient

import main
from main import (
    MAX_PENDING,
    _PENDING_TTL,
    _clean_pending,
    _is_expired,
    _pending,
    _redact,
    _safe_body,
    _store_pending,
    app,
)


def pairs(**fields):
    return [{"name": name, "value": value} for name, value in fields.items()]


def update_response(**blocks):
    """A /trading/secure/v5/update body: one {"value": rows} block per requested key."""
    return {key: {"value": rows} for key, rows in blocks.items()}


LIVE_SHAPED_UPDATE = update_response(
    cashFunds=[
        {"id": "1", "value": pairs(currencyCode="EUR", value=1700.5)},
        {"id": "2", "value": pairs(currencyCode="USD", value=0)},
    ],
    portfolio=[
        {"id": "FLATEX_EUR", "value": pairs(value=1700.5)},
        {"id": "15690087", "value": pairs(size=290.67, price=121.36, breakEvenPrice=69.48)},
    ],
)

SESSION_BLOB = json.dumps({"sessionId": "sid", "intAccount": 42})


def mocked_degiro(update_status: int, update_body, product_info=None):
    """Patches `main._client` with an httpx client answering DEGIRO's two sync calls."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.startswith("/trading/secure/v5/update/"):
            if isinstance(update_body, (bytes, str)):
                return httpx.Response(update_status, content=update_body)
            return httpx.Response(update_status, json=update_body)
        if request.url.path == "/product_search/secure/v5/products/info":
            return httpx.Response(200, json={"data": product_info or {}})
        raise AssertionError(f"unexpected DEGIRO call {request.url}")

    def client():
        return httpx.AsyncClient(base_url=main.DEGIRO_BASE, transport=httpx.MockTransport(handler))

    return patch("main._client", new=client)


class PortfolioCompletenessTest(unittest.TestCase):
    """A reshaped 200 must fail the sync, never come back as cash=0 / no positions."""

    def setUp(self):
        self.client = TestClient(app)

    def portfolio(self):
        return self.client.post("/portfolio", json={"sessionBlob": SESSION_BLOB})

    def test_live_shaped_response_is_parsed_and_enriched(self):
        info = {"15690087": {"isin": "IE00BGSF1X88", "symbol": "IB01", "name": "iShares", "closePrice": 121.36}}
        with mocked_degiro(200, LIVE_SHAPED_UPDATE, info):
            response = self.portfolio()

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["cashEur"], 1700.5)
        self.assertEqual(len(body["positions"]), 1)
        self.assertEqual(body["positions"][0]["isin"], "IE00BGSF1X88")
        self.assertEqual(body["positions"][0]["quantity"], 290.67)

    def test_an_all_sold_portfolio_is_a_legitimate_empty_list(self):
        body = update_response(
            cashFunds=[{"id": "1", "value": pairs(currencyCode="EUR", value=12.0)}],
            portfolio=[],
        )
        with mocked_degiro(200, body):
            response = self.portfolio()

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"cashEur": 12.0, "positions": []})

    def test_a_renamed_cash_block_is_refused(self):
        renamed = dict(LIVE_SHAPED_UPDATE)
        renamed["cashFund"] = renamed.pop("cashFunds")
        with mocked_degiro(200, renamed):
            response = self.portfolio()

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "UPSTREAM_FORMAT_CHANGED")

    def test_a_missing_portfolio_block_is_refused(self):
        without_positions = {"cashFunds": LIVE_SHAPED_UPDATE["cashFunds"]}
        with mocked_degiro(200, without_positions):
            response = self.portfolio()

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "UPSTREAM_FORMAT_CHANGED")

    def test_a_row_without_a_price_is_refused(self):
        # LIVE_SHAPED_UPDATE["cashFunds"] is already a {"value": rows} block;
        # passing it through update_response() again would wrap it twice and
        # fail as "Missing cashFunds" instead of exercising the missing price.
        body = update_response(
            cashFunds=LIVE_SHAPED_UPDATE["cashFunds"]["value"],
            portfolio=[{"id": "15690087", "value": pairs(size=290.67)}],
        )
        with mocked_degiro(200, body):
            response = self.portfolio()

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "UPSTREAM_FORMAT_CHANGED")

    def test_a_non_json_body_is_refused(self):
        with mocked_degiro(200, "<html>maintenance</html>"):
            response = self.portfolio()

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "UPSTREAM_FORMAT_CHANGED")

    def test_an_expired_session_is_still_a_401(self):
        with mocked_degiro(401, {}):
            response = self.portfolio()

        self.assertEqual(response.status_code, 401)

    def test_product_info_failure_keeps_the_positions(self):
        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.startswith("/trading/secure/v5/update/"):
                return httpx.Response(200, json=LIVE_SHAPED_UPDATE)
            return httpx.Response(500)

        def client():
            return httpx.AsyncClient(base_url=main.DEGIRO_BASE, transport=httpx.MockTransport(handler))

        with patch("main._client", new=client):
            response = self.portfolio()

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["positions"][0]["symbol"], "15690087")


class PendingCredentialLifecycleTest(unittest.TestCase):
    def setUp(self):
        _pending.clear()

    def tearDown(self):
        _pending.clear()

    def test_expired_attempts_are_swept(self):
        _pending["stale"] = {"username": "u", "password": "p", "created_at": time.time() - _PENDING_TTL - 1}
        _pending["fresh"] = {"username": "u", "password": "p", "created_at": time.time()}

        _clean_pending()

        self.assertNotIn("stale", _pending)
        self.assertIn("fresh", _pending)

    def test_a_stale_attempt_is_refused_by_complete_and_its_password_dropped(self):
        _pending["stale"] = {"username": "u", "password": "p", "created_at": time.time() - _PENDING_TTL - 1}

        with TestClient(app) as client:
            response = client.post("/complete", json={"processId": "stale", "code": "123456"})

        self.assertEqual(response.status_code, 410)
        self.assertEqual(response.json()["detail"], "AUTH_ATTEMPT_EXPIRED")
        self.assertNotIn("stale", _pending)

    def test_an_unknown_attempt_reports_an_expired_authentication(self):
        with TestClient(app) as client:
            response = client.post("/complete", json={"processId": "gone", "code": "123456"})

        self.assertEqual(response.status_code, 410)
        self.assertEqual(response.json()["detail"], "AUTH_ATTEMPT_EXPIRED")

    def test_pending_capacity_is_bounded(self):
        for index in range(MAX_PENDING):
            _store_pending(str(index), {"username": "u", "password": "p", "created_at": time.time()})

        with self.assertRaises(HTTPException) as raised:
            _store_pending("overflow", {"username": "u", "password": "p", "created_at": time.time()})
        self.assertEqual(raised.exception.status_code, 503)
        self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")

    def test_expiry_is_measured_against_the_ttl(self):
        now = 1_000_000.0
        self.assertFalse(_is_expired({"created_at": now - _PENDING_TTL}, now))
        self.assertTrue(_is_expired({"created_at": now - _PENDING_TTL - 1}, now))
        self.assertTrue(_is_expired({}, now))


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_health_is_unauthenticated(self):
        self.assertEqual(self.client.get("/health").json(), {"status": "ok"})

    def test_a_malformed_totp_code_is_reported_as_an_invalid_otp(self):
        response = self.client.post("/complete", json={"processId": "p", "code": "12ab"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_OTP")

    def test_unexpected_fields_are_refused(self):
        response = self.client.post("/initiate", json={"username": "u", "password": "p", "totp": "1"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_empty_credentials_are_refused_before_reaching_degiro(self):
        response = self.client.post("/initiate", json={"username": "", "password": "p"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_a_malformed_session_blob_is_refused(self):
        response = self.client.post("/portfolio", json={"sessionBlob": "[1]"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "Invalid sessionBlob format")


class LogRedactionTest(unittest.TestCase):
    def test_client_profile_identity_is_masked(self):
        payload = {"data": {
            "id": 123456, "intAccount": 42, "displayName": "Jean Dupont", "memberCode": "jdupont",
            "firstContact": {"firstName": "Jean"}, "flatexBankAccount": {"iban": "DE00"},
            "email": "jean@example.org",
        }}

        redacted = _redact(payload)["data"]

        self.assertEqual(redacted["intAccount"], 42)
        for key in ("id", "displayName", "memberCode", "firstContact", "flatexBankAccount", "email"):
            self.assertEqual(redacted[key], "***", key)
        self.assertNotIn("Dupont", _safe_body(payload))

    def test_session_id_is_masked_at_any_depth(self):
        self.assertEqual(_redact({"a": [{"sessionId": "s"}]}), {"a": [{"sessionId": "***"}]})


if __name__ == "__main__":
    unittest.main()
