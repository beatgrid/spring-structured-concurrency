package com.beatgridmedia.concurrent.managed;

import com.beatgridmedia.concurrent.managed.ManagedTaskScope.ShutdownOnFailure;

/**
 * Factory for {@link ShutdownOnFailure} scope instances.
 *
 * @author Leon van Zantvoort
 */
public final class ShutdownOnFailureFactoryImpl extends ManagedTaskScopeFactoryImpl
        implements ShutdownOnFailureFactory {

    /**
     * Creates a new instance of the {@link ShutdownOnFailure} scope supported by this factory.
     *
     * @return a new instance of the {@link ShutdownOnFailure} scope supported by this factory.
     */
    @Override
    public ShutdownOnFailure create() {
        return new ShutdownOnFailure(getName(), getThreadFactory(), createTaskManagingWrappers());
    }
}
