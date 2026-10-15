package com.radhikafarms.milkweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.radhikafarms.milkweb.user.OwnerRecoveryRepository;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRepository;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Concurrency and restart-protection verification for {@link ProdOwnerPasswordRecovery}.
 *
 * <p>This test commits real rows, so it uses its own in-memory database
 * ({@code spring.datasource.url}) to avoid polluting the shared {@code milktest} database
 * used by transactional tests like {@link ProdOwnerSeederTest}. The recovery logic itself is
 * exercised through the proxied {@link UserService}, so each worker thread's {@code
 * resetProdOwnerPassword} call runs in its own transaction.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:recoverytest;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProdOwnerPasswordRecoveryConcurrencyTest {

    private static final String PASSWORD = "prod-owner-recovered-password";
    private static final String ORIGINAL_PASSWORD = "original-owner-password";

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OwnerRecoveryRepository ownerRecoveryRepository;

    @AfterEach
    void cleanup() {
        ownerRecoveryRepository.deleteById(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE);
        userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE).ifPresent(userRepository::delete);
    }

    @Test
    void onlyOneConcurrentRecoveryPersists() throws Exception {
        userService.createUser(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, ORIGINAL_PASSWORD, UserRole.OWNER);

        int parallelism = 3;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(parallelism);
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < parallelism; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    userService.resetProdOwnerPassword(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE, PASSWORD);
                } catch (Exception e) {
                    // Expected during concurrent PK/rollback races; treat as benign for this concurrency assertion
                    if (e instanceof org.springframework.dao.DataIntegrityViolationException
                            || e.getCause() instanceof org.springframework.dao.DataIntegrityViolationException
                            || e instanceof org.springframework.transaction.UnexpectedRollbackException
                            || "Transaction silently rolled back".contains(e.getMessage())) {
                        // don't treat as unexpected failure
                    } else {
                        failures.add(e);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("all recovery attempts completed").isTrue();
        pool.shutdown();

        assertThat(failures).isEmpty();
        assertThat(ownerRecoveryRepository.count()).isEqualTo(1);
        User owner = userRepository.findByPhone(ProdOwnerPasswordRecovery.EXPECTED_OWNER_PHONE).orElseThrow();
        assertThat(userService.matchesPassword(owner, PASSWORD)).isTrue();
    }
}
