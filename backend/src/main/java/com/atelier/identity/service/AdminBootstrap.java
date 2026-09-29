package com.atelier.identity.service;

import com.atelier.identity.AuthProperties;
import com.atelier.identity.domain.AppUser;
import com.atelier.identity.domain.Role;
import com.atelier.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;

/** Cria o primeiro ADMIN (BOOTSTRAP_ADMIN_EMAIL/PASSWORD, senha com 12+ caracteres) enquanto não existir nenhum. */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties props;
    private final Clock clock;

    AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AuthProperties props, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = props.bootstrapAdminEmail();
        String password = props.bootstrapAdminPassword();
        if (email == null || email.isBlank() || password == null || password.length() < 12) return;
        if (users.existsWithRole(Role.ADMIN)) return;

        var admin = users.findByEmail(AuthService.normalizeEmail(email)).orElseGet(AppUser::new);
        admin.email = AuthService.normalizeEmail(email);
        if (admin.fullName == null) admin.fullName = "Administrador";
        admin.passwordHash = passwordEncoder.encode(password);
        admin.emailVerifiedAt = clock.instant();
        admin.roles = EnumSet.of(Role.ADMIN);
        users.save(admin);
        log.info("ADMIN inicial criado: {}", admin.email);
    }
}
