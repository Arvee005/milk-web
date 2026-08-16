package com.radhikafarms.milkweb.auth;

import com.radhikafarms.milkweb.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class MilkWebUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public MilkWebUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        return userRepository.findByPhone(phone)
                .map(MilkWebUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
