package io.signalharvester.collection.source.http;

import io.micronaut.http.annotation.FilterMatcher;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the declarative client and client filters that belong to external-source collection traffic.
 */
@Documented
@FilterMatcher
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
public @interface ExternalSourceTransport {}
