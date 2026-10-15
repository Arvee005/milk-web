package com.radhikafarms.milkweb.auth;

import com.radhikafarms.milkweb.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window attempt counters for the public login and registration endpoints.
 *
 * <p>Login is limited by phone number (stops password guessing against one account, whatever IP the
 * attacker uses) and by client IP (slows one machine guessing across many accounts). Only failures count,
 * and a successful login clears that phone's counter. Registration is limited by client IP.
 *
 * <p>State is in memory, so it resets on restart and is per instance. That is enough for the current
 * single-instance deployment; several instances would need a shared store. The number of tracked keys is
 * capped so a flood of distinct phones or spoofed IPs cannot exhaust memory: when the cap is reached after
 * pruning, new keys are not tracked (fail open) and a warning is logged.
 */
@Component
public class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);
    static final int MAX_TRACKED_KEYS = 50_000;

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimiter(RateLimitProperties properties, Clock businessClock) {
        this.properties = properties;
        this.clock = businessClock;
    }

    /** Throws if this phone or IP has too many recent failed logins. Does not count an attempt. */
    public void checkLogin(String phone, String clientIp) {
        Duration window = properties.getLoginWindow();
        requireBelow(phoneKey(phone), properties.getLoginFailuresPerPhone(), window);
        requireBelow(loginIpKey(clientIp), properties.getLoginFailuresPerIp(), window);
    }

    public void recordLoginFailure(String phone, String clientIp) {
        Duration window = properties.getLoginWindow();
        increment(phoneKey(phone), window);
        increment(loginIpKey(clientIp), window);
    }

    public void recordLoginSuccess(String phone) {
        windows.remove(phoneKey(phone));
    }

    /** Counts a registration attempt from this IP, throwing once the limit is exceeded. */
    public void checkAndRecordRegistration(String clientIp) {
        String key = "register-ip:" + clientIp;
        Duration window = properties.getRegistrationWindow();
        requireBelow(key, properties.getRegistrationsPerIp(), window);
        increment(key, window);
    }

    private void requireBelow(String key, int limit, Duration window) {
        Window current = windows.get(key);
        Instant now = clock.instant();
        if (current != null && !current.isExpired(now) && current.count() >= limit) {
            throw new TooManyAttemptsException(Duration.between(now, current.resetsAt()));
        }
    }

    private void increment(String key, Duration window) {
        Instant now = clock.instant();
        if (!windows.containsKey(key) && windows.size() >= MAX_TRACKED_KEYS) {
            windows.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            if (windows.size() >= MAX_TRACKED_KEYS) {
                log.warn("Authentication rate limiter is tracking {} keys; not tracking new keys until some expire.",
                        windows.size());
                return;
            }
        }
        windows.compute(key, (ignored, current) -> current == null || current.isExpired(now)
                ? new Window(1, now.plus(window))
                : new Window(current.count() + 1, current.resetsAt()));
    }

    private static String phoneKey(String phone) {
        return "login-phone:" + phone;
    }

    private static String loginIpKey(String clientIp) {
        return "login-ip:" + clientIp;
    }

    private record Window(int count, Instant resetsAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(resetsAt);
        }
    }
}
