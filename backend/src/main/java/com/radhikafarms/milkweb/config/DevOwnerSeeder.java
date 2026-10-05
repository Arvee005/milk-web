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
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Creates the initial OWNER account for local development so a fresh H2 database can be logged into
 * without a manual SQL insert.
 *
 * <p>The account is only created when the {@code dev} profile is the <em>only</em> active profile and both
 * {@code milkweb.dev-owner.phone} and {@code milkweb.dev-owner.password} were supplied through the
 * environment. Nothing is created from committed defaults: when configuration is missing or invalid the
 * seeder logs an actionable message and skips.
 *
 * <p>The raw password is passed to {@link UserService#createUser} so it is hashed with the application's
 * existing BCrypt encoder. The password value is never logged; only its length may appear in a validation
 * warning.
 *
 * <p>The accepted password rules mirror the existing customer-provisioning contract
 * ({@code OwnerCreateCustomerRequest} requires at least 8 characters) and additionally reject anything BCrypt
 * cannot hash, which is more than 72 bytes. Length is measured in UTF-8 bytes, not Java characters, because a
 * character count understates a multi-byte password.
 */
@Component
@Profile("dev")
public class DevOwnerSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DevOwnerSeeder.class);

	static final String PHONE_PROPERTY = "milkweb.dev-owner.phone";
	static final String PASSWORD_PROPERTY = "milkweb.dev-owner.password";
	private static final String DEV_PROFILE = "dev";
	private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{10}");
	private static final int MIN_PASSWORD_LENGTH = 8;
	private static final int MAX_PASSWORD_BYTES = 72;
	private static final String SETUP_HINT = "Export MILKWEB_DEV_OWNER_PHONE (10 digits) and "
			+ "MILKWEB_DEV_OWNER_PASSWORD (at least " + MIN_PASSWORD_LENGTH + " characters, at most "
			+ MAX_PASSWORD_BYTES + " UTF-8 bytes), then start the backend with --spring.profiles.active=dev. "
			+ "Never reuse these values outside local development.";

	private final UserService userService;
	private final Environment environment;

	public DevOwnerSeeder(UserService userService, Environment environment) {
		this.userService = userService;
		this.environment = environment;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!isDevOnlyProfileActive()) {
			log.warn("Skipped development owner seeding because it only runs when '{}' is the only active profile. "
					+ "Active profiles: {}.", DEV_PROFILE, activeProfileSummary());
			return;
		}

		String phone = trimToNull(environment.getProperty(PHONE_PROPERTY));
		String password = environment.getProperty(PASSWORD_PROPERTY);

		String problem = validate(phone, password);
		if (problem != null) {
			log.warn("Skipped development owner seeding: {}. {}", problem, SETUP_HINT);
			return;
		}

		Optional<User> existing = userService.findByPhone(phone);
		if (existing.isPresent()) {
			handleExistingUser(existing.get(), phone);
			return;
		}

		createOwner(phone, password);
	}

	private boolean isDevOnlyProfileActive() {
		String[] activeProfiles = environment.getActiveProfiles();
		return activeProfiles.length == 1 && DEV_PROFILE.equals(activeProfiles[0]);
	}

	private String activeProfileSummary() {
		String[] activeProfiles = environment.getActiveProfiles();
		return activeProfiles.length == 0 ? "none" : Arrays.toString(activeProfiles);
	}

	private String validate(String phone, String password) {
		if (phone == null) {
			return "MILKWEB_DEV_OWNER_PHONE is not set";
		}
		if (!PHONE_PATTERN.matcher(phone).matches()) {
			return "MILKWEB_DEV_OWNER_PHONE must be exactly 10 digits, but was '" + phone + "'";
		}
		if (password == null || password.isBlank()) {
			return "MILKWEB_DEV_OWNER_PASSWORD is not set";
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			return "MILKWEB_DEV_OWNER_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters, but was "
					+ password.length();
		}
		int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
		if (passwordBytes > MAX_PASSWORD_BYTES) {
			return "MILKWEB_DEV_OWNER_PASSWORD must be at most " + MAX_PASSWORD_BYTES
					+ " bytes for BCrypt hashing, but was " + passwordBytes + " bytes";
		}
		return null;
	}

	private void handleExistingUser(User user, String phone) {
		if (user.getRole() == UserRole.OWNER) {
			log.info("Development owner already exists for phone {}; leaving its password and role unchanged.", phone);
			return;
		}
		log.warn("Skipped development owner seeding: phone {} already exists with role {}. Refusing to change an "
				+ "existing account. Use a different MILKWEB_DEV_OWNER_PHONE or delete that account first.", phone,
				user.getRole());
	}

	private void createOwner(String phone, String password) {
		try {
			User created = userService.createUser(phone, password, UserRole.OWNER);
			log.info("Created development owner {} for phone {}. Sign in with that phone and the password from "
					+ "MILKWEB_DEV_OWNER_PASSWORD.", created.getId(), phone);
		} catch (IllegalArgumentException ex) {
			// UserService rejects a phone that is already registered, which is the only failure expected here:
			// the password was already validated against the BCrypt byte limit. The exception message is
			// deliberately not logged because it originates in another class and could in principle quote the
			// password, so only the exception type is reported.
			//
			// A database-level duplicate on uk_users_phone raises DataIntegrityViolationException, which is
			// intentionally not caught: it cannot be distinguished from other constraint failures without
			// hiding a real defect, so that case fails startup loudly instead.
			log.warn("Skipped development owner seeding for phone {}: the account already exists [{}].",
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
