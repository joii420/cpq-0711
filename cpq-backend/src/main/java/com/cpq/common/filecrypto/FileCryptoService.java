package com.cpq.common.filecrypto;

import com.cpq.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Central trust boundary for user-uploaded files.
 *
 * <p>Only the exact {@code prod} profile invokes the external service. Every other profile reads
 * the multipart temporary file once and returns the same bytes without validating crypto config
 * or issuing a network request.
 */
@ApplicationScoped
public class FileCryptoService {

    private static final Logger LOG = Logger.getLogger(FileCryptoService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> ALLOWED_UPSTREAM_CODES = Set.of(
            "EMPTY_FILE", "UNAUTHORIZED", "FILE_TOO_LARGE", "FILE_NOT_ENCRYPTED",
            "FILE_ALREADY_ENCRYPTED", "CRYPTO_FAILED", "CRYPTO_NOT_INITIALIZED",
            "CRYPTO_NOT_AUTHORIZED", "CRYPTO_LICENSE_EXPIRED", "CRYPTO_NO_ENCRYPT_LICENSE",
            "INTERNAL_ERROR");

    @ConfigProperty(name = "file.encryption.server")
    String server;

    @ConfigProperty(name = "file.encryption.token")
    Optional<String> token;

    @Inject
    ObjectMapper objectMapper;

    HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public ProcessedUpload process(FileUpload upload) {
        if (upload == null || upload.uploadedFile() == null) {
            throw failure(400, "上传文件为空", "EMPTY_UPLOAD_FILE", null, null);
        }

        final byte[] original;
        try {
            original = Files.readAllBytes(upload.uploadedFile());
        } catch (IOException e) {
            throw new BusinessException(400, "读取上传文件失败");
        }
        if (original.length == 0) {
            throw failure(400, "上传文件为空", "EMPTY_UPLOAD_FILE", null, null);
        }

        String fileName = normalizedFileName(upload.fileName());
        if (!isExactProdProfile()) {
            return new ProcessedUpload(fileName, original, false, null);
        }

        URI baseUri = validatedBaseUri();
        String configuredToken = token == null ? "" : token.orElse("").trim();
        if (configuredToken.isEmpty() || configuredToken.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw failure(503, "文件解密配置不完整，请联系管理员",
                    "FILE_CRYPTO_CONFIG_INVALID", null, null);
        }

        String requestId = UUID.randomUUID().toString();
        HttpResponse<byte[]> inspect = invoke(baseUri.resolve("/v1/inspect"), original, fileName,
                configuredToken, requestId, "inspect");
        boolean encrypted = parseInspect(inspect.body(), requestId);
        if (!encrypted) {
            return new ProcessedUpload(fileName, original, false, requestId);
        }

        HttpResponse<byte[]> decrypt = invoke(baseUri.resolve("/v1/decrypt"), original, fileName,
                configuredToken, requestId, "decrypt");
        byte[] plaintext = decrypt.body();
        if (plaintext == null || plaintext.length == 0) {
            throw failure(502, "文件解密服务返回无效响应",
                    "FILE_CRYPTO_BAD_GATEWAY", null, requestId);
        }
        return new ProcessedUpload(fileName, plaintext, true, requestId);
    }

    boolean isExactProdProfile() {
        List<String> profiles = ConfigUtils.getProfiles();
        return profiles.size() == 1 && "prod".equals(profiles.getFirst());
    }

    private HttpResponse<byte[]> invoke(URI uri, byte[] body, String fileName, String bearerToken,
                                        String requestId, String operation) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/octet-stream")
                .header("X-File-Name", encodeFileName(fileName))
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            LOG.infof("[file-crypto] requestId=%s operation=%s bytes=%d elapsedMs=%d status=%d",
                    requestId, operation, body.length, elapsedMs, response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw mapUpstreamFailure(response.statusCode(), response.body(), requestId);
            }
            return response;
        } catch (HttpTimeoutException e) {
            logTransportFailure(requestId, operation, body.length, started, "timeout");
            throw failure(504, "文件解密服务超时，请稍后重试",
                    "FILE_CRYPTO_TIMEOUT", null, requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logTransportFailure(requestId, operation, body.length, started, "interrupted");
            throw failure(502, "文件解密服务通信失败",
                    "FILE_CRYPTO_BAD_GATEWAY", null, requestId);
        } catch (ConnectException e) {
            logTransportFailure(requestId, operation, body.length, started, "connect_failed");
            throw failure(502, "文件解密服务通信失败",
                    "FILE_CRYPTO_BAD_GATEWAY", null, requestId);
        } catch (IOException e) {
            logTransportFailure(requestId, operation, body.length, started, "io_failed");
            throw failure(502, "文件解密服务通信失败",
                    "FILE_CRYPTO_BAD_GATEWAY", null, requestId);
        }
    }

    private boolean parseInspect(byte[] body, String requestId) {
        if (body == null || body.length == 0) {
            throw invalidInspect(requestId);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) throw invalidInspect(requestId);
            JsonNode encrypted = root.get("encrypted");
            JsonNode legacy = root.get("isEncrypted");
            if ((encrypted != null && !encrypted.isBoolean())
                    || (legacy != null && !legacy.isBoolean())
                    || (encrypted == null && legacy == null)) {
                throw invalidInspect(requestId);
            }
            return (encrypted != null && encrypted.booleanValue())
                    || (legacy != null && legacy.booleanValue());
        } catch (FileCryptoException e) {
            throw e;
        } catch (Exception e) {
            throw invalidInspect(requestId);
        }
    }

    private FileCryptoException mapUpstreamFailure(int status, byte[] responseBody, String requestId) {
        String upstreamCode = extractAllowedUpstreamCode(responseBody);
        return switch (status) {
            case 400 -> failure(400, "上传文件被加解密服务拒绝",
                    "FILE_CRYPTO_REJECTED", upstreamCode, requestId);
            case 401 -> failure(502, "文件解密服务认证失败，请联系管理员",
                    "FILE_CRYPTO_AUTH_FAILED", upstreamCode, requestId);
            case 413 -> failure(413, "上传文件超出加解密服务限制",
                    "FILE_CRYPTO_FILE_TOO_LARGE", upstreamCode, requestId);
            case 422 -> failure(422, "上传文件无法解密",
                    "FILE_CRYPTO_REJECTED", upstreamCode, requestId);
            case 503 -> failure(503, "文件解密服务暂不可用，请稍后重试",
                    "FILE_CRYPTO_UNAVAILABLE", upstreamCode, requestId);
            default -> failure(502, "文件解密服务返回异常",
                    "FILE_CRYPTO_BAD_GATEWAY", upstreamCode, requestId);
        };
    }

    private String extractAllowedUpstreamCode(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) return null;
        try {
            JsonNode code = objectMapper.readTree(responseBody).get("code");
            if (code == null || !code.isTextual()) return null;
            String value = code.textValue();
            return ALLOWED_UPSTREAM_CODES.contains(value) ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private URI validatedBaseUri() {
        String value = server == null ? "" : server.trim();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = new URI(value);
            boolean schemeValid = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            boolean pathValid = uri.getRawPath() == null || uri.getRawPath().isEmpty();
            if (!schemeValid || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || !pathValid
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new URISyntaxException(value, "base URI must be scheme://host:port");
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw failure(503, "文件解密配置不完整，请联系管理员",
                    "FILE_CRYPTO_CONFIG_INVALID", null, null);
        }
    }

    static String encodeFileName(String fileName) {
        byte[] utf8 = fileName.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(utf8.length * 3);
        for (byte value : utf8) {
            int b = value & 0xff;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '.' || b == '_' || b == '~') {
                encoded.append((char) b);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((b >>> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static String normalizedFileName(String fileName) {
        return fileName == null || fileName.isBlank() ? "upload.bin" : fileName;
    }

    private static FileCryptoException invalidInspect(String requestId) {
        return failure(502, "文件解密服务返回无效响应",
                "FILE_CRYPTO_BAD_GATEWAY", null, requestId);
    }

    private static FileCryptoException failure(int status, String message, String bizCode,
                                               String upstreamCode, String requestId) {
        return new FileCryptoException(status, message, bizCode, upstreamCode, requestId);
    }

    private static void logTransportFailure(String requestId, String operation, int bytes,
                                            long started, String result) {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        LOG.warnf("[file-crypto] requestId=%s operation=%s bytes=%d elapsedMs=%d result=%s",
                requestId, operation, bytes, elapsedMs, result);
    }
}
