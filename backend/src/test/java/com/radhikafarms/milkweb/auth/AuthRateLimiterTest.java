package com.radhikafarms.milkweb.auth;

import com.radhikafarms.milkweb.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    private MutableClock clock;
    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLoginFailuresPerPhone(3);
        properties.setLoginFailuresPerIp(5);
        properties.setLoginWindow(Duration.ofMinutes(15));
        properties.setRegistrationsPerIp(2);
        properties.setRegistrationWindow(Duration.ofHours(1));
        clock = new MutableClock(Instant.parse("2026-10-15T06:00:00Z"));
        limiter = new AuthRateLimiter(properties, clock);
    }

    @Test
    void blocksAPhoneAfterTooManyFailuresFromAnyIps() {
        limiter.recordLoginFailure("9123456780", "1.1.1.1");
        limiter.recordLoginFailure("9123456780", "2.2.2.2");
        limiter.recordLoginFailure("9123456780", "3.3.3.3");

        assertThatThrownBy(() -> limiter.checkLogin("9123456780", "4.4.4.4"))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessage("Too many attempts. Please wait 15 minutes and try again.");
        assertThatCode(() -> limiter.checkLogin("9000000001", "4.4.4.4")).doesNotThrowAnyException();
    }

    @Test
    void unblocksWhenTheWindowEnds() {
        for (int i = 0; i < 3; i++) {
            limiter.recordLoginFailure("9123456780", "1.1.1.1");
        }
        clock.advance(Duration.ofMinutes(14));
        assertThatThrownBy(() -> limiter.checkLogin("9123456780", "1.1.1.1"))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(1)));

        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.checkLogin("9123456780", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void successfulLoginClearsThePhoneCounter() {
        limiter.recordLoginFailure("9123456780", "1.1.1.1");
        limiter.recordLoginFailure("9123456780", "1.1.1.1");
        limiter.recordLoginSuccess("9123456780");
        limiter.recordLoginFailure("9123456780", "1.1.1.1");
        limiter.recordLoginFailure("9123456780", "1.1.1.1");

        assertThatCode(() -> limiter.checkLogin("9123456780", "9.9.9.9")).doesNotThrowAnyException();
    }

    @Test
    void blocksAnIpGuessingAcrossManyPhones() {
        for (int i = 0; i < 5; i++) {
            limiter.recordLoginFailure("912345678" + i, "1.1.1.1");
        }

        assertThatThrownBy(() -> limiter.checkLogin("9000000001", "1.1.1.1"))
                .isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> limiter.checkLogin("9000000001", "2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void limitsRegistrationsPerIp() {
        limiter.checkAndRecordRegistration("1.1.1.1");
        limiter.checkAndRecordRegistration("1.1.1.1");

        assertThatThrownBy(() -> limiter.checkAndRecordRegistration("1.1.1.1"))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessage("Too many attempts. Please wait 60 minutes and try again.");
        assertThatCode(() -> limiter.checkAndRecordRegistration("2.2.2.2")).doesNotThrowAnyException();

        clock.advance(Duration.ofHours(1));
        assertThatCode(() -> limiter.checkAndRecordRegistration("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void stopsTrackingNewKeysWhenFullInsteadOfGrowingWithoutBound() {
        for (int i = 0; i < AuthRateLimiter.MAX_TRACKED_KEYS; i++) {
            limiter.checkAndRecordRegistration("ip-" + i);
        }
        // Full and nothing expired: a new key is not tracked, so it is never blocked.
        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> limiter.checkAndRecordRegistration("new-ip")).doesNotThrowAnyException();
        }
        // Once old windows expire they are pruned and new keys are tracked again.
        clock.advance(Duration.ofHours(1));
        limiter.checkAndRecordRegistration("new-ip");
        limiter.checkAndRecordRegistration("new-ip");
        assertThatThrownBy(() -> limiter.checkAndRecordRegistration("new-ip"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
