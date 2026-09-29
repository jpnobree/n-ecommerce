package com.atelier.identity.service;

import com.atelier.identity.api.dto.UpdateProfileRequest;
import com.atelier.identity.domain.AppUser;
import com.atelier.identity.repository.UserRepository;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dados do próprio usuário ("/api/me"). */
@Service
public class AccountService {

    private final UserRepository users;
    private final SessionService sessions;
    private final AuthService auth;
    private final PasswordEncoder passwordEncoder;

    AccountService(UserRepository users, SessionService sessions, AuthService auth, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.sessions = sessions;
        this.auth = auth;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public AppUser get(Long userId) {
        return users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public AppUser update(Long userId, UpdateProfileRequest req) {
        AppUser user = get(userId);
        if (req.cpf() != null && users.existsByCpfAndIdNot(req.cpf(), userId)) {
            throw new BusinessException(ErrorCode.CPF_IN_USE);
        }
        user.fullName = req.name().trim();
        user.phone = req.phone();
        user.cpf = req.cpf();
        user.birthDate = req.birthDate();
        user.marketingOptIn = Boolean.TRUE.equals(req.marketingOptIn());
        return user;
    }

    /** Troca a senha, encerra todas as sessões e abre uma nova para quem fez a troca. */
    @Transactional
    public SessionService.Session changePassword(Long userId, String current, String next, SessionService.ClientInfo client) {
        AppUser user = get(userId);
        if (!passwordEncoder.matches(current, user.passwordHash)) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }
        user.passwordHash = passwordEncoder.encode(next);
        sessions.revokeAll(user);
        auth.sendPasswordChangedEmail(user);
        return sessions.start(user, client);
    }

    @Transactional
    public void logoutEverywhere(Long userId) {
        sessions.revokeAll(get(userId));
    }
}
