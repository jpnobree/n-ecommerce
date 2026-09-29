package com.atelier.identity.api.dto;

import com.atelier.identity.domain.AppUser;

import java.time.LocalDate;
import java.util.List;

public record UserResponse(Long id, String email, String name, String phone, String cpf, LocalDate birthDate,
                           boolean emailVerified, boolean marketingOptIn, List<String> roles) {

    public static UserResponse of(AppUser u) {
        return new UserResponse(u.id, u.email, u.fullName, u.phone, u.cpf, u.birthDate, u.emailVerifiedAt != null,
                u.marketingOptIn, u.roles.stream().map(Enum::name).sorted().toList());
    }
}
