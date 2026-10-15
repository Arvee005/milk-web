package com.radhikafarms.milkweb.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final OwnerRecoveryRepository ownerRecoveryRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, OwnerRecoveryRepository ownerRecoveryRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.ownerRecoveryRepository = ownerRecoveryRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User createUser(String phone, String rawPassword, UserRole role) {
        if (userRepository.findByPhone(phone).isPresent()) {
            throw new IllegalArgumentException("Phone number already registered: " + phone);
        }

        User user = new User();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        return userRepository.save(user);
    }

    public Optional<User> findByPhone(String phone) {
        return userRepository.findByPhone(phone);
    }

    public boolean matchesPassword(User user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    /**
     * Creates a user only if no user with that phone exists, tolerating the
     * concurrent-startup race where two replicas both observe an empty table.
     *
     * <p>A duplicate phone raises {@link DataIntegrityViolationException}, which
     * the caller treats as "someone else created it first" rather than as a
     * defect. The raw password is never included in the exception message.
     */
    @Transactional
    public User createUserResilient(String phone, String rawPassword, UserRole role) {
        if (userRepository.findByPhone(phone).isPresent()) {
            throw new IllegalArgumentException("Phone number already registered: " + phone);
        }
        User user = new User();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            return userRepository.findByPhone(phone)
                    .orElseThrow(() -> ex);
        }
    }

    public long countUsers() {
        return userRepository.count();
    }

    public enum OwnerRecoveryOutcome {
        RESET,
        ALREADY_RECOVERED,
        NO_OWNER,
        NOT_OWNER
    }

    /**
     * Resets the password hash for the OWNER account with the given phone, exactly once.
     *
     * <p>The recovery marker is checked and written in the same transaction as the password update, so a
     * committed marker always accompanies a committed reset. The marker row is the durable, database-backed
     * proof that recovery ran; it is keyed by phone and committed atomically with the password change.
     *
     * <p>Concurrency: the marker's primary key on {@code phone} serializes concurrent recovery attempts. The
     * password update runs first (acquiring a row lock), then the marker is inserted; if a concurrent
     * transaction already committed the marker, this transaction's insert raises a constraint violation and
     * the whole transaction rolls back, undoing its password update. Exactly one attempt therefore persists.
     *
     * @param phone        the exact phone of the existing OWNER account to reset
     * @param rawPassword  the new password, already validated against the BCrypt byte limit
     * @return the recovery outcome
     */
    @Transactional
    public OwnerRecoveryOutcome resetProdOwnerPassword(String phone, String rawPassword) {
        if (ownerRecoveryRepository.existsById(phone)) {
            return OwnerRecoveryOutcome.ALREADY_RECOVERED;
        }

        User user = userRepository.findByPhone(phone).orElse(null);
        if (user == null) {
            return OwnerRecoveryOutcome.NO_OWNER;
        }
        if (user.getRole() != UserRole.OWNER) {
            return OwnerRecoveryOutcome.NOT_OWNER;
        }

        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        ownerRecoveryRepository.save(new OwnerRecovery(phone, Instant.now(), "MILKWEB_PROD_OWNER_RECOVERY"));
        return OwnerRecoveryOutcome.RESET;
    }
}
