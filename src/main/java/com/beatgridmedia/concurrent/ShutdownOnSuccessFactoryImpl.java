package com.beatgridmedia.concurrent;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnSuccess;

/**
 * Factory for the {@link ShutdownOnSuccess} instances as provided by the JDK.
 *
 * @author Leon van Zantvoort
 */
public final class ShutdownOnSuccessFactoryImpl<T> extends AbstractStructuredTaskScopeFactory<ShutdownOnSuccess<T>>
        implements ShutdownOnSuccessFactory<T> {

    /**
     * Creates a new instance of the {@link StructuredTaskScope} supported by this factory.
     *
     * @return a new instance of the {@link StructuredTaskScope} supported by this factory.
     */
    @Override
    public ShutdownOnSuccess<T> create() {
        return new ShutdownOnSuccess<>(getName(), getThreadFactory());
    }
}
