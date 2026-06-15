package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * V6 工序主数据只读仓储。
 */
@ApplicationScoped
public class ProcessMasterRepository implements PanacheRepositoryBase<ProcessMaster, UUID> {

    /**
     * 按关键字模糊搜索（processNo 或 processName），返回分页查询对象。
     * 调用方用 .page(...).list() 分页。
     *
     * @param keyword 可为 null / 空，表示不过滤
     */
    public PanacheQuery<ProcessMaster> search(String keyword) {
        Sort sort = Sort.by("processNo").ascending();
        if (keyword == null || keyword.isBlank()) {
            return findAll(sort);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("kw", "%" + keyword.toLowerCase() + "%");
        return find("LOWER(processNo) LIKE :kw OR LOWER(processName) LIKE :kw", sort, params);
    }

    /** 按工序名称精确取第一条（process_no 升序）。供导入工序回填用（决策 #5）。 */
    public Optional<ProcessMaster> findFirstByProcessName(String name) {
        return find("processName = ?1 ORDER BY processNo ASC", name).firstResultOptional();
    }
}
