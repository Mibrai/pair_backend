package org.program.pair.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class HashFixRunner implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final String SEYD_EMAIL = "seyd.njoya@icloud.com";
    private static final String SEYD_PASSWORD = "Cameroun1@";
    private static final String DEFAULT_PASSWORD = "Pair2024!";
    private static final int VALID_BCRYPT_LENGTH = 60;

    @Override
    @Transactional
    public void run(String... args) {
        int fixed = 0;

        var users = userRepository.findAll();
        for (var user : users) {
            if (user.getPasswordHash() == null || user.getPasswordHash().length() < VALID_BCRYPT_LENGTH) {
                String newHash;
                if (SEYD_EMAIL.equals(user.getEmail())) {
                    newHash = passwordEncoder.encode(SEYD_PASSWORD);
                    log.info("HashFixRunner: fixing hash for {} (Cameroun1@)", user.getEmail());
                } else {
                    newHash = passwordEncoder.encode(DEFAULT_PASSWORD);
                    log.info("HashFixRunner: fixing hash for {} (Pair2024!)", user.getEmail());
                }
                user.setPasswordHash(newHash);
                userRepository.save(user);
                fixed++;
            }
        }

        if (fixed > 0) {
            log.info("HashFixRunner: fixed {} invalid password hashes", fixed);
        } else {
            log.info("HashFixRunner: no invalid hashes found, skipping");
        }
    }
}
