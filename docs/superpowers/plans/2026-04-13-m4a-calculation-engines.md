# M4a: Calculation Engines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Integrate Drools 7.74.x for discount calculation and approval routing, JEXL for formula computation, and a matching JS frontend formula engine. Pass all 7 acceptance criteria before proceeding to M4b.

**Architecture:** Three independent engines. Drools: dynamic DRL generation from DB data, KieBase caching with version-based invalidation. JEXL: expression array → JEXL string → eval with variable injection. Frontend JS: equivalent expression parser using decimal.js. All Drools/JEXL services annotated @Blocking.

**Tech Stack:** Drools 7.74.x (drools-core + drools-compiler + kie-api), Apache Commons JEXL, decimal.js, Feature flags

---

**Task 1:** Add Drools 7.74.x + JEXL dependencies to pom.xml, configure JVM --add-opens
**Task 2:** KieBaseManager service (dynamic DRL compile, ConcurrentHashMap cache, version invalidation by max updated_at)
**Task 3:** DiscountCalculationService (@Blocking, DRL from PricingStrategy/PricingRule, salience=-priority, multi-strategy match, no-strategy fallback=100)
**Task 4:** ApprovalRoutingService (@Blocking, DRL from ApprovalRule, FIXED +1000 offset, DYNAMIC region/dept match, fallback=earliest SYSTEM_ADMIN)
**Task 5:** Drools fallback services (pure Java equivalents behind cpq.engine.drools.enabled flag)
**Task 6:** FormulaCalculationService (@Blocking, JEXL, component row-level + product subtotal two-level calc, cross-component default=0, ±0.01 tolerance)
**Task 7:** Frontend JS formula engine (formulaEngine.ts, decimal.js, same two-level calc, expression array parser)
**Task 8:** Formula validation API (POST /api/cpq/formulas/validate, backend JEXL recompute + tolerance check)
**Task 9:** Discount engine unit tests (9 scenarios: no-strategy, base-only, single-bulk, multi-bulk-best, below-threshold, multi-strategy-priority, all-expired, cache-hit, cache-invalidate)
**Task 10:** Approval routing unit tests (7 scenarios: FIXED, DYNAMIC-region, DYNAMIC-dept, same-priority-FIXED-wins, multi-rule-priority, no-match-fallback, submitted-not-affected-by-rule-change)
**Task 11:** Formula engine unit tests (8 scenarios: arithmetic, brackets, cross-component, default-0, circular-detect, product-attr, tolerance-boundary, tolerance-exceeded)
**Task 12:** Frontend-backend consistency test (1000 random inputs, all within ±0.01)
**Task 13:** @Blocking thread model verification (REST trigger, check logs for no IO-thread warnings)
**Task 14:** Performance benchmark (KieBase cold <500ms, single exec <50ms, 100-concurrent p95 <200ms)
**Task 15:** Fallback switch verification (flag=false, results equivalent)

### Gate: ALL 7 acceptance criteria must pass before M4b starts.
