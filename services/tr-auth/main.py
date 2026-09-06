"""
Trade Republic Auth Sidecar
---------------------------
Handles the HTTP auth flow that requires an AWS WAF browser challenge.
Exposes a minimal HTTP API consumed by the Spring backend.

Flow:
  POST /initiate  { phoneNumber, pin }  → { processId }
  POST /complete  { processId, tan }    → { sessionToken }
"""

import base64
import hashlib
import json
import uuid
import logging
from typing import Optional
from urllib.parse import quote

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from playwright.async_api import async_playwright
from pydantic import BaseModel, ConfigDict, Field

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("tr-auth")
logging.getLogger("httpx").setLevel(logging.WARNING)

app = FastAPI()

TR_API = "https://api.traderepublic.com"
TR_APP = "https://app.traderepublic.com"

# No pending-state store: the process id round-trips through Java and Trade
# Republic validates it, and /complete always fetches a fresh WAF token (the one
# from /initiate may have expired by the time the user types the code). A store
# keyed by process id with no TTL only ever grew with abandoned logins.


# ─── Helpers ──────────────────────────────────────────────────────────────────

def generate_device_info() -> str:
    device_id = hashlib.sha512(uuid.uuid4().bytes).hexdigest()
    return base64.b64encode(json.dumps({"stableDeviceId": device_id}).encode()).decode()


async def get_waf_token() -> Optional[str]:
    """Loads app.traderepublic.com in headless Chromium and retrieves the AWS WAF token."""
    log.info("Launching headless browser to obtain AWS WAF token...")
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
        context = await browser.new_context(
            user_agent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                       "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        )
        page = await context.new_page()
        await page.add_init_script(
            "Object.defineProperty(navigator, 'webdriver', { get: () => undefined })"
        )

        try:
            await page.goto(TR_APP, wait_until="networkidle", timeout=20000)
        except Exception:
            await page.wait_for_timeout(5000)

        # Try JS API first
        waf_token = None
        try:
            waf_token = await page.evaluate(
                "window.AWSWafIntegration ? window.AWSWafIntegration.getToken() : null"
            )
            if waf_token:
                log.info("Got WAF token via AWSWafIntegration.getToken()")
        except Exception:
            pass

        # Fallback: cookies
        if not waf_token:
            cookies = await context.cookies()
            for cookie in cookies:
                if "aws-waf-token" in cookie.get("name", "").lower():
                    waf_token = cookie["value"]
                    log.info("Got WAF token from cookie")
                    break

        await browser.close()

        if not waf_token:
            log.warning("Could not obtain AWS WAF token — request may fail with 403")
        return waf_token


def tr_headers(waf_token: Optional[str]) -> dict:
    headers = {
        "Accept": "*/*",
        "Accept-Language": "fr",
        "Cache-Control": "no-cache",
        "Content-Type": "application/json",
        "Pragma": "no-cache",
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "x-tr-app-version": "13.40.5",
        "x-tr-device-info": generate_device_info(),
        "x-tr-platform": "web",
        "Origin": TR_APP,
        "Referer": TR_APP + "/",
    }
    if waf_token:
        headers["x-aws-waf-token"] = waf_token
    return headers


def normalise_phone(phone: str) -> str:
    phone = phone.strip()
    if phone.startswith("0"):
        phone = "+33" + phone[1:]
    return phone


def mask_phone(phone: str) -> str:
    # Below 7 chars, prefix + suffix would reveal most (or all) of the number.
    if len(phone) <= 6:
        return "****"
    return phone[:3] + "****" + phone[-2:]


def cookie_names(headers: httpx.Headers) -> list[str]:
    names: list[str] = []
    for cookie_str in headers.get_list("set-cookie"):
        first = cookie_str.split(";", 1)[0].strip()
        if "=" in first:
            names.append(first.split("=", 1)[0])
    return names


def cookie_value(resp: httpx.Response, name: str) -> Optional[str]:
    """Reads a cookie TR set on this response.

    httpx's parsed jar first; then the raw Set-Cookie headers, in case the jar
    dropped the cookie for Secure/domain reasons. The name is matched
    case-insensitively on the first `name=value` pair of each header only.
    """
    value = resp.cookies.get(name)
    if value:
        return value
    prefix = name.lower() + "="
    for cookie_str in resp.headers.get_list("set-cookie"):
        first = cookie_str.split(";", 1)[0].strip()
        if first.lower().startswith(prefix):
            return first[len(prefix):]
    return None


# ─── Endpoints ────────────────────────────────────────────────────────────────

class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    phoneNumber: str = Field(min_length=1, max_length=30)
    pin: str = Field(min_length=1, max_length=20)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # Both are interpolated into the Trade Republic path: bound them to what a
    # process id and a TAN look like so a caller cannot steer the authenticated
    # request elsewhere under the TR host. TANs are 4 digits today; 6 stays
    # accepted for the older flow.
    processId: str = Field(pattern=r"^[A-Za-z0-9._:-]{1,100}$")
    tan: str = Field(pattern=r"^\d{4,6}$")


class RefreshRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    refreshToken: str = Field(min_length=1, max_length=10_000)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    _: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    fields = {
        str(error["loc"][-1])
        for error in exc.errors()
        if error.get("loc")
    }
    # VALIDATION_CODE_INVALID is the code TradeRepublicAdapter.mapAuthError and
    # the frontend already understand for a bad TAN.
    detail = "VALIDATION_CODE_INVALID" if "tan" in fields else "INVALID_DATA"
    return JSONResponse(status_code=400, content={"detail": detail})


@app.post("/initiate")
async def initiate(req: InitiateRequest):
    waf_token = await get_waf_token()
    phone = normalise_phone(req.phoneNumber)
    log.info("Initiating TR auth for %s", mask_phone(phone))

    async with httpx.AsyncClient(timeout=15) as client:
        try:
            resp = await client.post(
                f"{TR_API}/api/v1/auth/web/login",
                json={"phoneNumber": phone, "pin": req.pin},
                headers=tr_headers(waf_token),
            )
            log.info("TR /login → %d", resp.status_code)
            resp.raise_for_status()
        except httpx.HTTPStatusError as e:
            raise HTTPException(status_code=e.response.status_code,
                                detail=f"TR rejected request: {e.response.text[:200]}")

    data = resp.json()
    process_id = data.get("processId")
    if not process_id:
        raise HTTPException(status_code=502, detail=f"TR did not return processId: {data}")

    return {"processId": process_id}


@app.post("/complete")
async def complete(req: CompleteRequest):
    # Always fetch a fresh WAF token — the one from /initiate may have expired
    # by the time the user reads and types the 2FA code.
    waf_token = await get_waf_token()

    async with httpx.AsyncClient(timeout=15) as client:
        try:
            resp = await client.post(
                f"{TR_API}/api/v1/auth/web/login/{quote(req.processId, safe='')}/{quote(req.tan, safe='')}",
                headers=tr_headers(waf_token),
            )
            log.info("TR /login/complete → %d  set-cookie names: %s",
                     resp.status_code, cookie_names(resp.headers))
            resp.raise_for_status()
        except httpx.HTTPStatusError as e:
            raise HTTPException(status_code=e.response.status_code,
                                detail=f"TR rejected 2FA: {e.response.text[:200]}")

    # Session token is in Set-Cookie: tr_session=<value>
    # Use resp.cookies (httpx parses all Set-Cookie headers correctly).
    # Fallback: manual parse in case httpx misses it due to Secure/domain filtering.
    session_token = cookie_value(resp, "tr_session")
    log.info("TR set-cookie names: %s", cookie_names(resp.headers))

    if not session_token:
        raise HTTPException(status_code=502,
                            detail="No tr_session cookie in TR response. "
                                   "The 2FA code may be invalid or expired.")

    refresh_token = cookie_value(resp, "tr_refresh")

    log.info("TR auth complete — session token obtained (refresh token: %s)",
             "yes" if refresh_token else "no")
    return {"sessionToken": session_token, "refreshToken": refresh_token}


@app.post("/refresh")
async def refresh_session(req: RefreshRequest):
    """Refresh the TR session using the stored refresh token (no 2FA needed)."""
    log.info("Refreshing TR session via tr_refresh token")
    # On the client, not per request: httpx deprecated per-request cookies.
    async with httpx.AsyncClient(timeout=15, cookies={"tr_refresh": req.refreshToken}) as client:
        try:
            resp = await client.post(
                f"{TR_API}/api/v1/auth/web/refresh",
                headers={
                    "Accept": "*/*",
                    "Content-Type": "application/json",
                    "Origin": TR_APP,
                    "Referer": TR_APP + "/",
                    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                  "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                },
            )
            log.info("TR /refresh → %d", resp.status_code)
            resp.raise_for_status()
        except httpx.HTTPStatusError as e:
            raise HTTPException(status_code=e.response.status_code,
                                detail=f"TR refresh failed: {e.response.text[:200]}")

    session_token = resp.cookies.get("tr_session")
    refresh_token = resp.cookies.get("tr_refresh")

    if not session_token:
        raise HTTPException(status_code=502, detail="No tr_session in TR refresh response")

    log.info("TR session refreshed successfully")
    return {"sessionToken": session_token, "refreshToken": refresh_token or req.refreshToken}


@app.get("/health")
async def health():
    return {"status": "ok"}
