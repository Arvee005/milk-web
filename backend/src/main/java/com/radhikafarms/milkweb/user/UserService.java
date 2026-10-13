package com.radhikafarms.milkweb.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
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
}
