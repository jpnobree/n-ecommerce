package com.atelier.identity.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

// Entidades sem associações lazy: campos públicos são seguros e evitam getters/setters vazios.
@Entity
@Table(name = "app_user")
public class AppUser {

    /** Bloqueio progressivo a partir da 5ª falha: 1, 5 e 15 minutos (PRD, seção 8.3). */
    static final int MAX_FAILED_LOGINS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(columnDefinition = "citext")
    public String email;

    public String passwordHash;
    public String fullName;
    public String phone;
    public String cpf;
    public LocalDate birthDate;
    public Instant emailVerifiedAt;

    @Enumerated(EnumType.STRING)
    public UserStatus status = UserStatus.ACTIVE;

    public int tokenVersion;
    public int failedLoginCount;
    public Instant lockedUntil;
    public boolean marketingOptIn;
    public String termsVersion;
    public Instant termsAcceptedAt;
    public Instant lastLoginAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    public Set<Role> roles = EnumSet.noneOf(Role.class);

    @Version
    public int version;

    @CreationTimestamp
    public Instant createdAt;

    @UpdateTimestamp
    public Instant updatedAt;

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void recordFailedLogin(Instant now) {
        failedLoginCount++;
        if (failedLoginCount >= MAX_FAILED_LOGINS) {
            long minutes = switch (failedLoginCount - MAX_FAILED_LOGINS) {
                case 0 -> 1;
                case 1 -> 5;
                default -> 15;
            };
            lockedUntil = now.plus(Duration.ofMinutes(minutes));
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    /** Invalida imediatamente todos os access tokens emitidos (claim "tv"). */
    public void revokeAccessTokens() {
        tokenVersion++;
    }
}
