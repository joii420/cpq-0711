package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.ProcessMasterDTO;
import com.cpq.basicdata.v6.dto.ProcessMasterUpsertRequest;
import com.cpq.basicdata.v6.entity.ProcessMaster;
import com.cpq.basicdata.v6.repository.ProcessMasterRepository;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * V6 工序主数据只读服务（供 {@link com.cpq.basicdata.v6.resource.ProcessMasterResource} 使用）。
 */
@ApplicationScoped
public class ProcessMasterReadService {

    @Inject
    ProcessMasterRepository repository;

    /**
     * 分页查询工序主数据。
     *
     * <p>兼容重载（{@code SelParamCandidateService} 等既有调用方）：等价于不传任何排序 / 过滤参数。
     *
     * @param page    从 0 开始
     * @param size    每页条数（最大 200，超过抛 400）
     * @param keyword 可为 null，不过滤
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public PageResult<ProcessMasterDTO> list(int page, int size, String keyword) {
        return list(page, size, keyword, null, null, null, null);
    }

    /**
     * 分页查询工序主数据 + 排序 + 过滤（task-0728 · api.md A2）。
     *
     * <p>count 与分页取自同一个 {@code PanacheQuery}，过滤条件对两者天然同步。
     *
     * @param sortBy          排序字段，见 {@code ProcessMasterRepository.SORT_WHITELIST}；非法值回退默认序
     * @param sortOrder       {@code asc}（默认） / {@code desc}
     * @param isOutsource     null=全部；true=外协；false=自制（{@code is_outsource IS NULL} 两侧都不出现）
     * @param processCategory null / 空白=全部；否则精确匹配
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public PageResult<ProcessMasterDTO> list(int page, int size, String keyword, String sortBy,
                                             String sortOrder, Boolean isOutsource, String processCategory) {
        if (size > 200) {
            throw new BusinessException(400, "INVALID_PAGE_SIZE: size 不能超过 200");
        }
        var query = repository.search(keyword, sortBy, sortOrder, isOutsource, processCategory);
        long total = query.count();
        List<ProcessMasterDTO> content = query.page(Page.of(page, size))
                .list()
                .stream()
                .map(ProcessMasterDTO::from)
                .collect(Collectors.toList());
        return new PageResult<>(content, page, size, total);
    }

    /** 工序分类去重列表（task-0728 · api.md A3），供过滤下拉取选项。空表返回 {@code []}。 */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<String> listCategories() {
        return repository.listDistinctCategories();
    }

    /** 新建工序: processNo 必填且唯一; processName 必填。 */
    @Transactional
    public ProcessMasterDTO create(ProcessMasterUpsertRequest req) {
        String no = req == null || req.processNo == null ? null : req.processNo.trim();
        if (no == null || no.isEmpty()) {
            throw new BusinessException(400, "工序编号不能为空");
        }
        if (req.processName == null || req.processName.isBlank()) {
            throw new BusinessException(400, "工序名称不能为空");
        }
        if (ProcessMaster.count("processNo", no) > 0) {
            throw new BusinessException(409, "工序编号已存在: " + no);
        }
        ProcessMaster e = new ProcessMaster();
        e.processNo = no;
        applyEditable(e, req);
        e.persist();
        return ProcessMasterDTO.from(e);
    }

    /** 编辑工序: processNo 为业务键, 锁定不可改(忽略请求里的 processNo)。 */
    @Transactional
    public ProcessMasterDTO update(UUID id, ProcessMasterUpsertRequest req) {
        ProcessMaster e = ProcessMaster.findById(id);
        if (e == null) {
            throw new BusinessException(404, "工序不存在: " + id);
        }
        if (req == null || req.processName == null || req.processName.isBlank()) {
            throw new BusinessException(400, "工序名称不能为空");
        }
        applyEditable(e, req);
        e.persist();
        return ProcessMasterDTO.from(e);
    }

    /** 硬删除工序(process_master 无状态字段)。无外键引用; 视图按 process_no LEFT JOIN, 删除后回退显示代码。 */
    @Transactional
    public void delete(UUID id) {
        ProcessMaster e = ProcessMaster.findById(id);
        if (e == null) {
            throw new BusinessException(404, "工序不存在: " + id);
        }
        e.delete();
    }

    /** 把可编辑字段(processNo 除外)从请求拷到实体。 */
    private void applyEditable(ProcessMaster e, ProcessMasterUpsertRequest req) {
        e.processName = req.processName == null ? null : req.processName.trim();
        e.processCategory = req.processCategory;
        e.isOutsource = req.isOutsource;
        e.standardCurrency = req.standardCurrency;
        e.standardUnit = req.standardUnit;
        e.defaultDefectRate = req.defaultDefectRate;
    }
}
