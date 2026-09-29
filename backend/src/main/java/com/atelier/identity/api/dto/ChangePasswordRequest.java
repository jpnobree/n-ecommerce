package com.atelier.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                    @NotNull @Size(min = 8, max = 128) String newPassword) {
}
