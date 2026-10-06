# Master Plan: Adaptive Resilience Platform (AI + Guardrails, Pod + Pool level)

> Approved design document. Neeche **Approach, System Design, LLD, in-memory storage aur LLM API models** bhi hain. Kaam section J (MVP 1→4) aur section P (PR process) ke hisaab se hota hai.

## Context
**Problem:** Load aane par CPU low rehta hai par throughput gir jaata hai. Threads DB/HTTP pe wait karte hain (thread-pool starvation, Hikari pool exhaustion). Static thresholds har region, har load pattern ke liye sahi nahi baithte. Pod autoscaling aur pool sizing alag-alag decide hote hain, to ek doosre se takra sakte hain (zyada pods ka matlab zyada DB connections, aur DB aur dabta hai).

**Goal:** Ek layered system jisme:
- Business aur engineers har region ki **min-max limits** tay karein.
- Ek **deterministic guard** hard constraints enforce kare.
- **AI (dedicated company LLM + chhote ML models)** sirf us range ke andar suggest kare aur explain kare.
- Har decision audit ho sake.

**Differentiator:** Alag-alag tukde (adaptive concurrency, pool resize, predictive autoscaling, AI SRE agents with human approval) pehle se exist karte hain. Naya hissa yeh hai ki **pod count, per-pod pool size aur memory, shared DB budget, aur region-wise business limits** ek hi guard layer ke andar coordinate hon, aur AI us bounded space mein optimise kare.

---

## Architecture: 7 layers (upar se neeche)

```
L1 Business & Governance     -> region-wise min-max, SLO, criticality, approvals
L2 Data & Observability      -> metrics, memory, DB capacity, business data, audit store
L3 Intelligence (AI)         -> L3a numeric ML models, L3b dedicated LLM (advisory)
L4 Deterministic Guard  ★    -> clamp, DB budget, memory formula, step/cooldown/freeze
L5 Pod Control               -> HPA/KEDA, guard ke diye replica range ke andar
L6 In-Process Control        -> adaptive concurrency limit, bulkhead, Hikari pool
L7 Human-in-Loop & Audit     -> approval, AI engineers audit, dashboards
```
Rule: **L3 sirf suggest karta hai. L4 hi final value decide karta hai. L5/L6 sirf L4 ki value apply karte hain.**

### L1. Business & Governance
- **Kaun:** Product owner + software engineers (+ SRE / DBA).
- **Kya tay hoga (per region, per service):**
  - Pod replicas min-max
  - Per-pod pool size min-max
  - Concurrency limit min-max
  - SLO (p95 latency, error rate), business criticality, cost cap
- **Basis:** Load test results + SLO + business calendar (sale, month-end, region peak hours).
- Limits **policy-as-code** mein versioned ho (Git), change review ke saath.

### L2. Data & Observability
- **Runtime metrics:**
  - Hikari: active, idle, pending, connection acquire time
  - Executor queue size
  - p95 latency, error rate
  - BLOCKED/WAITING thread count (sampling, extra signal)
- **Memory:** pod limit, heap used, old-gen after GC, metaspace, native/direct memory, thread count, OOM kills.
- **DB:** max connections, current connections per region, DB CPU/latency.
- **Business data:** traffic calendar, region criticality, incident history, runbooks, SLO docs.
- **Audit store:** har decision ka input, suggestion, clamp result, final value, kisne approve kiya.
- Har data point pe **region tag** hona zaroori hai (isolation ke liye).

### L3. Intelligence (AI)
**L3a. Numeric models** (chhote, saste, testable)
- Load forecast (next 15-60 min) per region.
- Anomaly detection (latency, pending threads, memory drift).
- Memory coefficient calibration (ek connection/thread asal mein kitni memory leta hai).

**L3b. Dedicated company LLM** (advisory)
- **Input:** L3a ke outputs + business context (RAG se: SLO, region rules, incident history, runbooks).
- **Output:** Range ke andar suggested values + **natural-language explanation** (kyun yeh value).
- **Start:** RAG / context-in-prompt. Fine-tuning tabhi jab RAG kaafi na ho.
- **Limits:**
  - LLM ko **koi write permission nahi**.
  - Har call sirf ek region ke scoped data ke saath.
  - Prompt injection se bachav: logs/tickets ka text "data" hai, "instruction" nahi.
- Control loop **LLM ke bina bhi chalna chahiye**. LLM down ho to static default.

### L4. Deterministic Guard ★ (core, sabse important)
Har suggestion yahan se guzarta hai:
1. `final = clamp(suggestion, region_min, region_max)`
2. **DB budget:** `replicas × pool_size ≤ region_db_budget`. Violation ho to pool ya replicas ko proportionally kam karo.
3. **Memory formula:** `heap + metaspace + threads×stack + native + pool×per_conn_overhead ≤ pod_memory_limit × safety_factor`
4. **Max step size:** jaise ek cycle mein ±20% se zyada nahi.
5. **Cooldown:** jaise do changes ke beech kam se kam X minute.
6. **Freeze:** anomaly, incident ya deploy ke time auto-change band.
7. **Fallback:** kuch bhi fail ho to L1 ke static defaults.
8. **Blast radius:** ek cycle mein ek region; regions ek ke baad ek.
9. Har decision audit store (L2) mein log ho.

### L5. Pod Control
- HPA/KEDA replicas sirf L4 ke diye min-max ke andar.
- **DB-bound wait pe scale-up mat karo.** Pending connections high aur DB saturated ho to pods badhane se problem badhegi.
- Predictive scale-up (KEDA + forecast) baad wale phase mein.

### L6. In-Process Control (har pod ke andar)
- **Adaptive concurrency limit:** Netflix `concurrency-limits` (Gradient2/AIMD/Vegas) ya Envoy adaptive concurrency filter, L4 ki min-max ke andar. Yeh fast loop hai (seconds) aur ismein AI nahi hota.
- **Bulkhead:** blocking kaam ke liye dedicated executors, `commonPool` mein blocking nahi.
- **Hikari pool:** default **fixed size** (Hikari khud yahi recommend karta hai). Default mein sirf concurrency limit tune hota hai. Pool resize **opt-in** hai, rarely, L4 ke through, `HikariConfigMXBean.setMaximumPoolSize()` se. FlexyPool ko evaluate karo.
- Java version: JDK 24+ pe `synchronized` pinning ka issue lagbhag khatam hai (JEP 491).

### L7. Human-in-Loop & Audit
- AI engineers suggestions aur explanations audit karein.
- Approval flow: Phase 2 mein har change human approve kare, Phase 3 mein sirf range se bahar ya bade changes.
- Dashboards: suggestion vs final value vs actual outcome, per region.

---

## Cross-cutting concerns
- **Region isolation:** per-region limits, DB budget, guard instance, scoped data/permissions. Global layer sirf shared constraints ke liye (shared DB, cost cap), aur woh bhi deterministic.
- **Security & privacy:** business data LLM mein jaata hai, to access control, data masking, prompt injection checks.
- **Two loops, two speeds:** fast loop (L6, seconds, no AI) aur slow loop (L3 → L4 → L5/L6, minutes).

## Rollout phases
1. **Observe:** L2 metrics + dashboards, load test se static min-max (L1). AI nahi.
2. **Rule-based adaptive:** L4 guard + L6 adaptive concurrency limit.
3. **Shadow AI:** L3 suggest karta hai, apply nahi hota. Suggestion vs actual compare.
4. **Recommend:** AI suggestion + human approval, phir apply.
5. **Bounded autonomy:** range ke andar chhote changes auto, bade changes human.

## Evaluation (AI zaroori hai, yeh kaise prove karenge)
- Purane incidents aur load patterns ko **replay** karke compare karo: static vs rule-based (Phase 2) vs AI (Phase 3).
- **Metrics:** SLO breaches, incidents per month, p95 latency, DB connection saturation, cost (pods), OOM count, false freezes.
- Agar rule-based approach kaafi nikla, to AI ka hissa advisory/explanation tak simit rakho.

## Existing building blocks (web se verify kiye)
- Netflix `concurrency-limits`: Java, AIMD / Vegas / Gradient2.
- Envoy adaptive concurrency filter (gradient controller).
- `HikariConfigMXBean.setMaximumPoolSize()` runtime pe. Caveat: Hikari fixed pool recommend karta hai.
- FlexyPool: metrics-based dynamic pool resizing.
- KEDA (custom metrics) + Kedastral (predictive).
- AI SRE agents with human approval (LangChain), Observe → Recommend → Autonomy maturity model, OPA/Kyverno policy guardrails.
- JEP 491 (JDK 24), ScopedValue final (JEP 506, JDK 25).

---

# PART 2: Approach + System Design + LLD

## A. Problem ko approach kaise karenge (step by step)
1. **Problem ko measurable banao:** "throughput drop + CPU low" ko signals mein badlo: Hikari pending > 0 lagataar, acquire time p95 badhna, executor queue badhna, p95 latency SLO se upar.
2. **Constraints pehle likho:** region-wise min-max (L1), DB budget, memory formula. Yeh hard rules hain aur kabhi AI override nahi karega.
3. **Decision loop banao:** collect → analyse (forecast + anomaly) → suggest (LLM, optional) → guard (clamp + checks) → approve (mode ke hisaab se) → apply → audit.
4. **Har step replaceable rakho** (interfaces), taaki in-memory se DB, simulator se real Kubernetes, ek LLM se doosra baad mein swap ho sake.
5. **Fail-safe default:** koi bhi step fail ho to last good ya static default value.

## B. Scope (pehla version)
- **Multi-module Maven project** (Java 21, Spring Boot 3.x), isi repo mein: ek **library/starter** jo doosri services POM dependency ki tarah add karengi, aur ek **controller service** (section L dekho).
- **Storage:** sab in-memory (`ConcurrentHashMap` + bounded ring buffers), repository interfaces ke peeche.
- **Pods / Kubernetes:** abhi **simulated**. Ek `SimulatedClusterActuator` replicas aur pool size "apply" karta hai aur metrics generate karta hai. Ek local demo ke liye real Hikari pool ko `HikariConfigMXBean` se resize kar sakte hain.
- **LLM:** Claude Messages API, API key env variable se (`ANTHROPIC_API_KEY`), official Anthropic Java SDK (`com.anthropic:anthropic-java`).
- **Baad mein (scope se bahar):** real Kubernetes/KEDA actuator, persistent DB, ML forecasting, UI.

## C. High-Level System Design

```
          (Pods / Simulator)                     (Product owner / Engineer)
                │ metrics push                         │ policies, approvals
                ▼                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│ adaptive-resilience-controller (Spring Boot)                           │
│                                                                        │
│  REST API layer: /metrics  /policies  /decisions  /approvals  /audit   │
│        │                                                               │
│  MetricsIngestService ──► MetricsStore (ring buffer per pod/region)    │
│                                                                        │
│  DecisionScheduler (har region, har N sec)                             │
│     └─► DecisionEngine                                                 │
│          1. SignalAnalyzer      (aggregate, EWMA forecast, anomaly)    │
│          2. AdvisorService      (LLM, optional, timeout, fallback)     │
│          3. GuardService ★      (clamp, DB budget, memory, step,       │
│                                  cooldown, freeze)                     │
│          4. ApprovalService     (mode: SHADOW / RECOMMEND / AUTO)      │
│          5. Actuator            (Simulated / Hikari MXBean / K8s later)│
│          6. AuditService        (har step ka record)                   │
│                                                                        │
│  Stores (in-memory): Policy, Metrics, Decision, Audit, Approval        │
└────────────────────────────────────────────────────────────────────────┘
                │ HTTPS (API key)
                ▼
        Claude Messages API (structured JSON output)
```

**Do loops:**
- **Fast loop (pod ke andar, seconds):** adaptive concurrency limit (Netflix `concurrency-limits`), guard ki min-max ke andar. Isme LLM nahi hota.
- **Slow loop (controller, jaise har 60 sec):** upar wala DecisionEngine flow.

**Modes (rollout phases ke hisaab se), per region:**
- `SHADOW`: decision banta hai, sirf audit hota hai, apply nahi hota.
- `RECOMMEND`: decision `PENDING_APPROVAL` mein jaata hai, human approve kare tab apply.
- `AUTO`: guard ke andar chhote changes auto, bade changes (step > threshold) approval ke liye.

## D. Decision flow (ek cycle, ek region)
1. `SignalAnalyzer` last window (jaise 5 min) ke metrics se `RegionSignals` banata hai: avg/p95 latency, pending connections, acquire time, queue size, memory, thread states, forecast load.
2. **Freeze check:** anomaly, deploy, incident flag ya manual freeze ho to cycle skip + audit.
3. `RuleBasedAdvisor` hamesha ek baseline suggestion deta hai (deterministic, LLM ke bina).
4. `LlmAdvisor` (agar enabled hai) signals + policy + business context ke saath LLM call karta hai. Timeout ya error ho to baseline suggestion use hota hai.
5. `GuardService` final value nikalta hai (L4 ke saare rules) aur har lagaye gaye rule ka reason record karta hai.
6. Mode ke hisaab se apply / pending / sirf audit.
7. `AuditRecord` mein sab kuch: signals, baseline, LLM suggestion, guard steps, final value, mode, approver.

## E. Low-Level Design (Java)

**Package structure** (`io.github.harshgupta2.resilience`, groupId `io.github.harsh-gupta-2` se match; hyphen Java package mein allowed nahi):
```
api/           controllers + request/response DTOs
domain/        records: RegionPolicy, MetricSnapshot, RegionSignals, Suggestion, Decision, AuditRecord
store/         interfaces + inmemory/ implementations
engine/        DecisionScheduler, DecisionEngine, SignalAnalyzer
advisor/       Advisor (interface), RuleBasedAdvisor, LlmAdvisor, LlmClient, ClaudeLlmClient
guard/         GuardService, GuardRule (interface) + rules: ClampRule, DbBudgetRule,
               MemoryBudgetRule, MaxStepRule, CooldownRule, FreezeRule
actuator/      Actuator (interface), SimulatedClusterActuator, HikariPoolActuator
approval/      ApprovalService
config/        AppConfig, LlmProperties
```

**Core interfaces:**
```java
interface Advisor { Suggestion suggest(RegionSignals s, RegionPolicy p); }
interface LlmClient { Optional<LlmSuggestion> advise(LlmAdviceRequest req); }  // empty on timeout/error/refusal
interface GuardRule { GuardResult apply(Proposed p, GuardContext ctx); }      // chain of rules, order fixed
interface Actuator { ApplyResult apply(String region, TargetConfig target); }
interface PolicyStore / MetricsStore / DecisionStore / AuditStore              // in-memory abhi
```

**Key domain records:**
```java
record Range(int min, int max) {}

record RegionPolicy(
    String region, String service,
    Range replicas, Range poolSizePerPod, Range concurrencyLimit,
    int dbConnectionBudget,            // region ka total DB connections
    long podMemoryLimitMb, double memorySafetyFactor,   // jaise 0.85
    double maxStepPercent,             // jaise 0.20
    Duration cooldown,
    Mode mode,                         // SHADOW / RECOMMEND / AUTO
    TargetConfig staticDefault,        // fallback
    String businessCriticality,        // HIGH / MEDIUM / LOW
    Slo slo) {}
// version / updatedBy policy store (controller) ka hissa hain, core record ka nahi (PR 2 mein decide hua).

record MetricSnapshot(
    String region, String podId, Instant at,
    int hikariActive, int hikariIdle, int hikariPending, double acquireTimeMsP95,
    int executorQueueSize, int blockedThreads, int waitingThreads,
    double latencyMsP95, double errorRate, double rps,
    long heapUsedMb, long oldGenAfterGcMb, long nonHeapMb, int threadCount) {}

record TargetConfig(int replicas, int poolSizePerPod, int concurrencyLimit) {}

record Decision(
    String id, String region, Instant at, Mode mode,
    TargetConfig current, TargetConfig baseline, TargetConfig llmSuggested,
    TargetConfig finalTarget, List<String> guardSteps,
    DecisionStatus status,             // AUDIT_ONLY / PENDING_APPROVAL / APPLIED / REJECTED / FROZEN
    String llmExplanation, String approvedBy) {}
```

**GuardService order (deterministic):**
1. `FreezeRule` → freeze ho to current config hi rakho.
2. `ClampRule` → har value ko policy range mein clamp.
3. `MaxStepRule` → current se ±maxStep% se zyada change nahi.
4. `DbBudgetRule` → `replicas × poolSizePerPod ≤ dbConnectionBudget`. Na ho to pool ghatao (min tak), phir bhi na ho to replicas ghatao.
5. `MemoryBudgetRule` → `heapMaxMb + nonHeapMb + threadCount × stackMb + poolSize × perConnOverheadMb ≤ podMemoryLimitMb × safetyFactor`. Na ho to pool/concurrency ghatao.
6. `CooldownRule` → last apply se cooldown pura nahi hua to change nahi.
- Har rule `GuardResult(TargetConfig, Optional<String> reason)` return karta hai, reasons `guardSteps` mein jaate hain.
- Agar clamp ke baad bhi constraints satisfy na hon (policy hi galat hai), to `staticDefault` + alert.

**Concurrency in the controller:**
- Har region ka cycle alag `ScheduledExecutorService` task ya virtual thread mein. Ek region fail ho to doosre ko asar nahi (region isolation).
- Per-region lock (`ConcurrentHashMap<String, ReentrantLock>`), taaki ek region ke do cycles ek saath na chalein.
- LLM call ka **hard timeout** (jaise 20 sec). Loop kabhi LLM ka wait karke atakta nahi.

## F. In-memory storage design
| Store | Structure | Notes |
|---|---|---|
| `PolicyStore` | `ConcurrentHashMap<String region, List<RegionPolicy>>` (versions) | Har update naya version, purana history mein. |
| `MetricsStore` | `ConcurrentHashMap<String region, ConcurrentHashMap<String podId, RingBuffer<MetricSnapshot>>>` | **Bounded** (jaise last 600 snapshots per pod). Unbounded list = memory leak (problem #4 hi). |
| `DecisionStore` | `ConcurrentHashMap<String id, Decision>` + per-region bounded deque | Pending approvals isi se. |
| `AuditStore` | Append-only bounded deque (jaise last 10,000) | Baad mein DB / Kafka topic mein jaayega. |
| `ApplyStateStore` | `ConcurrentHashMap<String region, AppliedState(TargetConfig, Instant lastAppliedAt)>` | Cooldown aur current config ke liye. |
- Sab store **interfaces** ke peeche hain. Baad mein PostgreSQL/Redis implementation daal sakte hain bina engine badle.
- Restart pe data chala jaata hai (known limitation). Policies `application.yml` se seed hongi.

## G. REST API (request / response models)

**1. Metrics ingest:** `POST /api/v1/metrics`
```json
// request
{ "region": "ap-south-1", "podId": "orders-7f9c", "at": "2026-10-06T10:00:00Z",
  "hikari": { "active": 18, "idle": 0, "pending": 25, "acquireTimeMsP95": 850 },
  "executorQueueSize": 120, "threads": { "blocked": 4, "waiting": 60, "total": 210 },
  "latencyMsP95": 1200, "errorRate": 0.02, "rps": 340,
  "memory": { "heapUsedMb": 900, "oldGenAfterGcMb": 600, "nonHeapMb": 180 } }
// response 202
{ "accepted": true }
```

**2. Policy:** `PUT /api/v1/policies/{region}` (PO + engineer), `GET /api/v1/policies/{region}`
```json
{ "service": "orders", "replicas": {"min": 2, "max": 10},
  "poolSizePerPod": {"min": 5, "max": 20}, "concurrencyLimit": {"min": 20, "max": 200},
  "dbConnectionBudget": 120, "podMemoryLimitMb": 2048, "memorySafetyFactor": 0.85,
  "maxStepPercent": 0.2, "cooldownSeconds": 300, "mode": "RECOMMEND",
  "staticDefault": {"replicas": 4, "poolSizePerPod": 10, "concurrencyLimit": 80},
  "businessCriticality": "HIGH", "slo": {"latencyMsP95": 500, "errorRate": 0.01},
  "updatedBy": "harsh" }
```
Validation: `min ≤ max`, `staticDefault` range ke andar, `replicas.max × poolSizePerPod.min ≤ dbConnectionBudget`, warna 400.

**3. Decisions:** `POST /api/v1/decisions/{region}/evaluate` (manual trigger), `GET /api/v1/decisions?region=&status=`
```json
// response
{ "id": "dec-123", "region": "ap-south-1", "mode": "RECOMMEND", "status": "PENDING_APPROVAL",
  "current":      {"replicas": 4, "poolSizePerPod": 10, "concurrencyLimit": 80},
  "baseline":     {"replicas": 5, "poolSizePerPod": 10, "concurrencyLimit": 60},
  "llmSuggested": {"replicas": 8, "poolSizePerPod": 18, "concurrencyLimit": 60},
  "final":        {"replicas": 5, "poolSizePerPod": 12, "concurrencyLimit": 64},
  "guardSteps": ["MaxStepRule: replicas 8→5 (max 20% step)",
                 "DbBudgetRule: 5×18=90 ok", "MemoryBudgetRule: pool 18→12 (memory)"],
  "llmExplanation": "Pending connections high, DB not saturated; scale moderately..." }
```

**4. Approval:** `POST /api/v1/decisions/{id}/approve` | `/reject`
```json
{ "by": "harsh", "comment": "ok for peak hour" }
```

**5. Freeze:** `POST /api/v1/regions/{region}/freeze` | `/unfreeze` with `{ "by": "...", "reason": "deploy" }`

**6. Audit:** `GET /api/v1/audit?region=&from=&to=`

**7. Simulator (demo):** `POST /api/v1/simulator/{region}/scenario` with `{ "scenario": "DB_SLOW" | "TRAFFIC_SPIKE" | "MEMORY_LEAK" | "NORMAL" }`

## H. LLM calling design (Claude Messages API)
- **Auth:** API key **sirf environment variable** `ANTHROPIC_API_KEY` se. Code, config files ya logs mein kabhi nahi. Client: `AnthropicOkHttpClient.fromEnv()`.
- **SDK:** `com.anthropic:anthropic-java` (official). Exact version build ke time check karenge.
- **Model:** `claude-opus-5-5`, `application.yml` mein configurable (`llm.model`).
- **Effort:** `OutputConfig.Effort.MEDIUM` se start. Low ke saath bhi evaluation karenge, kyunki yeh task chhota aur structured hai.
- **Structured output:** class-based `.outputConfig(LlmSuggestion.class)`. Response seedha typed object mein aata hai, manual JSON parsing nahi.
- **Timeout + retries:** client pe request timeout (jaise 20 sec), SDK ke default 2 retries. Uske baad `Optional.empty()` aur baseline use hota hai.
- **Stop reason check:** `refusal` ya `max_tokens` ho to suggestion ignore karo, audit mein reason likho.
- **Refusal fallback:** Opus 5.5 ke liye server-side `fallbacks` option available hai. Java builder ka exact tareeka implementation ke time SDK examples se confirm karenge. Hamara apna fallback (baseline/static default) hamesha rahega.
- **Prompt injection:** metrics aur business notes "data" ke roop mein JSON mein bhejo. System prompt clearly kahe ki data ke andar ki instructions follow nahi karni. LLM ko koi tool ya write permission nahi.
- **Enabled flag:** `llm.enabled=false` ho to sirf RuleBasedAdvisor (bina key ke bhi app chal jaaye).

**LLM request model (hum kya bhejenge):**
```java
record LlmAdviceRequest(
    String region, String service, String businessCriticality,
    RegionPolicy policy,          // ranges, budget, SLO
    TargetConfig current,
    RegionSignals signals,        // aggregated metrics + forecast
    TargetConfig ruleBasedBaseline,
    List<String> recentDecisions, // last 5 decisions ka summary
    List<String> businessContext  // jaise "Diwali sale 6-10pm", region notes
) {}
```
- **System prompt (stable, cache-friendly):** role ("capacity advisor"), hard rules (range ke bahar value mat do, DB budget respect karo), output format ka matlab, injection warning.
- **User message:** upar wala `LlmAdviceRequest` JSON ke roop mein.

**LLM response model (structured output schema):**
```java
record LlmSuggestion(
    @JsonPropertyDescription("Suggested pod replicas, must be within policy range") int replicas,
    @JsonPropertyDescription("Suggested Hikari max pool size per pod") int poolSizePerPod,
    @JsonPropertyDescription("Suggested concurrency limit per pod") int concurrencyLimit,
    @JsonPropertyDescription("0.0-1.0 confidence") double confidence,
    @JsonPropertyDescription("Short reasoning for engineers to audit") String explanation,
    @JsonPropertyDescription("Risks or things engineers should check") List<String> risks
) {}
```
- LLM ki values guard se pehle bhi validate hongi (range, null, negative). Galat ho to discard.

**Call ka sketch (SDK docs ke patterns se):**
```java
StructuredMessageCreateParams<LlmSuggestion> params = MessageCreateParams.builder()
    .model(props.model())                      // "claude-opus-5-5"
    .maxTokens(4000L)
    .system(SYSTEM_PROMPT)
    .outputConfig(LlmSuggestion.class)
    .addUserMessage(objectMapper.writeValueAsString(request))
    .build();
// effort: OutputConfig.Effort.MEDIUM (exact combination with typed outputConfig build ke time confirm)
```

## I. Config (`application.yml`)
```yaml
controller:
  cycle-seconds: 60
  metrics-window-minutes: 5
  ring-buffer-size: 600
llm:
  enabled: true
  model: claude-opus-5-5
  effort: medium
  timeout-seconds: 20
  max-tokens: 4000
policies:            # seed policies, region-wise
  - region: ap-south-1
    ...
```

## J. Build order: MVP-first (final)
Architecture upar wali hi hai (north star). Banayenge chhote, chalne layak MVPs mein. Har MVP ke end mein kuch dikhane layak hoga.

**MVP 1: `core` + starter (embedded mode)**
- Modules: parent `pom.xml`, `adaptive-resilience-core`, `adaptive-resilience-spring-boot-starter` (sirf embedded mode), `examples/demo-orders-service`.
- `core`: domain records, `GuardService` + saare rules (**sabse zyada unit/property tests yahan**), policy validation, `RuleBasedAdvisor`.
- Starter: metrics collector, concurrency limiter (default mein yahi tune hota hai), local guard, **default shadow mode**, actuator endpoint.
- **Pool resize opt-in:** `adaptive.resilience.pool-tuning.enabled=false` default. On karne par hi `HikariConfigMXBean` se resize.
- Demo service + load script: shadow mode mein suggestions log, auto mode mein concurrency limit adjust.

**MVP 2: Controller (rule-based, AI nahi)**
- `controller-autoconfigure` + thin `controller` app, in-memory stores, Policy/Metrics/Decision/Approval/Audit/Targets APIs.
- Starter ka `agent` mode (push + pull) aur **Prometheus source** (section N).
- Simulator scenarios (`DB_SLOW`, `TRAFFIC_SPIKE`, `MEMORY_LEAK`, `NORMAL`), modes (SHADOW/RECOMMEND/AUTO).
- Security basics (section O): service tokens, roles, signed targets.

**MVP 3: Evaluation pehle, phir LLM**
- **Evaluation/replay harness pehle:** recorded ya simulated metric timelines ko replay karke compare karo: static vs rule-based (vs baad mein LLM). Output: SLO breaches, DB saturation minutes, changes count, guard interventions.
- Phir `adaptive-resilience-llm-claude` + `LlmAdvisor` (`llm.enabled=false` default), shadow mode mein.
- LLM tabhi `RECOMMEND` mein jaye jab replay mein rule-based se behtar dikhe. Warna sirf explanation ke liye.

**MVP 4: Real infra + publish**
- Kubernetes actuator (kind/minikube pe demo), taaki pod × pool coordination asli mein dikhe.
- Controller HA (section O), persistent stores (PostgreSQL) interface ke peeche.
- BOM, docs, GitHub Packages/JitPack, phir Maven Central.

## P. Development process: AI banayega, human har PR review karega

**Setup:** Shuru se end tak saara code AI likhega. Har PR ko ek human review karke merge karega. Isliye AI ke kaam ke liye neeche ke rules **zaroori** hain.

### P1. No-hallucination rules (AI ke liye)
1. **Koi API guess nahi.** Har external library (Spring Boot, HikariCP, Netflix `concurrency-limits`, Anthropic Java SDK, Micrometer, Prometheus) ki class, method aur property ka naam official docs, source code ya compile karke verify hoga. Verify na ho paaye to code mein `TODO(verify)` aur PR description mein "Unverified" list mein likhna.
2. **Dependency tabhi add hogi jab** Maven se actually resolve ho (`mvn dependency:resolve`), version pinned ho, aur license compatible ho. Koi made-up artifact ya version nahi.
3. **Har claim ka saboot:** PR mein jo bhi likha ho ("tests pass", "latency kam hui", "rule sahi hai"), uska command aur output PR description mein. Bina chalaye "kaam karta hai" nahi likhna.
4. **Pata nahi to puchho.** Requirement ya design clear na ho to AI guess nahi karega. PR mein "Questions for reviewer" section mein puchega, ya kaam rokega.
5. **Plan se hatna ho to batao.** Plan (yeh document) se koi deviation ho to PR mein clearly likhna: kya badla, kyun, aur kya alternative tha.
6. **Numbers invent nahi.** Default values (jaise 20% step, 300 sec cooldown) "starting assumption" ke roop mein config mein, aur PR mein mention. Performance numbers sirf measured.
7. **Secrets kabhi code/config/logs mein nahi** (API key, tokens sirf env se).

### P2. Logically aur "insaan ki tarah" sochna
- **Simple pehle:** sabse simple solution jo kaam kare. Clever code nahi, readable code, kyunki reviewer insaan hai.
- **Har design choice ke saath "kyun":** chhote decisions code comment ya PR mein, bade decisions `docs/adr/NNN-title.md` (Architecture Decision Record): context, options, decision, trade-offs.
- **Failure pehle socho:** har feature ke liye "yeh kaise fail hoga?" (null, timeout, controller down, galat config) aur uska test.
- **Reviewer ka time bachao:** PR chhota, ek concern, padhne layak.

### P3. PR rules
- **Size:** chhota (ideally ~400 lines se kam, tests ko chhod ke). Ek PR = ek clear step.
- **Har PR mein tests.** Guard rules ke liye unit + property tests zaroori.
- **Local checks pass hone ke baad hi PR:** `mvn -B verify` (build, tests, formatting/lint jab add ho jaaye).
- **CI:** pehle PR mein hi GitHub Actions workflow jo har PR pe `mvn -B verify` chalaye.
- **Branch:** AI sirf apne designated branch pe kaam karega. Ek time pe ek PR. Merge hone ke baad branch latest `main` se dobara shuru hoga.
- **Human merge karega.** AI khud merge nahi karega.

### P4. PR description template
```
## Kya aur kyun
## Plan mein kahan (MVP / step)
## Kaise verify kiya (commands + output)
## Assumptions
## Unverified items (TODO(verify))
## Risks / limitations
## Plan se deviation (agar koi)
## Questions for reviewer
```

### P5. Reviewer checklist (human ke liye)
- [ ] PR plan ke step se match karta hai, scope se bahar kuch nahi.
- [ ] Koi unknown/made-up API ya dependency nahi (shak ho to "source dikhao" bolo).
- [ ] Tests meaningful hain, sirf happy path nahi.
- [ ] Guard ke safety rules bypass nahi ho sakte.
- [ ] Koi secret nahi, logs mein sensitive data nahi.
- [ ] Naam aur code samajh mein aate hain.

### P6. MVP 1 ke pehle PRs (proposed)
1. **PR 1:** Maven multi-module skeleton (parent, `core`, `starter` khaali), Java 21, GitHub Actions CI, `.gitignore`, README (project naam + plan link). License aap chunoge.
2. **PR 2:** `core` domain records + policy validation + tests.
3. **PR 3:** Guard rules + `GuardService` + unit/property tests.
4. **PR 4:** `RuleBasedAdvisor` + tests.
5. **PR 5:** Starter: properties, auto-config, shadow mode, metrics collector, actuator endpoint.
6. **PR 6:** Concurrency limiter integration (pehle `concurrency-limits` ka current version aur maintenance status verify; theek na ho to apna simple AIMD limiter, ADR ke saath).
7. **PR 7:** Demo service + load script + README quick start.

## N. Metrics source: Prometheus ya agent push
- Zyadatar companies mein metrics pehle se Prometheus mein hote hain. Controller ka `MetricsSource` interface:
  - `PrometheusMetricsSource`: PromQL queries se region/service ke metrics padhta hai (Hikari, latency, JVM memory via Micrometer). Query templates config mein, taaki har company apne metric names map kar sake.
  - `PushMetricsSource`: agent push (`POST /api/v1/metrics`), jahan Prometheus nahi hai.
- Prometheus mode mein starter ko sirf **targets pull** aur apply karna hota hai (push optional), isliye adoption aasaan.
- Thread-state sampling (`ThreadMXBean`) default off ya kam frequency pe, kyunki yeh mehnga hai.

## O. Security + HA
**Security (controller prod config badalta hai, isliye zaroori):**
- **AuthN:** service-to-controller: per-service token (env se) ya mTLS. Humans: company SSO/OIDC (yeh aapka strong area hai).
- **AuthZ (roles):** `POLICY_ADMIN` (PO + lead engineer, policies badalna), `APPROVER` (decisions approve), `VIEWER` (audit padhna), `SERVICE` (sirf apni service ke metrics push/targets pull).
- **Policy change bhi audit** hota hai (kisne, kab, purana vs naya), aur badi range change pe dusre insaan ka approval (four-eyes).
- **Signed targets:** controller targets ko sign kare (jaise JWS), starter verify kare. Tampered ya expired (`validUntil`) target ignore.
- Starter ka **local guard + local-limits** hamesha last line of defense. Compromised controller bhi local range ke bahar nahi le ja sakta.
- LLM: API key sirf controller ke env/secret manager mein. Business data masking, prompt injection guard (section H).

**HA (High availability):**
- MVP 1-3 mein ek controller instance + in-memory (known limitation, docs mein likhna).
- MVP 4: multiple instances + shared store (PostgreSQL) + **leader election** (jaise ShedLock ya Kubernetes Lease), taaki ek region ka decision cycle sirf ek instance chalaye.
- Controller down = system "freeze" jaisa: pods last applied ya local static values pe chalte rehte hain. Isliye controller kabhi critical path mein nahi hai.

## L. Log isse POM dependency ki tarah kaise use karenge

### L1. Do cheezein hongi: library + service
- **Library (starter):** har business service (orders, payments...) apne `pom.xml` mein add karegi. Yeh pod ke andar chalti hai: metrics collect karti hai, concurrency limit lagati hai, aur controller se aayi target values apply karti hai.
- **Controller (service):** alag deploy hota hai (jar / Docker image), dependency nahi. Isme DecisionEngine, Guard, LLM call, approvals, audit hain.
- **LLM API key sirf controller mein** rehti hai. Business services ko key ki zaroorat nahi.

### L2. Maven modules
```
Practice-Project-1/ (rename baad mein, jaise adaptive-resilience)
  pom.xml                                     parent (versions, plugins)
  adaptive-resilience-bom/                    BOM: sab modules ke versions align
  adaptive-resilience-core/                   pure Java (no Spring): domain records, GuardRule + rules,
                                              policy validation, RuleBasedAdvisor
  adaptive-resilience-llm-claude/             LlmClient ka Claude implementation (optional)
  adaptive-resilience-spring-boot-starter/    business services ke liye (agent)
  adaptive-resilience-controller-autoconfigure/  controller logic as library (engine, APIs, stores)
  adaptive-resilience-controller/             thin Spring Boot app (deployable)
  examples/demo-orders-service/               starter use karne ka example
```
- `core` ko starter aur controller dono use karte hain, isliye guard rules ek hi jagah likhe jaate hain.
- `llm-claude` alag module hai taaki jo LLM nahi chahta uske classpath pe Anthropic SDK na aaye. Baad mein doosre providers ke liye `llm-xyz` modules ban sakte hain (`LlmClient` interface same).

### L3. Starter kya karega (auto-configuration)
- Spring Boot 3 auto-config (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`), sab beans `@ConditionalOnMissingBean` / `@ConditionalOnProperty`, taaki user override kar sake.
- **Metrics collector:** `HikariPoolMXBean` (active, idle, pending) + Micrometer metrics (agar classpath pe ho), executor queue, thread states (`ThreadMXBean`, sampling), heap/old-gen.
- **Concurrency limiter:** servlet filter (Netflix `concurrency-limits` pe based), limit policy range ke andar.
- **Target applier:** controller se target lekar limiter update. Pool resize (`HikariConfigMXBean.setMaximumPoolSize()`) sirf tab jab `pool-tuning.enabled=true` (default off, kyunki Hikari fixed pool recommend karta hai). **Local guard bhi lagata hai** (`core` ke rules + local min-max), yaani controller galat value bheje tab bhi pod safe rahe (defense in depth).
- **Actuator endpoint:** `/actuator/adaptive-resilience` jo current limits, last target aur last decision id dikhaye.
- **Fail-safe:** controller unreachable ho to last applied ya local static values. App kabhi controller ki wajah se start/run fail nahi hogi.

### L4. Starter ke do modes
| Mode | Kya hota hai | Kiske liye |
|---|---|---|
| `agent` (default) | Metrics controller ko push, targets controller se pull | Badi teams, multi-pod, multi-region, LLM/approval chahiye |
| `embedded` | Controller nahi. Pod ke andar hi `core` ka guard + RuleBasedAdvisor sirf pool/concurrency adjust karta hai. Pod scaling nahi | Chhoti service, jaldi try karna ho |

### L5. Agent ↔ Controller protocol
- `POST /api/v1/metrics` (har 15 sec, batch), upar section G wala model.
- `GET /api/v1/targets/{region}/{service}`. Response: `{ "decisionId": "...", "version": 7, "target": {"poolSizePerPod": 12, "concurrencyLimit": 64}, "validUntil": "..." }`. `ETag`/`version` se sirf change hone par apply.
- Controller mein naya endpoint: `GET /api/v1/targets/...` (section G mein add).
- Auth: service token (env variable se) ya mTLS. Token kabhi `application.yml` mein hardcode nahi.

### L6. Consumer service ka `pom.xml`
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.harsh-gupta-2</groupId>
      <artifactId>adaptive-resilience-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.harsh-gupta-2</groupId>
    <artifactId>adaptive-resilience-spring-boot-starter</artifactId>
  </dependency>
</dependencies>
```

### L7. Consumer service ka `application.yml`
```yaml
adaptive:
  resilience:
    enabled: true
    mode: agent                  # agent | embedded
    service-name: orders
    region: ap-south-1
    controller:
      url: https://resilience-controller.internal
      token: ${ADAPTIVE_RESILIENCE_TOKEN}
      push-interval: 15s
      pull-interval: 30s
    apply-mode: shadow           # shadow (sirf log) | auto (apply)
    local-limits:                # hard safety, controller bhi inse bahar nahi le ja sakta
      pool-size: { min: 5, max: 20 }
      concurrency-limit: { min: 20, max: 200 }
```
- Bina kisi config ke add karne par starter **shadow mode** mein chalta hai, yaani kuch badalta nahi, sirf metrics aur logs. Isse naya user safely try kar sakta hai.

### L8. Publish kaise karenge
1. **Pehle:** local `mvn install` aur example service se test.
2. **Phir:** GitHub Packages ya JitPack (jaldi share karne ke liye).
3. **Baad mein:** Maven Central. `io.github.<github-username>` namespace GitHub account se verify hota hai. Sources jar, javadoc jar, GPG signing chahiye. Exact steps publish ke time Sonatype docs se confirm karenge.
- **Versioning:** SemVer (`0.x` jab tak API stable nahi). BOM ke through sab modules ek version pe.
- **Compatibility:** Java 21+, Spring Boot 3.x. README mein compatibility table.
- **Docs:** README (quick start, config reference, modes, FAQ). `examples/demo-orders-service` ek chalta hua example.

### L9. Build order
- Section J (MVP 1→4) follow karna hai.

## M. Koi apni application ke hisaab se kaise tailor karega (extension points)

Haan, koi bhi dependency add karke ise apni application ke hisaab se customise kar sakta hai, **bina hamara code badle**. Customisation ke 4 levels hain:

**Level 1: Sirf config (`application.yml`)**
Limits, modes (`shadow`/`auto`, `agent`/`embedded`), intervals, region, service name, aur har feature ka on/off (`adaptive.resilience.limiter.enabled=false`, `...pool-tuning.enabled=false`).

**Level 2: Apna bean do (Spring override)**
Starter ke saare beans `@ConditionalOnMissingBean` hain. User apna bean define kare to hamara default hat jaata hai. Public extension interfaces:

| Interface | Default | User kya bana sakta hai |
|---|---|---|
| `MetricsCollector` | Hikari + Micrometer + JVM | Apne custom metrics (jaise queue depth, Kafka lag) |
| `BusinessContextProvider` | Empty | Apna business data (sale calendar, region notes) jo LLM ko context mein jaaye |
| `Advisor` | RuleBasedAdvisor (+ LlmAdvisor) | Apna ML model ya rules |
| `LlmClient` | Claude (`llm-claude` module) | Self-hosted ya doosra LLM provider |
| `GuardRule` | Clamp, DB budget, memory, step, cooldown, freeze | **Extra** rules (jaise "business hours mein scale-down nahi") |
| `Actuator` | Simulated / Hikari MXBean | Kubernetes, KEDA ya apna infra |
| `PolicyStore`, `AuditStore`, ... | In-memory | PostgreSQL, Redis, Kafka |
| `ApprovalNotifier` | Log only | Slack, email, Jira |

Example: apna guard rule add karna
```java
@Bean
GuardRule noScaleDownInBusinessHours(Clock clock) {
    return (proposed, ctx) -> isBusinessHours(clock)
        && proposed.replicas() < ctx.current().replicas()
            ? GuardResult.adjusted(proposed.withReplicas(ctx.current().replicas()),
                                   "No scale-down in business hours")
            : GuardResult.unchanged(proposed);
}
```

**Level 3: Sirf `core` module use karna**
Jo Spring use nahi karte (Quarkus, Micronaut, plain Java), woh `adaptive-resilience-core` lekar guard rules aur policy model directly use kar sakte hain.

**Level 4: Apna controller banana**
Controller ka logic bhi ek library module mein hoga (`adaptive-resilience-controller-autoconfigure`), aur deployable `controller` app sirf ek patli wrapper hai. Koi company apna controller app bana sakti hai, apne beans (store, actuator, LLM, approval) ke saath. Isse module list mein yeh ek module aur add hota hai.

**Safety rule jo tailor nahi ho sakta:**
User **extra** `GuardRule` add kar sakta hai, par core safety rules (Clamp, DbBudget, MemoryBudget, Freeze) hamesha **last mein** chalte hain aur unhe remove/override nahi kar sakte. Matlab koi bhi custom advisor ya rule range aur DB budget ke bahar value nahi bhej sakta. Yahi hamara differentiator protect karta hai.

**Stable API ka vaada:**
- Extension interfaces `...api` package mein, SemVer ke saath. Internal classes `...internal` mein, unpe koi guarantee nahi.
- License: open source (jaise Apache 2.0), taaki companies use aur fork kar sakein. Final choice aapki.
- README mein har extension point ka chhota example.

## K. Testing / Verification
- **Extension tests:** user ka custom `GuardRule`/`Advisor`/`LlmClient` bean default ko replace kare; custom rule ke baad bhi core safety rules last mein chalein aur range/DB budget kabhi break na ho.
- **Starter tests:** `ApplicationContextRunner` se auto-config tests (default shadow mode, properties binding, user ke beans override, Hikari na ho to bhi app start ho), controller down hone par fallback.
- **Unit tests:** har GuardRule (clamp, DB budget violation, memory, step, cooldown, freeze), policy validation, SignalAnalyzer.
- **LLM tests:** `LlmClient` ka fake implementation. Cases: normal suggestion, out-of-range values, timeout, refusal, malformed. Har case mein final value guard ke andar honi chahiye.
- **Scenario tests (simulator):** `DB_SLOW` pe replicas nahi badhne chahiye (DB-bound); `TRAFFIC_SPIKE` pe controlled scale-up; `MEMORY_LEAK` pe pool/concurrency ghatna + alert.
- **Property test:** random signals + random LLM output, final value hamesha `replicas × pool ≤ budget` aur range ke andar.
- **Manual:** `mvn spring-boot:run`, simulator scenario chalao, `/decisions` aur `/audit` check karo. LLM ke liye `ANTHROPIC_API_KEY` set karke ek real call.

---

## Risks
- LLM numeric decisions mein galat ho sakta hai. Mitigation: numeric hissa L3a mein, final value L4 se.
- Pod aur pool ki oscillation. Mitigation: cooldown, step size, single source of truth (L4).
- Data kam (incidents rare). Mitigation: RAG pehle, fine-tuning baad mein; load-test data se augment.
- Complexity: ek saath sab mat banao, phases follow karo.

## Open questions (discussion mein puchne ke liye)
1. Region-wise min-max ka owner kaun, aur review cadence kya?
2. DB ki asli max connections kitni hain? Pooler (PgBouncer / RDS Proxy) hai ya nahi?
3. Freeze aur fallback ka exact trigger kya hoga?
4. Dedicated LLM: self-hosted ya vendor? Business data ki privacy policy kya hai?
5. Company mein pehle se kya hai (AIOps tool, autoscaling policy, policy-as-code)?
6. Success ka definition: kaun sa metric kitna sudharna chahiye?
