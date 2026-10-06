# Adaptive Resilience

Guard-first adaptive resilience for Java services.

Load ke time CPU low rehta hai par throughput gir jaata hai, kyunki threads DB/HTTP pe wait karte hain (thread-pool starvation, Hikari pool exhaustion). Yeh project us problem ko is tarah handle karta hai:

- Business aur engineers har region ki **min-max limits** tay karte hain.
- Ek **deterministic guard** hard constraints enforce karta hai (range, `pods × pool ≤ DB budget`, memory, step size, cooldown, freeze).
- **AI (optional)** sirf us range ke andar suggest karta hai aur explain karta hai. Final value hamesha guard decide karta hai.

Poora design: [`docs/PLAN.md`](docs/PLAN.md).

## Status
**MVP 1, PR 4: rule-based advisor.** Progress: [`docs/PROGRESS.md`](docs/PROGRESS.md). Build order `docs/PLAN.md` ke section J mein hai.

## Modules
| Module | Kya hai |
|---|---|
| `adaptive-resilience-core` | Plain Java: domain model, policy validation, guard rules, rule-based advisor |
| `adaptive-resilience-spring-boot-starter` | Business services ke liye dependency (Spring Boot auto-config baad ke PR mein) |

## Build
Requirements: Java 21+. Maven install karne ki zaroorat nahi, wrapper use karo:

```bash
./mvnw verify
```

## Contributing
- Har PR `.github/pull_request_template.md` follow karta hai.
- Rules (no guessed APIs, har claim ka saboot, chhote PRs): `docs/PLAN.md`, section P.
- Bade design decisions: `docs/adr/`.
