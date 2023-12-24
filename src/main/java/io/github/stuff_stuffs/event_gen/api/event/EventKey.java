package io.github.stuff_stuffs.event_gen.api.event;

import java.util.Comparator;
import java.util.List;

public record EventKey<Mut, View>(Class<Mut> mut, Class<View> view, /*Nullable*/ Comparator<Mut> comparator) {
    public interface Factory<Mut, View> {
        Mut convert(View view);

        Mut invoker(List<Mut> events);
    }
}
