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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors {@link DevOwnerSeederTest} but for the production seeder. The default test context does not
 * activate {@code prod}, so every test here runs against the same H2 test database the rest of the suite
 * uses and must confirm the bean is absent unless {@code prod} is the only active profile.
 */
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@SpringBootTest
 @Transactional
class ProdOwnerSeederTest {

    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();
    private static final List<String> PROD_ONLY = List.of("prod");
    private static final String PHONE = "9000000003";
    private static final String PASSWORD = "prod-owner-password";
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
    void isNotRegisteredWhenTheProdProfileIsNotActive() {
        assertThat(environment.getActiveProfiles()).doesNotContain("prod");
        assertThat(applicationContext.getBeanNamesForType(ProdOwnerSeeder.class)).isEmpty();
    }

    @Test
    void createsAnOwnerWithABcryptHashedPassword() {
        seeder(environment(PROD_ONLY, PHONE, PASSWORD)).run(NO_ARGS);

        User owner = userRepository.findByPhone(PHONE).orElseThrow();
        assertThat(owner.getRole()).isEqualTo(UserRole.OWNER);
        assertThat(owner.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(userService.matchesPassword(owner, PASSWORD)).isTrue();
    }

    @Test
    void doesNotCreateAnythingWhenOptInIsNotEnabled() {
        long usersBefore = userRepository.count();

        seeder(environment(PROD_ONLY, null, null)).run(NO_ARGS);
        seeder(environment(PROD_ONLY, "", "")).run(NO_ARGS);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void doesNotCreateAnythingWhenOnlyThePhoneIsSet() {
        long usersBefore = userRepository.count();

        seeder(environment(PROD_ONLY, PHONE, null)).run(NO_ARGS);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void doesNotCreateAnythingWhenOnlyThePasswordIsSet() {
        long usersBefore = userRepository.count();

        seeder(environment(PROD_ONLY, null, PASSWORD)).run(NO_ARGS);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void doesNotCreateAnythingWhenThePhoneIsNotTenDigits() {
        long usersBefore = userRepository.count();

        for (String invalidPhone : List.of("12345", "12345678901", "+919000000001", "90000A0001")) {
            seeder(environment(PROD_ONLY, invalidPhone, PASSWORD)).run(NO_ARGS);
        }

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void doesNotCreateAnythingWhenThePasswordIsTooShort() {
        long usersBefore = userRepository.count();

        seeder(environment(PROD_ONLY, PHONE, "short7c")).run(NO_ARGS);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void doesNotCreateAnythingWhenThePasswordExceedsTheBcryptByteLimit() {
        long usersBefore = userRepository.count();

        seeder(environment(PROD_ONLY, PHONE, "a".repeat(73))).run(NO_ARGS);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void doesNotCreateAnythingWhenAUnicodePasswordExceedsTheBcryptByteLimit() {
        String password = TWO_BYTE_CHAR.repeat(37);
        assertThat(password.length()).isLessThan(MAX_PASSWORD_BYTES);

        long usersBefore = userRepository.count();
        seeder(environment(PROD_ONLY, PHONE, password)).run(NO_ARGS);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void doesNotCreateAnythingWhenAUserAlreadyExists() {
        userService.createUser("9999999999", "another-account", UserRole.CUSTOMER);
        long usersBefore = userRepository.count();

        seeder(environment(PROD_ONLY, PHONE, PASSWORD)).run(NO_ARGS);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(userRepository.findByPhone(PHONE)).isNotPresent();
    }

    @Test
    void doesNotPromoteAnExistingCustomerToOwner() {
        User existing = userService.createUser(PHONE, "customer-password", UserRole.CUSTOMER);

        seeder(environment(PROD_ONLY, PHONE, PASSWORD)).run(NO_ARGS);

        User reloaded = userRepository.findByPhone(PHONE).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(existing.getId());
        assertThat(reloaded.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(userService.matchesPassword(reloaded, "customer-password")).isTrue();
    }

    @Test
    void doesNotCreateAnythingWhenProdIsNotTheOnlyActiveProfile() {
        long usersBefore = userRepository.count();

        for (List<String> activeProfiles : List.of(List.of("prod", "dev"), List.of("dev"), List.of("default"))) {
            seeder(environment(activeProfiles, PHONE, PASSWORD)).run(NO_ARGS);
        }

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void createsTheOwnerWhenAUnicodePasswordIsWithinTheBcryptByteLimit() {
        String password = TWO_BYTE_CHAR.repeat(30);

        seeder(environment(PROD_ONLY, PHONE, password)).run(NO_ARGS);

        User owner = userRepository.findByPhone(PHONE).orElseThrow();
        assertThat(owner.getRole()).isEqualTo(UserRole.OWNER);
        assertThat(userService.matchesPassword(owner, password)).isTrue();
    }

    @Test
    void doesNotDuplicateOnRepeatedInvocation() {
        ProdOwnerSeeder seeder = seeder(environment(PROD_ONLY, PHONE, PASSWORD));

        seeder.run(NO_ARGS);
        String hashAfterFirstRun = userRepository.findByPhone(PHONE).orElseThrow().getPasswordHash();
        seeder.run(NO_ARGS);

        assertThat(userRepository.findByPhone(PHONE)).isPresent();
        assertThat(userRepository.findByPhone(PHONE).orElseThrow().getPasswordHash()).isEqualTo(hashAfterFirstRun);
        assertThat(userRepository.findByPhone(PHONE).orElseThrow().getRole()).isEqualTo(UserRole.OWNER);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    private ProdOwnerSeeder seeder(Environment environment) {
        return new ProdOwnerSeeder(userService, environment);
    }

    private static Environment environment(List<String> activeProfiles, String phone, String password) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles.toArray(String[]::new));
        Map<String, Object> properties = new HashMap<>();
        if (phone != null) {
            properties.put(ProdOwnerSeeder.PHONE_PROPERTY, phone);
        }
        if (password != null) {
            properties.put(ProdOwnerSeeder.PASSWORD_PROPERTY, password);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("prod-owner-test", properties));
        return environment;
    }
}
