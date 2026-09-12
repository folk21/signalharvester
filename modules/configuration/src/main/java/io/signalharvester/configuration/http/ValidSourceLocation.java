package io.signalharvester.configuration.http;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that an HTTP request contains an absolute HTTP(S) source location accepted by the domain.
 */
@Documented
@Constraint(validatedBy = SourceLocationValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSourceLocation {

    String message() default "must be an absolute HTTP(S) URI with a host and without credentials or a fragment";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
