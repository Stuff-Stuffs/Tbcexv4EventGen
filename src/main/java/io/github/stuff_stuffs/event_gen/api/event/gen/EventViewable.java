package io.github.stuff_stuffs.event_gen.api.event.gen;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface EventViewable {
    Class<?> viewClass();
}
