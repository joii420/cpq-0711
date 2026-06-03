# M4b: Pricing & Quotation Business Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement pricing strategy management, the 5-step quotation wizard, approval workflow, quotation management list, in-app notifications, quotation copy, and scheduled tasks.

**Architecture:** Quotation is the central entity with 7-state machine. QuotationLineItem→QuotationLineComponentData stores tab component data as JSONB row_data. Submission is an atomic transaction (snapshot→route→status). Notifications written synchronously, emails async. 5 scheduled tasks via @Scheduled.

**Tech Stack:** Java 17, Quarkus, Drools, JEXL, Quarkus Mailer, Quarkus Scheduler, React Steps wizard, Ant Design

---

### Sub-phase 4b-A: Pricing Strategy (Week 14)
**Task 1:** Flyway V9 — PricingStrategy + PricingRule tables
**Task 2:** PricingStrategy CRUD API (nested rules, KieBase cache invalidation on change)
**Task 3:** Pricing import/export API (Excel via POI)
**Task 4:** Pricing strategy frontend (left customer sidebar, right strategy config with rule table)
**Task 5:** Pricing operation logs

### Sub-phase 4b-B: Quotation Generator (Week 15-17)
**Task 6:** Flyway V10 — Quotation + QuotationLineItem + QuotationLineProcess + QuotationLineComponentData + QuotationLineItemSnapshot + QuotationApproval tables + SEQUENCE
**Task 7:** Quotation CRUD API (auto number QT-YYYYMMDD-XXXX, permission filter by role)
**Task 8:** Draft save API (last-write-wins, contact snapshot realtime in DRAFT)
**Task 9:** Step 1 frontend (customer search/select, contact dropdown, metadata form, progress steps)
**Task 10:** Step 2 frontend - 3-step modal (product→process→template selection with exact match)
**Task 11:** Step 2 frontend - product card rendering (dynamic product_attributes, tab component tables from snapshot, formula auto-calc)
**Task 12:** Step 2 frontend - DATA_SOURCE field interaction (300ms debounce, required check, loading, error, 5min cache)
**Task 13:** Datasource execute API (POST /datasources/{id}/execute with system param injection)
**Task 14:** Step 2 frontend - template switch + row operations (confirm dialog, 20-row warning)
**Task 15:** Draft auto-save frontend (10s interval + localStorage fallback)
**Task 16:** Step 3 frontend (read frontend total, call discount API, display breakdown, manual adjust with reason)
**Task 17:** Discount calculation API (POST /quotations/{id}/calculate-discount, Drools engine)
**Task 18:** Step 4 frontend (payment terms, delivery cycle)
**Task 19:** Step 5 frontend (full preview, pre-submit validation, submit button)
**Task 20:** Submit API - atomic transaction (customer snapshot → product snapshot → JEXL verify → Drools routing → status SUBMITTED; async notification)

### Sub-phase 4b-C: Approval + Quotation Management (Week 17-18)
**Task 21:** Flyway V11 — ApprovalRule table
**Task 22:** ApprovalRule CRUD API (FIXED/DYNAMIC, KieBase cache invalidation)
**Task 23:** Approve/Reject API (status transitions, QuotationApproval record, permission check)
**Task 24:** Approval rule config frontend (rule table, create modal, priority sort, fallback display)
**Task 25:** Quotation management list frontend (role-based tabs, search, filter, pagination, row actions)
**Task 26:** Sales manager approval queue frontend (badge count, inline approve/reject)
**Task 27:** Operation log frontend (table, filters, Excel export)

### Sub-phase 4b-D: Notifications + Copy + Scheduled Tasks (Week 18-19)
**Task 28:** Notification CRUD API (list, unread count <100ms, mark read, mark all read)
**Task 29:** NotificationService (sync write + async email, 6 trigger scenarios)
**Task 30:** Notification frontend (bell icon + badge with 30s polling, dropdown panel, full list page)
**Task 31:** Approval notification integration (post-commit hooks in submit/approve/reject)
**Task 32:** Quotation copy API (full copy, DATA_SOURCE fields cleared, reset number/date/status)
**Task 33:** Quotation copy frontend (copy button, redirect to new draft, source label, archived template warning)
**Task 34:** 5 scheduled tasks (@Scheduled @Blocking: quote expiry 00:30, strategy expiry 01:00, approval reminder 09:00, token cleanup 03:00, notification cleanup Mon 04:00)
**Task 35:** Backfill M1 notifications (password reset, role change triggers)

### Execution Order
```
4b-A: 1→2+3→4→5
4b-B: 6→7+8+13+17→9→10→11+12+14→15→16→18→19→20
4b-C: 21→22+23→24+25+26+27
4b-D: 28→29→30→31, 32→33, 34+35
```
