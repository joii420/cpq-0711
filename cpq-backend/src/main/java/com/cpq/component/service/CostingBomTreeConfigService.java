package com.cpq.component.service;

import com.cpq.component.entity.CostingBomTreeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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

    @Inject
    CostingTreeSqlValidator validator;

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
        // TODO(§10): 切换生效/改SQL后触发核价draft快照重算入口 <定位结论：
        //   codegraph 检索 buildCostingCardValues / ensureCardValues / refreshSnapshotsByComponent
        //   均为按 quotationId 或 componentId 局部重算，未发现"清空全部 QuotationLineItem.costingCardValues"
        //   的全局批量失效入口；本次改的是全局配置(costing_bom_tree_config)，理论上影响所有已存快照，
        //   需要新增/复用一个批量失效方法（如 UPDATE quotation_line_item SET costing_card_values = NULL），
        //   但为避免在不了解快照懒算/预取全貌的情况下臆造错误的失效范围，留待集成阶段(Task 3.1 起)
        //   结合 CardSnapshotService 实际读写路径决定。>
        return e;
    }

    /** 设为生效：单事务先把当前 active 置 false（避开部分唯一索引），再置目标 true。 */
    @Transactional
    public void setActive(UUID id) {
        CostingBomTreeConfig target = CostingBomTreeConfig.findById(id);
        if (target == null) {
            throw new RuntimeException("配置不存在: " + id);
        }
        CostingBomTreeConfig.update("isActive = false where isActive = true");
        CostingBomTreeConfig.getEntityManager().flush();
        target.isActive = true;
        // TODO(§10): 切换生效/改SQL后触发核价draft快照重算入口 <同上定位结论：暂未接入，留待集成阶段。>
    }

    @Transactional
    public void delete(UUID id) {
        CostingBomTreeConfig.deleteById(id);
    }
}
