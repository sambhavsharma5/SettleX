# SettleX

Algorithmic Multi-Party Debt Optimization Engine. Given a cluttered graph of who-owes-whom,
SettleX nets the obligations and emits the **minimum viable set of transfers** that squares
everyone out.

Spring Boot 3.3.5 · Java 21 · Maven · no runtime dependencies beyond the framework.

---

## Quick start

```bash
./mvnw spring-boot:run          # http://localhost:8085
```

Open <http://localhost:8085> for the dashboard, or call the API directly:

```bash
curl -s localhost:8085/api/v1/settlements/optimize \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"OPTIMAL_SUBSET","transactions":[
        {"debtor":"Ana","creditor":"Ben","amount":100},
        {"debtor":"Ben","creditor":"Cara","amount":100},
        {"debtor":"Cara","creditor":"Ana","amount":90},
        {"debtor":"Ana","creditor":"Cara","amount":20},
        {"debtor":"Ben","creditor":"Ana","amount":30}]}'
```

The port follows `PORT`, defaulting to `8085`.

### Docker

```bash
docker build -t settlex:1.0.0 .
docker run --rm -p 8085:8085 settlex:1.0.0
```

Multi-stage build (Maven on `temurin-21-alpine`, runtime on `eclipse-temurin:21-jre-alpine`),
runs as the unprivileged `settlex` user, and ships a `HEALTHCHECK` against `/actuator/health`.

---

## The two engines

Both implement `SettlementEngine` and are dispatched by an exhaustive `switch` over
`OptimizationStrategy`.

### `GREEDY` — max-heap matching

Repeatedly settles the largest debtor against the largest creditor, discharging at least one
participant per round. Runs in `O(N log N)` and always emits at most `N-1` transfers.

That bound is not always *tight*. Pairing greedily can shatter a set of independent zero-sum
groups into a needlessly tangled chain.

### `OPTIMAL_SUBSET` — bitmask DP

A ledger decomposes into independent **zero-sum groups** ("islands"): subsets whose net
positions sum to zero and can be settled without touching anyone outside them. Settling each
island independently beats the global greedy pairing.

The engine finds the **maximum** number of such groups `k` by dynamic programming over
subsets, which yields a provable lower bound on the transfer count of `N - k`. It then
settles each island on its own and hits that bound exactly.

> If the ledger splits into `k` groups, at most `k` transfers can be the *last* payment in a
> group, so at least `N - k` transfers are needed. That is the bound the engine attains.

Complexity is `O(3^N / 2)`, so the exact path is capped by
`settlex.engine.exact-solver-max-participants` (default **16**). Above that limit the engine
transparently **falls back to greedy** and flags the result:

```json
{ "isOptimalFallback": true, "isMathematicallyOptimal": false, "optimalTransactionLowerBound": -1 }
```

Optimality is never asserted without proof. A greedy result that happens to match the bound
is reported as `isMathematicallyOptimal: true`; otherwise it is `false`, not "probably fine".

### The difference, concretely

The `greedy-defeated` sample has net positions Quinn `+3`, Priya `+4`, Sam `-3`, Omar `-2`,
Raj `-2`:

| Engine | Transfers | Cash in motion | Proof |
|---|---|---|---|
| Greedy | **4** | 7.00 | optimality unproven |
| Optimal subset | **3** | 7.00 | provably minimal |

---

## API

Base path `/api/v1/settlements`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/optimize` | Optimize a ledger with the chosen strategy |
| `POST` | `/benchmark` | Run both engines concurrently and compare |
| `GET` | `/samples` | Seven curated example ledgers |
| `GET` | `/actuator/health` | Liveness/readiness probe |

`benchmark` executes both engines on a dedicated pool via `CompletableFuture` and reports
per-engine timings plus the delta.

### Errors

Failures return a consistent envelope with an HTTP status, a stable machine-readable `code`,
a human `message`, and field-level `violations`:

| Code | Status | Cause |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean-validation failure (empty list, non-positive amount, oversized field) |
| `MALFORMED_REQUEST` | 400 | Unparseable body, unknown field, or unparseable enum |
| `UNBALANCED_LEDGER` | 400 | Residual net total ≠ 0 (defensive invariant) |
| `CURRENCY_PRECISION_ERROR` | 400 | Amount outside the supported minor-unit range |
| `UNSUPPORTED_STRATEGY` | 400 | Strategy the engine does not implement |
| `ENGINE_INVARIANT_VIOLATED` | 500 | Solver produced an inconsistent plan |
| `INTERNAL_ERROR` | 500 | Anything unhandled |

---

## Money handling

- Amounts are `BigDecimal` on the wire and `long` **minor units** (cents) internally. The
  engines never touch floating point.
- Normalization uses `RoundingMode.HALF_EVEN`.
- Net balance is `Σ(credits) − Σ(debits)`: positive is a creditor, negative is a debtor.
- Participants netting to exactly zero are eliminated entirely; they neither pay nor receive.
- Overflow is bounded and surfaces as `CURRENCY_PRECISION_ERROR` rather than a wrapped
  arithmetic error.

Because every `Transaction` is a bilateral `debtor → creditor` obligation, gross debits and
gross credits always match by construction. `UnbalancedLedgerException` therefore guards the
residual net total (`Σ net`), not the gross totals.

---

## Dashboard

Single self-contained `src/main/resources/static/index.html`:

- **Dual side-by-side graphs** — the cluttered raw ledger against the optimized plan, drawn
  with vis-network.
- **Transaction builder** plus a dropdown of the seven sample graphs.
- **Strategy toggle** between greedy and exact.
- **Live metrics** — transactions reduced, cash volume saved, execution time in
  microseconds, participants, zero-sum groups, remaining cash flow.
- **Settlement receipt checklist** with per-payment check-off and copy-to-clipboard.
- **Benchmark tab** comparing both engines side by side.

Every API call uses a relative path (`/api/v1/...`). No hostname or port is hard-coded, so
the page works unchanged on any port, behind a reverse proxy, or under a context path.

---

## Configuration

`src/main/resources/application.yml`:

```yaml
server:
  port: ${PORT:8085}

settlex:
  engine:
    exact-solver-max-participants: 16
  benchmark:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 64
```

---

## Tests

```bash
./mvnw test
```

130 JUnit 5 tests covering exact-cent arithmetic and `HALF_EVEN` boundaries, ledger
normalization, greedy invariants (`≤ N−1` transfers, no over-payment, full discharge),
bitmask DP against a brute-force optimum on small inputs, island separation, fallback
behavior above the limit, both engines against every sample graph, HTTP status and error
mapping for each failure mode, and the served dashboard.

---

## Layout

```
src/main/java/com/settlex/
├── SettleXApplication.java
├── config/       EngineConfig, SettleXProperties
├── controller/   SettlementController, GlobalExceptionHandler
├── dto/          Transaction, Settlement, SettlementPlan, SettlementRequest, ...
├── engine/       SettlementEngine, GreedyPriorityQueueEngine, OptimalSubsetPartitionEngine
│   └── support/  MoneyMath, Position, LedgerNormalizer, BitmaskPartitionSolver, PlanAssembler
├── exception/    CurrencyPrecisionException, UnbalancedLedgerException, ...
└── service/      SettlementService, SampleDataService
```

Both engines are fully deterministic: positions sort by descending magnitude with
alphabetical tie-breaks, so identical input always produces an identical settlement list.

---

MIT
