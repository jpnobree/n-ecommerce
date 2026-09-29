package com.atelier.identity.api.dto;

import com.atelier.identity.validation.Cpf;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

public record UpdateProfileRequest(
        @NotBlank @Size(max = 120) String name,
        @Pattern(regexp = "[0-9]{10,11}", message = "telefone com DDD, só números") String phone,
        @Cpf String cpf,
        @Past LocalDate birthDate,
        Boolean marketingOptIn) {
}
