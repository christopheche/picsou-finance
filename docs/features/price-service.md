# Feature: Price Service

> Last updated: 2026-09-06

## Context

Picsou needs EUR prices for crypto assets (BTC, ETH, SOL, etc.) and stocks/ETFs (PEA/Compte-Titres holdings) to display account balances in a unified currency. Prices are fetched from two free providers: CoinGecko for crypto and Yahoo Finance for stocks/ETFs, both rate-limited per IP. The scheduler refreshes prices hourly for everything held.

Because those providers can and do refuse to answer, the service is built so that a refusal degrades the *age* of a price rather than its existence: a 15-minute in-memory cache, then a batched provider call, then the last price recorded in `price_snapshot`. See [ADR 2026-08-01](../decisions/2026-08-01-last-known-price-fallback.md) for why valuing from a dated price beats valuing from nothing.

## How it works

### Provider routing

`PriceService.getPriceEur(ticker)` routes each ticker to the appropriate provider:

- **CoinGecko** (`CoinGeckoPriceProvider`): Handles crypto tickers (BTC, ETH, SOL, BNB, ADA, XRP, DOGE, DOT, MATIC, AVAX, LINK, UNI, ATOM, LTC, NEAR, ARB, OP, SHIB, PEPE, SUI). Uses the `/simple/price` endpoint with `vs_currencies=eur`. Supports batch queries (all tickers in one request).
- **Yahoo Finance** (`YahooFinancePriceProvider`): Handles everything CoinGecko does not -- stocks, ETFs, indices. Uses the unofficial `/v8/finance/chart/{ticker}` endpoint. Fetched per-ticker (no batch). Tickers like `IWDA.AS`, `MC.PA` are already EUR-denominated; foreign-currency tickers (USD/JPY/GBp/...) are converted to EUR inside the adapter via Yahoo's own `{CURRENCY}EUR=X` chart endpoint, with a 15-minute FX cache mirroring the price cache TTL. See [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md).

Both providers implement `PriceProviderPort` with `supports(ticker)` and `getPricesEur(tickers)`.

### Resolution chain

`PriceService.resolve(tickers, cryptoOnly)` answers every on-demand read, in this order:

1. **In-memory cache** — `ConcurrentHashMap<String, CachedPrice>` keyed by uppercase ticker, `CACHE_TTL_SECONDS` = 900 s for a hit. Hits are returned as a live `Quote` dated today.
2. **One batched provider call** for everything still missing — CoinGecko takes the whole crypto set in a single request, Yahoo is per-ticker (it has no batch endpoint). Skipped for a ticker whose last attempt came back empty and is still within the miss TTL (the *negative cache*, below), and skipped entirely while `CoinGeckoPriceProvider` is in its post-429 cooldown.
3. **Last recorded price** — `price_snapshot`, most recent row per ticker within 7 days, one query for the whole set (`findRecentByTickers`). Returned as a `Quote` with `live = false` and `asOf` = the snapshot's date.

Anything still unresolved returns nothing. A `Quote` never carries a `null` price — its constructor rejects one — so "unresolved" is always an absent key, never a present-but-empty one.

**Failures are remembered, not just successes.** A miss is cached in that same map as a `CachedPrice` with a `null` price and its own TTL, `MISS_CACHE_TTL_SECONDS` = 60 s. Without step 2's negative cache, a ticker the provider cannot resolve was re-fetched on *every* read: the dashboard, the account cards, the holdings table and the history chart each iterate the same holdings, so one permanently-unresolvable ticker produced dozens of identical Yahoo 404s per minute across Tomcat threads (GH issue #76). The 60 s window is deliberately far shorter than the 900 s hit TTL — a miss is more likely to be transient (rate limiting) than a hit is to be stale, so recovery stays fast while the storm collapses to one call per ticker per minute; it also matches `CoinGeckoPriceProvider`'s default post-429 cooldown, so the retry lines up with the cooldown lifting. A cached miss does not end resolution: step 3 still runs, so an outage degrades a price's *age* rather than its existence. Batching the whole page's tickers into a single call is the other half of the same fix. `PriceServiceTest.aMissIsRetriedAfterSixtySeconds_whileAHitIsStillServed` pins both TTLs through an injected `Clock`.

**Provider calls are single-flight.** The dashboard fires several requests in parallel (totals, account cards, goals, the history live point) and each values the same holdings, so on a cold or just-expired cache every Tomcat thread that saw the gap used to issue its own identical provider call before any of them had written the cache. Fetches now run under one lock (`fetchLock`) and re-check the cache once inside it, so the threads that lose the race reuse the winner's answer — or its remembered miss. Cache hits never touch the lock. `concurrentReadsOfTheSameMissingTicker_costOneProviderCall` pins it.

`Quote(price, asOf, live)` is the shape callers get from `getQuote`/`getCryptoQuote`/`getQuotes`/`getCryptoQuotes`. `getPriceEur`/`getCryptoPriceEur` delegate to it and drop the freshness, so existing callers gained the fallback without changing.

`refreshPrices(Set<String> tickers)` is the *write* path: it serves still-fresh cache entries, partitions the rest into crypto and stock sets to call each provider once (under the same single-flight lock), updates the cache and records the day's `price_snapshot` rows for what it fetched. It honours the negative cache the same way the read path does — a remembered miss is not re-fetched inside its 60 s, and the ticker is simply **absent** from the result, never present with a `null` value. That distinction matters: `refreshCryptoQuotes` layers the last-known-price fallback on top for sync paths by looking at which crypto tickers are *missing* from the live map, and a `null` entry once masqueraded as a live price, skipped the fallback and let the exchange sync write a partial balance into `balance_snapshot` (`refreshCryptoQuotes_afterAReadPathMiss_stillValuesFromTheRecordedPrice`). Only live prices are ever written back to `price_snapshot`, or a stale price would be laundered into a fresh-looking one and the fallback would walk itself forward indefinitely.

Writing today's row is check-then-insert outside any transaction, so two callers recording a ticker's first price of the day (the hourly tick alongside a page load, or the boot-time tick alongside `PriceBackfillRunner`) can both insert; the loser's `uk_price_snapshot_ticker_date` violation is caught, the winner's row re-read and updated (`upsertSnapshot`). The backfill tolerates the same race per row. Inside a surrounding transaction (`WalletSyncService.sync`) PostgreSQL has already aborted the transaction, so there the exception still propagates as before.

`refreshHeldPrices(Set<String> tickers)` is the *request* path behind `GET /api/prices`. The frontend feeds it every holding of every account and cannot tell a coin from a share, so the service asks which of the requested tickers are held in a `CRYPTO` account (`AccountHoldingRepository.findDistinctTickersByAccountType` ∪ `AccountRepository.findDistinctTickersByType`) and routes those through `refreshCryptoPrices`, the rest through `refreshPrices` — the same split `SchedulerService.refreshPrices` applies hourly. Those two queries are global, like prices themselves; the answer only decides routing, and the result never contains a ticker the caller did not ask for.

### Currency conversion

`PriceService.toEur(balance, currency, ticker)` converts an account balance to EUR:
- If currency is EUR and no ticker is set, returns the balance as-is.
- Otherwise, uses the ticker (preferred) or currency code to fetch a price, then multiplies.

### Scheduler

`SchedulerService.refreshPrices()` runs every hour (`fixedDelay = 3600000`), starting **five minutes after boot** (`initialDelay = 300000`): a `fixedDelay` task otherwise fires at context refresh, before Spring Boot calls the application runners, so its first tick ran on the scheduler thread while `StartupSyncService` and `PriceBackfillRunner` were already hitting the same providers from the main thread against an empty cache — the boot-time burst behind the [2026-08-01 incident](../decisions/2026-08-01-last-known-price-fallback.md). It builds **one** global set — `account.ticker` for accounts that are themselves one asset, **union** `AccountHoldingRepository.findDistinctTickers()` for everything held inside brokerage/exchange/wallet accounts — and calls `PriceService.refreshPrices()` once. Prices are global (no member scoping anywhere in the cache, the table or the providers), so iterating members would only re-fetch shared tickers once per member.

Both halves are split by account type before the call: `AccountRepository.findDistinctTickersByType(CRYPTO)` joins the crypto holding tickers, and `findDistinctTickersExcludingType(CRYPTO)` feeds the rest. A manual crypto account tracking one coin carries its symbol on the account row and has no holdings at all, so reading only holdings sent it down the Yahoo Finance branch — the exact contamination the split below exists to prevent. Both are repository projections rather than `findAll()`: one column is read, and loading every account entity hourly to reach it is waste.

### Key files

- `backend/src/main/java/com/picsou/service/PriceService.java` -- Resolution chain (cache → batched provider call → last recorded price), `Quote`, conversion
- `backend/src/main/java/com/picsou/repository/PriceSnapshotRepository.java` -- `findRecentByTickers`, the batched fallback lookup
- `backend/src/main/java/com/picsou/service/AccountService.java` -- `valuation()`: value and cost basis from one quote map
- `backend/src/main/java/com/picsou/service/SchedulerService.java` -- Hourly price refresh cron
- `backend/src/main/java/com/picsou/adapter/CoinGeckoPriceProvider.java` -- CoinGecko `/simple/price` with ticker-to-ID mapping
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` -- Yahoo Finance `/v8/finance/chart/{ticker}`
- `backend/src/main/java/com/picsou/port/PriceProviderPort.java` -- Port interface with `supports()` and `getPricesEur()`

### Flow

```
Account page loads --> needs EUR prices for its holdings
        |
        v
AccountService.valuation(account)  --> ONE call with the whole ticker set
        |
        v
PriceService.getQuotes({BTC, SOL, ATOM, ...})
        |
        +-- in cache, not expired ------------> Quote(price, today, live=true)
        |
        +-- missing
                |
                +-- attempted < 60s ago, or CoinGecko cooling down --> skip the network
                |
                +-- otherwise: ONE batched call, one thread at a time (fetchLock,
                |       cache re-checked inside it)
                |       GET /simple/price?ids=bitcoin,solana,cosmos&vs_currencies=eur
                |               |
                |               +-- answered --> cache + Quote(price, today, live=true)
                |
                v
        still missing --> price_snapshot, latest row <= 7 days old (one query)
                |
                +-- found  --> Quote(price, snapshotDate, live=false)   [UI marks it]
                |
                +-- none   --> no quote; asset excluded from BOTH value and cost basis
                                and remembered as a miss for 60s

Scheduler (every hour):
        |
        v
SchedulerService.refreshPrices()
        |
        v
account tickers UNION holding tickers, split by account type
        |
        v
CRYPTO --> PriceService.refreshCryptoPrices   |   rest --> PriceService.refreshPrices
        |
        +-- fresh cache entry (hit, or a miss < 60s old) --> served / left out, no call
        |
        v
Partition: crypto --> CoinGecko (batched) | stocks --> Yahoo (per ticker; never for crypto-only)
        |
        v
Update cache + upsert today's price_snapshot rows (insert race tolerated)

GET /api/prices?tickers=BTC,SNX,IWDA.AS  (polled by the frontend):
        |
        v
PriceService.refreshHeldPrices(tickers)
        |
        +-- held in a CRYPTO account (holding or account ticker) --> refreshCryptoPrices
        |
        +-- everything else ----------------------------------------> refreshPrices

Boot (PriceBackfillRunner):
        |
        v
CRYPTO holdings --> backfillHistoricalCryptoPrices (unmapped coin skipped with a warning)
other holdings  --> backfillHistoricalPrices        (CoinGecko, else Yahoo)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| CoinGecko free tier | No API key needed, supports batch queries, reliable | CoinMarketCap (requires key) |
| Yahoo Finance (unofficial) | Free, covers European tickers (.PA, .AS) | Alpha Vantage (key required, limited) |
| 15-minute cache TTL | Balance between freshness and API rate limits | No cache (too many requests) or 1-hour cache (stale prices) |
| Hourly scheduler refresh | Keeps cache warm; ensures dashboard loads fast | Fetch on every dashboard request (slow) |
| Provider partition by `supports()` | Clean separation: CoinGecko gets crypto, Yahoo gets everything else | Hardcoded crypto ticker list (duplicated, harder to maintain) |
| `price_snapshot` as the fallback store | Already written daily, already durable, already the right shape (one EUR price per ticker per day) | A second cache table, or persisting the in-memory cache |
| 7-day fallback ceiling | A day-old crypto price is slightly wrong; a month-old one is fiction | Unbounded (silently valuing on ancient prices) |
| 60-second negative cache | One failed attempt per minute per ticker instead of one per read; lines up with CoinGecko's default 60 s cooldown | Retrying every read (the behaviour that sustained a 429 for hours); sharing the 900 s hit TTL (15 minutes of amber fallback after a 30 s outage) |
| Single-flight provider calls (`fetchLock`) | Parallel dashboard requests collapse to one call per gap instead of one per thread | Per-ticker in-flight futures (more machinery for the same free-tier budget) |
| Type-aware request path (`refreshHeldPrices`) | The client cannot tell a coin from a share; the server can, from the account type | A `cryptoOnly` flag on `GET /api/prices` (every caller would have to get it right) |

## Gotchas / Pitfalls

- **`supports()` enforces a symbol shape**: beyond rejecting 12-char ISINs, `YahooFinancePriceProvider.supports()` accepts an optional leading `^` for indices, then alphanumerics and the separators Yahoo uses for exchange suffixes, share classes and FX pairs (`IWDA.AS`, `BRK-B`, `USDEUR=X`), with a 20-character limit for the complete symbol. Anything containing whitespace or a slash is not a symbol. This matters because OpenFIGI returns Bloomberg *bond descriptions* in its `ticker` field (`AIRBAL 14.5 08/14/29 REGS`): WebClient percent-encodes the spaces but **not** the slashes, so the request lands on `/v8/finance/chart/AIRBAL%2014.5%2008/14/29%20REGS` — a different API path entirely — and 404s forever.
- **`GET /api/prices` honours both caches and is type-aware**: `PriceController` calls `refreshHeldPrices()`, which serves fresh hits, skips the network for a miss remembered less than 60 s ago (leaving the ticker *out* of the response rather than mapping it to `null`), and routes anything held in a `CRYPTO` account crypto-only. Do not add client-side throttling or a second cache in front of it, and do not switch the controller back to the generic `refreshPrices` — that sent an unmapped coin (SNX, STX, APT, SEI) to Yahoo Finance and recorded the same-named equity's share price in `price_snapshot`. `PriceControllerTest` and `refreshHeldPrices_neverSendsACryptoHoldingToYahoo` pin it.
- **`months` is bounded on the way in**: `GET /api/prices/{ticker}/history?months=` (and `GET /api/history`) feed the value straight to `LocalDate.now().minusMonths(months)`. Past the supported year range that throws `DateTimeException`, which no handler covers, so the caller saw a 500 and the log an ERROR stack trace; a negative value silently inverted the range and returned an empty series with a 200. Both controllers now refuse anything outside 1..`MAX_MONTHS` (600, i.e. 50 years) with an `IllegalArgumentException` — a 400 naming the bound.
- **Yahoo Finance is unofficial**: The Yahoo Finance API is undocumented and can break or get rate-limited without notice. FX conversion is now applied inside `YahooFinancePriceProvider` using the `{CURRENCY}EUR=X` chart endpoint; `GBp`/`GBX` is treated as `GBP / 100`. If the FX call fails the ticker is omitted from the result map (no fabricated rate) — downstream consumers must tolerate a missing key.
- **CoinGecko rate limits, and how they used to sustain themselves**: the keyless free tier is throttled per IP. A 429 was previously answered with *more* traffic — nothing was cached on failure, so every holding of every account re-issued a single-ticker request on every page render, and the provider counts the calls it rejects. On 2026-08-01 that turned a startup burst into two hours of missing prices. Four things now prevent it: reads are batched (one request per set, not per holding), a failed ticker is left alone for 60 s, concurrent threads share one in-flight fetch, and `CoinGeckoPriceProvider` refuses to send anything at all until its post-429 cooldown expires (`Retry-After` when sane, else 60 s, capped at 15 min). If you add a price call, batch it and route it through `PriceService` — a direct adapter call bypasses all four.
- **The miss TTL once silently regressed to 900 s**: a rebase dropped `MISS_CACHE_TTL_SECONDS` while keeping the comments that referred to it, and nothing failed because the only negative-cache test made three *immediate* reads. `CachedPrice.isExpired(Instant)` takes the clock's instant precisely so a TTL can be asserted; keep the two TTL tests when touching the cache.
- **Read paths must resolve the whole set at once**: `AccountService.valuation` and `CryptoExchangeSyncService.getPositions` build their ticker set first and make one call. Reverting either to a per-holding lookup re-creates the amplification above, and the tests that pin it (`aSetOfTickersCostsOneProviderCall`, `aFailedLookupIsNotRetriedOnEveryRead`) are the only thing that will say so.
- **Call `valuation()` once per account, never `liveBalanceEur` then `calculateInvestedAmount`**: both accessors run the whole pass, so the pair costs twice the work — and, worse, the two runs can straddle a cache expiry or the end of a 429 cooldown, so the value excludes an asset the cost basis then includes. That is the -85% disagreement rebuilt from two calls that were each individually correct. `SchedulerService.dailySnapshots` and both `HistoryService` live points take a single `Valuation`.
- **Nothing priced is not a small balance**: `Valuation.anyPriced()` is false only when an account holds assets and none could be valued, and every caller that *persists* a valuation must refuse rather than record it — `dailySnapshots` skips the day, `CryptoExchangeSyncService` throws, `WalletSyncService` throws. A zero written into `balance_snapshot` is permanent: no later sync revisits a past date.
- **A holding with no ticker is priced only if the connector valued it**: there is no lookup to fail, so it is not an unpriced asset in the outage sense — but `anyPriced()` must never outrun an actual figure. With a `providerValueEur` (only Bourse Direct reports one) the line is added to the value *and* its cost basis counted, and the account is priced: leaving the flag false there turned the refusal above into a permanent one, and such an account stopped receiving snapshots altogether. Without one, nothing can put a number on the line, so it leaves both sides — marking the account priced anyway would let `dailySnapshots` record a cash-only balance for an account holding real assets. `AccountServiceTest.aHoldingTheProviderValuedItselfCountsAsPriced` and `aHoldingWithNoTickerAndNoProviderValueLeavesBothSidesAlone` pin the two halves.
- **The in-memory cache dies on restart, the fallback does not**: `PriceService` holds prices in the heap only, and `StartupSyncService` replays the whole daily sync on every boot. That combination is why a restart used to blank the interface — the cache was empty and the burst got rate-limited. `price_snapshot` is now consulted whenever the providers come back empty, so a cold JVM values from yesterday's row instead of showing nothing.
- **The fallback is keyed by ticker alone, exactly like the cache**: so it inherits the crypto/equity symbol ambiguity. `getCryptoQuote` checks `CoinGeckoPriceProvider.supports()` **before** touching either, which is what stops an unmapped symbol (`STX`, `SNX`, `SEI`, `APT`) from reading a row a same-named stock wrote. Keep that check first.
- **`backfillHistoricalPrices` skips tickers whose history is already there — but it scans, it does not probe**: it runs at every boot, once per held ticker, and used to re-request twelve months only to discard every row as a duplicate, exhausting the rate limit seconds after startup. Coverage is now decided by walking the whole range and rejecting any hole longer than 7 days (a weekend is 2, an Easter or Christmas week reaches 5). Checking only the two ends is the tempting simplification and it is wrong: an instance offline for three months has history on *both* sides of the hole, so every boot would declare it covered and `HistoryService` would flat-line the chart across those months forever. A ticker whose history simply starts late (an asset younger than the range) reads as uncovered and is re-requested each boot — we cannot tell "the provider has nothing earlier" from "we never asked" without asking.
- **Every write path keeps crypto and everything else apart**: `refreshPrices` routes whatever CoinGecko cannot map to Yahoo Finance **and records what it fetches** in `price_snapshot`, so feeding it a coin whose symbol is a listed equity would write that company's share price into the very table the fallback reads from — poisoning it for every account, not just the crypto one. `SchedulerService.refreshPrices` therefore resolves `AccountType.CRYPTO` holding tickers through `refreshCryptoPrices` and the rest through `refreshPrices`; `refreshHeldPrices` applies the same split to `GET /api/prices`; and `PriceBackfillRunner` sends `CRYPTO` holdings through `backfillHistoricalCryptoPrices`, which skips an unmapped coin instead of persisting twelve months of the equity's closes under its symbol (`cryptoBackfill_skipsAnUnmappedTicker_insteadOfRecordingTheEquitysHistory`, `PriceBackfillRunnerTest`). Before holding tickers were warmed at all, no crypto symbol ever reached these methods; a new caller that takes tickers from holdings must choose a side the same way.
- **Ticker collection must exclude soft-deleted accounts**: `AccountService.delete` only stamps `deleted_at`, and holdings stay behind, so a query over `AccountHolding` alone keeps returning a deleted account's tickers forever — refreshed hourly, against a rate-limited free tier. `AccountHoldingRepository.findDistinctTickers` joins `Account` and filters on `deletedAt`.
- **Provider priority is `supports()`-based**: CoinGecko checks a hardcoded ticker-to-ID map. If a new crypto asset is added (e.g. a new token), it must be added to `TICKER_TO_ID` in `CoinGeckoPriceProvider`.
- **`toEur()` returns raw balance on failure**: If no price is available for a symbol, `toEur()` logs a warning and returns the unconverted balance. This can lead to incorrect dashboard values if a price provider is down.
- **Historical/intraday series use today's FX**: `getHistoricalPricesEur` and `getIntradayPricesEur` fetch the FX rate once per call and apply it to every candle in the series. Per-day FX would multiply API calls ~250× for a one-year backfill with marginal accuracy gain — see [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md) for the trade-off.
- **Snapshots from before the FX fix were wiped**: `PriceFxCleanupRunner` purges `price_snapshot` once at boot (guarded by the `price.fx_fix_cleanup_done` app_setting flag from `V31`) so `PriceBackfillRunner` rebuilds 12 months of history with FX-corrected prices.

## Tests

- `PriceServiceTest` -- resolution chain (fallback to the last recorded price, its 7-day ceiling, the negative cache and its 60 s TTL against the 900 s hit TTL, one provider call per set and per concurrent gap, crypto-only never reading a snapshot), the write path (a remembered miss is absent from the result and still falls back in `refreshCryptoQuotes`, the type-aware `refreshHeldPrices`, the tolerated insert race), plus the backfill guard, its coverage skip and its crypto-only route
- `PriceBackfillRunnerTest` -- the boot-time backfill splits crypto holdings from the rest
- `PriceControllerTest` -- `GET /api/prices` goes through `refreshHeldPrices`, never the generic refresh; `GET /{ticker}/history` refuses an overflowing or negative `months` without touching the repository
- `CoinGeckoPriceProviderTest` -- ticker mapping, failure grading, and the post-429 cooldown (including `Retry-After` handling)
- `AccountServiceTest` -- an unpriced holding leaves the cost basis as well as the value; a recorded price still values the account and is reported as stale
- `YahooFinancePriceProviderTest` -- unit tests for response parsing
- `SchedulerServiceTest` -- the hourly refresh keeps crypto tickers on the CoinGecko branch and is deferred past the startup runners; `dailySnapshots` writes live and invested EUR, skips an existing row and an unpriced account, and one account's failure (a valuation bug or a concurrent snapshot write) does not cost the others their snapshot

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related feature: [Crypto tracking](./crypto-tracking.md)
- Related feature: [Trade Republic](./trade-republic.md)
