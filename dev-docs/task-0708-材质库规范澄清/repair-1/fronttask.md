# 前端任务 — 材质 化学式/名称 双列展示（task-0708 · repair-1）

> 技术总监下发 · 2026-07-09 · 优先级 P2
> 配套：`backtask.md`｜`api.md`
> **背景**：修正 task-0708 决策#2——`symbol`=化学式、`name`=名称，两列都展示，名称可编辑。

---

## 零、涉及文件
| 文件 | 改动 |
|------|------|
| `src/pages/config/MaterialRecipeManagement.tsx` | symbol 列改「化学式」+ 新增「名称」列 + 搜索占位加名称 |
| `src/pages/config/MaterialRecipeEditDrawer.tsx` | symbol 标签改「化学式」+ 取消隐藏 name（可编辑「名称」）|

> `MaterialRecipeDTO` 本就含 `name` 字段，service/类型无需改。

---

## 一、RF1 — 列表（MaterialRecipeManagement.tsx）

1. **symbol 列标签**：现 `{ title: '材质名称', dataIndex: 'symbol', ... }`（约第 81 行）→ 改 **`title: '化学式'`**。
2. **新增「名称」列**（`name`），紧跟化学式列后：
   ```tsx
   { title: '名称', dataIndex: 'name', key: 'name', width: 160 },
   ```
   → 列顺序：材质编号 / **化学式** / **名称** / 类型 / 状态 / 创建时间 / 修改时间 / 排序。
3. **搜索占位**：现 `placeholder="搜索 材质编号 / 材质名称 / 元素"`（约第 155 行）→ 改 **`"搜索 材质编号 / 化学式 / 名称 / 元素"`**（后端已支持按 name 搜，见 backtask RB4）。

---

## 二、RF2 — 编辑抽屉（MaterialRecipeEditDrawer.tsx）

1. **symbol 标签**：现 `<Form.Item name="symbol" label="材质名称" ...>`（约第 266 行）→ 改 **`label="化学式"`**（placeholder 如「Ag / AgC3」）。
2. **取消隐藏 name，作可编辑「名称」**：现约第 130 行注释「名称/配比管理 UI 已隐藏，导入/新建统一置 null」——恢复 `name` 的 `Form.Item`（放在化学式后）：
   ```tsx
   <Form.Item name="name" label="名称">
     <Input placeholder="留空默认=化学式" style={{ width: 180 }} />
   </Form.Item>
   ```
   - **非必填**：留空时后端默认 name=symbol（见 backtask RB2）。
   - 编辑已有材质时回显当前 name（存量已回填=symbol）。
3. **配比 spec_label 仍隐藏**（不恢复）。
4. 提交时把 `name` 纳入 `MaterialRecipeUpsertRequest`（原 task-0708 置 null，现传表单值/留空由后端兜默认）。

---

## 三、RF3 — 自检
```bash
cd cpq-frontend
npx tsc --noEmit -p tsconfig.json          # 0 错误
for f in src/pages/config/MaterialRecipeManagement.tsx src/pages/config/MaterialRecipeEditDrawer.tsx; do
  curl -s --noproxy '*' -o /dev/null -w "$f %{http_code}\n" "http://localhost:5174/$f"
done
```
- tsc 0 错；两 tsx Vite 200。
- 手动走查：材质页有「化学式」+「名称」两列；编辑抽屉化学式标签正确、名称可编辑、留空保存后回显=化学式；按名称能搜到。

---

## 四、验收标准
- [ ] 列表：化学式列 + 名称列并列展示（导入数据两列相同值）
- [ ] 编辑抽屉：化学式标签正确；名称可编辑；留空保存 → 名称=化学式
- [ ] 搜索：按 名称 能命中
- [ ] 配比仍隐藏
- [ ] 一行「已自检」声明（tsc 0 错 + 两 tsx Vite 200）
