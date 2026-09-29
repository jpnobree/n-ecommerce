package com.atelier.identity.api.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(min = 8, max = 128) String password,
        @NotNull @AssertTrue(message = "é preciso aceitar os termos") Boolean acceptTerms,
        Boolean marketingOptIn) {
}
