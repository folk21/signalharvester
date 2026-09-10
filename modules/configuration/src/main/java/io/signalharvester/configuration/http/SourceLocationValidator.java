package io.signalharvester.configuration.http;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;
import java.net.URI;

/**
 * Applies the configured-source URI invariants at the HTTP validation boundary before use-case execution.
 */
@Singleton
public final class SourceLocationValidator implements ConstraintValidator<ValidSourceLocation, String> {

    @Override
    public boolean isValid(
            String value,
            AnnotationValue<ValidSourceLocation> annotationMetadata,
            ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }

        try {
            URI location = URI.create(value);
            return location.isAbsolute()
                    && ("http".equalsIgnoreCase(location.getScheme())
                            || "https".equalsIgnoreCase(location.getScheme()))
                    && location.getHost() != null
                    && !location.getHost().isBlank()
                    && location.getUserInfo() == null
                    && location.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
