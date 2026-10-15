package com.radhikafarms.milkweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Limits for the public authentication endpoints, bound from {@code milkweb.rate-limit.*}.
 *
 * <p>Defaults are suitable for production; tests raise them so unrelated tests sharing a Spring context do
 * not trip each other's counters.
 */
@ConfigurationProperties(prefix = "milkweb.rate-limit")
public class RateLimitProperties {

    /** Failed logins allowed for one phone number per login window. */
    private int loginFailuresPerPhone = 5;
    /** Failed logins allowed from one client IP per login window. */
    private int loginFailuresPerIp = 30;
    private Duration loginWindow = Duration.ofMinutes(15);
    /** Registration attempts allowed from one client IP per registration window. */
    private int registrationsPerIp = 10;
    private Duration registrationWindow = Duration.ofHours(1);

    public int getLoginFailuresPerPhone() {
        return loginFailuresPerPhone;
    }

    public void setLoginFailuresPerPhone(int loginFailuresPerPhone) {
        this.loginFailuresPerPhone = loginFailuresPerPhone;
    }

    public int getLoginFailuresPerIp() {
        return loginFailuresPerIp;
    }

    public void setLoginFailuresPerIp(int loginFailuresPerIp) {
        this.loginFailuresPerIp = loginFailuresPerIp;
    }

    public Duration getLoginWindow() {
        return loginWindow;
    }

    public void setLoginWindow(Duration loginWindow) {
        this.loginWindow = loginWindow;
    }

    public int getRegistrationsPerIp() {
        return registrationsPerIp;
    }

    public void setRegistrationsPerIp(int registrationsPerIp) {
        this.registrationsPerIp = registrationsPerIp;
    }

    public Duration getRegistrationWindow() {
        return registrationWindow;
    }

    public void setRegistrationWindow(Duration registrationWindow) {
        this.registrationWindow = registrationWindow;
    }
}
