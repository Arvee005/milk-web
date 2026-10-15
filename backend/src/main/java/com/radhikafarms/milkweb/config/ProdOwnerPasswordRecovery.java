package com.radhikafarms.milkweb.config;

import com.radhikafarms.milkweb.user.OwnerRecoveryRepository;
import com.radhikafarms.milkweb.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One-time recovery of the production OWNER password, disabled by default.
 *
 * <p>The account is reset only when:
 * <ul>
 *   <li>the {@code prod} profile is the <em>only</em> active profile,</li>
 *   <li>{@code MILKWEB_PROD_OWNER_RECOVERY=true} is set in the environment (defaults to false), and</li>
 *   <li>the existing owner row for phone {@code 7668895474} has role {@code OWNER} and no prior
 *       recovery marker exists.</li>
 * </ul>
 *
 * <p>The new password is read from the existing {@code MILKWEB_PROD_OWNER_PASSWORD} variable
 * (bound to {@code milkweb.prod-owner.password}) and hashed with the application's existing BCrypt
 * {@code PasswordEncoder}. No second password variable is introduced.
 *
 * <p>The password and hash are never logged; only the phone, role, outcome, and counts are.
 */
@Component
@Profile("prod")
public class ProdOwnerPasswordRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdOwnerPasswordRecovery.class);

    static final String RECOVERY_ENABLED_PROPERTY = "milkweb.prod-owner.recovery-enabled";
    static final String PASSWORD_PROPERTY = "milkweb.prod-owner.password";
    static final String EXPECTED_OWNER_PHONE = "7668895474";
    private static final String PROD_PROFILE = "prod";
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserService userService;
    private final OwnerRecoveryRepository ownerRecoveryRepository;
    private final Environment environment;

    public ProdOwnerPasswordRecovery(UserService userService,
                                     OwnerRecoveryRepository ownerRecoveryRepository,
                                     Environment environment) {
        this.userService = userService;
        this.ownerRecoveryRepository = ownerRecoveryRepository;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isProdOnlyProfileActive()) {
            log.warn("Skipped owner password recovery because it only runs when '{}' is the only active profile. Active profiles: {}.",
                    PROD_PROFILE, activeProfileSummary());
            return;
        }

        boolean recoveryEnabled = environment.getProperty(RECOVERY_ENABLED_PROPERTY, Boolean.class, false);
        if (!recoveryEnabled) {
            log.info("Owner password recovery is disabled (set MILKWEB_PROD_OWNER_RECOVERY=true to enable). No action taken.");
            return;
        }

        String password = environment.getProperty(PASSWORD_PROPERTY);
        String problem = validatePassword(password);
        if (problem != null) {
            log.warn("Skipped owner password recovery: {}. Set MILKWEB_PROD_OWNER_PASSWORD (at least {} chars, at most {} UTF-8 bytes).",
                    problem, MIN_PASSWORD_LENGTH, MAX_PASSWORD_BYTES);
            return;
        }

        try {
            UserService.OwnerRecoveryOutcome outcome = userService.resetProdOwnerPassword(EXPECTED_OWNER_PHONE, password);
            logOutcome(outcome);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            if (ownerRecoveryRepository.existsById(EXPECTED_OWNER_PHONE)) {
                log.info("Skipped owner password recovery: a completed-reset marker already exists for phone {} (concurrent startup).",
                        EXPECTED_OWNER_PHONE);
                return;
            }
            throw ex;
        }
    }

    private void logOutcome(UserService.OwnerRecoveryOutcome outcome) {
        switch (outcome) {
            case RESET -> log.info("Recovered production owner password for phone {} (role OWNER).", EXPECTED_OWNER_PHONE);
            case ALREADY_RECOVERED -> log.info("Skipped owner password recovery: a completed-reset marker already exists for phone {}.",
                    EXPECTED_OWNER_PHONE);
            case NO_OWNER -> log.warn("Skipped owner password recovery: no OWNER account exists for phone {}.", EXPECTED_OWNER_PHONE);
            case NOT_OWNER -> log.warn("Skipped owner password recovery: the account for phone {} does not have the OWNER role.",
                    EXPECTED_OWNER_PHONE);
            default -> log.warn("Skipped owner password recovery for phone {} with outcome {}.", EXPECTED_OWNER_PHONE, outcome);
        }
    }

    private boolean isProdOnlyProfileActive() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length == 1 && PROD_PROFILE.equals(activeProfiles[0]);
    }

    private String activeProfileSummary() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length == 0 ? "none" : Arrays.toString(activeProfiles);
    }

    private String validatePassword(String password) {
        if (password == null || password.isBlank()) {
            return "MILKWEB_PROD_OWNER_PASSWORD is not set";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "MILKWEB_PROD_OWNER_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters";
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes > MAX_PASSWORD_BYTES) {
            return "MILKWEB_PROD_OWNER_PASSWORD must be at most " + MAX_PASSWORD_BYTES
                    + " UTF-8 bytes for BCrypt hashing (was " + passwordBytes + " bytes)";
        }
        return null;
    }
}
