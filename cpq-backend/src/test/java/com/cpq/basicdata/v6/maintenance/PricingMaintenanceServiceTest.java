package com.cpq.basicdata.v6.maintenance;

import com.cpq.basicdata.v6.maintenance.dto.RowsDTO;
import com.cpq.basicdata.v6.maintenance.dto.SaveGroupRequest;
import com.cpq.basicdata.v6.maintenance.dto.SaveGroupResult;
import com.cpq.common.exception.BusinessException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核价基础数据维护 saveGroup/readRows 单测（task-0712 · B6）。
 * 覆盖三态（CREATED/UNCHANGED/UPGRADED）、source=MANUAL、乐观锁 409、护栏 422、AXIS 篡改忽略、
 * production_energy 折旧/能耗独立版本、MATERIAL_BOM 主从升版。@QuarkusTest 连真实 DB，用 TEST 料号隔离。
 */
@QuarkusTest
class PricingMaintenanceServiceTest {

    @Inject PricingMaintenanceService service;
    @Inject EntityManager em;

    static final String MAT = "TEST-0712-MAT";
    static final String GHOST = "TEST-0712-GHOST";   // 完全不存在（无主档、无核价数据）
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000712");

    @Transactional void seed() {
        // MASTER 校验依赖：工序主表插入测试工序（幂等）
        em.createNativeQuery("INSERT INTO process_master(process_no, process_name) " +
            "SELECT 'TP10','测试工序10' WHERE NOT EXISTS (SELECT 1 FROM process_master WHERE process_no='TP10')").executeUpdate();
        em.createNativeQuery("INSERT INTO process_master(process_no, process_name) " +
            "SELECT 'TP20','测试工序20' WHERE NOT EXISTS (SELECT 1 FROM process_master WHERE process_no='TP20')").executeUpdate();
        // 料号存在性校验依赖：主档插入测试料号（幂等）
        em.createNativeQuery("INSERT INTO material_master(material_no, material_name) " +
            "SELECT :m,'测试料号-0712' WHERE NOT EXISTS (SELECT 1 FROM material_master WHERE material_no=:m)")
            .setParameter("m", MAT).executeUpdate();
    }

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:m OR finished_material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM production_energy WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM capacity WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:m,:g)").setParameter("m", MAT).setParameter("g", GHOST).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); seed(); }
    @AfterEach  void after()  { cleanup(); }

    // ---- 构造前端行 / 请求 ----
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    private static SaveGroupRequest req(String expectedVer, List<Map<String, Object>> rows) {
        SaveGroupRequest r = new SaveGroupRequest();
        r.expectedCurrentVersion = expectedVer;
        r.rows = rows;
        return r;
    }

    private String selfProcessVersion() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:m AND price_type='SELF_PROCESS' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    // ==================================================================
    @Test void save_create_then_unchanged() {
        var rows = List.of(
            row("operation_no", "TP10", "pricing_price", "1.5", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"),
            row("operation_no", "TP20", "pricing_price", "0.8", "currency", "CNY", "unit", "PCS", "defect_rate", "0.01"));

        SaveGroupResult r1 = service.saveGroup(MAT, "SELF_PROCESS", req(null, rows), UID);
        assertEquals("CREATED", r1.result, "空 tab 从零存应 CREATED");
        assertEquals("2000", r1.version, "首版应 2000");
        assertEquals("2000", selfProcessVersion());

        // source=MANUAL
        long manual = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:m AND price_type='SELF_PROCESS' AND is_current=true AND source='MANUAL'")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(2L, manual, "手工保存的行 source 应为 MANUAL");

        // 内容未变重存 → UNCHANGED，不产生新版本
        SaveGroupResult r2 = service.saveGroup(MAT, "SELF_PROCESS", req("2000", rows), UID);
        assertEquals("UNCHANGED", r2.result, "内容未变应 UNCHANGED");
        assertEquals("2000", selfProcessVersion(), "内容未变不应升版");
        assertEquals(2L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:m AND price_type='SELF_PROCESS' AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue(), "不应产生重复 current 行");
    }

    @Test void save_changeValue_upgrades_keepsHistory() {
        var v0 = List.of(row("operation_no", "TP10", "pricing_price", "1.5", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"));
        service.saveGroup(MAT, "SELF_PROCESS", req(null, v0), UID);

        var v1 = List.of(row("operation_no", "TP10", "pricing_price", "9.9", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"));
        SaveGroupResult r = service.saveGroup(MAT, "SELF_PROCESS", req("2000", v1), UID);
        assertEquals("UPGRADED", r.result);
        assertEquals("2001", r.version, "改值应升版 +1");
        assertEquals("2001", selfProcessVersion());

        // 旧版本行保留为 is_current=false
        long oldRows = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:m AND price_type='SELF_PROCESS' AND version_no='2000' AND is_current=false")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, oldRows, "旧版本(2000)应保留为历史");
    }

    @Test void save_optimisticLock_conflict409() {
        service.saveGroup(MAT, "SELF_PROCESS",
            req(null, List.of(row("operation_no", "TP10", "pricing_price", "1.5", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"))), UID);
        // 库内当前版=2000，前端却带过期版本号 1999
        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "SELF_PROCESS",
                req("1999", List.of(row("operation_no", "TP10", "pricing_price", "2.0", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"))), UID));
        assertEquals(409, ex.getCode());
        assertEquals("2000", selfProcessVersion(), "冲突不应写库");
    }

    /** 并发过期写入：5 个请求带同一过期 expectedCurrentVersion 同时保存 → 恰 1 成功、其余 409（TOCTOU 修复）。 */
    @Test void save_concurrentStaleWrites_onlyOneSucceeds() throws Exception {
        service.saveGroup(MAT, "SELF_PROCESS",
            req(null, List.of(row("operation_no", "TP10", "pricing_price", "1.0", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"))), UID);
        assertEquals("2000", selfProcessVersion());

        final int N = 5;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch ready = new CountDownLatch(N);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futs = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            final int k = i;
            futs.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    service.saveGroup(MAT, "SELF_PROCESS",
                        req("2000", List.of(row("operation_no", "TP10", "pricing_price", String.valueOf(10 + k), "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"))), UID);
                    return 200;
                } catch (BusinessException e) {
                    return e.getCode();
                }
            }));
        }
        ready.await();
        go.countDown();   // 5 线程同时冲

        int ok = 0, conflict = 0, other = 0;
        for (Future<Integer> f : futs) {
            int c = f.get(30, TimeUnit.SECONDS);
            if (c == 200) ok++; else if (c == 409) conflict++; else other++;
        }
        pool.shutdownNow();

        assertEquals(1, ok, "并发过期写入应只有 1 个成功");
        assertEquals(N - 1, conflict, "其余应 409（过期）");
        assertEquals(0, other, "不应有其它错误码");
        assertEquals("2001", selfProcessVersion(), "只升一版（无链式 UPGRADED）");
        long cur = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:m AND price_type='SELF_PROCESS' AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, cur, "单 current 行");
    }

    @Test void save_emptyRows_guard422() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "SELF_PROCESS", req(null, List.of()), UID));
        assertEquals(422, ex.getCode());
    }

    @Test void save_nonexistentMaterial_404_noPollution() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.saveGroup(GHOST, "SELF_PROCESS",
                req(null, List.of(row("operation_no", "TP10", "pricing_price", "1.5", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"))), UID));
        assertEquals(404, ex.getCode(), "完全不存在的料号保存应 404");
        long polluted = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:g").setParameter("g", GHOST).getSingleResult()).longValue();
        assertEquals(0L, polluted, "不应落库污染 /parts");
    }

    @Test void readInterfaces_nonexistentMaterial_404() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.readRows(GHOST, "SELF_PROCESS", null)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.overview(GHOST)).getCode());
    }

    @Test void save_axisTampering_ignored() {
        // 行里塞 AXIS 列 price_type/system_type 篡改 → 应被忽略（服务端以 registry 为准）
        var rows = List.of(row("operation_no", "TP10", "pricing_price", "1.5", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02",
            "price_type", "HACKED", "system_type", "QUOTE"));
        service.saveGroup(MAT, "SELF_PROCESS", req(null, rows), UID);
        long correct = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:m AND price_type='SELF_PROCESS' AND system_type='PRICING' AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, correct, "轴应由服务端注入 SELF_PROCESS/PRICING，前端篡改被忽略");
        long hacked = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:m AND price_type='HACKED'")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(0L, hacked);
    }

    @Test void save_invalidEnum_400_legalOk() {
        // 非法币种 → 400 并指明列
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "SELF_PROCESS",
                req(null, List.of(row("operation_no", "TP10", "pricing_price", "1.5", "currency", "XXX", "unit", "PCS", "defect_rate", "0.02"))), UID));
        assertEquals(400, ex1.getCode());
        assertTrue(ex1.getMessage().contains("currency"), "应指明列 currency, 实际: " + ex1.getMessage());

        // 非法 PLATING 费用类型（固定二选一）→ 400
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "PLATING",
                req(null, List.of(row("cost_type", "非法费类", "pricing_price", "1.0", "currency", "CNY", "unit", "PCS", "defect_rate", "0.01"))), UID));
        assertEquals(400, ex2.getCode());

        // 合法 CAPACITY（production_type=BATCH_FIXED / is_effective=true 布尔）→ 正常 CREATED
        SaveGroupResult ok = service.saveGroup(MAT, "CAPACITY",
            req(null, List.of(row("process_no", "TP10", "production_type", "BATCH_FIXED", "is_effective", true))), UID);
        assertEquals("CREATED", ok.result);

        // 非法 production_type → 400
        BusinessException ex3 = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "CAPACITY",
                req("2000", List.of(row("process_no", "TP10", "production_type", "INVALID", "is_effective", true))), UID));
        assertEquals(400, ex3.getCode());

        // 非布尔 is_effective（字符串）→ 400
        BusinessException ex4 = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "CAPACITY",
                req("2000", List.of(row("process_no", "TP10", "production_type", "UNIT", "is_effective", "yes"))), UID));
        assertEquals(400, ex4.getCode());
    }

    @Test void save_masterValidation_missingProcess_400() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "SELF_PROCESS",
                req(null, List.of(row("operation_no", "NOPE999", "pricing_price", "1.5", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"))), UID));
        assertEquals(400, ex.getCode(), "MASTER 编码不存在于主表应 400");
    }

    /**
     * 阻断缺陷修复验证：已导入数据的编码体系与主表/ENUM options 不相交（历史遗留），
     * 原样保存（零改动）不应再被 MASTER/ENUM 校验拦截——只校验"相对当前版本新增/改动过的值"。
     */
    @Transactional void seedLegacyIncompatibleRow() {
        // 直接写库模拟"已导入"数据：operation_no=Z002（不在 process_master）+ currency=USD_LEGACY（不在 ENUM options）
        em.createNativeQuery(
            "INSERT INTO unit_price(code, finished_material_no, system_type, price_type, cost_type, operation_no, " +
            "  pricing_price, currency, unit, defect_rate, version_no, is_current, source) " +
            "VALUES (:m, :m, 'PRICING', 'SELF_PROCESS', '自制加工费', 'Z002', 1.50, 'USD_LEGACY', 'PCS', 0.02, '2000', true, 'IMPORT')")
            .setParameter("m", MAT).executeUpdate();
    }

    @Test void save_legacyIncompatibleValues_untouched_unchangedNotBlocked() {
        seedLegacyIncompatibleRow();

        RowsDTO before = service.readRows(MAT, "SELF_PROCESS", null);
        assertEquals(1, before.rows.size());
        List<Map<String, Object>> sameRows = new ArrayList<>();
        for (Map<String, Object> r : before.rows) {
            Map<String, Object> fr = new LinkedHashMap<>();
            fr.put("operation_no", r.get("operation_no"));
            fr.put("pricing_price", r.get("pricing_price"));
            fr.put("currency", r.get("currency"));
            fr.put("unit", r.get("unit"));
            fr.put("defect_rate", r.get("defect_rate"));
            sameRows.add(fr);
        }

        // ① 原样保存（零改动）→ 不应 400，应 UNCHANGED
        SaveGroupResult r1 = service.saveGroup(MAT, "SELF_PROCESS", req("2000", sameRows), UID);
        assertEquals("UNCHANGED", r1.result, "含历史非主表码/非法枚举的组，原样保存应 UNCHANGED 而非 400");
        assertEquals("2000", selfProcessVersion(), "原样保存不应升版");

        // ② 只改价格（工序码/币种仍是历史遗留值不变）→ 不应 400，应 UPGRADED
        Map<String, Object> changed = new LinkedHashMap<>(sameRows.get(0));
        changed.put("pricing_price", "8.88");
        SaveGroupResult r2 = service.saveGroup(MAT, "SELF_PROCESS", req("2000", List.of(changed)), UID);
        assertEquals("UPGRADED", r2.result, "只改价格、历史非法码不变，应正常 UPGRADED");
        assertEquals("2001", selfProcessVersion());

        // ③ 新增一行填一个真正不存在的新工序码 → 仍应 400（对新录入值继续守门）
        Map<String, Object> withNewInvalid = new LinkedHashMap<>();
        withNewInvalid.put("operation_no", "BRAND-NEW-NOPE");
        withNewInvalid.put("pricing_price", "1.0");
        withNewInvalid.put("currency", "USD_LEGACY");   // 沿用历史遗留值，本身放行
        withNewInvalid.put("unit", "PCS");
        withNewInvalid.put("defect_rate", "0.01");
        List<Map<String, Object>> withNewRow = new ArrayList<>(List.of(changed));
        withNewRow.add(withNewInvalid);
        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "SELF_PROCESS", req("2001", withNewRow), UID));
        assertEquals(400, ex.getCode(), "新增行填了主表不存在的全新工序码，仍应 400");
    }

    /** 从零新建（该组无当前版本）：所有值都是"新"，仍严格全量校验，非法新码应 400。 */
    @Test void save_fromScratch_stillStrictValidation_400() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "SELF_PROCESS",
                req(null, List.of(row("operation_no", "TOTALLY-FAKE", "pricing_price", "1.5",
                    "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"))), UID));
        assertEquals(400, ex.getCode(), "从零新建填非法码仍应 400（existingCodes=∅，等价全量校验）");

        // ENUM 同理：从零新建填非法枚举仍 400
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
            service.saveGroup(MAT, "SELF_PROCESS",
                req(null, List.of(row("operation_no", "TP10", "pricing_price", "1.5",
                    "currency", "NOT_A_CURRENCY", "unit", "PCS", "defect_rate", "0.02"))), UID));
        assertEquals(400, ex2.getCode(), "从零新建填非法枚举仍应 400");
    }

    @Test void depreciation_energy_independentVersions() {
        var dep = List.of(row("process_no", "TP10", "unit_price", "1.11", "currency", "CNY", "unit", "H"));
        var eng = List.of(row("process_no", "TP10", "unit_price", "2.22", "currency", "CNY", "unit", "H"));
        service.saveGroup(MAT, "DEPRECIATION", req(null, dep), UID);
        service.saveGroup(MAT, "ENERGY", req(null, eng), UID);

        // 改折旧 → 折旧升版，能耗不动
        service.saveGroup(MAT, "DEPRECIATION", req("2000", List.of(row("process_no", "TP10", "unit_price", "3.33", "currency", "CNY", "unit", "H"))), UID);

        String depV = String.valueOf(em.createNativeQuery(
            "SELECT calc_version FROM production_energy WHERE material_no=:m AND price_type='DEPRECIATION' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getSingleResult());
        String engV = String.valueOf(em.createNativeQuery(
            "SELECT calc_version FROM production_energy WHERE material_no=:m AND price_type='ENERGY' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getSingleResult());
        assertEquals("2001", depV, "折旧应升版");
        assertEquals("2000", engV, "能耗版本不应受折旧升版影响");
    }

    @Test void materialBom_masterDetail_upgrade() {
        var v0 = List.of(
            row("seq_no", 1, "component_no", "C-A", "operation_no", "TP10", "composition_qty", "2.0", "calc_type", "材料", "production_no", "PROD-1"),
            row("seq_no", 2, "component_no", "C-B", "operation_no", "TP10", "composition_qty", "1.0", "calc_type", "材料", "production_no", "PROD-1"));
        SaveGroupResult r0 = service.saveGroup(MAT, "MATERIAL_BOM", req(null, v0), UID);
        assertEquals("CREATED", r0.result);
        assertEquals("2000", r0.version);

        // 主表 source=MANUAL + bom_version=2000
        long m = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=true AND source='MANUAL' AND bom_version='2000'")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, m, "主表应 1 条 current，source=MANUAL");

        // 改子表用量 → 升版
        var v1 = List.of(
            row("seq_no", 1, "component_no", "C-A", "operation_no", "TP10", "composition_qty", "5.0", "calc_type", "材料", "production_no", "PROD-1"),
            row("seq_no", 2, "component_no", "C-B", "operation_no", "TP10", "composition_qty", "1.0", "calc_type", "材料", "production_no", "PROD-1"));
        SaveGroupResult r1 = service.saveGroup(MAT, "MATERIAL_BOM", req("2000", v1), UID);
        assertEquals("UPGRADED", r1.result);
        assertEquals("2001", r1.version, "子表整批改应升主表 bom_version");
    }

    /**
     * 三态统一回归（Critical 修复）：PricingSheetRegistry.childFixedGk 曾遗留
     * characteristic=null，V344 回填后 gk 恒匹配 0 行 → 维护路径读空表 + 保存产生双 current。
     * 覆盖：①保存后能读回（gk 不再被卡住）②派生正确（calc_type→characteristic）③连存两次无双 current。
     */
    @Test void materialBom_characteristic_derivedAndReadable_noDoubleCurrent() {
        var v0 = List.of(
            row("seq_no", 1, "component_no", "C-A", "operation_no", "TP10", "composition_qty", "2.0", "calc_type", "材料", "production_no", "PROD-1"),
            row("seq_no", 2, "component_no", "EL-01", "operation_no", "TP10", "composition_qty", "1.0", "calc_type", "元素", "production_no", "PROD-1"));
        SaveGroupResult r0 = service.saveGroup(MAT, "MATERIAL_BOM", req(null, v0), UID);
        assertEquals("CREATED", r0.result);
        assertEquals("2000", r0.version);

        // ① 读回：维护路径 gk（childFixedGk 不再含 characteristic）应能看到刚保存的 2 行，不是空表。
        RowsDTO dto = service.readRows(MAT, "MATERIAL_BOM", null);
        assertEquals(2, dto.rows.size(), "childGk 不应再被 characteristic=null 卡成空表");

        // ② 派生正确：calc_type='元素' → RECIPE；calc_type='材料'（含缺省）→ ASSEMBLY。
        long recipeRows = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=true AND calc_type='元素' AND characteristic='RECIPE'")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, recipeRows, "calc_type=元素 应派生 characteristic=RECIPE");
        long assemblyRows = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=true AND calc_type='材料' AND characteristic='ASSEMBLY'")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, assemblyRows, "calc_type=材料 应派生 characteristic=ASSEMBLY");
        long nullCharacteristic = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=true AND characteristic IS NULL")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(0L, nullCharacteristic, "保存路径不应再写出 characteristic=NULL 的行（cz_view='RECIPE' 谓词捞不到）");

        // ③ 不产生双 current：内容不变原样重存，is_current=true 的子行数不应翻倍。
        SaveGroupResult r1 = service.saveGroup(MAT, "MATERIAL_BOM", req("2000", v0), UID);
        assertEquals("UNCHANGED", r1.result, "内容未变应 UNCHANGED，不应因 gk 匹配不到旧行而误判全新组");
        long curRows = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(2L, curRows, "重存不应产生双 current（旧行未被下线导致的行数翻倍）");
    }

    @Test void readRows_currentAndEditable() {
        service.saveGroup(MAT, "SELF_PROCESS",
            req(null, List.of(row("operation_no", "TP10", "pricing_price", "1.5", "currency", "CNY", "unit", "PCS", "defect_rate", "0.02"))), UID);
        RowsDTO dto = service.readRows(MAT, "SELF_PROCESS", null);
        assertTrue(dto.isCurrent);
        assertTrue(dto.editable, "当前版可编辑");
        assertEquals("2000", dto.version);
        assertEquals(1, dto.rows.size());
        assertEquals("TP10", String.valueOf(dto.rows.get(0).get("operation_no")));
        assertEquals("测试工序10", String.valueOf(dto.rows.get(0).get("operation_name")), "NAME 列应 join 带出工序名");
        // DECIMAL 以按列 scale 定标的字符串返回（api.md §4）
        Object pp = dto.rows.get(0).get("pricing_price");
        assertInstanceOf(String.class, pp, "DECIMAL 必须是字符串, 非 JSON 数字");
        assertEquals("1.500000", pp, "pricing_price scale=6 定标字符串");
        assertEquals("0.0200", dto.rows.get(0).get("defect_rate"), "defect_rate scale=4 定标字符串");
    }

    /** TC-D-06：折旧极小单价 0.000003 (scale 6) 必须返回 "0.000003" 定标字符串，禁科学计数 3E-6。 */
    @Test void readRows_decimal_scaledPlainString_noSciNotation() {
        service.saveGroup(MAT, "DEPRECIATION",
            req(null, List.of(row("process_no", "TP10", "unit_price", "0.000003", "currency", "CNY", "unit", "H"))), UID);
        RowsDTO dto = service.readRows(MAT, "DEPRECIATION", null);
        Object up = dto.rows.get(0).get("unit_price");
        assertInstanceOf(String.class, up, "DECIMAL 必须序列化为字符串");
        assertEquals("0.000003", up, "小数应定标 plain 字符串");
        assertFalse(String.valueOf(up).toLowerCase().contains("e"), "禁科学计数");
    }
}
