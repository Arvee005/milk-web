package com.radhikafarms.milkweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRepository;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
@Transactional
class DevOwnerSeederTest {

	private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();
	private static final List<String> DEV_ONLY = List.of("dev");
	private static final String PHONE = "9000000001";
	private static final String PASSWORD = "dev-owner-password";
	private static final int MAX_PASSWORD_BYTES = 72;
	/** Two UTF-8 bytes per Java character. */
	private static final String TWO_BYTE_CHAR = "\u00e9";
	/** Four UTF-8 bytes per code point, written as a surrogate pair so this file stays ASCII. */
	private static final String FOUR_BYTE_CHAR = "\uD83D\uDE00";

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private Environment environment;

	@Test
	void isNotRegisteredWhenTheDevProfileIsNotActive() {
		assertThat(environment.getActiveProfiles()).doesNotContain("dev");
		assertThat(applicationContext.getBeanNamesForType(DevOwnerSeeder.class)).isEmpty();
	}

	@Test
	void createsAnOwnerWithABcryptHashedPassword() {
		seeder(environment(DEV_ONLY, PHONE, PASSWORD)).run(NO_ARGS);

		User owner = userRepository.findByPhone(PHONE).orElseThrow();
		assertThat(owner.getRole()).isEqualTo(UserRole.OWNER);
		assertThat(owner.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
		assertThat(userService.matchesPassword(owner, PASSWORD)).isTrue();
	}

	@Test
	void trimsWhitespaceAroundThePhone() {
		seeder(environment(DEV_ONLY, "  " + PHONE + " ", PASSWORD)).run(NO_ARGS);

		assertThat(userRepository.findByPhone(PHONE)).isPresent();
	}

	@Test
	void createsNothingOnASecondStartup() {
		DevOwnerSeeder seeder = seeder(environment(DEV_ONLY, PHONE, PASSWORD));

		seeder.run(NO_ARGS);
		String hashAfterFirstRun = userRepository.findByPhone(PHONE).orElseThrow().getPasswordHash();
		seeder.run(NO_ARGS);

		assertThat(userRepository.findByPhone(PHONE)).isPresent();
		assertThat(userRepository.findByPhone(PHONE).orElseThrow().getPasswordHash()).isEqualTo(hashAfterFirstRun);
		assertThat(userRepository.findByPhone(PHONE).orElseThrow().getRole()).isEqualTo(UserRole.OWNER);
	}

	@Test
	void keepsThePasswordOfAnExistingOwner() {
		User existing = userService.createUser(PHONE, "original-password", UserRole.OWNER);

		seeder(environment(DEV_ONLY, PHONE, PASSWORD)).run(NO_ARGS);

		User reloaded = userRepository.findByPhone(PHONE).orElseThrow();
		assertThat(reloaded.getId()).isEqualTo(existing.getId());
		assertThat(userService.matchesPassword(reloaded, "original-password")).isTrue();
		assertThat(userService.matchesPassword(reloaded, PASSWORD)).isFalse();
	}

	@Test
	void doesNotPromoteAnExistingCustomerToOwner() {
		User existing = userService.createUser(PHONE, "customer-password", UserRole.CUSTOMER);

		seeder(environment(DEV_ONLY, PHONE, PASSWORD)).run(NO_ARGS);

		User reloaded = userRepository.findByPhone(PHONE).orElseThrow();
		assertThat(reloaded.getId()).isEqualTo(existing.getId());
		assertThat(reloaded.getRole()).isEqualTo(UserRole.CUSTOMER);
		assertThat(userService.matchesPassword(reloaded, "customer-password")).isTrue();
	}

	@Test
	void skipsWhenThePhoneIsMissing() {
		long usersBefore = userRepository.count();

		seeder(environment(DEV_ONLY, null, PASSWORD)).run(NO_ARGS);
		seeder(environment(DEV_ONLY, "", PASSWORD)).run(NO_ARGS);

		assertThat(userRepository.count()).isEqualTo(usersBefore);
	}

	@Test
	void skipsWhenThePhoneIsNotTenDigits() {
		long usersBefore = userRepository.count();

		for (String invalidPhone : List.of("12345", "12345678901", "+919000000001", "90000A0001")) {
			seeder(environment(DEV_ONLY, invalidPhone, PASSWORD)).run(NO_ARGS);
		}

		assertThat(userRepository.count()).isEqualTo(usersBefore);
	}

	@Test
	void skipsWhenThePasswordIsMissing() {
		long usersBefore = userRepository.count();

		seeder(environment(DEV_ONLY, PHONE, null)).run(NO_ARGS);
		seeder(environment(DEV_ONLY, PHONE, "   ")).run(NO_ARGS);

		assertThat(userRepository.count()).isEqualTo(usersBefore);
	}

	@Test
	void skipsWhenThePasswordIsShorterThanEightCharacters() {
		long usersBefore = userRepository.count();

		seeder(environment(DEV_ONLY, PHONE, "short7c")).run(NO_ARGS);

		assertThat(userRepository.count()).isEqualTo(usersBefore);
	}

	@Test
	void skipsWhenDevIsNotTheOnlyActiveProfile() {
		long usersBefore = userRepository.count();

		for (List<String> activeProfiles : List.of(List.of("dev", "prod"), List.of("prod"), List.of("default"))) {
			seeder(environment(activeProfiles, PHONE, PASSWORD)).run(NO_ARGS);
		}

		assertThat(userRepository.count()).isEqualTo(usersBefore);
	}

	@Test
	void skipsWhenThePasswordExceedsTheBcryptByteLimit() {
		long usersBefore = userRepository.count();

		seeder(environment(DEV_ONLY, PHONE, "a".repeat(73))).run(NO_ARGS);

		assertThat(userRepository.count()).isEqualTo(usersBefore);
	}

	@Test
	void skipsWhenAUnicodePasswordExceedsTheBcryptByteLimitDespiteItsCharacterCount() {
		// 37 characters but 74 UTF-8 bytes: a character-count limit would have accepted this.
		String password = TWO_BYTE_CHAR.repeat(37);
		assertThat(password.length()).isLessThan(MAX_PASSWORD_BYTES);

		long usersBefore = userRepository.count();
		seeder(environment(DEV_ONLY, PHONE, password)).run(NO_ARGS);

		assertThat(userRepository.count()).isEqualTo(usersBefore);
	}

	@Test
	void skipsWhenAFourByteCharacterPasswordExceedsTheBcryptByteLimit() {
		// 19 code points (38 UTF-16 units) but 76 UTF-8 bytes.
		String password = FOUR_BYTE_CHAR.repeat(19);

		long usersBefore = userRepository.count();
		seeder(environment(DEV_ONLY, PHONE, password)).run(NO_ARGS);

		assertThat(userRepository.count()).isEqualTo(usersBefore);
	}

	@Test
	void createsTheOwnerWhenAUnicodePasswordIsWithinTheBcryptByteLimit() {
		// 30 characters and 60 UTF-8 bytes: accepted, so the byte check does not over-reject.
		String password = TWO_BYTE_CHAR.repeat(30);

		seeder(environment(DEV_ONLY, PHONE, password)).run(NO_ARGS);

		User owner = userRepository.findByPhone(PHONE).orElseThrow();
		assertThat(owner.getRole()).isEqualTo(UserRole.OWNER);
		assertThat(userService.matchesPassword(owner, password)).isTrue();
	}

	private DevOwnerSeeder seeder(Environment environment) {
		return new DevOwnerSeeder(userService, environment);
	}

	private static Environment environment(List<String> activeProfiles, String phone, String password) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.setActiveProfiles(activeProfiles.toArray(String[]::new));
		Map<String, Object> properties = new HashMap<>();
		if (phone != null) {
			properties.put(DevOwnerSeeder.PHONE_PROPERTY, phone);
		}
		if (password != null) {
			properties.put(DevOwnerSeeder.PASSWORD_PROPERTY, password);
		}
		environment.getPropertySources().addFirst(new MapPropertySource("dev-owner-test", properties));
		return environment;
	}
}
