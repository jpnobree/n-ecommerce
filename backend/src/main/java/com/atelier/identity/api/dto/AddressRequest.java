package com.atelier.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @Size(max = 40) String label,
        @NotBlank @Size(max = 120) String recipientName,
        @NotBlank @Pattern(regexp = "[0-9]{10,11}", message = "telefone com DDD, só números") String phone,
        @NotBlank @Pattern(regexp = "[0-9]{8}", message = "CEP deve ter 8 dígitos") String postalCode,
        @NotBlank @Pattern(regexp = "AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO", message = "UF inválida") String state,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 100) String district,
        @NotBlank @Size(max = 200) String street,
        @NotBlank @Size(max = 20) String number,
        @Size(max = 100) String complement,
        @Size(max = 200) String reference) {
}
