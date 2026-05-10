# M5-M7: Output, Testing & Launch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete the quotation lifecycle with detail pages, PDF/Excel export, email sending, extend/accept/reject operations, then run comprehensive testing, optimize, and deploy.

---

## M5: Quotation Output (Week 19-20)

**Task 1:** Quotation detail API (GET /quotations/{id}/detail, snapshot data for SUBMITTED+, realtime for DRAFT, include approval history)
**Task 2:** Quotation detail frontend - page framework (top action bar dynamic by status+role, approval history collapsible, content area, operation log collapsible)
**Task 3:** Detail page - header rendering (number, date, customer, contact, project, expiry, payment, delivery; snapshot vs realtime logic)
**Task 4:** Detail page - product detail rendering (multi-product cards, dynamic product_attributes, tab component tables from snapshot, formula recalc)
**Task 5:** PDF export API (POST /quotations/{id}/export/pdf with options: show_discount, show_processes, show_tab_details; Qute template; Chinese font; <3s for 10 products)
**Task 6:** PDF Qute template (company header, quotation info, product tables, summary, terms, signature area; conditional sections)
**Task 7:** Excel export API (POST /quotations/{id}/export/excel with options: show_discount, include_raw_data_sheet; Apache POI; <2s)
**Task 8:** Export frontend (PDF options modal 3 checkboxes, Excel options modal 2 checkboxes, download trigger)
**Task 9:** Print functionality (window.print() + @media print CSS)
**Task 10:** Email send API (POST /quotations/{id}/send; APPROVED-only; auto PDF attachment; status→SENT; log)
**Task 11:** Email send frontend (modal: to/cc/subject/body/attachment options)
**Task 12:** Extend API (PUT /quotations/{id}/extend; SENT/APPROVED only; no re-approval)
**Task 13:** Extend frontend (DatePicker modal)
**Task 14:** Accept/Reject API (POST /accept: atomic accumulated_amount update; POST /reject; SENT+creator only; irreversible)
**Task 15:** Accept/Reject frontend (SENT-only buttons, confirm dialog)

## M6: Integration Testing & Optimization (Week 21-22)

**Task 1:** E2E test case design (all PRD user scenarios)
**Task 2:** 5 golden path E2E tests (full lifecycle, reject-resubmit, copy-edit, template→quote, strategy→discount)
**Task 3:** Permission matrix test (4 roles × 15 modules)
**Task 4:** State machine test (7 states, all legal/illegal transitions)
**Task 5:** Concurrency tests (dual ACCEPTED atomic, multi-tab save, 100-concurrent list)
**Task 6:** API performance test (k6 100-concurrent: list<500ms, discount<200ms, submit<1s, PDF<3s)
**Task 7:** Database optimization (slow query indexes, JSONB GIN, keyset pagination)
**Task 8:** OWASP Top 10 security test (SQL injection, XSS, CSRF, auth bypass, sensitive data)
**Task 9:** Frontend performance (code splitting, virtual scroll, lazy load, <2s first paint)
**Task 10:** Frontend polish (loading states, validation UX, empty states, responsive 1280-1920px)
**Task 11:** Scheduled task verification (manual trigger all 5)
**Task 12:** Data consistency checks (snapshots, discount rates, formula tolerance)
**Task 13:** Bug fixes and regression

## M7: UAT & Launch (Week 23-24)

**Task 1:** UAT environment setup (separate PostgreSQL + Quarkus, seed data script)
**Task 2:** UAT execution (business users test by role, P0/P1/P2 triage)
**Task 3:** Production environment (PostgreSQL + Quarkus single-instance JVM, HTTPS, SMTP, JVM params, readonly DB user)
**Task 4:** Flyway production migration
**Task 5:** Production seed data (admin account, dictionaries, processes, initial approval rules)
**Task 6:** AES key configuration (production-specific, not in repo)
**Task 7:** Monitoring and logging (health endpoint, JSON logs, alerts)
**Task 8:** Deploy and smoke test (full flow: login→customer→quote→approve→PDF→email)
**Task 9:** Contingency plan (rollback, daily backup, Drools fallback switch)
