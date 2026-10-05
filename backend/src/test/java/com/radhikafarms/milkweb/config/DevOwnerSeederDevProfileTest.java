package com.radhikafarms.milkweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRepository;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the dev-profile wiring end to end: with {@code dev} active and the owner settings supplied as
 * properties, the bean exists and the owner is created while the context starts.
 *
 * <p>Uses the {@code application-dev.properties} datasource, so it never touches the test database used by
 * the other tests. The two assertions live in one test because {@code ApplicationRunner} runs once per
 * application context, not once per test method.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
		"milkweb.dev-owner.phone=" + DevOwnerSeederDevProfileTest.SEED_PHONE,
		"milkweb.dev-owner.password=" + DevOwnerSeederDevProfileTest.SEED_PASSWORD
})
class DevOwnerSeederDevProfileTest {

	static final String SEED_PHONE = "9000000002";
	static final String SEED_PASSWORD = "dev-owner-seeded-password";

	@Autowired
	private Environment environment;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserService userService;

	@AfterEach
	void removeSeededOwner() {
		userRepository.findByPhone(SEED_PHONE).ifPresent(userRepository::delete);
	}

	@Test
	void registersTheSeederAndCreatesTheOwnerOnStartup() {
		assertThat(environment.getActiveProfiles()).containsExactly("dev");
		assertThat(applicationContext.getBean("devOwnerSeeder")).isInstanceOf(DevOwnerSeeder.class);

		User owner = userRepository.findByPhone(SEED_PHONE).orElseThrow();
		assertThat(owner.getRole()).isEqualTo(UserRole.OWNER);
		assertThat(owner.getPasswordHash()).isNotEqualTo(SEED_PASSWORD).startsWith("$2");
		assertThat(userService.matchesPassword(owner, SEED_PASSWORD)).isTrue();
	}
}
