package com.radhikafarms.milkweb.config;

import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Creates the initial OWNER account for a fresh production deployment.
 *
 * <p>The account is created only when the {@code prod} profile is the <em>only</em> active profile, the
 * table is empty, and both {@code milkweb.prod-owner.phone} and {@code milkweb.prod-owner.password} are
 * supplied through the environment. Nothing is created from committed defaults: when configuration is
 * missing or invalid the seeder logs an actionable message and skips.
 *
 * <p>The raw password is passed to {@link UserService#createUserResilient(String, String, UserRole)} so
 * it is hashed with the application's existing BCrypt encoder. The password value is never logged; only
 * its length may appear in a validation warning.
 *
 * <p>The accepted password rules mirror the existing customer-provisioning contract
 * ({@code OwnerCreateCustomerRequest} requires at least 8 characters) and additionally reject anything
 * BCrypt cannot hash, which is more than 72 bytes. Length is measured in UTF-8 bytes, not Java
 * characters, because a character count understates a multi-byte password.
 *
 * <p>Seeding is opt-in: the feature is disabled unless {@code MILKWEB_PROD_OWNER_PHONE} and
 * {@code MILKWEB_PROD_OWNER_PASSWORD} are both set. An empty table is required so this can never overwrite
 * or duplicate an existing account.
 */
@Component
@Profile("prod")
public class ProdOwnerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdOwnerSeeder.class);

    static final String PHONE_PROPERTY = "milkweb.prod-owner.phone";
    static final String PASSWORD_PROPERTY = "milkweb.prod-owner.password";
    private static final String PROD_PROFILE = "prod";
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{10}");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final String SETUP_HINT = "Export MILKWEB_PROD_OWNER_PHONE (10 digits) and "
            + "MILKWEB_PROD_OWNER_PASSWORD (at least " + MIN_PASSWORD_LENGTH + " characters, at most "
            + MAX_PASSWORD_BYTES + " UTF-8 bytes), then start the backend with --spring.profiles.active=prod. "
            + "Never reuse these values outside production.";

    private final UserService userService;
    private final Environment environment;

    public ProdOwnerSeeder(UserService userService, Environment environment) {
        this.userService = userService;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isProdOnlyProfileActive()) {
            log.warn("Skipped production owner seeding because it only runs when '{}' is the only active "
                    + "profile. Active profiles: {}.", PROD_PROFILE, activeProfileSummary());
            return;
        }

        String phone = trimToNull(environment.getProperty(PHONE_PROPERTY));
        String password = environment.getProperty(PASSWORD_PROPERTY);

        String problem = validate(phone, password);
        if (problem != null) {
            log.warn("Skipped production owner seeding: {}. {}", problem, SETUP_HINT);
            return;
        }

        if (userService.countUsers() > 0) {
            log.info("Skipped production owner seeding: the users table already contains at least one "
                    + "account, so the initial OWNER is left to be created by the operator.");
            return;
        }

        createOwner(phone, password);
    }

    private boolean isProdOnlyProfileActive() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length == 1 && PROD_PROFILE.equals(activeProfiles[0]);
    }

    private String activeProfileSummary() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length == 0 ? "none" : java.util.Arrays.toString(activeProfiles);
    }

    private String validate(String phone, String password) {
        if (phone == null) {
            return "MILKWEB_PROD_OWNER_PHONE is not set";
        }
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            return "MILKWEB_PROD_OWNER_PHONE must be exactly 10 digits, but was '" + phone + "'";
        }
        if (password == null || password.isBlank()) {
            return "MILKWEB_PROD_OWNER_PASSWORD is not set";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "MILKWEB_PROD_OWNER_PASSWORD must be at least " + MIN_PASSWORD_LENGTH
                    + " characters, but was " + password.length() + " characters";
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes > MAX_PASSWORD_BYTES) {
            return "MILKWEB_PROD_OWNER_PASSWORD must be at most " + MAX_PASSWORD_BYTES
                    + " bytes for BCrypt hashing, but was " + passwordBytes + " bytes";
        }
        return null;
    }

    private void createOwner(String phone, String password) {
        try {
            User created = userService.createUserResilient(phone, password, UserRole.OWNER);
            log.info("Created production owner {} for phone {}. Sign in with that phone and the password "
                    + "from MILKWEB_PROD_OWNER_PASSWORD.", created.getId(), phone);
        } catch (IllegalArgumentException ex) {
            // UserService rejects a phone that is already registered, which is the only expected failure
            // here: the password was already validated against the BCrypt byte limit. The exception message
            // is deliberately not logged because it originates in another class and could in principle quote
            // the password, so only the exception type is reported.
            log.warn("Skipped production owner seeding for phone {}: the account already exists [{}].",
                    phone, ex.getClass().getSimpleName());
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}