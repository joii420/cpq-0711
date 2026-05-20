package com.cpq.datasource.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * I3: HTTP_API 数据源解析 — 调用外部 REST API.
 *
 * <p><b>严格安全约束 (默认拒绝)</b>:
 * <ul>
 *   <li>Host 必须在配置白名单 {@code cpq.http-api.allowed-hosts} (逗号分隔)</li>
 *   <li>拒绝私有 IP / loopback / link-local (防 SSRF)</li>
 *   <li>仅 HTTPS (除非 host 在 {@code cpq.http-api.allow-http-hosts})</li>
 *   <li>GET only (本期实现)</li>
 *   <li>超时 5 秒 (HttpClient 连接+响应)</li>
 *   <li>Bearer Token 必须从环境变量取: api_config.auth_token_env = "MY_TOKEN_VAR" → System.getenv("MY_TOKEN_VAR")</li>
 *   <li>结果 Caffeine 5 分钟 TTL 缓存 (key = url + auth header), 减少外调</li>
 * </ul>
 *
 * <p>config 期望字段:
 * <pre>{@code
 * api_config: {
 *   url_template: "https://api.example.com/price/{partNo}",  // {placeholder} 由 driverRow 替换
 *   response_path: "data.price",  // 简单 dot-path 提取 JSON 字段
 *   auth_token_env: "EXAMPLE_API_TOKEN"  // 从环境变量取 Bearer token (选填)
 * }
 * }</pre>
 *
 * <p>失败语义: 任何安全/网络/解析失败一律返 null + LOG.warn, 不抛.
 *
 * <p>未配置白名单 = HTTP_API 完全关闭 (生产环境必须显式 opt-in).
 */
@ApplicationScoped
public class HttpApiResolver implements DataSourceResolver {

    private static final Logger LOG = Logger.getLogger(HttpApiResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "cpq.http-api.allowed-hosts")
    Optional<String> allowedHostsConfig;

    @ConfigProperty(name = "cpq.http-api.allow-http-hosts")
    Optional<String> allowHttpHostsConfig;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)  // 防 SSRF 重定向
            .build();

    private final Cache<String, Object> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    @Override
    public String type() { return "HTTP_API"; }

    @Override
    public Object resolve(Map<String, Object> config, Map<String, Object> driverRow) {
        Set<String> allowedHosts = parseHostSet(allowedHostsConfig.orElse(""));
        if (allowedHosts.isEmpty()) {
            LOG.warnf("HTTP_API resolver invoked but cpq.http-api.allowed-hosts is empty — refuse (opt-in required)");
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> apiConfig = (Map<String, Object>) config.get("api_config");
        if (apiConfig == null) return null;

        Object urlTplObj = apiConfig.get("url_template");
        if (urlTplObj == null) return null;
        String url;
        try {
            url = interpolate(urlTplObj.toString(), driverRow);
        } catch (Exception e) {
            LOG.warnf("HTTP_API url interpolation failed: %s", e.getMessage());
            return null;
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            LOG.warnf("HTTP_API invalid url: %s", url);
            return null;
        }
        String host = uri.getHost();
        if (host == null) return null;
        if (!allowedHosts.contains(host.toLowerCase())) {
            LOG.warnf("HTTP_API host %s not in allowed-hosts whitelist; refuse", host);
            return null;
        }
        boolean isHttps = "https".equalsIgnoreCase(uri.getScheme());
        if (!isHttps && !parseHostSet(allowHttpHostsConfig.orElse("")).contains(host.toLowerCase())) {
            LOG.warnf("HTTP_API non-https url %s; refuse (host not in allow-http-hosts)", url);
            return null;
        }
        // SSRF: 拒绝私有 IP
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
                    LOG.warnf("HTTP_API host %s resolves to private/loopback IP %s; refuse",
                            host, addr.getHostAddress());
                    return null;
                }
            }
        } catch (Exception e) {
            LOG.warnf("HTTP_API DNS resolution failed for %s: %s", host, e.getMessage());
            return null;
        }

        Object tokenEnvObj = apiConfig.get("auth_token_env");
        String bearerToken = null;
        if (tokenEnvObj != null) {
            String envName = tokenEnvObj.toString();
            bearerToken = System.getenv(envName);
            if (bearerToken == null || bearerToken.isBlank()) {
                LOG.warnf("HTTP_API auth_token_env=%s not set in process environment; refuse", envName);
                return null;
            }
        }

        String cacheKey = url + "|" + (bearerToken == null ? "" : Integer.toHexString(bearerToken.hashCode()));
        Object cached = cache.getIfPresent(cacheKey);
        if (cached != null) return cached;

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET();
            if (bearerToken != null) {
                reqBuilder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOG.warnf("HTTP_API non-2xx status=%d url=%s", resp.statusCode(), url);
                return null;
            }
            String body = resp.body();
            Object value = extractValue(body, Optional.ofNullable(apiConfig.get("response_path"))
                    .map(Object::toString).orElse(null));
            if (value != null) cache.put(cacheKey, value);
            return value;
        } catch (Exception e) {
            LOG.warnf("HTTP_API request failed url=%s: %s", url, e.getMessage());
            return null;
        }
    }

    /** URL template `{name}` → driverRow.get(name) URL-encode */
    private static String interpolate(String template, Map<String, Object> driverRow) {
        if (driverRow == null) return template;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end < 0) {
                    out.append(template.substring(i));
                    break;
                }
                String name = template.substring(i + 1, end);
                Object v = driverRow.get(name);
                if (v == null) throw new IllegalArgumentException("driver row 缺字段: " + name);
                out.append(URLEncoder.encode(v.toString(), StandardCharsets.UTF_8));
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** JSON dot-path 提取 (如 "data.price"); responsePath=null 时返完整 body string */
    private static Object extractValue(String body, String responsePath) {
        if (body == null) return null;
        if (responsePath == null || responsePath.isBlank()) return body;
        try {
            JsonNode node = MAPPER.readTree(body);
            for (String seg : responsePath.split("\\.")) {
                if (node == null) return null;
                node = node.get(seg);
            }
            if (node == null || node.isNull()) return null;
            if (node.isNumber()) return node.numberValue();
            if (node.isBoolean()) return node.booleanValue();
            return node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> parseHostSet(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 用于诊断 — 当前白名单 host (仅 SYSTEM_ADMIN 调试使用) */
    public List<String> debugAllowedHosts() {
        return List.copyOf(parseHostSet(allowedHostsConfig.orElse("")));
    }
}
