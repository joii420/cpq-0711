package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.ProcessMasterDTO;
import com.cpq.basicdata.v6.entity.ProcessMaster;
import com.cpq.basicdata.v6.repository.ProcessMasterRepository;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
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
     * @param page    从 0 开始
     * @param size    每页条数（最大 200，超过抛 400）
     * @param keyword 可为 null，不过滤
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public PageResult<ProcessMasterDTO> list(int page, int size, String keyword) {
        if (size > 200) {
            throw new BusinessException(400, "INVALID_PAGE_SIZE: size 不能超过 200");
        }
        var query = repository.search(keyword);
        long total = query.count();
        List<ProcessMasterDTO> content = query.page(Page.of(page, size))
                .list()
                .stream()
                .map(ProcessMasterDTO::from)
                .collect(Collectors.toList());
        return new PageResult<>(content, page, size, total);
    }
}
