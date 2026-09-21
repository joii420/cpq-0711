package com.cpq.priceadjust.ac260920;

import com.cpq.priceadjust.ac260918.Rp0918aDb;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * task-260920 · S-1 私有造数。所有对象挂在本轮随机客户 {@code T260920-<8hex>} 名下（派工前缀 T260920-）。
 * 结构沿用 repair-260918 已验证可升版的夹具形态（元素页签 + 小计页签、SPECIFIED 范围、本客户自有元素取价策略），
 * 只改前缀与本任务需要的造数动作（六分支、直接插审核行、作废依据单、驳回史）。
 *
 * <p>🔒 清理：先按「本轮客户号 / 本轮建的报价单 id」查出 id，再按 id 删；客户号有格式守卫。
 */
public final class T920Fixture {

    public static final String PREFIX = "T260920";
    public static final Pattern OWN_CUSTOMER = Pattern.compile("^T260920-[0-9a-f]{8}$");
    public static final BigDecimal P_PREV = new BigDecimal("28892.500000000000");
    public static final BigDecimal P_TARGET = new BigDecimal("28893.500000000000");
    public static final BigDecimal FIXED = new BigDecimal("1.234567890123");

    /** AC-2 列出的六个判定分支（需求文档 ③ AC-2 原文）。 */
    public enum Branch {
        /** 无依据单无驳回史 → 推进 */ NO_BASIS_NO_REJECT(false),
        /** 无依据单有驳回史 → 进池（反例外） */ NO_BASIS_REJECTED(true),
        /** 有依据单无指针 → 进池 */ BASIS_NO_POINTER(true),
        /** 有指针且相关元素价无变化 → 推进 */ POINTER_NO_CHANGE(false),
        /** 有指针且有变化 → 进池 */ POINTER_CHANGED(true),
        /** 扫不出相关元素 → 进池 */ NO_RELEVANT_ELEMENT(true);

        public final boolean pooled;

        Branch(boolean pooled) {
            this.pooled = pooled;
        }
    }

    public final Rp0918aDb db;
    public final String run;
    public final String customerNo;
    public UUID customerId;
    public UUID salesRepId;
    public UUID strategyId;
    public UUID templateId;
    public UUID templateSeriesId;
    public UUID elementComponentId;
    private String frozenTabs;

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> componentIds = new ArrayList<>();
    private final List<UUID> templateIds = new ArrayList<>();

    public record Quote(UUID id, String number, Map<Integer, String> materialByOrdinal, Map<String, UUID> lineByMaterial) {
        public String material(int ordinal) {
            String m = materialByOrdinal.get(ordinal);
            if (m == null) throw new IllegalStateException(number + " 没有第 " + ordinal + " 行");
            return m;
        }

        public UUID line(String material) {
            UUID id = lineByMaterial.get(material);
            if (id == null) throw new IllegalStateException(number + " 里没有料号 " + material);
            return id;
        }

        public List<String> materials() {
            return new ArrayList<>(materialByOrdinal.values());
        }
    }

    public T920Fixture(Rp0918aDb db) {
        this.db = db;
        this.run = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.customerNo = PREFIX + "-" + run;
    }

    public String material(String label, int ordinal) {
        return PREFIX + "-" + run + "-" + label + "-" + String.format("%04d", ordinal);
    }

    /** 版本号列宽 20：T920 + 8 位 run + '-' + 标签（≤6）。 */
    public String versionNo(String tag) {
        return "T920" + run + "-" + (tag.length() > 6 ? tag.substring(0, 6) : tag);
    }

    // ── 客户 / 策略 / 元素取价 ─────────────────────────────────────────────────

    public T920Fixture createCustomerAndStrategy() {
        customerId = UUID.randomUUID();
        db.exec("INSERT INTO customer (id, code, name, remarks) VALUES (:id, :code, :name, 'T260920 S-1 自建测试客户')",
            "id", customerId, "code", customerNo, "name", PREFIX + " 测试客户 " + run);
        salesRepId = (UUID) db.scalar("SELECT id FROM \"user\" ORDER BY created_at LIMIT 1");
        strategyId = UUID.randomUUID();
        int dom = ((LocalDate.now().getDayOfMonth() + 13) % 28) + 1;   // 周期日 ≠ 今天 —— 定时扫描也不会替本客户生成
        db.exec("INSERT INTO customer_price_adjust_strategy (id, customer_no, enabled, cycle_type, cycle_day_of_month, "
                + "execute_time, material_scope_mode, cost_diff_threshold) "
                + "VALUES (:id, :c, true, 'MONTHLY_DAY', CAST(:dom AS smallint), CAST('23:59:00' AS time), 'SPECIFIED', 0)",
            "id", strategyId, "c", customerNo, "dom", dom);
        db.exec("INSERT INTO customer_price_adjust_element (strategy_id, element_code) VALUES (:s, 'Ag')", "s", strategyId);
        createTemplate();
        return this;
    }

    public void addScope(Collection<String> materials) {
        for (String m : materials) {
            db.exec("INSERT INTO customer_price_adjust_material (strategy_id, material_no) VALUES (:s, :m) ON CONFLICT DO NOTHING",
                "s", strategyId, "m", m);
        }
    }

    public List<String> scope() {
        List<String> out = new ArrayList<>();
        for (Object o : db.column("SELECT material_no FROM customer_price_adjust_material WHERE strategy_id = :s "
            + "ORDER BY material_no", "s", strategyId)) out.add((String) o);
        return out;
    }

    /** 本客户自有元素取价策略，使 f_customer_element_price(本客户) 银 = target（只写本客户一行，写完复核）。 */
    public void setElementPriceTarget(BigDecimal target) {
        List<Object[]> latest = db.rows("SELECT source_id, raw_price FROM element_daily_price WHERE element_name = 'Ag' "
            + "AND raw_price IS NOT NULL AND price_date <= CURRENT_DATE ORDER BY price_date DESC, source_id LIMIT 1");
        if (latest.isEmpty()) throw new IllegalStateException("测试库没有银日价，无法构造元素取价策略");
        UUID src = (UUID) latest.get(0)[0];
        BigDecimal raw = new BigDecimal(latest.get(0)[1].toString());
        BigDecimal factor = new BigDecimal("0.000000000001");
        BigDecimal premium = target.subtract(raw.multiply(factor).setScale(12, RoundingMode.HALF_UP));
        db.exec("INSERT INTO element_price_strategy (customer_no, element_code, source_id, method, factor, premium, status) "
                + "VALUES (:c, 'Ag', :src, 'LATEST', CAST(:f AS numeric), CAST(:p AS numeric), 'ACTIVE')",
            "c", customerNo, "src", src, "f", factor.toPlainString(), "p", premium.toPlainString());
        Object got = db.scalar("SELECT unit_price FROM f_customer_element_price(:c, CURRENT_DATE) WHERE element_code = 'Ag'",
            "c", customerNo);
        if (got == null || new BigDecimal(got.toString()).compareTo(target) != 0) {
            throw new IllegalStateException("夹具复核失败：f_customer_element_price 银 = " + got + "，期望 " + target);
        }
    }

    // ── 模板（元素页签 + 小计页签） ─────────────────────────────────────────────

    private void createTemplate() {
        UUID elemId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        templateId = UUID.randomUUID();
        templateSeriesId = UUID.randomUUID();
        String elemCode = PREFIX + "-ELEM-" + run;
        String subCode = PREFIX + "-SUB-" + run;
        String fields = "[{\"name\":\"element\",\"field_type\":\"INPUT_TEXT\"},"
            + "{\"name\":\"price\",\"field_type\":\"INPUT_NUMBER\",\"is_amount\":true,\"is_subtotal\":true},"
            + "{\"name\":\"fixedAmount\",\"field_type\":\"INPUT_NUMBER\",\"is_amount\":true,\"is_subtotal\":true}]";
        String subFormulas = "[{\"expression\":[{\"type\":\"component_subtotal\",\"component_code\":\"" + elemCode
            + "\",\"value\":\"__amount_total__\"}]}]";
        db.exec("INSERT INTO component (id, name, code, component_type, fields, formulas, element_code_field, "
                + "element_price_field, element_currency_field) VALUES (:id, :n, :code, 'NORMAL', CAST(:f AS jsonb), '[]', "
                + "'element', 'price', 'currency')", "id", elemId, "n", PREFIX + " 元素页签 " + run, "code", elemCode, "f", fields);
        componentIds.add(elemId);
        db.exec("INSERT INTO component (id, name, code, component_type, fields, formulas) VALUES (:id, :n, :code, 'SUBTOTAL', "
            + "'[]', CAST(:fm AS jsonb))", "id", subId, "n", PREFIX + " 小计页签 " + run, "code", subCode, "fm", subFormulas);
        componentIds.add(subId);
        frozenTabs = "[{\"componentId\":\"" + elemId + "\",\"componentCode\":\"" + elemCode + "\","
            + "\"tabName\":\"元素成本\",\"componentType\":\"NORMAL\",\"sortOrder\":1,"
            + "\"fields\":[{\"name\":\"element\",\"fieldType\":\"INPUT_TEXT\"},"
            + "{\"name\":\"price\",\"fieldType\":\"INPUT_NUMBER\",\"isAmount\":true,\"isSubtotal\":true},"
            + "{\"name\":\"fixedAmount\",\"fieldType\":\"INPUT_NUMBER\",\"isAmount\":true,\"isSubtotal\":true}],"
            + "\"formulas\":[],\"formula_assignments\":[]},"
            + "{\"componentId\":\"" + subId + "\",\"componentCode\":\"" + subCode + "\","
            + "\"tabName\":\"合计\",\"componentType\":\"SUBTOTAL\",\"sortOrder\":2,"
            + "\"fields\":[],\"formula_assignments\":[],\"formulas\":[{\"expression\":[{"
            + "\"type\":\"component_subtotal\",\"component_code\":\"" + elemCode + "\",\"value\":\"__amount_total__\"}]}]}]";
        db.exec("INSERT INTO template (id, template_series_id, name, version, status, template_kind, customer_id, "
                + "components_snapshot, product_attributes, subtotal_formula, template_sql_views_snapshot, formulas, "
                + "created_at, updated_at, published_at) VALUES (:id, :series, :name, 'v1', 'PUBLISHED', 'QUOTATION', :cust, "
                + "CAST(:snap AS jsonb), '[]', '[]', '{}', '[]', now(), now(), now())",
            "id", templateId, "series", templateSeriesId, "name", PREFIX + " 模板 " + run, "cust", customerId, "snap", frozenTabs);
        templateIds.add(templateId);
        frozenTab(elemId, 1, "元素成本", PREFIX + " 元素页签 " + run, elemCode, "NORMAL", fields, "[]", true);
        frozenTab(subId, 2, "合计", PREFIX + " 小计页签 " + run, subCode, "SUBTOTAL", "[]", subFormulas, false);
        elementComponentId = elemId;
    }

    private void frozenTab(UUID cid, int sort, String tab, String cname, String ccode, String ctype, String fields,
                           String formulas, boolean elementRole) {
        UUID tcId = UUID.randomUUID();
        db.exec("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name) VALUES (:id, :t, :c, :s, :n)",
            "id", tcId, "t", templateId, "c", cid, "s", sort, "n", tab);
        String roles = elementRole ? "'element', 'price', 'currency'" : "NULL, NULL, NULL";
        db.exec("INSERT INTO template_component_snapshot (template_id, template_component_id, component_id, sort_order, "
                + "tab_name, component_name, component_code, component_type, fields, formulas, element_code_field, "
                + "element_price_field, element_currency_field) VALUES (:t, :tc, :c, :s, :n, :cn, :cc, :ct, "
                + "CAST(:f AS jsonb), CAST(:fm AS jsonb), " + roles + ")",
            "t", templateId, "tc", tcId, "c", cid, "s", sort, "n", tab, "cn", cname, "cc", ccode, "ct", ctype,
            "f", fields, "fm", formulas);
    }

    // ── 报价单（DRAFT = 活单；每行一个料号 + 一条银元素页签数据） ─────────────────

    public Quote createQuote(String label, int lineCount) {
        UUID qid = UUID.randomUUID();
        String number = PREFIX + "-" + run + "-" + label;
        db.exec("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, customer_template_id, "
                + "original_amount, total_amount, final_discount_rate, tax_amount, remarks, created_at, updated_at) "
                + "VALUES (:id, :no, :cust, :name, :rep, 'DRAFT', :tpl, 0, 0, 100, 0, 'T260920 S-1 自建', "
                + "clock_timestamp(), clock_timestamp())",
            "id", qid, "no", number, "cust", customerId, "name", PREFIX + " " + label, "rep", salesRepId, "tpl", templateId);
        quotationIds.add(qid);
        db.exec("INSERT INTO quotation_view_structure (quotation_id, view_kind, structure) VALUES (:q, 'QUOTE_CARD', "
            + "CAST(:s AS jsonb))", "q", qid, "s", "{\"tabs\":" + frozenTabs + "}");
        String sub = P_PREV.add(FIXED).setScale(9, RoundingMode.HALF_UP).toPlainString();
        db.exec("INSERT INTO quotation_line_item (id, quotation_id, template_id, product_part_no_snapshot, product_name_snapshot, "
                + "composite_type, annual_volume, discount_source, discount_rate_applied, subtotal, line_unit_price, "
                + "discount_base_amount, line_final_price, line_discount_amount, line_total_amount, quote_card_values, "
                + "costing_card_values, sort_order, created_at) "
                + "SELECT gen_random_uuid(), :q, :tpl, :p || lpad(g::text, 4, '0'), 'T260920 品名 ' || g, 'SIMPLE', 7, "
                + "'SUBTOTAL', 12.34, CAST(:sub AS numeric), 1, 2, 3, 4, CAST(:sub AS numeric), "
                + "jsonb_build_object('marker', 'T260920', 'ordinal', g), jsonb_build_object('marker', 'T260920-c'), g, "
                + "clock_timestamp() FROM generate_series(1, :n) g",
            "q", qid, "tpl", templateId, "p", PREFIX + "-" + run + "-" + label + "-", "sub", sub, "n", lineCount);
        db.exec("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, snapshot_rows, row_data, "
                + "row_version, subtotal, created_at) SELECT gen_random_uuid(), li.id, :cid, "
                + "jsonb_build_array(jsonb_build_object('driverRow', jsonb_build_object('element', 'Ag', 'price', :p0, "
                + "'fixedAmount', :fixed, 'currency', 'CNY'), 'basicDataValues', '{}'::jsonb)), "
                + "jsonb_build_array(jsonb_build_object('marker', 'T260920')), 5, CAST(:sub AS numeric), clock_timestamp() "
                + "FROM quotation_line_item li WHERE li.quotation_id = :q",
            "cid", elementComponentId, "p0", P_PREV.toPlainString(), "fixed", FIXED.toPlainString(), "sub", sub, "q", qid);
        db.exec("UPDATE quotation SET original_amount = t.s, total_amount = t.s FROM (SELECT coalesce(sum(line_total_amount), 0) s "
            + "FROM quotation_line_item WHERE quotation_id = :q) t WHERE id = :q", "q", qid);
        Map<Integer, String> byOrd = new LinkedHashMap<>();
        Map<String, UUID> byMat = new LinkedHashMap<>();
        for (Object[] r : db.rows("SELECT sort_order, product_part_no_snapshot, id FROM quotation_line_item WHERE quotation_id = :q "
            + "ORDER BY sort_order", "q", qid)) {
            byOrd.put(((Number) r[0]).intValue(), (String) r[1]);
            byMat.put((String) r[1], (UUID) r[2]);
        }
        if (byOrd.size() != lineCount) throw new IllegalStateException("夹具失败：" + number + " 行数 " + byOrd.size());
        return new Quote(qid, number, byOrd, byMat);
    }

    /** 让某些行「扫不出相关元素」：删掉它们自己的元素页签数据（按本夹具建的行 id 精确删除）。 */
    public void removeElementData(Collection<UUID> ownLineIds) {
        if (ownLineIds.isEmpty()) return;
        db.exec("DELETE FROM quotation_line_component_data WHERE line_item_id IN (:l) AND line_item_id IN "
            + "(SELECT li.id FROM quotation_line_item li WHERE li.quotation_id IN (:q))", "l", new ArrayList<>(ownLineIds),
            "q", new ArrayList<>(quotationIds));
    }

    /** AC-7：把本夹具建的依据单改成 CANCELLED（离开活单范围）。 */
    public void cancelQuote(UUID ownQuoteId) {
        if (!quotationIds.contains(ownQuoteId)) throw new IllegalStateException("拒绝：不是本夹具建的报价单");
        db.exec("UPDATE quotation SET status = 'CANCELLED', updated_at = now() WHERE id = :q", "q", ownQuoteId);
    }

    // ── 版本 / 指针 / 审核行 ──────────────────────────────────────────────────

    public UUID insertVersion(String tag, String status, BigDecimal agPrice, int ageSeconds) {
        UUID id = UUID.randomUUID();
        db.exec("INSERT INTO element_price_version (id, customer_no, version_no, base_date, status, trigger_type, created_at) "
                + "VALUES (:id, :c, :vn, CURRENT_DATE, :st, 'MANUAL', now() - make_interval(secs => :age))",
            "id", id, "c", customerNo, "vn", versionNo(tag), "st", status, "age", ageSeconds);
        db.exec("INSERT INTO element_price_version_item (version_id, element_code, current_price, currency, price_unit, "
            + "no_price, inherited_from_previous) VALUES (:v, 'Ag', CAST(:p AS numeric), 'CNY', 'kg', false, false)",
            "v", id, "p", agPrice.toPlainString());
        return id;
    }

    public void setPointer(String material, UUID versionId) {
        db.exec("INSERT INTO material_price_version_ref (customer_no, material_no, version_id, updated_at) VALUES (:c, :m, :v, now()) "
                + "ON CONFLICT (customer_no, material_no) DO UPDATE SET version_id = EXCLUDED.version_id, updated_at = now()",
            "c", customerNo, "m", material, "v", versionId);
    }

    public UUID pointer(String material) {
        return (UUID) db.scalar("SELECT version_id FROM material_price_version_ref WHERE customer_no = :c AND material_no = :m",
            "c", customerNo, "m", material);
    }

    /** 直接插一条审核行（造状态用：已作废 + QUEUED / 同一时刻等）。basisQuoteId 可为 null。 */
    public UUID insertReview(UUID versionId, String material, String status, String budgetStatus, UUID basisQuoteId,
                             String createdAtLiteral) {
        UUID id = UUID.randomUUID();
        String basis = basisQuoteId == null ? "NULL" : "CAST('" + basisQuoteId + "' AS uuid)";
        String created = createdAtLiteral == null ? "now()" : "CAST('" + createdAtLiteral + "' AS timestamptz)";
        db.exec("INSERT INTO material_price_review (id, version_id, customer_no, material_no, basis_quotation_id, status, "
                + "budget_status, created_at, updated_at) VALUES (:id, :v, :c, :m, " + basis + ", :st, :bs, " + created + ", "
                + created + ")",
            "id", id, "v", versionId, "c", customerNo, "m", material, "st", status, "bs", budgetStatus);
        return id;
    }

    /** 驳回史：在一个本客户的旧（已作废）版本下插一条 REJECTED 审核行。 */
    public void insertRejectHistory(String material) {
        UUID old = (UUID) db.scalar("SELECT id FROM element_price_version WHERE customer_no = :c AND version_no = :vn",
            "c", customerNo, "vn", versionNo("REJOLD"));
        if (old == null) old = insertVersion("REJOLD", "SUPERSEDED", P_PREV, 86_400);
        insertReview(old, material, "REJECTED", "READY", null, null);
    }

    // ── 六分支夹具（AC-2 / AC-3 共用） ──────────────────────────────────────────

    public record Branches(Map<Branch, List<String>> byBranch, UUID vPrev, UUID vSame, Quote quote) {
        public Set<String> expectedPooled() {
            Set<String> s = new LinkedHashSet<>();
            byBranch.forEach((b, ms) -> { if (b.pooled) s.addAll(ms); });
            return s;
        }

        public Set<String> expectedAdvanced() {
            Set<String> s = new LinkedHashSet<>();
            byBranch.forEach((b, ms) -> { if (!b.pooled) s.addAll(ms); });
            return s;
        }

        public int total() {
            return byBranch.values().stream().mapToInt(List::size).sum();
        }
    }

    /**
     * 每个分支 k 个料号，全部加入范围。依据单 = 一张 4k 行的活单（有依据单的四个分支）；
     * 「无依据单」两个分支的料号不出现在任何报价单里。指针：vSame（银 = P_TARGET，与目标版本相同 ⇒ 无变化）/
     * vPrev（银 = P_PREV ⇒ 有变化）。目标版本由调用方建（AC-2 直接插 PENDING；AC-3 走真实生成入口）。
     */
    public Branches buildSixBranches(int k) {
        UUID vPrev = insertVersion("PREV", "SUPERSEDED", P_PREV, 7_200);
        UUID vSame = insertVersion("SAME", "SUPERSEDED", P_TARGET, 5_400);
        Quote q = createQuote("BR", 4 * k);
        Map<Branch, List<String>> m = new EnumMap<>(Branch.class);
        for (Branch b : Branch.values()) m.put(b, new ArrayList<>());
        for (int i = 1; i <= k; i++) {
            m.get(Branch.NO_BASIS_NO_REJECT).add(material("NB", i));
            m.get(Branch.NO_BASIS_REJECTED).add(material("RJ", i));
        }
        int ord = 1;
        for (Branch b : List.of(Branch.BASIS_NO_POINTER, Branch.POINTER_NO_CHANGE, Branch.POINTER_CHANGED,
            Branch.NO_RELEVANT_ELEMENT)) {
            for (int i = 0; i < k; i++) m.get(b).add(q.material(ord++));
        }
        for (String x : m.get(Branch.NO_BASIS_REJECTED)) insertRejectHistory(x);
        for (String x : m.get(Branch.POINTER_NO_CHANGE)) setPointer(x, vSame);
        for (String x : m.get(Branch.POINTER_CHANGED)) setPointer(x, vPrev);
        for (String x : m.get(Branch.NO_RELEVANT_ELEMENT)) setPointer(x, vPrev);
        List<UUID> noElem = new ArrayList<>();
        for (String x : m.get(Branch.NO_RELEVANT_ELEMENT)) noElem.add(q.line(x));
        removeElementData(noElem);
        List<String> all = new ArrayList<>();
        m.values().forEach(all::addAll);
        addScope(all);
        return new Branches(m, vPrev, vSame, q);
    }

    // ── 读 / 等待 ────────────────────────────────────────────────────────────

    /** [status, budget_status, budget_error, updated_at, id] */
    public Object[] review(UUID versionId, String material) {
        List<Object[]> r = db.rows("SELECT status, budget_status, budget_error, updated_at, id FROM material_price_review "
            + "WHERE version_id = :v AND material_no = :m", "v", versionId, "m", material);
        return r.isEmpty() ? null : r.get(0);
    }

    public UUID reviewId(UUID versionId, String material) {
        Object[] r = review(versionId, material);
        if (r == null) throw new AssertionError("找不到审核行：" + material + " @ " + versionId);
        return (UUID) r[4];
    }

    /** 等该版本「待处理」行里不再有 QUEUED / COMPUTING；返回剩余条数（0 = 已收敛）。 */
    public long awaitVersionSettled(UUID versionId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long left = notSettled(versionId);
        while (left > 0 && System.currentTimeMillis() < deadline) {
            sleep(300);
            left = notSettled(versionId);
        }
        return left;
    }

    public long notSettled(UUID versionId) {
        return db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v AND status = 'PENDING' "
            + "AND budget_status IN ('QUEUED', 'COMPUTING')", "v", versionId);
    }

    public String activityFingerprint() {
        return db.text("SELECT (SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') || ':' || "
            + "coalesce(string_agg(budget_status || status, ',' ORDER BY id), '') FROM material_price_review WHERE customer_no = :c) "
            + "|| '|' || (SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') FROM material_price_version_ref "
            + "WHERE customer_no = :c)", "c", customerNo);
    }

    public void awaitQuiet(long stableMillis, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        String last = activityFingerprint();
        long since = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            sleep(500);
            String now = activityFingerprint();
            if (!now.equals(last)) {
                last = now;
                since = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - since >= stableMillis) {
                return;
            }
        }
        System.out.println("[T260920] ⚠️ 等待后台收敛超时（" + maxMillis + "ms）");
    }

    public List<UUID> quotationIds() {
        return List.copyOf(quotationIds);
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ── 清理（按自建 id） ─────────────────────────────────────────────────────

    public void cleanup() {
        if (!OWN_CUSTOMER.matcher(customerNo).matches()) throw new IllegalStateException("拒绝清理：" + customerNo);
        List<String> failures = new ArrayList<>();
        List<UUID> qids = new ArrayList<>(quotationIds);
        Set<Object> jobIds = new LinkedHashSet<>(db.column("SELECT id FROM material_price_update_job WHERE customer_no = :c",
            "c", customerNo));
        if (!qids.isEmpty()) {
            jobIds.addAll(db.column("SELECT DISTINCT job_id FROM material_price_update_job_item WHERE quotation_id IN (:q)",
                "q", qids));
        }
        step(failures, "更新任务", "DELETE FROM material_price_update_job WHERE id IN (:ids)", jobIds);
        List<Object> reviews = db.column("SELECT id FROM material_price_review WHERE customer_no = :c", "c", customerNo);
        step(failures, "比对列", "DELETE FROM material_price_review_column WHERE review_id IN (:ids)", reviews);
        step(failures, "审核行", "DELETE FROM material_price_review WHERE id IN (:ids)", reviews);
        step(failures, "指针", "DELETE FROM material_price_version_ref WHERE id IN (:ids)",
            db.column("SELECT id FROM material_price_version_ref WHERE customer_no = :c", "c", customerNo));
        if (!qids.isEmpty()) {
            List<Object> co = db.column("SELECT id FROM costing_order WHERE quotation_id IN (:q)", "q", qids);
            step(failures, "核价单覆盖", "DELETE FROM costing_order_version_override WHERE costing_order_id IN (:ids)", co);
            step(failures, "核价单", "DELETE FROM costing_order WHERE id IN (:ids)", co);
            step(failures, "报价单", "DELETE FROM quotation WHERE id IN (:ids)", new ArrayList<>(qids));
        }
        step(failures, "价格版本", "DELETE FROM element_price_version WHERE id IN (:ids)",
            db.column("SELECT id FROM element_price_version WHERE customer_no = :c", "c", customerNo));
        step(failures, "比对列配置", "DELETE FROM comparison_column_config WHERE id IN (:ids)",
            db.column("SELECT id FROM comparison_column_config WHERE customer_no = :c", "c", customerNo));
        step(failures, "调价策略", "DELETE FROM customer_price_adjust_strategy WHERE id IN (:ids)",
            db.column("SELECT id FROM customer_price_adjust_strategy WHERE customer_no = :c", "c", customerNo));
        step(failures, "元素取价策略", "DELETE FROM element_price_strategy WHERE id IN (:ids)",
            db.column("SELECT id FROM element_price_strategy WHERE customer_no = :c", "c", customerNo));
        step(failures, "模板", "DELETE FROM template WHERE id IN (:ids)", new ArrayList<>(templateIds));
        step(failures, "组件", "DELETE FROM component WHERE id IN (:ids)", new ArrayList<>(componentIds));
        if (customerId != null) step(failures, "客户", "DELETE FROM customer WHERE id IN (:ids)", List.of(customerId));
        long residue = db.count("SELECT (SELECT count(*) FROM material_price_review WHERE customer_no = :c) + "
            + "(SELECT count(*) FROM material_price_version_ref WHERE customer_no = :c) + "
            + "(SELECT count(*) FROM element_price_version WHERE customer_no = :c) + "
            + "(SELECT count(*) FROM customer WHERE code = :c) + "
            + "(SELECT count(*) FROM quotation WHERE quotation_number LIKE :qp)", "c", customerNo, "qp", PREFIX + "-" + run + "-%");
        if (residue > 0) failures.add("清理后仍有 " + residue + " 行残留");
        if (!failures.isEmpty()) {
            T920Evidence.log("cleanup-residue", customerNo + "：" + String.join("；", failures));
            throw new AssertionError("T260920 清理未完成：" + String.join("；", failures));
        }
        T920Evidence.log("cleanup", "客户 " + customerNo + " 自建数据已按 id 清理（报价单 " + qids.size() + " 张）");
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
