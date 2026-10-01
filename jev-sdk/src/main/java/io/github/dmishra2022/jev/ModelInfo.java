package io.github.dmishra2022.jev;

import java.util.Objects;
import java.util.Optional;

/**
 * A model or alias accepted by the {@code model} field.
 *
 * @param name        the model ID or alias, e.g. {@code jev-latest}
 * @param description what the model is for
 * @param releaseDate when the model or alias was released, as reported by the API
 */
public record ModelInfo(String name, Optional<String> description, Optional<String> releaseDate) {

    public ModelInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(releaseDate, "releaseDate");
    }
}
