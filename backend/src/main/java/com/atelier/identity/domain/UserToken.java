package com.atelier.identity.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Token de uso único enviado por e-mail (verificação, redefinição de senha). */
@Entity
@Table(name = "user_token")
public class UserToken {

    public enum Type { EMAIL_VERIFY, PASSWORD_RESET }

    @Id
    public UUID id;
    public Long userId;

    @Enumerated(EnumType.STRING)
    public Type type;

    public String tokenHash;
    public Instant expiresAt;
    public Instant usedAt;
    public Instant createdAt;

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
