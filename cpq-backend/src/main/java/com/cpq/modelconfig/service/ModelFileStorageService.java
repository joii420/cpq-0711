package com.cpq.modelconfig.service;

import com.cpq.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 3D 模型文件本地磁盘存储（task-0712 B5）。
 *
 * <p><b>已知限制/架构决策（需 architect 后续复核）</b>：本工程此前无任何可用的二进制文件存储实现
 * —— {@code com.cpq.partmodel.resource.PartModelResource#upload} 是未实现的 TODO 桩
 * （返回 {@code status=not_implemented}），CAD 转换 POC 文档规划的对象存储（MinIO/OSS/S3）
 * 尚未落地。为不阻塞 B5 交付，本服务采用最小可用的<b>本地磁盘</b>存储 + 按 {@code model_config_file.id}
 * 建服务端点回源（见 {@code ModelConfigResource#downloadFile}），隔离在本类内，后续换 MinIO/S3
 * 只需替换本类实现，不影响 Service/Resource 契约。
 */
@ApplicationScoped
public class ModelFileStorageService {

    private static final Logger LOG = Logger.getLogger(ModelFileStorageService.class);

    @ConfigProperty(name = "cpq.model-config.storage-dir")
    String storageDir;

    private Path baseDir;

    @PostConstruct
    void init() {
        baseDir = Path.of(storageDir);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 3D 模型存储目录: " + baseDir, e);
        }
        LOG.infof("ModelFileStorageService: storage dir = %s", baseDir.toAbsolutePath());
    }

    /** 存储结果：物理路径 + 字节数 + MD5。 */
    public record StoredFile(Path path, long sizeBytes, String md5Hash) {}

    /**
     * 把上传的 multipart 文件落盘为 {@code {fileId}{ext}}，ext 取原始文件名后缀（未知则空）。
     */
    public StoredFile store(UUID fileId, FileUpload upload) {
        if (upload == null) {
            throw new BusinessException("文件不能为空");
        }
        String ext = extractExtension(upload.fileName());
        Path target = baseDir.resolve(fileId + ext);
        try {
            Files.copy(upload.uploadedFile(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(500, "文件保存失败: " + e.getMessage());
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            size = upload.size();
        }
        String md5 = md5Hex(target);
        return new StoredFile(target, size, md5);
    }

    /** 按 fileId 前缀在存储目录内查找物理文件（ext 未持久化，容忍目录内同前缀单文件）。 */
    public Path resolveExisting(UUID fileId) {
        if (fileId == null) return null;
        String prefix = fileId.toString();
        try (var stream = Files.list(baseDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            LOG.warnf("ModelFileStorageService: resolveExisting 失败 fileId=%s: %s", fileId, e.getMessage());
            return null;
        }
    }

    public void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warnf("ModelFileStorageService: 删除文件失败 %s: %s", path, e.getMessage());
        }
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) return "";
        String ext = fileName.substring(idx).toLowerCase();
        // 仅允许字母数字后缀，防目录穿越/异常字符落盘
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }

    private String md5Hex(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.warnf("ModelFileStorageService: md5 计算失败 %s: %s", path, e.getMessage());
            return null;
        }
    }
}
