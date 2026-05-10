# M3: Configuration Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement Component management (tab components with fields/formulas/DATA_SOURCE binding), Template configuration (drag-and-drop assembly with version management), Product-Template binding, and Template version comparison.

**Architecture:** Components store fields/formulas as JSONB. Templates reference components via TemplateComponent join table. Publishing snapshots component definitions into components_snapshot JSONB. ProductTemplateBinding uses process_ids_hash (SHA-256) for efficient matching. dnd-kit for drag-and-drop in React.

**Tech Stack:** Java 17, Quarkus, PostgreSQL JSONB, React dnd-kit, Ant Design Tabs/Tree/Table

---

### Sub-phase 3A: Component Management (Week 8-9)

**Task 1:** Flyway V6 — ComponentDirectory + Component tables (JSONB fields/formulas)
**Task 2:** ComponentDirectory CRUD API (tree structure, nested directories)
**Task 3:** Component CRUD API (fields JSONB validation, column_count auto-calc, formulas↔FORMULA field name consistency, single is_subtotal, delete protection)
**Task 4:** Formula circular reference detection (DFS on cross-component references in expression)
**Task 5:** DATA_SOURCE field validation (datasource_binding completeness check)
**Task 6:** Component frontend - directory tree (Ant Design Tree, right-click menu, breadcrumb)
**Task 7:** Component frontend - field configuration table (drag sort, type dropdown, content, checkboxes, add/delete row, header preview)
**Task 8:** Component frontend - DATA_SOURCE config modal (2-step: select datasource → bind params)
**Task 9:** Component frontend - formula builder (operator toolbar, drag chips: blue=local fields, orange=other component subtotals, remove chips)

### Sub-phase 3B: Template Configuration (Week 10-11)

**Task 10:** Flyway V7 — Template + TemplateComponent tables
**Task 11:** Template CRUD API (category/status filter, template_series_id auto/inherit)
**Task 12:** Template publish API (validate subtotal_formula + components, snapshot to components_snapshot, version auto-increment)
**Task 13:** Template archive API (binding check, in-progress quotation check, force option)
**Task 14:** Create new draft API (copy from published version)
**Task 15:** TemplateComponent management API (add/remove/reorder, DRAFT only)
**Task 16:** Template frontend - drag canvas (dnd-kit: left panel components → center dropzone → tabs with component tables; product attributes 3-col grid)
**Task 17:** Template frontend - subtotal formula builder (component_subtotal + product_attribute references)
**Task 18:** Template frontend - right panel (basic info, publish/archive buttons, version history list, "new draft" button)
**Task 19:** Template preview modes (detailed view / summary view toggle)
**Task 20:** Template auto-save (30s interval to backend for DRAFT)

### Sub-phase 3C: Binding + Comparison (Week 11-12)

**Task 21:** Flyway V8 — ProductTemplateBinding table (process_ids JSONB, process_ids_hash, partial unique index WHERE is_default=true)
**Task 22:** ProductTemplateBinding API (CRUD, hash auto-calc, default toggle, PUBLISHED-only binding)
**Task 23:** Product-Template binding frontend (left product sidebar, right 3-tab: process selection, binding management with 3-step modal, template version view)
**Task 24:** Template comparison API (POST /compare, diff from components_snapshot, return structured diff + stats)
**Task 25:** Template comparison frontend (version dropdowns, overview/detail toggle, diff stats panel, side-by-side comparison)

### Execution Order
```
3A: 1→2→3+4+5→6→7→8→9
3B: 10→11+12+13+14+15→16→17→18→19→20
3C: 21→22→23, 24→25
```

### Verification Checklist
- [ ] Create component with FIXED_VALUE + DATA_SOURCE + INPUT + FORMULA fields
- [ ] DATA_SOURCE 2-step binding modal works
- [ ] Formula builder drag-and-drop, cross-component reference, circular reference blocked
- [ ] Create template, drag in components, configure product attributes and subtotal formula
- [ ] Publish template: snapshot correct, version v1.0→v1.1
- [ ] Archive with binding/quotation checks
- [ ] Product + process combo → template binding, default template setting
- [ ] Version comparison diff display correct
