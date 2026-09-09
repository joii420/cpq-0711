package com.cpq.quotation.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * task-260904 B-5/B-6：料号类型的<b>主数据</b>批量取数（{@link BomNodeTypeResolver} 的数据 Port）。
 *
 * <p>两张主数据表实证<b>完全互斥</b>（{@code ds_quote_material} 45 行 / {@code material_recipe}
 * 260 行 / 交集 0）：
 * <ul>
 *   <li>在 <b>材质表</b> {@code material_recipe.code} → 材质</li>
 *   <li>在 <b>物料表</b> {@code ds_quote_material} → 看 {@code material_type}（零件 / 外购件；
 *       为空按 A0-1 兜底判零件）</li>
 * </ul>
 *
 * <p>🚫 <b>N+1 硬指标</b>：本服务只提供<b>批量</b>入口，固定 <b>2 条 SQL</b>，与料号数无关。
 * 不提供单料号方法——单料号方法一旦存在就会被放进循环（{@code PublishedTemplateReader} 类注释里
 * 记着的同型事故）。调用方必须先把整请求/整单的料号收齐再调一次。
 *
 * <h3>🚨 为什么 {@link #load} 必须带客户号（repair-260908 · C-3）</h3>
 * {@code ds_quote_material} 自 <b>V425</b> 起带 {@code customer_no} 维度，业务唯一键是
 * {@code uq_ds_quote_material (customer_no, material_no)} —— 即<b>同一个 material_no 可以在
 * 多个客户名下各存一行，各有各的 material_type</b>（现网实测 16 个料号如此）。
 * 改造前本方法按 {@code material_no IN (…)} 取数并塞进 {@code Map}，同 key 后来者覆盖，
 * 而 SQL 没有 {@code ORDER BY} ⇒ <b>取到哪一行由 PG 的返回顺序决定</b>。
 * 一旦两家客户对同一料号填了不同的类型（零件 vs 外购件），BOM 叶子就会被判成另一家的类型，
 * 失败形态是<b>金额算错但页面完全正常</b>——不报错、不缺行、不留痕。
 * <p>带上 {@code customer_no} 之后，谓词命中的是唯一索引的完整键 ⇒ 每个 material_no
 * <b>结构上至多一行</b>，last-wins 不再可能发生（不是「现网数据恰好一致」这种数据性保证）。
 * <p>🚫 {@code material_recipe}（材质库）没有客户维度，那条查询不加也不该加客户条件。
 */
@ApplicationScoped
public class MasterPartTypeService {

    private static final Logger LOG = Logger.getLogger(MasterPartTypeService.class);

    /** 单次 IN 的分片上限（PG 绑定参数上限 65535，留足余量；正常一张报价单远达不到）。 */
    private static final int CHUNK = 5000;

    @Inject
    EntityManager em;

    /**
     * 批量取主数据类型索引。
     *
     * @param customerNo 本次上下文的客户号（{@code customer.code}）。<b>必填</b>——见类注释「为什么必须带客户号」；
     *                   null/空白直接 {@link IllegalArgumentException}，🚫 不退化成不过滤
     * @param partNos 本次要判定的全部料号（null/空 → 返回空索引，0 条 SQL）
     * @return 永不为 null；<b>空索引 ≠ 未附加</b>——空索引意味着「查过了，一个都没命中」，
     *         {@link BomNodeTypeResolver} 据此对每个料号报 {@code LEAF_PART_NOT_IN_MASTER}
     */
    public BomNodeTypeResolver.MasterTypeIndex load(String customerNo, Collection<String> partNos) {
        // 🚫 不做「客户号为空就不过滤」的兜底：那正是改造前的 last-wins 形态，
        //    退化成静默错值比抛出来难查得多。调用方（报价树/整单快照）都由报价单确定客户，取不到才是真 bug。
        if (customerNo == null || customerNo.isBlank()) {
            throw new IllegalArgumentException(
                    "MasterPartTypeService.load 必须带客户号：ds_quote_material 的业务唯一键是 "
                  + "(customer_no, material_no)，不带客户号取数是顺序不保证的 last-wins（repair-260908 · C-3）");
        }
        if (partNos == null || partNos.isEmpty()) return BomNodeTypeResolver.MasterTypeIndex.empty();
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(partNos));
        keys.removeIf(s -> s == null || s.isBlank());
        if (keys.isEmpty()) return BomNodeTypeResolver.MasterTypeIndex.empty();

        Set<String> recipeCodes = new LinkedHashSet<>();
        Map<String, String> materialTypeByNo = new LinkedHashMap<>();

        // 分片只在料号数 > CHUNK 时才发生（现网一张单的树节点数量级为百，恒为 1 片 ⇒ 2 条 SQL）。
        for (int from = 0; from < keys.size(); from += CHUNK) {
            List<String> chunk = keys.subList(from, Math.min(from + CHUNK, keys.size()));
            try {
                @SuppressWarnings("unchecked")
                List<Object> codes = em.createNativeQuery(
                        "SELECT code FROM material_recipe WHERE code IN (:codes)")
                        .setParameter("codes", chunk).getResultList();
                for (Object c : codes) if (c != null) recipeCodes.add(c.toString());
            } catch (Exception e) {
                LOG.errorf("[master-part-type] 查 material_recipe 失败: %s", e.getMessage());
                throw e;
            }
            try {
                @SuppressWarnings("unchecked")
                List<Object[]> mats = em.createNativeQuery(
                        "SELECT material_no, material_type FROM ds_quote_material"
                      + " WHERE customer_no = :cust AND material_no IN (:nos)")
                        .setParameter("cust", customerNo)
                        .setParameter("nos", chunk).getResultList();
                for (Object[] r : mats) {
                    if (r == null || r[0] == null) continue;
                    materialTypeByNo.put(r[0].toString(), r[1] == null ? null : r[1].toString());
                }
            } catch (Exception e) {
                LOG.errorf("[master-part-type] 查 ds_quote_material 失败: %s", e.getMessage());
                throw e;
            }
        }

        LOG.debugf("[master-part-type] parts=%d recipeHit=%d materialHit=%d sql=%d",
                keys.size(), recipeCodes.size(), materialTypeByNo.size(),
                2 * ((keys.size() + CHUNK - 1) / CHUNK));
        return new BomNodeTypeResolver.MasterTypeIndex(recipeCodes, materialTypeByNo);
    }
}
