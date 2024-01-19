package com.beatgridmedia.concurrent;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;

/**
 * Factory for the {@link ShutdownOnFailure} instances as provided by the JDK.
 *
 * @author Leon van Zantvoort
 */
public final class ShutdownOnFailureFactoryImpl extends AbstractStructuredTaskScopeFactory
        implements ShutdownOnFailureFactory {

    /**
     * Creates a new instance of the {@link StructuredTaskScope} supported by this factory.
     *
     * @return a new instance of the {@link StructuredTaskScope} supported by this factory.
     */
    @Override
    public ShutdownOnFailure create() {
        return new StructuredTaskScope.ShutdownOnFailure(getName(), getThreadFactory());
    }
}
