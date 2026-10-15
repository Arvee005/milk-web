package com.radhikafarms.milkweb.customer;

import com.radhikafarms.milkweb.customer.exception.PhoneAlreadyRegisteredException;
import com.radhikafarms.milkweb.user.UserRepository;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent self-registrations for one phone must produce exactly one account, and every losing request
 * must fail as a clean {@link PhoneAlreadyRegisteredException} (409) rather than a raw constraint error.
 *
 * <p>Commits real rows, so it uses its own in-memory database. H2 does not prove PostgreSQL locking
 * behavior; the unique constraint {@code uk_users_phone} is the guarantee on both.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:registrationtest;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CustomerRegistrationConcurrencyTest {

    private static final String PHONE = "9876500000";

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        customerRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void onlyOneConcurrentRegistrationForAPhoneSucceeds() throws Exception {
        int parallelism = 4;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(parallelism);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < parallelism; i++) {
            int attempt = i;
            pool.submit(() -> {
                try {
                    start.await();
                    customerService.registerCustomer(PHONE, "password-" + attempt, "Customer " + attempt, null);
                    successes.incrementAndGet();
                } catch (PhoneAlreadyRegisteredException ex) {
                    conflicts.incrementAndGet();
                } catch (Throwable ex) {
                    unexpected.add(ex);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(parallelism - 1);
        assertThat(userRepository.findByPhone(PHONE)).isPresent();
        assertThat(customerRepository.count()).isEqualTo(1);
    }
}
