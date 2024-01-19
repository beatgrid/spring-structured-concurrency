package com.beatgridmedia.concurrent.managed;

import com.beatgridmedia.concurrent.managed.ManagedTaskScope.ShutdownOnSuccess;

import java.util.concurrent.StructuredTaskScope;

/**
 * Factory for {@link ShutdownOnSuccess} scope instances.
 *
 * @author Leon van Zantvoort
 */
public final class ShutdownOnSuccessFactoryImpl extends ManagedTaskScopeFactoryImpl
        implements ShutdownOnSuccessFactory {

    /**
     * Creates a new instance of the {@link StructuredTaskScope.ShutdownOnSuccess} scope supported by this factory.
     *
     * @param <T> the type of the result of the task.
     * @return a new instance of the {@link StructuredTaskScope.ShutdownOnSuccess} scope supported by this factory.
     */
    @Override
    public <T> ShutdownOnSuccess<T> create() {
        return new ShutdownOnSuccess<T>(getName(), getThreadFactory(), createTaskManagingWrappers());
    }
}
