package com.beatgridmedia.concurrent;

import java.util.concurrent.StructuredTaskScope;

/**
 * Factory for structured task scopes.
 *
 * @param <T> the type of the {@link StructuredTaskScope} supported by this factory.
 * @author Leon van Zantvoort
 */
public interface StructuredTaskScopeFactory<T extends StructuredTaskScope<?>> {

    /**
     * Creates a new instance of the {@link StructuredTaskScope} supported by this factory.
     *
     * @return a new instance of the {@link StructuredTaskScope} supported by this factory.
     */
    T create();
}
