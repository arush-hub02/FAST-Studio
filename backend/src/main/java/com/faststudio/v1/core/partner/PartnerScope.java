package com.faststudio.v1.core.partner;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The scope a partner read endpoint needs; PartnerInterceptor checks it against the sharing and the caller. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PartnerScope {
    String value();
}
