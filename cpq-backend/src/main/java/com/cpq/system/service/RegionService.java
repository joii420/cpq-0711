package com.cpq.system.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.dto.RegionDTO;
import com.cpq.system.entity.Region;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class RegionService {

    private static final Logger LOG = Logger.getLogger(RegionService.class);

    public PageResult<RegionDTO> list(int page, int size) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        long total = Region.count();
        List<RegionDTO> content = Region
                .find("ORDER BY sortOrder ASC")
                .page(page, size)
                .list()
                .stream()
                .map(r -> RegionDTO.from((Region) r))
                .collect(Collectors.toList());
        LOG.debugf("list regions page=%d size=%d total=%d", page, size, total);
        return new PageResult<>(content, page, size, total);
    }

    @Transactional
    public RegionDTO create(RegionDTO dto) {
        long existing = Region.count("code = ?1", dto.code);
        if (existing > 0) {
            throw new BusinessException("Region code already exists: " + dto.code);
        }
        Region region = new Region();
        region.code = dto.code;
        region.name = dto.name;
        region.sortOrder = dto.sortOrder != null ? dto.sortOrder : 0;
        region.status = dto.status != null ? dto.status : "ACTIVE";
        region.persist();
        LOG.infof("Created region code=%s name=%s", region.code, region.name);
        return RegionDTO.from(region);
    }

    @Transactional
    public RegionDTO update(UUID id, RegionDTO dto) {
        Region region = Region.findById(id);
        if (region == null) {
            throw new BusinessException(404, "Region not found: " + id);
        }
        region.name = dto.name;
        if (dto.sortOrder != null) {
            region.sortOrder = dto.sortOrder;
        }
        LOG.infof("Updated region id=%s name=%s", id, region.name);
        return RegionDTO.from(region);
    }

    @Transactional
    public RegionDTO updateStatus(UUID id, String status) {
        Region region = Region.findById(id);
        if (region == null) {
            throw new BusinessException(404, "Region not found: " + id);
        }
        if ("DISABLED".equals(status)) {
            long activeUsers = User.count("regionId = ?1 AND status = 'ACTIVE'", id);
            if (activeUsers > 0) {
                throw new BusinessException("Cannot disable region with active users: " + activeUsers + " active user(s)");
            }
        }
        region.status = status;
        LOG.infof("Updated region id=%s status=%s", id, status);
        return RegionDTO.from(region);
    }
}
