package com.radhikafarms.milkweb.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Best-effort client IP for rate limiting.
 *
 * <p>Behind Render's proxy, {@code getRemoteAddr()} is the proxy, so the first {@code X-Forwarded-For} entry
 * is used (Render places the real client IP first). A client may be able to forge that header, so it is only
 * used for limits whose bypass is tolerable: per-phone login limits do not depend on it, and registrations
 * stay PENDING until the owner approves them.
 */
final class ClientIpResolver {

    private ClientIpResolver() {
    }

    static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty() && first.length() <= 64) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
