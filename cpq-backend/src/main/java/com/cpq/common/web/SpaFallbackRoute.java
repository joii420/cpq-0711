package com.cpq.common.web;

import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * SPA HTML5-history fallback. The Quarkus static-resources handler serves files
 * from META-INF/resources (where the frontend dist is bundled at build time).
 * When the browser requests a client-side route like /quotations/123, the static
 * handler 404s and calls rc.next(); this route then rewrites the request to
 * /index.html so React Router can take over.
 *
 * Excluded: anything under /api/ or /q/ (REST + Quarkus management), and any
 * path whose last segment contains a dot (treated as an asset to avoid masking
 * a genuine 404 for /assets/foo.js).
 */
@ApplicationScoped
public class SpaFallbackRoute {

    @Route(path = "/*", methods = HttpMethod.GET, order = 9999)
    void fallback(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (path.startsWith("/api/") || path.startsWith("/q/")) {
            rc.next();
            return;
        }
        int lastSlash = path.lastIndexOf('/');
        String lastSeg = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (lastSeg.contains(".")) {
            rc.next();
            return;
        }
        rc.reroute("/index.html");
    }
}
