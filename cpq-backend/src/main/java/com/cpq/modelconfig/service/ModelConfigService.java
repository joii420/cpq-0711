package com.cpq.modelconfig.service;

import com.cpq.basicdata.v6.entity.MaterialCustomerMap;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.modelconfig.dto.ModelConfigDTO;
import com.cpq.modelconfig.entity.ModelConfig;
import com.cpq.modelconfig.entity.ModelConfigFile;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 3D 模型配置服务（task-0712 B5）。
 *
 * <p>核心不变量：同一 {@code (subjectType, subjectKey)} 仅一条 {@code isCurrent=true}
 * （部分唯一索引 {@code uq_model_config_current} 保证并发安全）；切当前版本用<b>受管实体</b>
 * 先置旧 false + {@code em.flush()} 落库、再置目标 true（避开瞬时两条 true 违反索引，
 * 参照 {@code CostingBomTreeConfigService#setActive} 同一idiom），不用 bulk update 防陈旧一级缓存读。
 */
@ApplicationScoped
public class ModelConfigService {

    private static final Set<String> VALID_SUBJECT_TYPES = Set.of("SALES_PART", "MATERIAL");

    @Inject
    EntityManager em;

    @Inject
    ModelFileStorageService fileStorage;

    // ────────────────────────────────────────────────────────────────
    // 查询
    // ────────────────────────────────────────────────────────────────

    public PageResult<ModelConfigDTO> list(String subjectType, String keyword, int page, int size) {
        StringBuilder jpql = new StringBuilder("1=1");
        List<Object> params = new ArrayList<>();
        if (subjectType != null && !subjectType.isBlank()) {
            validateSubjectType(subjectType);
            params.add(subjectType);
            jpql.append(" and subjectType = ?").append(params.size());
        }
        if (keyword != null && !keyword.isBlank()) {
            params.add("%" + keyword.trim().toLowerCase() + "%");
            int idx = params.size();
            jpql.append(" and (lower(subjectKey) like ?").append(idx)
                .append(" or lower(label) like ?").append(idx).append(")");
        }
        var query = ModelConfig.find(jpql.toString(), Sort.by("uploadedAt").descending(), params.toArray());
        long total = query.count();
        List<ModelConfig> rows = query.page(Page.of(page, size)).list();
        List<ModelConfigDTO> dtos = rows.stream().map(ModelConfigDTO::from).collect(Collectors.toList());
        enrichSubjectLabels(dtos);
        return new PageResult<>(dtos, page, size, total);
    }

    public List<ModelConfigDTO> versions(String subjectType, String subjectKey) {
        validateSubjectType(subjectType);
        requireSubjectKey(subjectKey);
        List<ModelConfig> rows = ModelConfig
                .find("subjectType = ?1 and subjectKey = ?2", Sort.by("version"), subjectType, subjectKey)
                .list();
        List<ModelConfigDTO> dtos = rows.stream().map(ModelConfigDTO::from).collect(Collectors.toList());
        enrichSubjectLabels(dtos);
        return dtos;
    }

    /** 运行端查当前版本（D15）。无记录返回 null（非阻断，前端占位）。 */
    public ModelConfigDTO current(String subjectType, String subjectKey) {
        validateSubjectType(subjectType);
        requireSubjectKey(subjectKey);
        ModelConfig m = ModelConfig
                .find("subjectType = ?1 and subjectKey = ?2 and isCurrent = true", subjectType, subjectKey)
                .firstResult();
        if (m == null) return null;
        ModelConfigDTO dto = ModelConfigDTO.from(m);
        enrichSubjectLabels(List.of(dto));
        return dto;
    }

    // ────────────────────────────────────────────────────────────────
    // 变更
    // ────────────────────────────────────────────────────────────────

    @Transactional
    public ModelConfigDTO upload(String subjectType, String subjectKey, String label,
                                  FileUpload glbFile, FileUpload thumbnailFile,
                                  boolean setCurrent, UUID uploadedBy) {
        validateSubjectType(subjectType);
        String key = subjectKey == null ? null : subjectKey.trim();
        requireSubjectKey(key);
        if (glbFile == null || glbFile.fileName() == null || glbFile.fileName().isBlank()) {
            throw new BusinessException("需上传 .glb 模型文件");
        }
        if (!glbFile.fileName().toLowerCase().endsWith(".glb")) {
            throw new BusinessException("GLB 文件格式非法，需为 .glb: " + glbFile.fileName());
        }

        Integer maxVersion = em.createQuery(
                        "select max(m.version) from ModelConfig m where m.subjectType = :st and m.subjectKey = :sk",
                        Integer.class)
                .setParameter("st", subjectType)
                .setParameter("sk", key)
                .getSingleResult();
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        // 部分唯一索引 uq_model_config_current：先把旧 current 置 false 并 flush 落库，
        // 再插入新 current 行，避免瞬时两条 true。
        if (setCurrent) {
            List<ModelConfig> currentRows = ModelConfig
                    .find("subjectType = ?1 and subjectKey = ?2 and isCurrent = true", subjectType, key)
                    .list();
            if (!currentRows.isEmpty()) {
                for (ModelConfig c : currentRows) c.isCurrent = false;
                em.flush();
            }
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 落盘用独立生成的 storageKey（与 JPA @GeneratedValue 的行 id 解耦），而非"persist()
        // 拿到生成 id 后再回填 URL 字段"——实测该顺序下 Hibernate 对已 persist() 的新瞬时对象
        // 后续字段变更未必反映进最终 INSERT（批量 insert 场景下复现 glb_url NOT NULL 违例）。
        // 所以改为：先落盘算出 URL → 所有字段一次性定型 → 最后统一 persist()。
        UUID glbKey = UUID.randomUUID();
        ModelFileStorageService.StoredFile glbStored = fileStorage.store(glbKey, glbFile);
        String glbUrl = fileUrl(glbKey);

        UUID thumbKey = null;
        ModelFileStorageService.StoredFile thumbStored = null;
        String thumbUrl = null;
        if (thumbnailFile != null && thumbnailFile.fileName() != null && !thumbnailFile.fileName().isBlank()) {
            thumbKey = UUID.randomUUID();
            thumbStored = fileStorage.store(thumbKey, thumbnailFile);
            thumbUrl = fileUrl(thumbKey);
        }

        ModelConfig entity = new ModelConfig();
        entity.subjectType = subjectType;
        entity.subjectKey = key;
        entity.version = nextVersion;
        entity.isCurrent = setCurrent;
        entity.label = label;
        entity.glbUrl = glbUrl;
        entity.thumbnailUrl = thumbUrl;
        entity.sizeKb = (int) Math.ceil(glbStored.sizeBytes() / 1024.0);
        entity.uploadedBy = uploadedBy;
        entity.uploadedAt = now;
        entity.persist();

        ModelConfigFile glbRow = new ModelConfigFile();
        glbRow.modelConfigId = entity.id;
        glbRow.fileRole = "GLB";
        glbRow.fileUrl = glbUrl;
        glbRow.fileSizeBytes = glbStored.sizeBytes();
        glbRow.md5Hash = glbStored.md5Hash();
        glbRow.uploadedAt = now;
        glbRow.persist();

        if (thumbStored != null) {
            ModelConfigFile thumbRow = new ModelConfigFile();
            thumbRow.modelConfigId = entity.id;
            thumbRow.fileRole = "THUMBNAIL";
            thumbRow.fileUrl = thumbUrl;
            thumbRow.fileSizeBytes = thumbStored.sizeBytes();
            thumbRow.md5Hash = thumbStored.md5Hash();
            thumbRow.uploadedAt = now;
            thumbRow.persist();
        }

        ModelConfigDTO dto = ModelConfigDTO.from(entity);
        enrichSubjectLabels(List.of(dto));
        return dto;
    }

    @Transactional
    public ModelConfigDTO setCurrent(UUID id) {
        ModelConfig target = ModelConfig.findById(id);
        if (target == null) throw new BusinessException(404, "3D 模型配置不存在: " + id);

        List<ModelConfig> others = ModelConfig.find(
                        "subjectType = ?1 and subjectKey = ?2 and isCurrent = true and id != ?3",
                        target.subjectType, target.subjectKey, target.id)
                .list();
        if (!others.isEmpty()) {
            for (ModelConfig o : others) o.isCurrent = false;
            em.flush();
        }
        target.isCurrent = true;

        ModelConfigDTO dto = ModelConfigDTO.from(target);
        enrichSubjectLabels(List.of(dto));
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        ModelConfig target = ModelConfig.findById(id);
        if (target == null) throw new BusinessException(404, "3D 模型配置不存在: " + id);

        List<ModelConfigFile> files = ModelConfigFile.find("modelConfigId", target.id).list();
        for (ModelConfigFile f : files) {
            fileStorage.deleteQuietly(fileStorage.resolveExisting(extractFileId(f.fileUrl)));
        }
        // model_config_file 由 DB 侧 ON DELETE CASCADE 级联清理（V330）。
        ModelConfig.deleteById(id);
    }

    // ────────────────────────────────────────────────────────────────
    // 文件下载（回源）
    // ────────────────────────────────────────────────────────────────

    public record FileDownload(Path path, String contentType) {}

    /**
     * {@code fileId} 是落盘/URL 用的 storageKey（见 upload() 注释，非 model_config_file.id）。
     * 按 fileUrl 精确匹配定位登记行，取 fileRole 猜 Content-Type。
     */
    public FileDownload resolveDownload(UUID fileId) {
        ModelConfigFile row = ModelConfigFile.find("fileUrl", fileUrl(fileId)).firstResult();
        if (row == null) throw new NotFoundException("文件不存在: " + fileId);
        Path path = fileStorage.resolveExisting(fileId);
        if (path == null) throw new NotFoundException("文件不存在于存储: " + fileId);
        return new FileDownload(path, guessContentType(row.fileRole, path));
    }

    private String guessContentType(String role, Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) return probed;
        } catch (Exception ignored) {
            // fall through to role-based default
        }
        if ("GLB".equals(role)) return "model/gltf-binary";
        if ("THUMBNAIL".equals(role)) return "image/png";
        return "application/octet-stream";
    }

    // ────────────────────────────────────────────────────────────────
    // 内部辅助
    // ────────────────────────────────────────────────────────────────

    private String fileUrl(UUID fileId) {
        return "/api/cpq/model-configs/files/" + fileId;
    }

    private UUID extractFileId(String fileUrl) {
        if (fileUrl == null) return null;
        int idx = fileUrl.lastIndexOf('/');
        String idStr = idx >= 0 ? fileUrl.substring(idx + 1) : fileUrl;
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void validateSubjectType(String subjectType) {
        if (subjectType == null || !VALID_SUBJECT_TYPES.contains(subjectType)) {
            throw new BusinessException("对象类型非法(须 SALES_PART/MATERIAL): " + subjectType);
        }
    }

    private void requireSubjectKey(String subjectKey) {
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new BusinessException("subjectKey 不能为空");
        }
    }

    /**
     * 批量补 subjectLabel（材质名 / 客户产品品名），禁逐行查（N+1 硬指标）：
     * 按 subjectType 分组收集 subjectKey 集合，各发 1 条批量 IN 查询。
     */
    private void enrichSubjectLabels(List<ModelConfigDTO> dtos) {
        if (dtos.isEmpty()) return;

        Set<String> materialCodes = dtos.stream()
                .filter(d -> "MATERIAL".equals(d.subjectType))
                .map(d -> d.subjectKey)
                .collect(Collectors.toSet());
        Set<String> salesPartNos = dtos.stream()
                .filter(d -> "SALES_PART".equals(d.subjectType))
                .map(d -> d.subjectKey)
                .collect(Collectors.toSet());

        Map<String, String> materialLabels = materialCodes.isEmpty() ? Map.of()
                : MaterialRecipe.<MaterialRecipe>find("code in ?1", materialCodes).list().stream()
                        .collect(Collectors.toMap(r -> r.code, r -> r.name, (a, b) -> a));

        Map<String, String> salesPartLabels = salesPartNos.isEmpty() ? Map.of()
                : MaterialCustomerMap.<MaterialCustomerMap>find("materialNo in ?1", salesPartNos).list().stream()
                        .filter(m -> m.customerMaterialName != null)
                        .collect(Collectors.toMap(m -> m.materialNo, m -> m.customerMaterialName, (a, b) -> a));

        for (ModelConfigDTO dto : dtos) {
            if ("MATERIAL".equals(dto.subjectType)) {
                dto.subjectLabel = materialLabels.get(dto.subjectKey);
            } else if ("SALES_PART".equals(dto.subjectType)) {
                dto.subjectLabel = salesPartLabels.get(dto.subjectKey);
            }
        }
    }
}
