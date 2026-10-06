# ADR 0001: Build setup (Maven multi-module, Java 21)

- **Status:** Proposed (PR 1)
- **Date:** 2026-10-06

## Context
Project ek library (starter) + ek controller service banega jise doosri teams Maven dependency ki tarah use karengi (`docs/PLAN.md`, sections J aur L). Code AI likhega aur har PR human review karega (section P), isliye build simple, reproducible aur CI mein same hona chahiye.

## Decision
1. **Maven multi-module**, parent `adaptive-resilience-parent`. Abhi sirf `adaptive-resilience-core` aur `adaptive-resilience-spring-boot-starter`. Baaki modules (BOM, controller, llm-claude) apne MVP ke PR mein aayenge.
2. **Java 21** (LTS). `maven-enforcer-plugin` Java 21+ aur Maven 3.9+ enforce karta hai, aur `dependencyConvergence` version conflicts pakadta hai.
3. **Coordinates:** groupId `io.github.harsh-gupta-2` (Maven Central ke `io.github.<github-username>` namespace pattern ke hisaab se; exact Central rules publish ke time verify honge). Java package `io.github.harshgupta2.resilience`, kyunki package naam mein hyphen allowed nahi.
4. **Maven Wrapper** (`./mvnw`, Maven 3.9.11), taaki local aur CI ek hi Maven version use karein.
5. **Testing:** JUnit 6 (`junit-bom`) + AssertJ. Property-testing library ka decision guard rules wale PR mein hoga.
6. **CI:** GitHub Actions, har PR aur `main` push pe `./mvnw -B verify`.

## Versions (Maven Central / GitHub tags se check kiye, 2026-10-06)
| Item | Version |
|---|---|
| JUnit BOM | 6.1.3 |
| AssertJ | 3.27.7 |
| maven-compiler-plugin | 3.16.0 |
| maven-surefire-plugin | 3.6.0 |
| maven-jar-plugin | 3.5.1 |
| maven-enforcer-plugin | 3.6.3 |
| Maven Wrapper | 3.3.4 (Maven 3.9.11) |
| actions/checkout | v7 |
| actions/setup-java | v6 (`distribution`, `java-version`, `cache: maven` inputs confirmed in its `action.yml`) |

## Consequences
- Spring Boot abhi classpath pe nahi hai. Spring Boot version (3.5.x vs 4.x) starter PR mein decide hoga.
- Plan mein package `com.harsh.resilience` likha tha; groupId se match karne ke liye `io.github.harshgupta2.resilience` kiya (plan update kar diya).
