package com.atelier.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(@NotBlank @Size(max = 100) String token, @NotNull @Size(min = 8, max = 128) String newPassword) {
}
