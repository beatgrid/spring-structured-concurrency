package com.beatgridmedia.concurrent;

import java.util.concurrent.StructuredTaskScope.ShutdownOnSuccess;

/**
 * Factory for the {@link ShutdownOnSuccess} instances as provided by the JDK.
 *
 * @author Leon van Zantvoort
 */
public interface ShutdownOnSuccessFactory extends StructuredTaskScopeFactory {

    /**
     * Creates a new instance of the {@link ShutdownOnSuccess} scope supported by this factory.
     *
     * @param <T> the type of the result of the task.
     * @return a new instance of the {@link ShutdownOnSuccess} scope supported by this factory.
     */
    <T> ShutdownOnSuccess<T> create();
}
