# Component & Template Frontend Refactor Design

> **Date:** 2026-04-14
> **Scope:** Medium-fidelity refactor of ComponentManagement.tsx and TemplateConfiguration.tsx to match HTML prototype interaction patterns
> **Approach:** Decompose into focused sub-components, add chip-based formula builder, prototype-aligned three-panel layouts

---

## 1. Component Management Refactor

### Layout
Three-panel: Left (220px directory tree) | Center (header preview + field config table + formula builder) | Right (230px field/datasource panels)

### File Decomposition

| File | Responsibility |
|------|---------------|
| `ComponentManagement.tsx` | Three-panel layout container, state management, API calls |
| `ComponentTree.tsx` | Left panel: directory tree with expand/collapse, right-click actions, new directory/component modals |
| `HeaderPreview.tsx` | Top of center: table header preview from current fields, column count badge, subtotal column highlight |
| `FieldConfigTable.tsx` | Center: field configuration rows (drag-sort handle, name input, type select, content, amount/subtotal checkboxes, notes). Add/delete row. |
| `FormulaBuilder.tsx` | Center bottom: operator toolbar buttons [+−×÷()%], formula table (name + expression zone + result type). Add/delete formula. |
| `FormulaZone.tsx` | **Shared component.** Renders a formula expression as colored chips: blue=field, green=operator, orange=cross-component subtotal. Each chip has × remove. Click-to-add from right panel or operator toolbar. |
| `FieldPanel.tsx` | Right panel with two tabs: "字段列表" (current component fields as blue cards, click to add to active formula) and "其他数据源" (other components' subtotal fields as orange cards). |

### Interaction Model
- Field sorting: up/down arrow buttons on each row (no drag library needed)
- Formula building: click field card in right panel → appends blue chip to active FormulaZone. Click operator button → appends green chip. Click cross-component subtotal card → appends orange chip.
- Each chip shows × to remove
- DATA_SOURCE field type: existing 2-step modal unchanged

### Visual Alignment with Prototype
- Section labels: 📋 表头预览, ⚙️ 字段配置, 🧮 公式管理
- Card sections with #fafafa headers and #e4e7ed borders
- Subtotal column/row uses #fffbf0 yellow highlight
- Operator chips: border #dcdfe6, hover to blue
- Formula chips: blue (#e1f0ff), green (#f0f9eb), orange (#fff8e6) with colored borders

---

## 2. Template Configuration Refactor

### Layout
Three-panel: Left (280px component palette) | Center (toolbar + product attributes + tab components + subtotal bar) | Right (350px config panel)

### File Decomposition

| File | Responsibility |
|------|---------------|
| `TemplateConfiguration.tsx` | Three-panel container, data loading/saving, 30s auto-save |
| `ComponentPalette.tsx` | Left panel: gradient-colored cards grouped by type (tab components blue-purple, formula component pink). Click to add as new tab. |
| `ProductAttributesGrid.tsx` | 3-column grid of product attribute fields (name, fieldType TEXT/NUMBER, required checkbox, default value). Add/delete/reorder. |
| `TabComponentArea.tsx` | Dashed dropzone hint + Ant Design Tabs (closable tabs). Each tab renders ComponentTablePreview. |
| `ComponentTablePreview.tsx` | Single tab content: table with column headers from component fields, formula cells highlighted green (#e8f5e9), add-row button. |
| `SubtotalFormulaBar.tsx` | Gradient bar (#667eea→#764ba2) at bottom. Formula configuration area (reuses FormulaZone with component_subtotal + product_attribute tokens). |
| `TemplateConfigPanel.tsx` | Right panel: basic info form (category select, name, description, usage note), publish/archive buttons, version history list, "create new draft" button. |
| `ViewToggle.tsx` | Detail/summary view switch buttons. Summary view shows simple 3-column table (tab name, items, subtotal). |

### Interaction Model
- Add component: click gradient card in left panel → new tab created in TabComponentArea
- Remove component: click × on tab header
- Product attributes: Ant Design Form.List with 3-col grid layout
- Subtotal formula: reuse FormulaZone, tokens are component subtotals (orange) and NUMBER product attributes (blue)
- Auto-save: 30s interval for DRAFT status

### Visual Alignment with Prototype
- Left panel cards: CSS gradient backgrounds matching prototype (#667eea→#764ba2 for tab components, #4facfe→#00f2fe for alternatives)
- Product attributes area: #f9f9f9 background, rounded border
- Tab buttons: active=blue (#1976d2), inactive=#f5f5f5
- Subtotal bar: gradient matching main theme
- Config panel: section titles with blue bottom border

---

## 3. Shared Components

| Component | Used By |
|-----------|---------|
| `FormulaZone.tsx` | ComponentManagement (field formulas), TemplateConfiguration (subtotal formula) |
| Chip styles (CSS) | Both pages, consistent colors |

---

## 4. What Changes, What Stays

### Changes
- ComponentManagement.tsx → decomposed into 7 files
- TemplateConfiguration.tsx → decomposed into 8 files
- Formula editing: JSON textarea → chip-based visual builder
- Layout: matches prototype three-panel structure
- Visual: prototype colors and section styling

### Stays
- All backend APIs unchanged
- componentService.ts / templateService.ts unchanged
- Router configuration unchanged
- Business logic (validation, saving, publishing) unchanged
