package com.atelier.identity.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** CPF com 11 dígitos (sem máscara) e dígitos verificadores válidos. Nulo é aceito. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Cpf.Validator.class)
public @interface Cpf {

    String message() default "CPF inválido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<Cpf, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || isValid(value);
        }

        public static boolean isValid(String cpf) {
            if (!cpf.matches("\\d{11}") || cpf.chars().distinct().count() == 1) return false;
            return checkDigit(cpf, 9) == cpf.charAt(9) - '0' && checkDigit(cpf, 10) == cpf.charAt(10) - '0';
        }

        private static int checkDigit(String cpf, int length) {
            int sum = 0;
            for (int i = 0; i < length; i++) {
                sum += (cpf.charAt(i) - '0') * (length + 1 - i);
            }
            int rest = (sum * 10) % 11;
            return rest == 10 ? 0 : rest;
        }
    }
}
