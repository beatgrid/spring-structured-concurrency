package com.beatgridmedia.concurrent.managed;

import com.beatgridmedia.concurrent.managed.ManagedTaskScope.CollectingScope;

/**
 * Factory for {@link CollectingScope} scope instances.
 *
 * @author Leon van Zantvoort
 */
public final class CollectingScopeFactoryImpl extends ManagedTaskScopeFactoryImpl implements CollectingScopeFactory {

    /**
     * Creates a new instance of the {@link CollectingScope} scope supported by this factory.
     *
     * @param <T> the type of the result of the task.
     * @return a new instance of the {@link CollectingScope} scope supported by this factory.
     */
    @Override
    public <T> CollectingScope<T> create() {
        return new CollectingScope<>(getName(), getThreadFactory(), createTaskManagingWrappers());
    }
}
