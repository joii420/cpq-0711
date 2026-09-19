package com.cpq.priceadjust.ac260918;

import com.cpq.priceadjust.service.PriceAdjustBudgetService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * S-BE 分片的私有造数（test.md §3.1）。所有对象都挂在一个<b>本轮随机</b>客户 {@code RP0918A-<8位hex>} 名下：
 * 客户 / 调价策略 / 元素取价策略 / 模板与组件 / 报价单与产品行 / 价格版本 / 指针 / 审核行 / 更新任务。
 *
 * <p><b>为什么是合成大单而不是 test.md §3.1 写的「复制 QT-20260909-0629」</b>（已在回报里请主线裁决）：
 * 原单挂在 {@code CUST-0004} 名下 —— 若副本仍挂 CUST-0004，本片生成版本 / 推进指针 / 建审核就会写到共享客户的
 * 数据上（违反私有写口径）；若把副本改挂到自建客户，副本里按客户取数的 {@code $view} 渲染结果会变，
 * 升版失败或结果漂移将无法与实现问题区分。合成单：同一模板、行数可控（12 / 1000 / 1200 / 1845）、
 * 每行载荷按真实大单体量填充（默认每行约 3×1.4 kB，1200 行 ≈ 5 MB，对齐问题说明 ④ 实测量级），
 * 且升版后的小计有<b>独立可算的期望值</b>（本期价 + 固定额，按 9 位结果边界舍入）。
 *
 * <p>🔒 清理：{@link #cleanup()} 先按「本轮客户号 / 本轮建的报价单 id」<b>查出</b>自建对象的 id，再<b>按 id 删</b>；
 * 客户号有格式守卫（{@link #OWN_CUSTOMER}），不匹配直接拒绝清理。
 */
public final class Rp0918aFixture {

    public static final String PREFIX = "RP0918A";
    public static final Pattern OWN_CUSTOMER = Pattern.compile("^RP0918A-[0-9a-f]{8}$");

    /** 上期 / 本期银价 —— 与开发库 V26091802 的量级一致（问题说明 ⑥ 环境口径）。 */
    public static final BigDecimal P_PREV = new BigDecimal("28892.500000000000");
    public static final BigDecimal P_TARGET = new BigDecimal("28893.500000000000");
    /** 每行固定额（第二个计入小计的金额字段），12 位小数，用来暴露任何精度截断。 */
    public static final BigDecimal FIXED = new BigDecimal("1.234567890123");
    /** 默认每行填充块数（每块 32 个 hex 字符）；3 处填充 × 44 × 32 ≈ 4.2 kB / 行。 */
    public static final int DEFAULT_PAD_BLOCKS = 44;

    public final Rp0918aDb db;
    public final String run;
    public final String customerNo;
    public UUID customerId;
    public UUID salesRepId;
    public UUID strategyId;
    public Template template;

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> componentIds = new ArrayList<>();
    private final List<UUID> templateIds = new ArrayList<>();

    public record Template(UUID id, UUID elementComponentId, UUID subtotalComponentId,
                           String elementCode, String subtotalCode, String frozenTabs) {
    }

    /** 报价单句柄：ordinal = 行号（sort_order，1 起）。 */
    public record Quote(UUID id, String number, String label, int lineCount,
                        Map<Integer, String> materialByOrdinal, Map<String, UUID> lineByMaterial,
                        Map<Integer, UUID> lineByOrdinal) {
        public UUID line(String material) {
            UUID id = lineByMaterial.get(material);
            if (id == null) throw new IllegalStateException("报价单 " + number + " 里没有料号 " + material);
            return id;
        }

        public String material(int ordinal) {
            String m = materialByOrdinal.get(ordinal);
            if (m == null) throw new IllegalStateException("报价单 " + number + " 没有第 " + ordinal + " 行");
            return m;
        }
    }

    /** 边界行：0 = 不设。 */
    public record Edge(int nullQuoteCardOrdinal, int nullComponentOrdinal, int noComponentDataOrdinal) {
        public static final Edge NONE = new Edge(0, 0, 0);
    }

    public Rp0918aFixture(Rp0918aDb db) {
        this.db = db;
        this.run = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.customerNo = PREFIX + "-" + run;
    }

    /**
     * 行小计的独立期望值 = 元素价 + 固定额，<b>按 9 位小数 HALF_UP 舍入</b>。
     * 依据：main-api「task-0810 精度契约」—— 产品小计走独立 9 位结果边界；并经 master 基线实测确认
     * （AC-19 基线：升版行 subtotal = 28894.734567890000，而非 12 位精确和 28894.734567890123）。
     */
    public static BigDecimal expectedSubtotal(BigDecimal elementPrice) {
        return elementPrice.add(FIXED).setScale(9, RoundingMode.HALF_UP);
    }

    public String material(String label, int ordinal) {
        return PREFIX + "-" + run + "-" + label + "-" + String.format("%04d", ordinal);
    }

    /** 版本号列宽 20：RP8A + 8 位 run + '-' + 标签（≤6）。 */
    public String versionNo(String tag) {
        String t = tag.length() > 6 ? tag.substring(0, 6) : tag;
        return "RP8A" + run + "-" + t;
    }

    // ── 客户 / 策略 ─────────────────────────────────────────────────────────

    public Rp0918aFixture createCustomer() {
        customerId = UUID.randomUUID();
        db.exec("INSERT INTO customer (id, code, name, remarks) VALUES (:id, :code, :name, 'RP0918A S-BE 自建测试客户')",
            "id", customerId, "code", customerNo, "name", PREFIX + " 测试客户 " + run);
        salesRepId = (UUID) db.scalar("SELECT id FROM \"user\" ORDER BY created_at LIMIT 1");
        if (salesRepId == null) throw new IllegalStateException("库里没有任何 user，无法建报价单");
        return this;
    }

    /**
     * 客户调价策略：启用、只勾银、范围 = 指定料号（SPECIFIED，料号由 {@link #addScope} 补）。
     * 周期设为「每月 N 日 23:59」且 N ≠ 今天 —— 即使别的测试进程开着定时扫描，也不会替本客户生成版本。
     */
    public Rp0918aFixture createAdjustStrategy() {
        strategyId = UUID.randomUUID();
        int today = LocalDate.now().getDayOfMonth();
        int dom = ((today + 13) % 28) + 1;
        db.exec("INSERT INTO customer_price_adjust_strategy (id, customer_no, enabled, cycle_type, cycle_day_of_month, "
                + "execute_time, material_scope_mode, cost_diff_threshold) "
                + "VALUES (:id, :c, true, 'MONTHLY_DAY', CAST(:dom AS smallint), CAST('23:59:00' AS time), 'SPECIFIED', 0)",
            "id", strategyId, "c", customerNo, "dom", dom);
        db.exec("INSERT INTO customer_price_adjust_element (strategy_id, element_code) VALUES (:s, 'Ag')", "s", strategyId);
        return this;
    }

    public void addScope(Collection<String> materials) {
        for (String m : materials) {
            db.exec("INSERT INTO customer_price_adjust_material (strategy_id, material_no) VALUES (:s, :m) "
                + "ON CONFLICT DO NOTHING", "s", strategyId, "m", m);
        }
    }

    public void addScopeFromQuotes(Collection<UUID> qids) {
        db.exec("INSERT INTO customer_price_adjust_material (strategy_id, material_no) "
                + "SELECT DISTINCT :s, product_part_no_snapshot FROM quotation_line_item WHERE quotation_id IN (:q) "
                + "ON CONFLICT DO NOTHING", "s", strategyId, "q", new ArrayList<>(qids));
    }

    /**
     * 元素取价策略（task-0722 的 {@code element_price_strategy}）：让 {@code f_customer_element_price(本客户, 今天)}
     * 对银恰好算出 {@code target}。表约束要求 factor &gt; 0 ⇒ factor 取最小正值 1e-12，premium 抵消
     * {@code raw × factor}（误差 &lt; 5e-13，函数 ROUND 到 9 位后精确等于 target）。
     * <b>只写本客户自己的一行，不碰全局元素日价。</b> 写完用函数本身复核，复核不过直接抛（夹具失败 ≠ 实现失败）。
     */
    public void setElementPriceTarget(BigDecimal target) {
        List<Object[]> latest = db.rows("SELECT source_id, raw_price FROM element_daily_price "
            + "WHERE element_name = 'Ag' AND raw_price IS NOT NULL AND price_date <= CURRENT_DATE "
            + "ORDER BY price_date DESC, source_id LIMIT 1");
        if (latest.isEmpty()) throw new IllegalStateException("测试库里没有任何银日价，无法构造元素取价策略");
        UUID sourceId = (UUID) latest.get(0)[0];
        BigDecimal raw = new BigDecimal(latest.get(0)[1].toString());
        BigDecimal factor = new BigDecimal("0.000000000001");
        BigDecimal premium = target.subtract(raw.multiply(factor).setScale(12, RoundingMode.HALF_UP));
        Object existing = db.scalar("SELECT id FROM element_price_strategy WHERE customer_no = :c AND element_code = 'Ag'",
            "c", customerNo);
        if (existing == null) {
            db.exec("INSERT INTO element_price_strategy (customer_no, element_code, source_id, method, factor, premium, status) "
                    + "VALUES (:c, 'Ag', :src, 'LATEST', CAST(:f AS numeric), CAST(:p AS numeric), 'ACTIVE')",
                "c", customerNo, "src", sourceId, "f", factor.toPlainString(), "p", premium.toPlainString());
        } else {
            db.exec("UPDATE element_price_strategy SET source_id = :src, factor = CAST(:f AS numeric), "
                    + "premium = CAST(:p AS numeric), updated_at = now() WHERE id = :id",
                "id", existing, "src", sourceId, "f", factor.toPlainString(), "p", premium.toPlainString());
        }
        Object got = db.scalar("SELECT unit_price FROM f_customer_element_price(:c, CURRENT_DATE) WHERE element_code = 'Ag'",
            "c", customerNo);
        if (got == null || new BigDecimal(got.toString()).compareTo(target) != 0) {
            throw new IllegalStateException("夹具复核失败：f_customer_element_price(" + customerNo + ") 银 = " + got
                + "，期望 " + target.toPlainString());
        }
    }

    // ── 模板（与 MaterialVersionUpgradePrecisionParityTest 同结构：元素页签 + 小计页签） ───────────

    public Template createTemplate() {
        UUID elemId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID tplId = UUID.randomUUID();
        String elemCode = PREFIX + "-ELEM-" + run;
        String subCode = PREFIX + "-SUB-" + run;
        String fields = "[{\"name\":\"element\",\"field_type\":\"INPUT_TEXT\"},"
            + "{\"name\":\"price\",\"field_type\":\"INPUT_NUMBER\",\"is_amount\":true,\"is_subtotal\":true},"
            + "{\"name\":\"fixedAmount\",\"field_type\":\"INPUT_NUMBER\",\"is_amount\":true,\"is_subtotal\":true}]";
        String subFormulas = "[{\"expression\":[{\"type\":\"component_subtotal\",\"component_code\":\"" + elemCode
            + "\",\"value\":\"__amount_total__\"}]}]";
        db.exec("INSERT INTO component (id, name, code, component_type, fields, formulas, element_code_field, "
                + "element_price_field, element_currency_field) VALUES (:id, :name, :code, 'NORMAL', CAST(:f AS jsonb), "
                + "'[]', 'element', 'price', 'currency')",
            "id", elemId, "name", PREFIX + " 元素页签 " + run, "code", elemCode, "f", fields);
        componentIds.add(elemId);
        db.exec("INSERT INTO component (id, name, code, component_type, fields, formulas) "
                + "VALUES (:id, :name, :code, 'SUBTOTAL', '[]', CAST(:fm AS jsonb))",
            "id", subId, "name", PREFIX + " 小计页签 " + run, "code", subCode, "fm", subFormulas);
        componentIds.add(subId);

        String frozenTabs = "[{\"componentId\":\"" + elemId + "\",\"componentCode\":\"" + elemCode + "\","
            + "\"tabName\":\"元素成本\",\"componentType\":\"NORMAL\",\"sortOrder\":1,"
            + "\"fields\":[{\"name\":\"element\",\"fieldType\":\"INPUT_TEXT\"},"
            + "{\"name\":\"price\",\"fieldType\":\"INPUT_NUMBER\",\"isAmount\":true,\"isSubtotal\":true},"
            + "{\"name\":\"fixedAmount\",\"fieldType\":\"INPUT_NUMBER\",\"isAmount\":true,\"isSubtotal\":true}],"
            + "\"formulas\":[],\"formula_assignments\":[]},"
            + "{\"componentId\":\"" + subId + "\",\"componentCode\":\"" + subCode + "\","
            + "\"tabName\":\"合计\",\"componentType\":\"SUBTOTAL\",\"sortOrder\":2,"
            + "\"fields\":[],\"formula_assignments\":[],\"formulas\":[{\"expression\":[{"
            + "\"type\":\"component_subtotal\",\"component_code\":\"" + elemCode + "\","
            + "\"value\":\"__amount_total__\"}]}]}]";

        db.exec("INSERT INTO template (id, template_series_id, name, version, status, template_kind, customer_id, "
                + "components_snapshot, product_attributes, subtotal_formula, template_sql_views_snapshot, formulas, "
                + "created_at, updated_at, published_at) VALUES (:id, :series, :name, 'v1', 'PUBLISHED', 'QUOTATION', "
                + ":cust, CAST(:snap AS jsonb), '[]', '[]', '{}', '[]', now(), now(), now())",
            "id", tplId, "series", UUID.randomUUID(), "name", PREFIX + " 模板 " + run, "cust", customerId,
            "snap", frozenTabs);
        templateIds.add(tplId);
        insertFrozenTab(tplId, elemId, 1, "元素成本", PREFIX + " 元素页签 " + run, elemCode, "NORMAL", fields, "[]",
            "element", "price", "currency");
        insertFrozenTab(tplId, subId, 2, "合计", PREFIX + " 小计页签 " + run, subCode, "SUBTOTAL", "[]", subFormulas,
            null, null, null);
        template = new Template(tplId, elemId, subId, elemCode, subCode, frozenTabs);
        return template;
    }

    private void insertFrozenTab(UUID tplId, UUID cid, int sortOrder, String tabName, String componentName,
                                 String componentCode, String componentType, String fields, String formulas,
                                 String elementCodeField, String elementPriceField, String elementCurrencyField) {
        UUID tcId = UUID.randomUUID();
        db.exec("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name) "
                + "VALUES (:id, :t, :c, :s, :n)",
            "id", tcId, "t", tplId, "c", cid, "s", sortOrder, "n", tabName);
        String roleCols = elementCodeField == null ? "NULL, NULL, NULL" : "'" + elementCodeField + "', '"
            + elementPriceField + "', '" + elementCurrencyField + "'";
        db.exec("INSERT INTO template_component_snapshot (template_id, template_component_id, component_id, sort_order, "
                + "tab_name, component_name, component_code, component_type, fields, formulas, element_code_field, "
                + "element_price_field, element_currency_field) VALUES (:t, :tc, :c, :s, :n, :cn, :cc, :ct, "
                + "CAST(:f AS jsonb), CAST(:fm AS jsonb), " + roleCols + ")",
            "t", tplId, "tc", tcId, "c", cid, "s", sortOrder, "n", tabName, "cn", componentName, "cc", componentCode,
            "ct", componentType, "f", fields, "fm", formulas);
    }

    // ── 报价单 ──────────────────────────────────────────────────────────────

    public Quote createQuote(String label, int lineCount, Map<Integer, String> materialOverrides, Edge edge) {
        return createQuote(label, lineCount, materialOverrides, edge, DEFAULT_PAD_BLOCKS);
    }

    /**
     * 造一张 DRAFT（活单）报价单：{@code lineCount} 行，每行一个料号（默认 {@link #material(label, ordinal)}，
     * 可按行号覆盖成指定料号 —— 用于「同一料号出现在两张单」），每行一条元素页签数据（银，上期价 + 固定额）。
     * 行小计 = {@link #expectedSubtotal}(上期价)（9 位结果边界，与系统重算口径一致，保证升版前不触发口径守卫）。
     */
    public Quote createQuote(String label, int lineCount, Map<Integer, String> materialOverrides, Edge edge,
                             int padBlocks) {
        if (template == null) throw new IllegalStateException("先 createTemplate()");
        UUID qid = UUID.randomUUID();
        String number = PREFIX + "-" + run + "-" + label;
        db.exec("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, "
                + "customer_template_id, original_amount, total_amount, final_discount_rate, tax_amount, remarks, "
                + "created_at, updated_at) VALUES (:id, :no, :cust, :name, :rep, 'DRAFT', :tpl, 0, 0, 100, 0, "
                + "'RP0918A S-BE 自建', clock_timestamp(), clock_timestamp())",
            "id", qid, "no", number, "cust", customerId, "name", PREFIX + " " + label + " " + lineCount + "行",
            "rep", salesRepId, "tpl", template.id());
        quotationIds.add(qid);
        db.exec("INSERT INTO quotation_view_structure (quotation_id, view_kind, structure) "
                + "VALUES (:q, 'QUOTE_CARD', CAST(:s AS jsonb))",
            "q", qid, "s", "{\"tabs\":" + template.frozenTabs() + "}");

        String prefix = PREFIX + "-" + run + "-" + label + "-";
        StringBuilder mExpr = new StringBuilder();
        if (materialOverrides != null && !materialOverrides.isEmpty()) {
            mExpr.append("CASE g");
            for (Map.Entry<Integer, String> e : materialOverrides.entrySet()) {
                mExpr.append(" WHEN ").append(e.getKey().intValue()).append(" THEN '")
                    .append(e.getValue().replace("'", "''")).append("'");
            }
            mExpr.append(" ELSE '").append(prefix).append("' || lpad(g::text, 4, '0') END");
        } else {
            mExpr.append("'").append(prefix).append("' || lpad(g::text, 4, '0')");
        }
        String subtotal = expectedSubtotal(P_PREV).toPlainString();
        Edge e = edge == null ? Edge.NONE : edge;

        db.exec("WITH src AS (SELECT g, " + mExpr + " AS mno, "
                + "coalesce((SELECT string_agg(md5(g::text || ':' || i::text || :salt), '') "
                + "FROM generate_series(1, :pad) i), '') AS pad FROM generate_series(1, :n) g) "
                + "INSERT INTO quotation_line_item (id, quotation_id, template_id, product_part_no_snapshot, "
                + "product_name_snapshot, composite_type, annual_volume, discount_source, discount_rate_applied, "
                + "subtotal, line_unit_price, discount_base_amount, line_final_price, line_discount_amount, "
                + "line_total_amount, quote_card_values, costing_card_values, sort_order, created_at) "
                + "SELECT gen_random_uuid(), :q, :tpl, mno, 'RP0918A 品名 ' || g, 'SIMPLE', 7, 'SUBTOTAL', 12.34, "
                + "CAST(:sub AS numeric), 1.000000000001, 2.000000000001, 3.000000000001, 4.000000000001, "
                + "CAST(:sub AS numeric), "
                + "CASE WHEN g = :nullCard THEN NULL ELSE jsonb_build_object('marker', 'RP0918A', 'ordinal', g, 'pad', pad) END, "
                + "jsonb_build_object('marker', 'RP0918A-costing', 'ordinal', g, 'pad', pad), g, clock_timestamp() FROM src",
            "salt", run, "pad", padBlocks, "n", lineCount, "q", qid, "tpl", template.id(), "sub", subtotal,
            "nullCard", e.nullQuoteCardOrdinal());

        db.exec("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, snapshot_rows, row_data, "
                + "row_version, subtotal, created_at) "
                + "SELECT gen_random_uuid(), li.id, :cid, "
                + "jsonb_build_array(jsonb_build_object('driverRow', jsonb_build_object('element', 'Ag', 'price', :p0, "
                + "'fixedAmount', :fixed, 'currency', 'CNY', 'memo', coalesce(li.costing_card_values->>'pad', '')), "
                + "'basicDataValues', '{}'::jsonb)), "
                + "jsonb_build_array(jsonb_build_object('marker', 'RP0918A')), 5, CAST(:sub AS numeric), clock_timestamp() "
                + "FROM quotation_line_item li WHERE li.quotation_id = :q AND li.sort_order <> :noComp",
            "cid", template.elementComponentId(), "p0", P_PREV.toPlainString(), "fixed", FIXED.toPlainString(),
            "sub", subtotal, "q", qid, "noComp", e.noComponentDataOrdinal());

        if (e.nullComponentOrdinal() > 0) {
            db.exec("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, snapshot_rows, row_data, "
                    + "row_version, created_at) SELECT gen_random_uuid(), li.id, NULL, "
                    + "'[{\"note\":\"RP0918A component_id 为空的边界行\"}]'::jsonb, '[]'::jsonb, 0, clock_timestamp() "
                    + "FROM quotation_line_item li WHERE li.quotation_id = :q AND li.sort_order = :o",
                "q", qid, "o", e.nullComponentOrdinal());
        }
        db.exec("UPDATE quotation SET original_amount = t.s, total_amount = t.s FROM "
                + "(SELECT coalesce(sum(line_total_amount), 0) s FROM quotation_line_item WHERE quotation_id = :q) t "
                + "WHERE id = :q", "q", qid);

        Map<Integer, String> byOrdinal = new LinkedHashMap<>();
        Map<String, UUID> byMaterial = new LinkedHashMap<>();
        Map<Integer, UUID> lineByOrdinal = new LinkedHashMap<>();
        for (Object[] r : db.rows("SELECT sort_order, product_part_no_snapshot, id FROM quotation_line_item "
            + "WHERE quotation_id = :q ORDER BY sort_order", "q", qid)) {
            int o = ((Number) r[0]).intValue();
            byOrdinal.put(o, (String) r[1]);
            byMaterial.put((String) r[1], (UUID) r[2]);
            lineByOrdinal.put(o, (UUID) r[2]);
        }
        if (byOrdinal.size() != lineCount) {
            throw new IllegalStateException("夹具失败：报价单 " + number + " 期望 " + lineCount + " 行，实际 " + byOrdinal.size());
        }
        return new Quote(qid, number, label, lineCount, byOrdinal, byMaterial, lineByOrdinal);
    }

    // ── 版本 / 指针 / 审核 ───────────────────────────────────────────────────

    /** 直接插入一个价格版本（只含银一条明细）；{@code ageSeconds} 控制 created_at 先后。 */
    public UUID insertVersion(String tag, String status, BigDecimal agPrice, BigDecimal prevPrice, BigDecimal changeRate,
                              int ageSeconds) {
        UUID id = UUID.randomUUID();
        db.exec("INSERT INTO element_price_version (id, customer_no, version_no, base_date, status, trigger_type, created_at) "
                + "VALUES (:id, :c, :vn, CURRENT_DATE, :st, 'MANUAL', now() - make_interval(secs => :age))",
            "id", id, "c", customerNo, "vn", versionNo(tag), "st", status, "age", ageSeconds);
        String prev = prevPrice == null ? "NULL" : "CAST('" + prevPrice.toPlainString() + "' AS numeric)";
        String rate = changeRate == null ? "NULL" : "CAST('" + changeRate.toPlainString() + "' AS numeric)";
        db.exec("INSERT INTO element_price_version_item (version_id, element_code, current_price, previous_price, "
                + "change_rate, currency, price_unit, no_price, inherited_from_previous) "
                + "VALUES (:v, 'Ag', CAST(:p AS numeric), " + prev + ", " + rate + ", 'CNY', 'kg', false, false)",
            "v", id, "p", agPrice.toPlainString());
        return id;
    }

    /** 把这些报价单里出现的全部料号的指针指到 versionId（upsert，只动本客户的行）。 */
    public void setPointersFromQuotes(UUID versionId, Collection<UUID> qids) {
        db.exec("INSERT INTO material_price_version_ref (customer_no, material_no, version_id, updated_at) "
                + "SELECT DISTINCT :c, product_part_no_snapshot, CAST(:v AS uuid), now() FROM quotation_line_item "
                + "WHERE quotation_id IN (:q) "
                + "ON CONFLICT (customer_no, material_no) DO UPDATE SET version_id = EXCLUDED.version_id, updated_at = now()",
            "c", customerNo, "v", versionId, "q", new ArrayList<>(qids));
    }

    public void setPointer(String material, UUID versionId) {
        db.exec("INSERT INTO material_price_version_ref (customer_no, material_no, version_id, updated_at) "
                + "VALUES (:c, :m, :v, now()) "
                + "ON CONFLICT (customer_no, material_no) DO UPDATE SET version_id = EXCLUDED.version_id, updated_at = now()",
            "c", customerNo, "m", material, "v", versionId);
    }

    public UUID pointer(String material) {
        return (UUID) db.scalar("SELECT version_id FROM material_price_version_ref WHERE customer_no = :c AND material_no = :m",
            "c", customerNo, "m", material);
    }

    /**
     * 走真实预算（既有包内可见方法 {@code processMaterial}，反射调用）为这些料号建审核行，并断言每条都「待处理 + 就绪」。
     * 预算本身会对判断依据单做一次试算升版 —— 大单上它也是被测对象（问题 5 / E-5）。
     */
    public Map<String, UUID> createReviewsViaBudget(PriceAdjustBudgetService budget, UUID versionId,
                                                    Collection<String> materials) {
        Map<String, UUID> out = new LinkedHashMap<>();
        for (String m : materials) {
            invokeProcessMaterial(budget, versionId, m);
            List<Object[]> r = db.rows("SELECT id, status, budget_status, coalesce(budget_error, '') FROM material_price_review "
                + "WHERE version_id = :v AND material_no = :m", "v", versionId, "m", m);
            if (r.size() != 1 || !"PENDING".equals(r.get(0)[1]) || !"READY".equals(r.get(0)[2])) {
                throw new IllegalStateException("夹具失败：料号 " + m + " 预算后审核行不是 PENDING/READY："
                    + (r.isEmpty() ? "无审核行" : r.get(0)[1] + "/" + r.get(0)[2] + " " + r.get(0)[3]));
            }
            out.put(m, (UUID) r.get(0)[0]);
        }
        return out;
    }

    /**
     * {@code processMaterial(UUID versionId, String customerNo, BigDecimal threshold, String materialNo)} 是包内可见方法
     * （签名取自既有测试 {@code PriceAdjustBudgetServiceDecision39Test}），本包不同包 ⇒ 反射调用。
     * 调用对象是注入进来的客户端代理（代理与 bean 同包、会覆写包内可见方法并委派给真实实例，事务拦截照常生效）。
     */
    private void invokeProcessMaterial(PriceAdjustBudgetService budget, UUID versionId, String material) {
        try {
            java.lang.reflect.Method pm = PriceAdjustBudgetService.class.getDeclaredMethod("processMaterial",
                UUID.class, String.class, BigDecimal.class, String.class);
            pm.setAccessible(true);
            pm.invoke(budget, versionId, customerNo, BigDecimal.ZERO, material);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("夹具失败：预算 processMaterial(" + material + ") 抛异常", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("夹具失败：找不到 processMaterial(UUID,String,BigDecimal,String)", e);
        }
    }

    // ── 任务 / 明细（AC-21 / AC-25 造「中断现场」用） ─────────────────────────

    public UUID insertJob(UUID versionId, String status, String ageInterval) {
        UUID id = UUID.randomUUID();
        db.exec("INSERT INTO material_price_update_job (id, customer_no, version_id, version_no, status, total_count, "
                + "triggered_at, created_at) SELECT :id, :c, v.id, v.version_no, :st, 0, now() - CAST(:age AS interval), "
                + "now() - CAST(:age AS interval) FROM element_price_version v WHERE v.id = :v",
            "id", id, "c", customerNo, "v", versionId, "st", status, "age", ageInterval);
        return id;
    }

    public UUID insertJobItem(UUID jobId, UUID quotationId, String material, UUID lineId, String status,
                              String ageInterval) {
        UUID id = UUID.randomUUID();
        db.exec("INSERT INTO material_price_update_job_item (id, job_id, quotation_id, material_no, line_item_id, status, "
                + "retry_count, created_at, updated_at) VALUES (:id, :j, :q, :m, :l, :st, 0, "
                + "now() - CAST(:age AS interval), now() - CAST(:age AS interval))",
            "id", id, "j", jobId, "q", quotationId, "m", material, "l", lineId, "st", status, "age", ageInterval);
        return id;
    }

    // ── 读 ─────────────────────────────────────────────────────────────────

    public BigDecimal lineSubtotal(UUID lineId) {
        Object v = db.scalar("SELECT subtotal FROM quotation_line_item WHERE id = :id", "id", lineId);
        return v == null ? null : new BigDecimal(v.toString());
    }

    /** 该行元素页签第一条 driverRow 的银单价（升版写价的落点）。 */
    public BigDecimal lineElementPrice(UUID lineId) {
        String v = db.text("SELECT cd.snapshot_rows->0->'driverRow'->>'price' FROM quotation_line_component_data cd "
            + "WHERE cd.line_item_id = :l AND cd.component_id = :c", "l", lineId, "c", template.elementComponentId());
        return v == null ? null : new BigDecimal(v);
    }

    public String lineQuoteCardText(UUID lineId) {
        return db.text("SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id", "id", lineId);
    }

    public List<UUID> quotationIds() {
        return List.copyOf(quotationIds);
    }

    // ── 异步收敛 ───────────────────────────────────────────────────────────

    /** 本客户名下审核 / 指针 / 明细 / 版本状态的指纹；连续 {@code stableMillis} 不变视为后台已停。 */
    public String activityFingerprint() {
        return db.text("SELECT "
                + "(SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') FROM material_price_review WHERE customer_no = :c) || '|' || "
                + "(SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') FROM material_price_version_ref WHERE customer_no = :c) || '|' || "
                + "(SELECT count(*) || ':' || coalesce(max(i.updated_at)::text, '') FROM material_price_update_job_item i "
                + "  JOIN material_price_update_job j ON j.id = i.job_id WHERE j.customer_no = :c) || '|' || "
                + "(SELECT count(*) || ':' || coalesce(string_agg(status, ',' ORDER BY id), '') FROM element_price_version WHERE customer_no = :c)",
            "c", customerNo);
    }

    public void awaitQuiet(long stableMillis, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        String last = activityFingerprint();
        long stableSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            sleep(500);
            String now = activityFingerprint();
            if (!now.equals(last)) {
                last = now;
                stableSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - stableSince >= stableMillis) {
                return;
            }
        }
        System.out.println("[RP0918A] ⚠️ 等待后台收敛超时（" + maxMillis + "ms），最后指纹 " + last);
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ── 清理（按自建 id） ─────────────────────────────────────────────────

    public void cleanup() {
        if (!OWN_CUSTOMER.matcher(customerNo).matches()) {
            throw new IllegalStateException("拒绝清理：客户号 " + customerNo + " 不是本片格式");
        }
        List<String> failures = new ArrayList<>();
        List<UUID> qids = new ArrayList<>(quotationIds);

        Set<Object> jobIds = new LinkedHashSet<>(db.column("SELECT id FROM material_price_update_job WHERE customer_no = :c",
            "c", customerNo));
        if (!qids.isEmpty()) {
            jobIds.addAll(db.column("SELECT DISTINCT job_id FROM material_price_update_job_item WHERE quotation_id IN (:q)",
                "q", qids));
        }
        step(failures, "更新任务", "DELETE FROM material_price_update_job WHERE id IN (:ids)", jobIds);
        step(failures, "审核行", "DELETE FROM material_price_review WHERE id IN (:ids)",
            db.column("SELECT id FROM material_price_review WHERE customer_no = :c", "c", customerNo));
        step(failures, "版本指针", "DELETE FROM material_price_version_ref WHERE id IN (:ids)",
            db.column("SELECT id FROM material_price_version_ref WHERE customer_no = :c", "c", customerNo));
        if (!qids.isEmpty()) {
            List<Object> costingOrders = db.column("SELECT id FROM costing_order WHERE quotation_id IN (:q)", "q", qids);
            step(failures, "核价单覆盖", "DELETE FROM costing_order_version_override WHERE costing_order_id IN (:ids)",
                costingOrders);
            step(failures, "核价单", "DELETE FROM costing_order WHERE id IN (:ids)", costingOrders);
            step(failures, "报价单（级联行/页签数据/版本记录/冻结结构）", "DELETE FROM quotation WHERE id IN (:ids)",
                new ArrayList<>(qids));
        }
        step(failures, "价格版本（级联明细）", "DELETE FROM element_price_version WHERE id IN (:ids)",
            db.column("SELECT id FROM element_price_version WHERE customer_no = :c", "c", customerNo));
        step(failures, "调价策略（级联元素/料号/日志）", "DELETE FROM customer_price_adjust_strategy WHERE id IN (:ids)",
            db.column("SELECT id FROM customer_price_adjust_strategy WHERE customer_no = :c", "c", customerNo));
        step(failures, "元素取价策略", "DELETE FROM element_price_strategy WHERE id IN (:ids)",
            db.column("SELECT id FROM element_price_strategy WHERE customer_no = :c", "c", customerNo));
        step(failures, "模板", "DELETE FROM template WHERE id IN (:ids)", new ArrayList<>(templateIds));
        step(failures, "组件", "DELETE FROM component WHERE id IN (:ids)", new ArrayList<>(componentIds));
        if (customerId != null) {
            step(failures, "客户", "DELETE FROM customer WHERE id IN (:ids)", List.of(customerId));
        }

        long residue = db.count("SELECT "
                + "(SELECT count(*) FROM material_price_update_job WHERE customer_no = :c) + "
                + "(SELECT count(*) FROM material_price_review WHERE customer_no = :c) + "
                + "(SELECT count(*) FROM material_price_version_ref WHERE customer_no = :c) + "
                + "(SELECT count(*) FROM element_price_version WHERE customer_no = :c) + "
                + "(SELECT count(*) FROM customer_price_adjust_strategy WHERE customer_no = :c) + "
                + "(SELECT count(*) FROM element_price_strategy WHERE customer_no = :c) + "
                + "(SELECT count(*) FROM customer WHERE code = :c) + "
                + "(SELECT count(*) FROM quotation WHERE quotation_number LIKE :qp)",
            "c", customerNo, "qp", PREFIX + "-" + run + "-%");
        if (residue > 0) failures.add("清理后仍有 " + residue + " 行残留（客户号 " + customerNo + "）");
        if (!failures.isEmpty()) {
            String msg = "RP0918A 清理未完成：" + String.join("；", failures);
            Rp0918aEvidence.log("cleanup-residue", msg);
            throw new AssertionError(msg);
        }
        Rp0918aEvidence.log("cleanup", "客户 " + customerNo + " 自建数据已按 id 清理完毕（报价单 " + qids.size() + " 张）");
    }

    private void step(List<String> failures, String what, String sql, Collection<?> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            db.exec(sql, "ids", new ArrayList<>(ids));
        } catch (RuntimeException e) {
            failures.add(what + " 删除失败: " + e.getMessage());
        }
    }
}
