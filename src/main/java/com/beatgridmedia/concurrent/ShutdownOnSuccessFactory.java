package com.beatgridmedia.concurrent;

import java.util.concurrent.StructuredTaskScope.ShutdownOnSuccess;

/**
 * Factory for the {@link ShutdownOnSuccess} instances as provided by the JDK.
 *
 * @param <T> the type of the result of the task.
 * @author Leon van Zantvoort
 */
public interface ShutdownOnSuccessFactory<T> extends StructuredTaskScopeFactory<ShutdownOnSuccess<T>> {
}
