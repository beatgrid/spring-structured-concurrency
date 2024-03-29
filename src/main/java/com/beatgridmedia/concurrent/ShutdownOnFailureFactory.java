package com.beatgridmedia.concurrent;

import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;

/**
 * Factory for the {@link ShutdownOnFailure} instances as provided by the JDK.
 *
 * @author Leon van Zantvoort
 */
public interface ShutdownOnFailureFactory extends StructuredTaskScopeFactory {

    /**
     * Creates a new instance of the {@link ShutdownOnFailure} scope supported by this factory.
     *
     * @return a new instance of the {@link ShutdownOnFailure} scope supported by this factory.
     */
    ShutdownOnFailure create();
}
