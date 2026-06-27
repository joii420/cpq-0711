# V6 基础数据导入「集合化落库」改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 V6 导入逐 sheet 落库里「逐 group / 逐行」的 N+1 远程往返,改成「整 sheet 一次性集合化批处理」,在**运行结果逐位不变**的前提下把导入从 ~30s 降到秒级。

**Architecture:** 在 `VersionedV6Writer` 新增 `writeVersionedGroups` / `writeVersionedMasterDetails` 两个**批量入口**(保留原单组方法给 ConfigureProductService 单发调用 + 作 golden 参照);批量入口用「常量前缀一次批量 SELECT + 内存决策 + id 集合批量 flip/delete + 跨组合并 INSERT」把每组 5~7 次往返压成每 sheet 常数 ~6 次。各 import handler 把「逐组 for 循环调 writer」改成「攒好整批一次调」。非 writer 的逐行 `upsert`(Q02 客户料号、P03/P04/P09~P12、P08 labor_rate)改成单条多值 `INSERT … ON CONFLICT`。全程用 feature flag 守护 + golden 等价测试(逐组 vs 批量产生逐位相同的表状态)。

**Tech Stack:** Java 17 / Quarkus / Hibernate `EntityManager` 原生 SQL / PostgreSQL 16(`is_current` 版本化表 + `id` UUID 主键) / JUnit 5 + `@QuarkusTest`。

---

## 为什么这样改能「不改结果」(等价性论证 — 实现前必读)

改造的每一步都从「逐组方法的现行语义」一对一推导出「批量等价式」。下面是**结果不变的证明骨架**,实现时每个 Task 都以此为验收口径。

### 单组 `writeVersionedGroup` 现行语义(每组)
1. `advisoryLock(table, groupKey)` — `pg_advisory_xact_lock`,仅串行化,**不影响任何查询结果**。
2. `existing = loadCurrentGroup` — 该组 `is_current=TRUE` 行。
3. `triggerSame = multisetEqual(existing, newRows, triggerCols)`;`contentSame = multisetEqual(existing, newRows, contentCols)`(纯内存,`norm()` 数字归一)。
4. 三分支:
   - **(a) triggerSame && contentSame** → 复用版本,不写,返回 `currentVersionOf`。
   - **(b) triggerSame && !contentSame** → 原地更新:`cur=currentVersionOf`;`deleteCurrent(group)`;插 newRows(version=cur, is_current=true);返回 cur。
   - **(c) !triggerSame**(含首写/existing 空) → 升版:`newVersion=nextVersionOf`(组内历史 `MAX(数字版本)+1`,无则 2000);existing 非空则 `flip(group)`;插 newRows(version=newVersion);返回 newVersion。

### 批量等价式(整 sheet 所有 group)
- **锁**:单组锁键 = `table|join(groupKey值)`,各组不同键。批量改为**单把锁**,键 = `table|join(常量前缀值)`(常量前缀 = 在本批所有 group 取值恒定的 groupKey 列,如 `system_type/customer_no/price_type/cost_type`)。同前缀串行、异前缀并行;它覆盖本批所有组 ⇒ 并发安全性不弱于逐组锁。**锁从不改变查询结果**,只改并发粒度 ⇒ 结果恒等。
- **读当前组(替代 N 次 `loadCurrentGroup`)**:一次 `SELECT id, <ver>, <gkCols>, <contentCols> … WHERE <常量前缀 IS NOT DISTINCT FROM> AND is_current=TRUE`,内存按 groupKey 分桶。某组的桶 = 恰好该组的当前行(其余被前缀带出的行按其自身 groupKey 分到别桶/被忽略)。⇒ 每组 `existing` 与逐组完全一致。
- **`currentVersionOf`**:从桶里取(已 SELECT `ver`),(a)/(b) 分支必有当前行(triggerSame 且 existing 空会被 I1 拒绝),取 `existing.get(0)[ver]`;同组多当前行版本号必相同(同次插入),`LIMIT 1` 等价于取任一 ⇒ 恒等。**省掉 N 次往返**。
- **`nextVersionOf`(替代 N 次)**:一次 `SELECT <gkCols>, MAX(CASE WHEN ver~'^[0-9]+$' THEN ver::int END) … WHERE <常量前缀> GROUP BY <gkCols>`,内存按组取值;无则 2000。`GROUP BY gkCols` 的每组 MAX = 逐组 `WHERE groupKey` 的 MAX(同一行集)⇒ 恒等。
- **`flip` / `deleteCurrent`(替代 N 次)**:逐组 `WHERE groupKey AND is_current=TRUE` 命中的行集 = B2 读到的该组当前行 = 已知 **id 集合**。批量改 `… WHERE id IN (:ids)`,id 集合 = 同一批行 ⇒ 命中行恒等、且 id 主键匹配天然 NULL 安全。
- **`INSERT`(跨组合并)**:每行携带自己的全部列值(groupKey+content+version+is_current);跨组合并成单条多值 INSERT 后,**落库行的列值逐位不变**(仅 INSERT 语句条数减少);`id` 为 `@GeneratedValue` 随机、`created_at/updated_at` 为 `NOW()`,本就与顺序无关,golden 比对忽略这三列。
- **决策分支**:(a)/(b)/(c) 判定全部基于 `existing` + `newRows` + `maxVer`,这三者批量与逐组取值恒等 ⇒ 每组落入同一分支、写同样的行、返回同样的 version。

### 约束(实现时断言,守护等价边界)
- **varying(非常量前缀)groupKey 列必须是文本类型**(现役 handler 全部是 code/material_no 等字符串)。分桶用 null 安全 `toString`(精确匹配 SQL `IS NOT DISTINCT FROM` 的文本相等),**不**用数字归一 `norm()`(那是 content 比较专用)。非文本 varying 列 → 该 handler 继续用单组方法或扩展批量。
- content 多集比较继续用现有 `norm()`(数字 `stripTrailingZeros`)⇒ 与逐组逐字一致。
- 批量入口逐组复用单组的不变量校验(I1 newRows 非空 / I2 列名不重叠 / I3 contentColumns 非空 / system_type 护栏 / safeIdent 白名单)。

---

## File Structure

| 文件 | 责任 | 改动 |
|---|---|---|
| `versioning/VersionedV6Writer.java` | 版本化写入器 | **改**:加 `writeVersionedGroups` / `writeVersionedMasterDetails` + 私有批量助手(constantColumns / loadCurrentByPrefix / maxVersionByPrefix / flipByIds / deleteByIds);保留原单组方法 + 现有埋点 |
| `versioning/VersionedBatchEquivTest.java` | golden 等价测试 | **建**:同输入跑「逐组 loop」vs「批量」于两份干净数据,断言表状态(业务列多集)逐位相同 |
| `pricing/CpqV6ImportConfig.java`（或复用现有 config）| feature flag | **建/改**:`cpq.v6import-setbased-writer`(默认 false→golden 验证后 true) |
| 14 个单表 handler(Q01/06/07/08/09/10/11/13/14/15/16/17 + P08/P21）| 攒批后一次调 | **改**:`for(group) writer.writeVersionedGroup` → 攒 `LinkedHashMap` 一次 `writeVersionedGroups` |
| 4 个主从 handler(Q04/P06/P07/MaterialBomMerge）| 攒批后一次调 | **改**:`for(group) writer.writeVersionedMasterDetail` → 一次 `writeVersionedMasterDetails` |
| Q02 + P03/P04/P09/P10/P11/P12 + P08.labor_rate | 逐行 upsert → 批量 | **改**:循环内 `repo.upsert`/`createNativeQuery` → 单条多值 `INSERT … ON CONFLICT`(组内按冲突键去重折叠) |

ConfigureProductService 的 6 处单组调用是**单发非循环**,不动。

---

## Task 1: 批量单表写入器 `writeVersionedGroups` + 私有助手

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/versioning/VersionedBatchEquivTest.java`(Task 3 建,本 Task 先编译通过)

- [ ] **Step 1: 在 `VersionedV6Writer` 加批量入口 + 助手**

在类内(原单组方法之后)新增:

```java
// ====================== 批量单表行集版本化（集合化，零 N+1） ======================
/**
 * 批量版本化写入：整 sheet 所有 group 一次提交。DB 往返与 group 数无关（常数 ~6 次），
 * 逐位等价于「对每个 group 依次调用 {@link #writeVersionedGroup}」。
 *
 * @param groups 保持插入顺序的 groupKey→newRows；各 groupKey 的列集必须一致。
 * @return 每个 groupKey → 本组生效版本号（与逐组返回值一致）。
 */
public Map<Map<String, Object>, String> writeVersionedGroups(
        String tableName, String versionColumn,
        List<String> contentColumns, List<String> versionTriggerColumns,
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups) {

    if (!ALLOWED_TABLES.contains(tableName)) throw new IllegalArgumentException("表未登记白名单: " + tableName);
    safeIdent(tableName); safeIdent(versionColumn);
    contentColumns.forEach(VersionedV6Writer::safeIdent);
    if (contentColumns.isEmpty()) throw new IllegalArgumentException("contentColumns 不能为空");
    List<String> triggerCols = (versionTriggerColumns == null) ? contentColumns : versionTriggerColumns;
    if (!new HashSet<>(contentColumns).containsAll(triggerCols))
        throw new IllegalArgumentException("versionTriggerColumns 必须是 contentColumns 子集: " + triggerCols);

    Map<Map<String, Object>, String> versionOut = new LinkedHashMap<>();
    if (groups.isEmpty()) return versionOut;

    List<String> gkCols = new ArrayList<>(groups.keySet().iterator().next().keySet());
    gkCols.forEach(VersionedV6Writer::safeIdent);
    for (Map.Entry<Map<String, Object>, List<Map<String, Object>>> e : groups.entrySet()) {
        Map<String, Object> gk = e.getKey();
        if (!new ArrayList<>(gk.keySet()).equals(gkCols))
            throw new IllegalArgumentException("同批 group 的 groupKey 列集必须一致: " + gk.keySet() + " vs " + gkCols);
        requireSystemType(tableName, gk);
        Set<String> overlap = new HashSet<>(gk.keySet()); overlap.retainAll(new HashSet<>(contentColumns));
        if (!overlap.isEmpty()) throw new IllegalArgumentException("contentColumns 与 groupKeyColumns 列名重叠: " + overlap);
        if (e.getValue().isEmpty()) throw new IllegalArgumentException("newRows 为空;整组下线请用专门 API: " + gk);
    }

    Map<String, Object> constPrefix = constantColumns(groups.keySet(), gkCols);
    advisoryLockPrefix(tableName, constPrefix);                                                  // 1 RT
    Map<List<String>, List<Map<String, Object>>> curByGk =
        loadCurrentByPrefix(tableName, versionColumn, constPrefix, gkCols, contentColumns);      // 1 RT
    Map<List<String>, Integer> maxVerByGk =
        maxVersionByPrefix(tableName, versionColumn, constPrefix, gkCols);                       // 1 RT

    List<UUID> flipIds = new ArrayList<>();
    List<UUID> deleteIds = new ArrayList<>();
    List<Map<String, Object>> toInsert = new ArrayList<>();
    for (Map.Entry<Map<String, Object>, List<Map<String, Object>>> e : groups.entrySet()) {
        Map<String, Object> gk = e.getKey();
        List<Map<String, Object>> newRows = e.getValue();
        List<String> key = gkKey(gk, gkCols);
        List<Map<String, Object>> existing = curByGk.getOrDefault(key, List.of());

        boolean triggerSame = multisetEqual(existing, newRows, triggerCols);
        boolean contentSame = multisetEqual(existing, newRows, contentColumns);

        if (triggerSame && contentSame) {                                  // (a)
            versionOut.put(gk, currentVersionFrom(existing, versionColumn));
            continue;
        }
        if (triggerSame) {                                                 // (b) 原地更新
            String cur = currentVersionFrom(existing, versionColumn);
            for (Map<String, Object> r : existing) deleteIds.add((UUID) r.get("__id"));
            for (Map<String, Object> row : newRows) toInsert.add(assembleRow(gk, row, versionColumn, cur));
            versionOut.put(gk, cur);
            continue;
        }
        Integer mx = maxVerByGk.get(key);                                  // (c) 升版
        String newVersion = (mx == null) ? "2000" : String.valueOf(mx + 1);
        if (!existing.isEmpty()) for (Map<String, Object> r : existing) flipIds.add((UUID) r.get("__id"));
        for (Map<String, Object> row : newRows) toInsert.add(assembleRow(gk, row, versionColumn, newVersion));
        versionOut.put(gk, newVersion);
    }

    if (!flipIds.isEmpty())   flipByIds(tableName, flipIds);               // 1 RT
    if (!deleteIds.isEmpty()) deleteByIds(tableName, deleteIds);          // 1 RT
    insertRowsBatched(tableName, toInsert);                                // ~1 RT
    return versionOut;
}

/** 本批所有 group 取值恒定的 groupKey 列（用 null 安全 toString 判定）。 */
private static Map<String, Object> constantColumns(Set<Map<String, Object>> gks, List<String> gkCols) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String col : gkCols) {
        String first = null; boolean init = false, constant = true;
        for (Map<String, Object> gk : gks) {
            String v = textKey(gk.get(col));
            if (!init) { first = v; init = true; }
            else if (!java.util.Objects.equals(first, v)) { constant = false; break; }
        }
        if (constant) out.put(col, gks.iterator().next().get(col));
    }
    return out;
}

/** null 安全文本键（匹配 SQL IS NOT DISTINCT FROM 的文本相等；不做数字归一）。 */
private static String textKey(Object v) { return v == null ? null : v.toString(); }
private static List<String> gkKey(Map<String, Object> gk, List<String> gkCols) {
    List<String> k = new ArrayList<>(gkCols.size());
    for (String c : gkCols) k.add(textKey(gk.get(c)));
    return k;
}

/** 单把 advisory 锁，键 = 表 + 常量前缀值（覆盖本批所有 group）。 */
private void advisoryLockPrefix(String table, Map<String, Object> constPrefix) {
    long t0 = System.nanoTime();
    String lockKey = table + "|" + constPrefix.values().stream()
        .map(String::valueOf).collect(java.util.stream.Collectors.joining("|"));
    em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
      .setParameter("k", lockKey).getSingleResult();
    Profile p = PROFILE.get(); p.lockN++; p.lockNs += System.nanoTime() - t0;
}

/** NULL 安全等值 WHERE（前缀列）：col IS NOT DISTINCT FROM :p_col AND ...；空前缀返 "TRUE"。 */
private static String prefixWhere(Map<String, Object> constPrefix) {
    if (constPrefix.isEmpty()) return "TRUE";
    StringBuilder sb = new StringBuilder(); int i = 0;
    for (String col : constPrefix.keySet()) {
        if (i++ > 0) sb.append(" AND ");
        sb.append(col).append(" IS NOT DISTINCT FROM :p_").append(col);
    }
    return sb.toString();
}
private Query bindPrefix(Query q, Map<String, Object> constPrefix) {
    for (Map.Entry<String, Object> e : constPrefix.entrySet()) q.setParameter("p_" + e.getKey(), e.getValue());
    return q;
}

/** 一次加载常量前缀下所有当前生效行，按完整 groupKey 分桶；每行附 __id + versionColumn 值。 */
@SuppressWarnings("unchecked")
private Map<List<String>, List<Map<String, Object>>> loadCurrentByPrefix(
        String table, String versionColumn, Map<String, Object> constPrefix,
        List<String> gkCols, List<String> contentColumns) {
    long t0 = System.nanoTime();
    LinkedHashSet<String> sel = new LinkedHashSet<>();
    sel.add("id"); sel.add(versionColumn); sel.addAll(gkCols); sel.addAll(contentColumns);
    List<String> selCols = new ArrayList<>(sel);
    Query q = em.createNativeQuery("SELECT " + String.join(", ", selCols) + " FROM " + table
        + " WHERE " + prefixWhere(constPrefix) + " AND is_current = TRUE");
    bindPrefix(q, constPrefix);
    List<Object> raw = q.getResultList();
    Map<List<String>, List<Map<String, Object>>> out = new HashMap<>();
    for (Object r : raw) {
        Object[] arr = (selCols.size() == 1) ? new Object[]{r} : (Object[]) r;
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < selCols.size(); i++) m.put(selCols.get(i), arr[i]);
        m.put("__id", m.get("id"));
        List<String> key = new ArrayList<>(gkCols.size());
        for (String c : gkCols) key.add(textKey(m.get(c)));
        out.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
    }
    Profile p = PROFILE.get(); p.loadN++; p.loadNs += System.nanoTime() - t0;
    return out;
}

/** 一次取常量前缀下每组历史 MAX(数字版本)。 */
@SuppressWarnings("unchecked")
private Map<List<String>, Integer> maxVersionByPrefix(
        String table, String versionColumn, Map<String, Object> constPrefix, List<String> gkCols) {
    long t0 = System.nanoTime();
    String gkSel = String.join(", ", gkCols);
    Query q = em.createNativeQuery("SELECT " + gkSel + ", MAX(CASE WHEN " + versionColumn
        + " ~ '^[0-9]+$' THEN " + versionColumn + "::int END) FROM " + table
        + " WHERE " + prefixWhere(constPrefix) + " GROUP BY " + gkSel);
    bindPrefix(q, constPrefix);
    List<Object> raw = q.getResultList();
    Map<List<String>, Integer> out = new HashMap<>();
    for (Object r : raw) {
        Object[] arr = (Object[]) r;                       // gkCols.size() >= 1 → 必为数组
        List<String> key = new ArrayList<>(gkCols.size());
        for (int i = 0; i < gkCols.size(); i++) key.add(textKey(arr[i]));
        Object mx = arr[gkCols.size()];
        out.put(key, mx == null ? null : ((Number) mx).intValue());
    }
    Profile p = PROFILE.get(); p.verN++; p.verNs += System.nanoTime() - t0;
    return out;
}

private static String currentVersionFrom(List<Map<String, Object>> existing, String versionColumn) {
    if (existing.isEmpty()) return "2000";
    return String.valueOf(existing.get(0).get(versionColumn));
}
private static Map<String, Object> assembleRow(Map<String, Object> gk, Map<String, Object> row,
                                               String versionColumn, String version) {
    Map<String, Object> all = new LinkedHashMap<>(gk);
    all.putAll(row);
    all.put(versionColumn, version);
    all.put("is_current", true);
    return all;
}
private void flipByIds(String table, List<UUID> ids) {
    long t0 = System.nanoTime();
    for (int s = 0; s < ids.size(); s += 1000) {
        List<UUID> chunk = ids.subList(s, Math.min(s + 1000, ids.size()));
        em.createNativeQuery("UPDATE " + table + " SET is_current = FALSE WHERE id IN (:ids)")
          .setParameter("ids", chunk).executeUpdate();
    }
    Profile p = PROFILE.get(); p.flipN++; p.flipNs += System.nanoTime() - t0;
}
private void deleteByIds(String table, List<UUID> ids) {
    long t0 = System.nanoTime();
    for (int s = 0; s < ids.size(); s += 1000) {
        List<UUID> chunk = ids.subList(s, Math.min(s + 1000, ids.size()));
        em.createNativeQuery("DELETE FROM " + table + " WHERE id IN (:ids)")
          .setParameter("ids", chunk).executeUpdate();
    }
    Profile p = PROFILE.get(); p.flipN++; p.flipNs += System.nanoTime() - t0;
}
```

确保文件顶部 `import java.util.*;` 已涵盖 `LinkedHashSet/HashMap/UUID`(现状 `import java.util.*;` 已满足;`Query` 已 import)。

- [ ] **Step 2: 编译验证**

Run: `cd cpq-backend && ./mvnw -q -o compile`
Expected: BUILD SUCCESS(0 错误)。

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java
git commit -m "feat(v6import): VersionedV6Writer 批量单表入口 writeVersionedGroups(集合化,零N+1)"
```

---

## Task 2: 批量主从写入器 `writeVersionedMasterDetails`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java`

- [ ] **Step 1: 加批量主从入口**(沿用 Task 1 助手:constantColumns/loadCurrentByPrefix/maxVersionByPrefix/flipByIds/insertRowsBatched)

```java
// ====================== 批量 BOM 主从版本化 ======================
/**
 * 批量主从版本化：整 sheet 所有 BOM group 一次提交。逐位等价于逐组
 * {@link #writeVersionedMasterDetail}。仅支持 childVersionColumn != null（多版本保留）路径，
 * 现役全部 BOM handler 均属此类。
 *
 * @param items 每项 = {masterGroupKey, childGroupKey, childRows}；masterFixedColumns 整批恒定。
 * @return masterGroupKey → 版本号。
 */
public Map<Map<String, Object>, String> writeVersionedMasterDetails(
        String masterTable, String masterVersionColumn, Map<String, ?> masterFixedColumns,
        String childTable, String childVersionColumn, List<String> childContentColumns,
        List<MasterDetailItem> items) {

    if (!ALLOWED_TABLES.contains(masterTable) || !ALLOWED_TABLES.contains(childTable))
        throw new IllegalArgumentException("表未登记白名单: " + masterTable + " / " + childTable);
    if (childVersionColumn == null)
        throw new IllegalArgumentException("批量主从仅支持 childVersionColumn != null（多版本保留）");
    safeIdent(masterTable); safeIdent(masterVersionColumn); safeIdent(childTable); safeIdent(childVersionColumn);
    childContentColumns.forEach(VersionedV6Writer::safeIdent);
    if (masterFixedColumns != null) masterFixedColumns.keySet().forEach(VersionedV6Writer::safeIdent);
    if (childContentColumns.isEmpty()) throw new IllegalArgumentException("childContentColumns 不能为空");

    Map<Map<String, Object>, String> versionOut = new LinkedHashMap<>();
    if (items.isEmpty()) return versionOut;

    List<String> mGkCols = new ArrayList<>(items.get(0).masterGroupKey.keySet());
    List<String> cGkCols = new ArrayList<>(items.get(0).childGroupKey.keySet());
    mGkCols.forEach(VersionedV6Writer::safeIdent);
    cGkCols.forEach(VersionedV6Writer::safeIdent);
    LinkedHashMap<Map<String, Object>, Object> masterKeys = new LinkedHashMap<>();
    LinkedHashMap<Map<String, Object>, Object> childKeys = new LinkedHashMap<>();
    for (MasterDetailItem it : items) {
        requireSystemType(masterTable, it.masterGroupKey);
        requireSystemType(childTable, it.childGroupKey);
        if (it.childRows.isEmpty()) throw new IllegalArgumentException("childRows 为空: " + it.masterGroupKey);
        masterKeys.put(it.masterGroupKey, null); childKeys.put(it.childGroupKey, null);
    }

    Map<String, Object> mPrefix = constantColumns(masterKeys.keySet(), mGkCols);
    Map<String, Object> cPrefix = constantColumns(childKeys.keySet(), cGkCols);

    advisoryLockPrefix(masterTable, mPrefix);                                                       // 1 RT
    Map<List<String>, List<Map<String, Object>>> curMaster =
        loadCurrentByPrefix(masterTable, masterVersionColumn, mPrefix, mGkCols, List.of());          // 1 RT（content 空→只取 id+ver+gk）
    Map<List<String>, List<Map<String, Object>>> curChild =
        loadCurrentByPrefix(childTable, childVersionColumn, cPrefix, cGkCols, childContentColumns);   // 1 RT
    Map<List<String>, Integer> maxVer =
        maxVersionByPrefix(masterTable, masterVersionColumn, mPrefix, mGkCols);                       // 1 RT

    List<UUID> masterFlip = new ArrayList<>(), childFlip = new ArrayList<>();
    List<Map<String, Object>> masterInsert = new ArrayList<>(), childInsert = new ArrayList<>();
    for (MasterDetailItem it : items) {
        List<String> cKey = gkKey(it.childGroupKey, cGkCols);
        List<String> mKey = gkKey(it.masterGroupKey, mGkCols);
        List<Map<String, Object>> existingChild = curChild.getOrDefault(cKey, List.of());
        if (multisetEqual(existingChild, it.childRows, childContentColumns)) {                        // 不写
            List<Map<String, Object>> em0 = curMaster.getOrDefault(mKey, List.of());
            versionOut.put(it.masterGroupKey, currentVersionFrom(em0, masterVersionColumn));
            continue;
        }
        Integer mx = maxVer.get(mKey);
        String newVersion = (mx == null) ? "2000" : String.valueOf(mx + 1);
        for (Map<String, Object> r : curMaster.getOrDefault(mKey, List.of())) masterFlip.add((UUID) r.get("__id"));
        for (Map<String, Object> r : existingChild) childFlip.add((UUID) r.get("__id"));
        Map<String, Object> master = new LinkedHashMap<>(it.masterGroupKey);
        if (masterFixedColumns != null) master.putAll(masterFixedColumns);
        master.put(masterVersionColumn, newVersion); master.put("is_current", true);
        masterInsert.add(master);
        for (Map<String, Object> row : it.childRows) {
            Map<String, Object> all = new LinkedHashMap<>(it.childGroupKey);
            all.putAll(row); all.put(childVersionColumn, newVersion); all.put("is_current", true);
            childInsert.add(all);
        }
        versionOut.put(it.masterGroupKey, newVersion);
    }

    if (!masterFlip.isEmpty()) flipByIds(masterTable, masterFlip);     // 1 RT
    if (!childFlip.isEmpty())  flipByIds(childTable, childFlip);       // 1 RT
    insertRowsBatched(masterTable, masterInsert);                       // ~1 RT（原逐行 insertRowGeneric → 合并）
    insertRowsBatched(childTable, childInsert);                        // ~1 RT
    return versionOut;
}

/** 批量主从单项入参。 */
public static final class MasterDetailItem {
    public final Map<String, Object> masterGroupKey;
    public final Map<String, Object> childGroupKey;
    public final List<Map<String, Object>> childRows;
    public MasterDetailItem(Map<String, Object> masterGroupKey, Map<String, Object> childGroupKey,
                            List<Map<String, Object>> childRows) {
        this.masterGroupKey = masterGroupKey; this.childGroupKey = childGroupKey; this.childRows = childRows;
    }
}
```

> 注:原逐组 `writeVersionedMasterDetail` 主表用 `insertRowGeneric`(逐行单插);批量改 `insertRowsBatched(masterTable, masterInsert)` 把各组主行合并 —— 多行列签名相同(masterGroupKey∪fixed∪version∪is_current)→ 单条多值 INSERT,落库行逐位不变。

- [ ] **Step 2: 编译验证** — `./mvnw -q -o compile` → BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java
git commit -m "feat(v6import): VersionedV6Writer 批量主从入口 writeVersionedMasterDetails"
```

---

## Task 3: golden 等价测试(逐组 loop vs 批量,逐位相同)

**Files:**
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/versioning/VersionedBatchEquivTest.java`

**测试原理**:同一组输入,分别在两套**完全隔离的数据**上跑「逐组 `writeVersionedGroup` 循环」与「`writeVersionedGroups` 批量」,再断言两表的**业务列多集逐位相同**(忽略 `id/created_at/updated_at`)。覆盖三分支(首写升版 / 完全相同复用 / 仅非触发列变原地更新)+ 重复导入幂等。`unit_price`(单表)+ `element_bom/element_bom_item`(主从)各一组。用 `system_type='__TEST_EQUIV'` 前缀隔离,`@AfterEach` 清理。

- [ ] **Step 1: 写等价测试(先失败)**

```java
package com.cpq.basicdata.v6.versioning;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class VersionedBatchEquivTest {

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    static final String ST_LOOP = "__EQUIV_LOOP";
    static final String ST_BATCH = "__EQUIV_BATCH";
    static final List<String> CONTENT = List.of("seq_no", "base_value", "currency");

    @AfterEach @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE system_type IN (?1,?2)")
          .setParameter(1, ST_LOOP).setParameter(2, ST_BATCH).executeUpdate();
    }

    /** 构造 3 个组、每组 1~2 行的输入；returns groupKey→rows（system_type 由调用方注入）。 */
    LinkedHashMap<Map<String,Object>, List<Map<String,Object>>> sample(String systemType) {
        LinkedHashMap<Map<String,Object>, List<Map<String,Object>>> g = new LinkedHashMap<>();
        for (String code : List.of("C1", "C2", "C3")) {
            Map<String,Object> gk = new LinkedHashMap<>();
            gk.put("system_type", systemType);
            gk.put("price_type", "TEST");
            gk.put("cost_type", "T");
            gk.put("code", code);
            gk.put("customer_no", "CUST1");
            gk.put("finished_material_no", "F1");
            List<Map<String,Object>> rows = new ArrayList<>();
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("seq_no", 1); r.put("base_value", new java.math.BigDecimal("1.50")); r.put("currency", "CNY");
            rows.add(r);
            g.put(gk, rows);
        }
        return g;
    }

    /** 读回某 system_type 下当前生效行的业务列多集（忽略 id/时间戳）。 */
    @SuppressWarnings("unchecked")
    Map<String,Integer> snapshot(String systemType) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT code, version_no, is_current, seq_no, base_value, currency FROM unit_price "
            + "WHERE system_type = ?1 ORDER BY code, version_no")
            .setParameter(1, systemType).getResultList();
        Map<String,Integer> tally = new HashMap<>();
        for (Object[] r : rows) {
            String key = r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3] + "|"
                + (r[4] == null ? "" : new java.math.BigDecimal(r[4].toString()).stripTrailingZeros().toPlainString())
                + "|" + r[5];
            tally.merge(key, 1, Integer::sum);
        }
        return tally;
    }

    @Transactional
    void runLoop(LinkedHashMap<Map<String,Object>, List<Map<String,Object>>> g) {
        for (Map.Entry<Map<String,Object>, List<Map<String,Object>>> e : g.entrySet())
            writer.writeVersionedGroup(new VersionedGroupSpec("unit_price", "version_no",
                e.getKey(), CONTENT, e.getValue()));
    }
    @Transactional
    void runBatch(LinkedHashMap<Map<String,Object>, List<Map<String,Object>>> g) {
        writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, g);
    }

    @Test
    void firstWrite_loop_equals_batch() {
        runLoop(sample(ST_LOOP));
        runBatch(sample(ST_BATCH));
        assertEquals(snapshot(ST_LOOP), snapshot(ST_BATCH), "首写升版后两路表状态应逐位相同");
    }

    @Test
    void reimport_identical_isIdempotent_andEqual() {
        runLoop(sample(ST_LOOP)); runLoop(sample(ST_LOOP));   // 重复导入(完全相同→复用版本不写)
        runBatch(sample(ST_BATCH)); runBatch(sample(ST_BATCH));
        assertEquals(snapshot(ST_LOOP), snapshot(ST_BATCH));
        // 且各自只 1 个当前版本（幂等）
        assertEquals(3, currentRowCount(ST_LOOP));
        assertEquals(3, currentRowCount(ST_BATCH));
    }

    @Test
    void contentChange_bumpsVersion_equal() {
        runLoop(sample(ST_LOOP)); runBatch(sample(ST_BATCH));
        // 改 base_value（content 变→升版）
        var g2loop = sample(ST_LOOP); g2loop.values().forEach(rs -> rs.get(0).put("base_value", new java.math.BigDecimal("9.90")));
        var g2batch = sample(ST_BATCH); g2batch.values().forEach(rs -> rs.get(0).put("base_value", new java.math.BigDecimal("9.90")));
        runLoop(g2loop); runBatch(g2batch);
        assertEquals(snapshot(ST_LOOP), snapshot(ST_BATCH), "升版后(含历史 is_current=false 行)两路应逐位相同");
    }

    int currentRowCount(String st) {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE system_type = ?1 AND is_current = TRUE")
            .setParameter(1, st).getSingleResult()).intValue();
    }
}
```

- [ ] **Step 2: 跑测试,确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=VersionedBatchEquivTest`
Expected: 3 tests PASS。若 FAIL → 批量与逐组有语义差,**不得**继续迁移 handler,先修 writer 至逐位相同。

- [ ] **Step 3: 加主从等价用例**

在同文件补 `element_bom`/`element_bom_item` 的 loop-vs-batch 用例(结构同上,改用 `writeVersionedMasterDetail` vs `writeVersionedMasterDetails`,snapshot 读 `element_bom_item` 的 `component_no/characteristic/is_current/...` 业务列多集),覆盖首写 + 子行变更升版。
Run: `./mvnw -q -o test -Dtest=VersionedBatchEquivTest` → 全 PASS。

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/test/java/com/cpq/basicdata/v6/versioning/VersionedBatchEquivTest.java
git commit -m "test(v6import): 批量 vs 逐组写入器 golden 等价测试(单表+主从,逐位相同)"
```

---

## Task 4: feature flag + 单表 handler 迁移(以 Q06 为完整范例)

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q06FixedProcessFeeHandler.java`(范例)
- 配置:`application.properties` 加 `cpq.v6import-setbased-writer=false`(golden + 真实导入验证后改 true)

**迁移配方(所有 14 个单表 handler 统一)**:把「`for (group) writer.writeVersionedGroup(new VersionedGroupSpec(table, ver, gk, CONTENT, rows[, TRIGGER]))`」改成「攒 `LinkedHashMap<gk, rows>` → 一次 `writer.writeVersionedGroups(table, ver, CONTENT, TRIGGER_or_null, groups)`」。`table/ver/CONTENT/TRIGGER` **逐字照抄**原 `VersionedGroupSpec` 实参。flag=false 时保留旧 loop 路径。`result.recordWrite` 计数等价迁移。

- [ ] **Step 1: 注入 flag**

在 `Q06FixedProcessFeeHandler` 加字段:
```java
@org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
boolean setBased;
```

- [ ] **Step 2: 改写第 100~109 行的写入循环**

把:
```java
        // 2) 每组走版本化写入
        for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
            try {
                writer.writeVersionedGroup(new VersionedGroupSpec(
                    "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue()));
                result.recordWrite("unit_price", e.getValue().size());
            } catch (Exception ex) {
                result.recordError(0, "_group_", ex.getMessage());
            }
        }
        return result;
```
改成:
```java
        // 2) 版本化写入：集合化(flag on) 或 逐组(flag off，保留回退)
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet())
                groups.put(groupKeyOf.get(e.getKey()), e.getValue());
            try {
                writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, groups);
                for (List<Map<String, Object>> rows : groups.values())
                    result.recordWrite("unit_price", rows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue()));
                    result.recordWrite("unit_price", e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
        return result;
```
补 import:`java.util.LinkedHashMap`(若未引)。

- [ ] **Step 3: 编译 + 现有 handler 单测**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=Q06FixedProcessFeeHandlerTest` (若无则 `-Dtest='*Handler*Test'`)
Expected: PASS(flag 默认 false → 走旧路径,行为不变)。

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q06FixedProcessFeeHandler.java cpq-backend/src/main/resources/application.properties
git commit -m "feat(v6import): Q06 单表写入集合化(flag 守护) + cpq.v6import-setbased-writer"
```

---

## Task 5: 其余 13 个单表 handler 迁移(同配方)

**Files(逐个 Modify,套用 Task 4 配方)**:

| handler | 表 / 版本列 | 备注 |
|---|---|---|
| Q01ElementPriceHandler | unit_price / version_no | 标准 |
| Q07IncomingOtherFeeHandler | unit_price / version_no | 标准 |
| Q08IncomingAnnualDiscountHandler | unit_price / version_no | 标准 |
| Q09IncomingRecoveryHandler | unit_price / version_no | 标准 |
| Q10SelfProcessFeeHandler | unit_price / version_no | 标准 |
| Q11FinishedOtherFeeHandler | unit_price / version_no | 标准 |
| Q13ComponentOtherFeeHandler | unit_price / version_no | 标准 |
| Q14AssemblyProcessFeeHandler | unit_price / version_no | 标准 |
| Q15AssemblyAnnualDiscountHandler | unit_price / version_no | 标准 |
| Q16PlatingSchemeHandler | plating_scheme / 见原调用 | 标准 |
| Q17PlatingCostHandler | unit_price / version_no | 标准 |
| P21PlatingSchemeHandler | plating_scheme / 见原调用 | 标准 |
| **P08CapacityHandler** | capacity / calc_version + **TRIGGER** | **特殊**,见 Task 6 |

- [ ] **Step 1~13(每个 handler 一步)**:套用 Task 4 三段式(注 flag → if(setBased) 攒批一次 `writeVersionedGroups(<照抄表/版本列>, <照抄 CONTENT>, <照抄 TRIGGER 或 null>, groups)` else 旧 loop → recordWrite 等价)。`TRIGGER` 仅当原 `VersionedGroupSpec` 是 6 参构造时照抄,否则传 `null`。

每个 handler 改完:
Run: `cd cpq-backend && ./mvnw -q -o compile` → SUCCESS;有对应 `*HandlerTest` 的跑之 → PASS。

- [ ] **Step 14: Commit**(可分多次)

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q01ElementPriceHandler.java ...(本批改动文件)
git commit -m "feat(v6import): 单表 handler(Q01/07/09.../P21)写入集合化"
```

---

## Task 6: P08Capacity 特殊迁移(用返回 version + labor_rate 批量)

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P08CapacityHandler.java`

**问题**:P08 用 `writeVersionedGroup` 的**返回 version** 给 `labor_rate` 当 `version_no`,且 labor_rate 是**组内逐 `LaborRow` 的第二层 N+1**(`em.createNativeQuery(... ON CONFLICT ...).executeUpdate()`)。

**配方**:
1. flag on:攒 `groups`(gk→capRows)→ `Map<gk,version> vers = writer.writeVersionedGroups("capacity","calc_version",CONTENT,VERSION_TRIGGER,groups)`。
2. 用 `vers.get(gk)` 取每组 version,把所有 `labor_rate` 行攒进一个 `List`,组完后**一次多值** `INSERT INTO labor_rate (...) VALUES (...),(...),... ON CONFLICT (version_no, process_no, COALESCE(material_no,''), COALESCE(labor_grade,'')) DO UPDATE SET ...`。
3. **组内按冲突键去重折叠**(后写覆盖 `standard_labor_rate`,其余列 COALESCE 保留),复刻原逐行 ON CONFLICT 顺序语义,避免单语句 `ON CONFLICT` 命中重复目标报 cardinality violation。
4. flag off:原逐组 + 逐行路径不动。

- [ ] **Step 1: 写 labor_rate 批量 upsert 助手**(在 handler 内私有方法,多值 VALUES + 上述 ON CONFLICT;去重 Map 键=`(version_no, process_no, material_no, '')`)。
- [ ] **Step 2: 改 handle**:setBased 分支按上面 1~3;保留 else 旧路径。
- [ ] **Step 3**: `./mvnw -q -o compile` → SUCCESS。
- [ ] **Step 4: Commit** `git add P08CapacityHandler.java && git commit -m "feat(v6import): P08 capacity + labor_rate 集合化(返回version驱动+批量upsert)"`

---

## Task 7: 4 个主从 handler 迁移(以 Q04 为完整范例)

**Files:** `Q04ElementBomHandler.java`(范例) + `P06MaterialBomHandler.java` / `P07ElementBomHandler.java` / `MaterialBomMergeHandler.java`

**迁移配方**:把「`for(group) writer.writeVersionedMasterDetail(masterTable, masterVer, masterGk, fixed, childTable, childVer, childGk, childContent, childRows)`」改成「攒 `List<MasterDetailItem>` → 一次 `writer.writeVersionedMasterDetails(masterTable, masterVer, fixed, childTable, childVer, childContent, items)`」。`masterFixedColumns/childVersionColumn/childContentColumns` 整 sheet 恒定,照抄。

- [ ] **Step 1: Q04 加 flag**(同 Task 4 Step 1)。
- [ ] **Step 2: 改 Q04 第 92~109 行**:

```java
        if (setBased) {
            List<VersionedV6Writer.MasterDetailItem> items = new ArrayList<>();
            for (Map.Entry<String, Map<List<Object>, Map<String, Object>>> e : childDedupByMat.entrySet()) {
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "QUOTE");
                masterGk.put("customer_no", ctx.customerNo);
                masterGk.put("material_no", e.getKey());
                Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                items.add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            }
            try {
                writer.writeVersionedMasterDetails("element_bom", "characteristic",
                    Map.of("bom_type", "MATERIAL"), "element_bom_item", "characteristic",
                    CHILD_CONTENT, items);
                for (VersionedV6Writer.MasterDetailItem it : items) {
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", it.childRows.size());
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<String, Map<List<Object>, Map<String, Object>>> e : childDedupByMat.entrySet()) {
                String materialNo = e.getKey();
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                try {
                    Map<String, Object> masterGk = new LinkedHashMap<>();
                    masterGk.put("system_type", "QUOTE");
                    masterGk.put("customer_no", ctx.customerNo);
                    masterGk.put("material_no", materialNo);
                    Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                    writer.writeVersionedMasterDetail(
                        "element_bom", "characteristic", masterGk, Map.of("bom_type", "MATERIAL"),
                        "element_bom_item", "characteristic", childGk, CHILD_CONTENT, childRows);
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", childRows.size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
                }
            }
        }
        return result;
```

- [ ] **Step 3: P06 / P07 / MaterialBomMerge 同配方迁移**(各自照抄原 `writeVersionedMasterDetail` 实参;注意 P06 material_bom_item 的 `childVersionColumn="bom_version"`、`masterFixedColumns` 见原调用)。
- [ ] **Step 4**: `./mvnw -q -o compile` → SUCCESS;有 `*BomHandlerTest`/`MaterialBomMergeHandlerTest` 的跑过 PASS。
- [ ] **Step 5: Commit** `git commit -m "feat(v6import): 主从 BOM handler(Q04/P06/P07/合并)写入集合化"`

---

## Task 8: 非 writer 逐行 upsert → 批量(Q02 范例 + P03/P04/P09~P12)

**Files:** `Q02CustomerMapHandler.java`(范例) + `MaterialCustomerMapRepository.java` + `P03/P04/P09/P10/P11/P12` handler

**配方**:循环里逐行 `repo.upsert(...)` / `createNativeQuery(... ON CONFLICT ...).executeUpdate()` → 攒 `List<行>` → 单条**多值** `INSERT … VALUES (..),(..),.. ON CONFLICT (<原冲突键>) DO UPDATE SET <原 SET>`;**组内先按冲突键去重折叠**(复刻逐行 ON CONFLICT 的"后写按 COALESCE 覆盖"语义)。Q02 已先 `deleteByCustomerNo` 清栈,批插无外部冲突,仅需处理 sheet 内重复键。

- [ ] **Step 1: `MaterialCustomerMapRepository` 加 `upsertBatch(List<MapRow> rows, UUID updatedBy)`**

```java
public int upsertBatch(List<MapRow> rows, UUID updatedBy) {
    if (rows.isEmpty()) return 0;
    // sheet 内按 (material_no, customer_no, customer_product_no) 去重折叠：后行 COALESCE 覆盖前行
    LinkedHashMap<List<String>, MapRow> dedup = new LinkedHashMap<>();
    for (MapRow r : rows) {
        List<String> k = List.of(nz(r.materialNo), nz(r.customerNo), nz(r.customerProductNo));
        dedup.merge(k, r, MapRow::coalesceOver);   // 见 MapRow.coalesceOver：EXCLUDED COALESCE 折叠
    }
    List<MapRow> finalRows = new ArrayList<>(dedup.values());
    StringBuilder vals = new StringBuilder();
    for (int i = 0; i < finalRows.size(); i++) {
        if (i > 0) vals.append(", ");
        int b = i * 11;
        vals.append("(:p").append(b).append(", :p").append(b+1).append(", :p").append(b+2)
            .append(", :p").append(b+3).append(", :p").append(b+4).append(", :p").append(b+5)
            .append(", :p").append(b+6).append(", :p").append(b+7).append(", :p").append(b+8)
            .append(", :p").append(b+9).append(", :p").append(b+10).append(", NOW(), NOW(), :ub)");
    }
    String sql = "INSERT INTO material_customer_map (material_no, customer_no, customer_name, "
        + "customer_material_name, customer_product_no, customer_drawing_no, seq_no, payment_method, "
        + "base_currency, quote_currency, exchange_rate, created_at, updated_at, updated_by) VALUES "
        + vals + " ON CONFLICT (material_no, customer_no, customer_product_no) DO UPDATE SET "
        + "  customer_name=COALESCE(EXCLUDED.customer_name, material_customer_map.customer_name), "
        + "  customer_material_name=COALESCE(EXCLUDED.customer_material_name, material_customer_map.customer_material_name), "
        + "  customer_drawing_no=COALESCE(EXCLUDED.customer_drawing_no, material_customer_map.customer_drawing_no), "
        + "  seq_no=COALESCE(EXCLUDED.seq_no, material_customer_map.seq_no), "
        + "  payment_method=COALESCE(EXCLUDED.payment_method, material_customer_map.payment_method), "
        + "  base_currency=COALESCE(EXCLUDED.base_currency, material_customer_map.base_currency), "
        + "  quote_currency=COALESCE(EXCLUDED.quote_currency, material_customer_map.quote_currency), "
        + "  exchange_rate=COALESCE(EXCLUDED.exchange_rate, material_customer_map.exchange_rate), "
        + "  updated_at=NOW(), updated_by=EXCLUDED.updated_by";
    Query q = em.createNativeQuery(sql);
    for (int i = 0; i < finalRows.size(); i++) {
        MapRow r = finalRows.get(i); int b = i * 11;
        q.setParameter("p"+b, r.materialNo); q.setParameter("p"+(b+1), r.customerNo);
        q.setParameter("p"+(b+2), r.customerName); q.setParameter("p"+(b+3), r.customerMaterialName);
        q.setParameter("p"+(b+4), r.customerProductNo); q.setParameter("p"+(b+5), r.customerDrawingNo);
        q.setParameter("p"+(b+6), r.seqNo); q.setParameter("p"+(b+7), r.paymentMethod);
        q.setParameter("p"+(b+8), r.baseCurrency); q.setParameter("p"+(b+9), r.quoteCurrency);
        q.setParameter("p"+(b+10), r.exchangeRate);
    }
    q.setParameter("ub", updatedBy);
    return q.executeUpdate();
}
private static String nz(String s){ return s==null?"":s; }
// MapRow：上面 11 个值字段的 record/POJO + coalesceOver(prev,next)=对每列 next!=null?next:prev
```

> 注:`material_customer_map` 无 `is_current` 版本列,直接业务键 upsert,与单组版本化无关。

- [ ] **Step 2: Q02 handle 改用 `upsertBatch`**:循环里只 `mapRows.add(new MapRow(...))` + `mmAcc.add(materialNo)`,循环后 `repo.upsertBatch(mapRows, ctx.importedBy)`(替代逐行 `repo.upsert`)。`deleteByCustomerNo` 仍在循环前(语义不变)。recordWrite 计数按 `mapRows.size()` 等价累计。
- [ ] **Step 3: P03/P04/P09/P10/P11/P12 同配方**:各自循环内单条 `createNativeQuery(... ON CONFLICT ...)` → 攒行 + 单条多值 INSERT(冲突键/SET 照抄原 SQL)。这几个 sheet 行数少,收益小但消除 N+1 一致性。
- [ ] **Step 4**: `./mvnw -q -o compile` → SUCCESS。
- [ ] **Step 5: Commit** `git commit -m "feat(v6import): Q02/P03/P04/P09-P12 逐行upsert→批量多值upsert"`

---

## Task 9: 真实导入验证(开 flag)+ 埋点复测 + golden 实库比对

**Files:** `application.properties`(flag→true)

- [ ] **Step 1: 关 flag(false)跑一次真实 QUOTE 导入,记 `[v6import]` 基线**(用现有埋点;backend.log)。
- [ ] **Step 2: 实库 golden 比对**:对**同一个** Excel,在两份隔离客户(或导入前后 dump)上分别 flag off / on 导入,用 SQL 比对各版本化表 + material_customer_map 的业务列多集逐位相同。任何差异 = 阻断,回到 writer 修。
- [ ] **Step 3: 开 flag(true)再导一次,读 `[v6import]`**:期望各 sheet `dbCalls` 从 groups×5 降到常数 ~6,`TOTAL elapsed` 从 ~30s 降到秒级。
- [ ] **Step 4: 把 `[v6import]` 埋点 `INFO→DEBUG`**(同首存 `[*-profile]` 收口惯例;`VersionedV6Writer`/`QuoteImportService`/`PricingImportService`)。
- [ ] **Step 5: Commit** `git commit -m "perf(v6import): 开启集合化写入 flag + 埋点降级DEBUG(实测30s→秒级,golden逐位等价)"`

---

## Task 10: RECORD + 收尾

- [ ] **Step 1**: `docs/RECORD.md` 追加 `[2026-06-27] V6导入 - 落库集合化(逐组/逐行N+1→每sheet常数批) | VersionedV6Writer+18handler+Q02族 | golden逐位等价+flag守护`。
- [ ] **Step 2**: 合并特性分支回 master(用户确认后),跑全量 `./mvnw -q -o test -Dtest='Versioned*,*Handler*Test'` 绿,清理 worktree。

---

## Self-Review(写完已自检)

- **Spec 覆盖**:5 类热点代码块全部有 Task —— writeVersionedGroup(T1)/writeVersionedMasterDetail(T2) 批量化 + golden(T3) + 14 单表 handler(T4/5) + P08 特殊(T6) + 4 主从 handler(T7) + Q02 族逐行 upsert(T8) + 实测验证(T9)。
- **不改结果**:每 Task 以「等价性论证」为验收;T3 golden 测试 + T9 实库比对双重护栏;flag 默认 off 可秒回退。
- **零 N+1**:writer 每 sheet 常数 ~6 RT;非 writer upsert 每 sheet 1 条多值;P08 labor_rate 1 条;均无循环内 DB 调用。
- **类型一致**:`writeVersionedGroups` 返回 `Map<Map<String,Object>,String>`;`MasterDetailItem` 字段名贯穿 T2/T7;助手 `loadCurrentByPrefix/maxVersionByPrefix/flipByIds/deleteByIds/constantColumns/gkKey/textKey` 在 T1 定义、T2 复用,签名一致。
