package com.cpq.component.service;

import com.cpq.component.entity.CostingBomTreeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 核价树递归 SQL 配置的 CRUD + 设为生效编排。
 *
 * <p>全局同一时刻最多一条 {@code isActive=true}（DB 部分唯一索引 {@code ux_cbt_active} 保障，
 * {@link #setActive(UUID)} 单事务内先清后置以避开索引冲突）。
 */
@ApplicationScoped
public class CostingBomTreeConfigService {

    private static final Logger LOG = Logger.getLogger(CostingBomTreeConfigService.class);

    @Inject
    CostingTreeSqlValidator validator;

    @Inject
    EntityManager em;

    public List<CostingBomTreeConfig> list() {
        return CostingBomTreeConfig.listAll();
    }

    @Transactional
    public CostingBomTreeConfig create(String name, String sqlTemplate) {
        CostingTreeSqlValidator.Result r = validator.validate(sqlTemplate);
        if (!r.ok) {
            throw new RuntimeException("递归 SQL 校验失败: " + r.message);
        }
        CostingBomTreeConfig e = new CostingBomTreeConfig();
        e.name = name;
        e.sqlTemplate = sqlTemplate;
        e.isActive = false;
        e.persist();
        return e;
    }

    @Transactional
    public CostingBomTreeConfig update(UUID id, String name, String sqlTemplate) {
        CostingTreeSqlValidator.Result r = validator.validate(sqlTemplate);
        if (!r.ok) {
            throw new RuntimeException("递归 SQL 校验失败: " + r.message);
        }
        CostingBomTreeConfig e = CostingBomTreeConfig.findById(id);
        if (e == null) {
            throw new RuntimeException("配置不存在: " + id);
        }
        e.name = name;
        e.sqlTemplate = sqlTemplate;
        // §10 失效钩子（Task 3.1 集成阶段接入，见 invalidateTreeTabCostingCardValues 注释）：
        // update() 改的是"当前配置的 SQL 内容"——只有它已经是 isActive=true 时才会被下次渲染实际读取。
        // 非生效配置改动不影响任何已渲染快照，故仅当 e.isActive 时才失效。
        if (e.isActive) {
            invalidateTreeTabCostingCardValues();
        }
        return e;
    }

    /**
     * 设为生效：单事务先把除目标外的当前 active 置 false（避开部分唯一索引），再置目标 true。
     *
     * <p>用<b>受管实体</b>写(非 bulk UPDATE)：
     * <ul>
     *   <li>幂等正确：对<b>已生效</b>配置再调一次时 {@code current==target} → 跳过置 false，
     *       {@code target.isActive=true} 保持 true(no-op),该行仍生效;
     *   <li>不产生陈旧读:bulk UPDATE 不会刷新持久化上下文里已加载的实体,后续同上下文
     *       {@code findById}/{@code findActive} 会读到旧一级缓存值(true 被误读为 false);受管实体写则
     *       读回一致。
     * </ul>
     * <p>部分唯一索引 {@code ux_cbt_active}(同一时刻最多一条 true):先把旧生效行置 false 并
     * {@code em.flush()} 落库,再置目标 true,避免瞬时两条 true 违反索引。
     */
    @Transactional
    public void setActive(UUID id) {
        CostingBomTreeConfig target = CostingBomTreeConfig.findById(id);
        if (target == null) {
            throw new RuntimeException("配置不存在: " + id);
        }
        CostingBomTreeConfig current = CostingBomTreeConfig.findActive();
        if (current != null && !current.id.equals(target.id)) {
            current.isActive = false;
            em.flush();   // 先让旧生效行落库为 false,再置目标 true(部分唯一索引)
        }
        target.isActive = true;
        invalidateTreeTabCostingCardValues();
    }

    /**
     * §10 失效钩子（Task 3.1 集成阶段接入）：递归 SQL 配置切换生效 / 改动生效配置内容后，
     * 只有<b>核价模板挂了树页签组件（{@code bom_recursive_expand=true}）</b>的存量报价单，其
     * {@code costingCardValues} 才依赖本配置渲染（见 {@link com.cpq.quotation.service.CostingTreeRenderService}
     * 与 {@code CardSnapshotService#templateHasTreeTab}）；不含树页签的模板/报价单完全不读本配置，
     * 若做不加区分的全局 {@code UPDATE quotation_line_item SET costing_card_values = NULL} 会误伤它们
     * （下次打开被迫走一次不必要的懒算）。故用 {@code EXISTS} 精确收窄到受影响的报价单行。
     *
     * <p>只置 NULL，不同步重算——沿用既有 P3 懒算纪律（{@code CardSnapshotService#ensureCardValues} 的
     * {@code IS NULL} 谓词下次打开该报价单/调用 ensureCardValues 时自动补算，单飞锁防并发重复算），
     * 不在本次配置切换事务里做重量级同步批量渲染。
     */
    @Transactional
    void invalidateTreeTabCostingCardValues() {
        int n = em.createNativeQuery(
                "UPDATE quotation_line_item li SET costing_card_values = NULL, costing_excel_values = NULL " +
                "WHERE li.costing_card_values IS NOT NULL AND EXISTS ( " +
                "  SELECT 1 FROM quotation q " +
                "  JOIN template_component tc ON tc.template_id = q.costing_card_template_id " +
                "  JOIN component c ON c.id = tc.component_id " +
                "  WHERE q.id = li.quotation_id AND c.bom_recursive_expand = true )")
            .executeUpdate();
        LOG.infof("[costing-bom-tree-config] 递归 SQL 配置变更，失效 %d 行含树页签模板的存量核价卡片值(懒算重算)", n);
    }

    @Transactional
    public void delete(UUID id) {
        CostingBomTreeConfig.deleteById(id);
    }
}
