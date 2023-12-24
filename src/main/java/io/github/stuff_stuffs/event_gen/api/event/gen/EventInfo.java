package io.github.stuff_stuffs.event_gen.api.event.gen;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface EventInfo {
    String defaultValue() default "";

    String combiner() default "";
}
