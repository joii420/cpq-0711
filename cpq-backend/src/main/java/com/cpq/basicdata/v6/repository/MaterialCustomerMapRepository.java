package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.MaterialCustomerMap;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class MaterialCustomerMapRepository implements PanacheRepositoryBase<MaterialCustomerMap, UUID> {

    @Inject
    EntityManager em;

    /**
     * PRICING 侧 upsert，冲突键 (system_type, material_no, customer_no, customer_product_no)
     * = 唯一索引 uq_mcm_composite。system_type 固定写死 'PRICING'（本方法专供 P05 核价料号映射；
     * QUOTE 侧写路径见 {@link #upsertQuote}）。
     */
    public int upsert(String materialNo, String customerNo, String customerName,
                      String customerMaterialName, String customerProductNo,
                      String customerDrawingNo, Integer seqNo, String paymentMethod,
                      String baseCurrency, String quoteCurrency, BigDecimal exchangeRate,
                      UUID updatedBy) {
        String sql =
            "INSERT INTO material_customer_map (system_type, material_no, customer_no, customer_name, " +
            "  customer_material_name, customer_product_no, customer_drawing_no, seq_no, " +
            "  payment_method, base_currency, quote_currency, exchange_rate, " +
            "  created_at, updated_at, updated_by) " +
            "VALUES ('PRICING', :materialNo, :customerNo, :customerName, :customerMaterialName, " +
            "  :customerProductNo, :customerDrawingNo, :seqNo, :paymentMethod, " +
            "  :baseCurrency, :quoteCurrency, :exchangeRate, NOW(), NOW(), :updatedBy) " +
            "ON CONFLICT (system_type, material_no, customer_no, customer_product_no) DO UPDATE SET " +
            "  customer_name          = COALESCE(EXCLUDED.customer_name,          material_customer_map.customer_name), " +
            "  customer_material_name = COALESCE(EXCLUDED.customer_material_name, material_customer_map.customer_material_name), " +
            "  customer_drawing_no    = COALESCE(EXCLUDED.customer_drawing_no,    material_customer_map.customer_drawing_no), " +
            "  seq_no                 = COALESCE(EXCLUDED.seq_no,                 material_customer_map.seq_no), " +
            "  payment_method         = COALESCE(EXCLUDED.payment_method,         material_customer_map.payment_method), " +
            "  base_currency          = COALESCE(EXCLUDED.base_currency,          material_customer_map.base_currency), " +
            "  quote_currency         = COALESCE(EXCLUDED.quote_currency,         material_customer_map.quote_currency), " +
            "  exchange_rate          = COALESCE(EXCLUDED.exchange_rate,          material_customer_map.exchange_rate), " +
            "  updated_at             = NOW(), " +
            "  updated_by             = EXCLUDED.updated_by";
        return em.createNativeQuery(sql)
            .setParameter("materialNo", materialNo)
            .setParameter("customerNo", customerNo)
            .setParameter("customerName", customerName)
            .setParameter("customerMaterialName", customerMaterialName)
            .setParameter("customerProductNo", customerProductNo)
            .setParameter("customerDrawingNo", customerDrawingNo)
            .setParameter("seqNo", seqNo)
            .setParameter("paymentMethod", paymentMethod)
            .setParameter("baseCurrency", baseCurrency)
            .setParameter("quoteCurrency", quoteCurrency)
            .setParameter("exchangeRate", exchangeRate)
            .setParameter("updatedBy", updatedBy)
            .executeUpdate();
    }

    /** 行载体：upsertBatch 一行的值字段（与 upsert 形参一一对应，另加 systemType/productionNo）。 */
    public static final class MapRow {
        public final String materialNo, customerNo, customerName, customerMaterialName,
                customerProductNo, customerDrawingNo, paymentMethod, baseCurrency, quoteCurrency;
        public final Integer seqNo;
        public final BigDecimal exchangeRate;
        /** V308 新增：QUOTE/PRICING 行类型 + 生产料号（QUOTE 行专用，回填底层生产料号）。
         *  旧 11 参构造保持 null，兼容既有调用点；Task 5 迁移调用点时再显式赋值。 */
        public final String systemType, productionNo;

        public MapRow(String materialNo, String customerNo, String customerName,
                      String customerMaterialName, String customerProductNo, String customerDrawingNo,
                      Integer seqNo, String paymentMethod, String baseCurrency, String quoteCurrency,
                      BigDecimal exchangeRate) {
            this(materialNo, customerNo, customerName, customerMaterialName, customerProductNo,
                customerDrawingNo, seqNo, paymentMethod, baseCurrency, quoteCurrency, exchangeRate,
                null, null);
        }

        public MapRow(String materialNo, String customerNo, String customerName,
                      String customerMaterialName, String customerProductNo, String customerDrawingNo,
                      Integer seqNo, String paymentMethod, String baseCurrency, String quoteCurrency,
                      BigDecimal exchangeRate, String systemType, String productionNo) {
            this.materialNo = materialNo; this.customerNo = customerNo; this.customerName = customerName;
            this.customerMaterialName = customerMaterialName; this.customerProductNo = customerProductNo;
            this.customerDrawingNo = customerDrawingNo; this.seqNo = seqNo; this.paymentMethod = paymentMethod;
            this.baseCurrency = baseCurrency; this.quoteCurrency = quoteCurrency; this.exchangeRate = exchangeRate;
            this.systemType = systemType; this.productionNo = productionNo;
        }

        /** 同冲突键折叠：逐 COALESCE 列「后行非空覆盖前行」，复刻逐行 ON CONFLICT 的 last-non-null 语义。
         *  注：key 四列（materialNo/customerNo/customerProductNo/systemType）按定义两行相同，取 next 即可。 */
        static MapRow coalesceOver(MapRow prev, MapRow next) {
            return new MapRow(
                next.materialNo, next.customerNo,
                next.customerName != null ? next.customerName : prev.customerName,
                next.customerMaterialName != null ? next.customerMaterialName : prev.customerMaterialName,
                next.customerProductNo,
                next.customerDrawingNo != null ? next.customerDrawingNo : prev.customerDrawingNo,
                next.seqNo != null ? next.seqNo : prev.seqNo,
                next.paymentMethod != null ? next.paymentMethod : prev.paymentMethod,
                next.baseCurrency != null ? next.baseCurrency : prev.baseCurrency,
                next.quoteCurrency != null ? next.quoteCurrency : prev.quoteCurrency,
                next.exchangeRate != null ? next.exchangeRate : prev.exchangeRate,
                next.systemType,
                next.productionNo != null ? next.productionNo : prev.productionNo);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * 批量 upsert，等价于对每行调用 {@link #upsert}（PRICING 路径，冲突键 + DO UPDATE SET 完全一致）。
     * sheet 内先按 (material_no, customer_no, customer_product_no, systemType) 去重折叠
     * （后行 COALESCE 覆盖前行），再分块（≤500 行/语句）发单条多值 INSERT … ON CONFLICT。
     */
    public int upsertBatch(List<MapRow> rows, UUID updatedBy) {
        if (rows == null || rows.isEmpty()) return 0;
        LinkedHashMap<List<String>, MapRow> dedup = new LinkedHashMap<>();
        for (MapRow r : rows) {
            List<String> k = List.of(nz(r.materialNo), nz(r.customerNo), nz(r.customerProductNo), nz(r.systemType));
            dedup.merge(k, r, MapRow::coalesceOver);
        }
        List<MapRow> folded = new ArrayList<>(dedup.values());
        final int CHUNK = 500;
        int affected = 0;
        for (int off = 0; off < folded.size(); off += CHUNK) {
            List<MapRow> chunk = folded.subList(off, Math.min(off + CHUNK, folded.size()));
            affected += upsertChunk(chunk, updatedBy);
        }
        return affected;
    }

    /** 每行占位数：11 个原 upsert 形参 + production_no。system_type 走字面量 'PRICING'，不占位。 */
    private static final int CHUNK_ROW_WIDTH = 12;

    private int upsertChunk(List<MapRow> chunk, UUID updatedBy) {
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) vals.append(", ");
            int b = i * CHUNK_ROW_WIDTH;
            vals.append("('PRICING', :p").append(b).append(", :p").append(b + 1).append(", :p").append(b + 2)
                .append(", :p").append(b + 3).append(", :p").append(b + 4).append(", :p").append(b + 5)
                .append(", :p").append(b + 6).append(", :p").append(b + 7).append(", :p").append(b + 8)
                .append(", :p").append(b + 9).append(", :p").append(b + 10).append(", :p").append(b + 11)
                .append(", NOW(), NOW(), :ub)");
        }
        String sql =
            "INSERT INTO material_customer_map (system_type, material_no, customer_no, customer_name, " +
            "  customer_material_name, customer_product_no, customer_drawing_no, seq_no, " +
            "  payment_method, base_currency, quote_currency, exchange_rate, production_no, " +
            "  created_at, updated_at, updated_by) VALUES " + vals +
            " ON CONFLICT (system_type, material_no, customer_no, customer_product_no) DO UPDATE SET " +
            "  customer_name          = COALESCE(EXCLUDED.customer_name,          material_customer_map.customer_name), " +
            "  customer_material_name = COALESCE(EXCLUDED.customer_material_name, material_customer_map.customer_material_name), " +
            "  customer_drawing_no    = COALESCE(EXCLUDED.customer_drawing_no,    material_customer_map.customer_drawing_no), " +
            "  seq_no                 = COALESCE(EXCLUDED.seq_no,                 material_customer_map.seq_no), " +
            "  payment_method         = COALESCE(EXCLUDED.payment_method,         material_customer_map.payment_method), " +
            "  base_currency          = COALESCE(EXCLUDED.base_currency,          material_customer_map.base_currency), " +
            "  quote_currency         = COALESCE(EXCLUDED.quote_currency,         material_customer_map.quote_currency), " +
            "  exchange_rate          = COALESCE(EXCLUDED.exchange_rate,          material_customer_map.exchange_rate), " +
            "  updated_at             = NOW(), " +
            "  updated_by             = EXCLUDED.updated_by";
        Query q = em.createNativeQuery(sql);
        for (int i = 0; i < chunk.size(); i++) {
            MapRow r = chunk.get(i); int b = i * CHUNK_ROW_WIDTH;
            q.setParameter("p" + b, r.materialNo);
            q.setParameter("p" + (b + 1), r.customerNo);
            q.setParameter("p" + (b + 2), r.customerName);
            q.setParameter("p" + (b + 3), r.customerMaterialName);
            q.setParameter("p" + (b + 4), r.customerProductNo);
            q.setParameter("p" + (b + 5), r.customerDrawingNo);
            q.setParameter("p" + (b + 6), r.seqNo);
            q.setParameter("p" + (b + 7), r.paymentMethod);
            q.setParameter("p" + (b + 8), r.baseCurrency);
            q.setParameter("p" + (b + 9), r.quoteCurrency);
            q.setParameter("p" + (b + 10), r.exchangeRate);
            q.setParameter("p" + (b + 11), r.productionNo);
        }
        q.setParameter("ub", updatedBy);
        return q.executeUpdate();
    }

    /**
     * QUOTE 侧 upsert，冲突键 material_no（部分唯一索引 uq_mcm_quote_no，WHERE system_type='QUOTE'）。
     * ★ 客户守卫：仅当既存行 customer_no = EXCLUDED.customer_no 才允许 DO UPDATE，否则整条语句 0 行受影响
     *   （调用方按返回值 0 判定"跨客户命中/串号"并转 recordError，不覆盖别的客户）。
     * ★ customer_product_no 直接 SET（非 COALESCE）：允许把组件登记行（customer_product_no=NULL）回填成
     *   客户料号映射行；其余描述字段维持 COALESCE 末值非空胜语义。
     * 若同 customer_no + customer_product_no 已绑定到另一个 material_no（撞
     * uq_mcm_quote_cust_prod），本方法未把该索引纳入 ON CONFLICT target，会直接抛 unique_violation，
     * 由调用方 per-row try/catch。
     */
    public int upsertQuote(MapRow r, UUID updatedBy) {
        String sql =
            "INSERT INTO material_customer_map (system_type, material_no, customer_no, customer_name, " +
            "  customer_material_name, customer_product_no, customer_drawing_no, seq_no, " +
            "  payment_method, base_currency, quote_currency, exchange_rate, production_no, " +
            "  created_at, updated_at, updated_by) " +
            "VALUES ('QUOTE', :materialNo, :customerNo, :customerName, :customerMaterialName, " +
            "  :customerProductNo, :customerDrawingNo, :seqNo, :paymentMethod, " +
            "  :baseCurrency, :quoteCurrency, :exchangeRate, :productionNo, NOW(), NOW(), :updatedBy) " +
            "ON CONFLICT (material_no) WHERE system_type='QUOTE' DO UPDATE SET " +
            "  customer_product_no     = EXCLUDED.customer_product_no, " +
            "  customer_name           = COALESCE(EXCLUDED.customer_name,          material_customer_map.customer_name), " +
            "  customer_material_name  = COALESCE(EXCLUDED.customer_material_name, material_customer_map.customer_material_name), " +
            "  customer_drawing_no     = COALESCE(EXCLUDED.customer_drawing_no,    material_customer_map.customer_drawing_no), " +
            "  seq_no                  = COALESCE(EXCLUDED.seq_no,                 material_customer_map.seq_no), " +
            "  payment_method          = COALESCE(EXCLUDED.payment_method,         material_customer_map.payment_method), " +
            "  base_currency           = COALESCE(EXCLUDED.base_currency,          material_customer_map.base_currency), " +
            "  quote_currency          = COALESCE(EXCLUDED.quote_currency,         material_customer_map.quote_currency), " +
            "  exchange_rate           = COALESCE(EXCLUDED.exchange_rate,          material_customer_map.exchange_rate), " +
            "  production_no           = COALESCE(EXCLUDED.production_no,          material_customer_map.production_no), " +
            "  updated_at              = NOW(), " +
            "  updated_by              = EXCLUDED.updated_by " +
            "WHERE material_customer_map.customer_no = EXCLUDED.customer_no";
        return em.createNativeQuery(sql)
            .setParameter("materialNo", r.materialNo)
            .setParameter("customerNo", r.customerNo)
            .setParameter("customerName", r.customerName)
            .setParameter("customerMaterialName", r.customerMaterialName)
            .setParameter("customerProductNo", r.customerProductNo)
            .setParameter("customerDrawingNo", r.customerDrawingNo)
            .setParameter("seqNo", r.seqNo)
            .setParameter("paymentMethod", r.paymentMethod)
            .setParameter("baseCurrency", r.baseCurrency)
            .setParameter("quoteCurrency", r.quoteCurrency)
            .setParameter("exchangeRate", r.exchangeRate)
            .setParameter("productionNo", r.productionNo)
            .setParameter("updatedBy", updatedBy)
            .executeUpdate();
    }

    /** ① replace-per-customer：删除该客户全部映射（重导前清栈，避免历史脏行残留扇出）。
     *  @deprecated 未按 system_type 收窄，会误删该客户 PRICING 行 + QUOTE 组件登记行（M4/M8）。
     *  Q02 replace-per-customer 场景请改用 {@link #deleteQuoteMappingsByCustomerNo}。 */
    @Deprecated
    public int deleteByCustomerNo(String customerNo) {
        return em.createNativeQuery(
                "DELETE FROM material_customer_map WHERE customer_no = :customerNo")
            .setParameter("customerNo", customerNo)
            .executeUpdate();
    }

    /** Q02 replace-per-customer 专用：只删该客户 QUOTE 侧「客户料号映射行」(customer_product_no 非空)，
     *  不动 PRICING 行、也不动组件登记行(customer_product_no IS NULL)。 */
    public int deleteQuoteMappingsByCustomerNo(String customerNo) {
        return em.createNativeQuery(
                "DELETE FROM material_customer_map WHERE customer_no=:c AND system_type='QUOTE' AND customer_product_no IS NOT NULL")
            .setParameter("c", customerNo)
            .executeUpdate();
    }
}
