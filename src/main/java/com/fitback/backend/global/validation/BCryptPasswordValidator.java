package com.fitback.backend.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BCryptPasswordValidator implements ConstraintValidator<BCryptPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return BCryptPasswordPolicy.isWithinByteLimit(value);
    }
}
