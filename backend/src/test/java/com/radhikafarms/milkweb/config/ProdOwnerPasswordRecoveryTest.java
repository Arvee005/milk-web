package com.radhikafarms.milkweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.radhikafarms.milkweb.user.OwnerRecoveryRepository;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRepository;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the one-time production OWNER password recovery runner.
 *
 * <p>The default test context does not activate {@code prod}, so the
 * {@link ProdOwnerPasswordRecovery} bean is absent unless {@code prod} is the only active
 * profile. The runner is constructed directly (mirroring {@link ProdOwnerSeederTest}) with a
 * hand-built {@link Environment} so the gating logic is exercised without a production database.
 *
 * <p>These tests run on a dedicated in-memory database (not the shared {@code milktest} used by
 * other test classes) so they never observe or leak committed state into the shared database.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:recoverytest;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class ProdOwnerPasswordRecoveryTest {

    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();
    private static final List<String> PROD_ONLY = List.of("prod");
    private static final String PASSWORD = "prod-owner-recovered-password";
    private static final String OLD_PASSWORD = "original-owner-password";

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OwnerRecoveryRepository ownerRecoveryRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void recoversTheOwnerPasswordWithABcryptHashMatchingTheNewPassword() {
        userService.createUser(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, OLD_PASSWORD, UserRole.OWNER);

        recovery(environment(PROD_ONLY, true, PASSWORD)).run(NO_ARGS);

        User reloaded = userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(userService.matchesPassword(reloaded, PASSWORD)).isTrue();
        assertThat(userService.matchesPassword(reloaded, OLD_PASSWORD)).isFalse();
        assertThat(ownerRecoveryRepository.existsById(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE)).isTrue();
    }

    @Test
    void isNotRegisteredWhenTheProdProfileIsNotActive() {
        assertThat(applicationContext.getBeanNamesForType(ProdOwnerPasswordRecovery.class)).isEmpty();
    }

    @Test
    void doesNotRecoverWhenRecoveryIsDisabledByDefault() {
        userService.createUser(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, OLD_PASSWORD, UserRole.OWNER);

        recovery(environment(PROD_ONLY, false, PASSWORD)).run(NO_ARGS);

        User reloaded = userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE).orElseThrow();
        assertThat(userService.matchesPassword(reloaded, OLD_PASSWORD)).isTrue();
        assertThat(ownerRecoveryRepository.existsById(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE)).isFalse();
    }

    @Test
    void doesNotRecoverWhenTheOwnerDoesNotExist() {
        assertThat(ownerRecoveryRepository.existsById(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE)).isFalse();

        UserService.OwnerRecoveryOutcome outcome = userService
                .resetProdOwnerPassword(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, PASSWORD);

        assertThat(outcome).isEqualTo(UserService.OwnerRecoveryOutcome.NO_OWNER);
        assertThat(ownerRecoveryRepository.existsById(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE)).isFalse();
    }

    @Test
    void doesNotRecoverAnAccountThatIsNotAnOwner() {
        userService.createUser(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, OLD_PASSWORD, UserRole.CUSTOMER);

        UserService.OwnerRecoveryOutcome outcome = userService
                .resetProdOwnerPassword(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, PASSWORD);

        assertThat(outcome).isEqualTo(UserService.OwnerRecoveryOutcome.NOT_OWNER);
        User reloaded = userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(userService.matchesPassword(reloaded, OLD_PASSWORD)).isTrue();
        assertThat(ownerRecoveryRepository.existsById(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE)).isFalse();
    }

    @Test
    void doesNotRecoverWithAnInvalidPassword() {
        userService.createUser(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, OLD_PASSWORD, UserRole.OWNER);
        long markersBefore = ownerRecoveryRepository.count();

        recovery(environment(PROD_ONLY, true, "short7c")).run(NO_ARGS);

        User reloaded = userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE).orElseThrow();
        assertThat(userService.matchesPassword(reloaded, OLD_PASSWORD)).isTrue();
        assertThat(ownerRecoveryRepository.count()).isEqualTo(markersBefore);
    }

    @Test
    void doesNotRecoverWhenProdIsNotTheOnlyActiveProfile() {
        userService.createUser(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, OLD_PASSWORD, UserRole.OWNER);
        long markersBefore = ownerRecoveryRepository.count();

        for (List<String> activeProfiles : List.of(List.of("prod", "dev"), List.of("dev"), List.of("default"))) {
            recovery(environment(activeProfiles, true, PASSWORD)).run(NO_ARGS);
        }

        User reloaded = userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE).orElseThrow();
        assertThat(userService.matchesPassword(reloaded, OLD_PASSWORD)).isTrue();
        assertThat(ownerRecoveryRepository.count()).isEqualTo(markersBefore);
    }

    @Test
    void doesNotRecoverTwiceOnRepeatedExecution() {
        userService.createUser(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, OLD_PASSWORD, UserRole.OWNER);
        ProdOwnerPasswordRecovery recovery = recovery(environment(PROD_ONLY, true, PASSWORD));

        recovery.run(NO_ARGS);
        String hashAfterFirstRun = userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE)
                .orElseThrow().getPasswordHash();

        recovery.run(NO_ARGS);

        User reloaded = userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isEqualTo(hashAfterFirstRun);
        assertThat(userService.matchesPassword(reloaded, PASSWORD)).isTrue();
        assertThat(userService.matchesPassword(reloaded, OLD_PASSWORD)).isFalse();
        assertThat(ownerRecoveryRepository.count()).isEqualTo(1);
    }

    @Test
    void doesNotModifyUnrelatedAccountsWhenOwnerRecoveryIsSkipped() {
        User unrelatedCustomer = userService.createUser("8000000001", "customer-password", UserRole.CUSTOMER);

        UserService.OwnerRecoveryOutcome outcome = userService.resetProdOwnerPassword("9000000002", PASSWORD);

        assertThat(outcome).isEqualTo(UserService.OwnerRecoveryOutcome.NO_OWNER);
        User reloaded = userRepository.findByPhone("8000000001").orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(unrelatedCustomer.getId());
        assertThat(userService.matchesPassword(reloaded, "customer-password")).isTrue();
        assertThat(reloaded.getPasswordHash()).isEqualTo(unrelatedCustomer.getPasswordHash());
        assertThat(ownerRecoveryRepository.existsById("9000000002")).isFalse();
    }

    private ProdOwnerPasswordRecovery recovery(Environment environment) {
        return new ProdOwnerPasswordRecovery(userService, ownerRecoveryRepository, environment);
    }

    private static Environment environment(List<String> activeProfiles, boolean recoveryEnabled, String password) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles.toArray(String[]::new));
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProdOwnerPasswordRecovery.RECOVERY_ENABLED_PROPERTY, String.valueOf(recoveryEnabled));
        if (password != null) {
            properties.put(ProdOwnerPasswordRecovery.PASSWORD_PROPERTY, password);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("owner-recovery-test", properties));
        return environment;
    
}
}