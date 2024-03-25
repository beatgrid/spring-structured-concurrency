package com.beatgridmedia.concurrent.managed;

import com.beatgridmedia.concurrent.AbstractStructuredTaskScopeFactory;
import com.beatgridmedia.concurrent.UncheckedTaskException;
import com.beatgridmedia.concurrent.managed.ManagedTaskScope.TaskManagingWrapper;
import jakarta.annotation.Nonnull;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Integer.MAX_VALUE;

/**
 * Factory for the managed task scopes.
 *
 * @author Leon van Zantvoort
 */
public abstract class ManagedTaskScopeFactoryImpl extends AbstractStructuredTaskScopeFactory
        implements ManagedTaskScopeFactory {

    private int threadCount;
    private int sharedThreadCount;
    private boolean lockOnFork;
    private TransactionTemplate transactionTemplate;

    private Semaphore applicationSemaphore;

    public ManagedTaskScopeFactoryImpl() {
        threadCount = MAX_VALUE;
        sharedThreadCount = MAX_VALUE;
        lockOnFork = false;
    }

    /**
     * The maximum number of parallel threads that can be active per {@link StructuredTaskScope}, or
     * {@code Integer.MAX_VALUE} for unlimited threads.
     *
     * @return the maximum number of parallel threads that can be active per {@link StructuredTaskScope}.
     */
    public int getThreadCount() {
        return threadCount;
    }

    /**
     * Sets the maximum number of parallel threads that can be active per {@link StructuredTaskScope}, or
     * {@code Integer.MAX_VALUE} for unlimited threads.
     *
     * @param threadCount the maximum number of parallel threads that can be active per {@link StructuredTaskScope}.
     */
    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    /**
     * The maximum number of parallel threads that can be active for all {@link StructuredTaskScope} instances
     * returned by this factory, or {@code Integer.MAX_VALUE} for unlimited threads.
     *
     * @return the maximum number of parallel threads that can be active for all {@link StructuredTaskScope} instances
     * returned by this factory.
     */
    public int getSharedThreadCount() {
        return sharedThreadCount;
    }

    /**
     * Whether to lock on fork or not.
     *
     * @return whether to lock on fork or not.
     */
    public boolean isLockOnFork() {
        return lockOnFork;
    }

    /**
     * Specifies whether to lock on fork or not in case the thread count is restricted.
     *
     * Lock-on-fork acquires a semaphore permit before the task is forked. When lock on fork is disabled, the
     * semaphore permit is acquired after forking the task.
     *
     * The lock-on-fork feature is particularly useful in the scenario when concurrent requests result a higher number
     * of forked subtasks. In this scenario, lock-on-fork can limit the number of threads that are created, which may
     * be particularly beneficial when platform threads are used. Secondly, lock-on-fork can be used to maintain a fair
     * distribution of parallel threads across multiple scope instances when the thread count is restricted.
     *
     * The trade-off of lock-on-fork is the fact that {@link StructuredTaskScope#fork(Callable)} may block while waiting
     * for a semaphore permit. Lock-on-fork is disabled by default.
     *
     * @param lockOnFork whether to lock on fork or not.
     */
    public void setLockOnFork(boolean lockOnFork) {
        this.lockOnFork = lockOnFork;
    }

    /**
     * Sets the number of concurrent threads that can be used per all {@link StructuredTaskScope} instances created by
     * this factory combined, or {@code Integer.MAX_VALUE} for unlimited threads.
     *
     * @param sharedThreadCount the number of concurrent threads that can be used per all {@link StructuredTaskScope}
     *                               instances created by this factory combined.
     */
    public void setSharedThreadCount(int sharedThreadCount) {
        this.sharedThreadCount = sharedThreadCount;
        this.applicationSemaphore = sharedThreadCount == MAX_VALUE ? null : new Semaphore(sharedThreadCount, true);
    }

    /**
     * The {@link TransactionTemplate} that is used to execute the tasks in a transaction, or {@code null} if no
     * transaction should be used.
     *
     * @return the {@link TransactionTemplate} that is used to execute the tasks in a transaction.
     */
    public TransactionTemplate getTransactionTemplate() {
        return transactionTemplate;
    }

    /**
     * Sets the {@link TransactionTemplate} that is used to execute the tasks in a transaction, or {@code null} if no
     * transaction should be used.
     *
     * @param transactionTemplate the {@link TransactionTemplate} that is used to execute the tasks in a transaction.
     */
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Returns the {@link TaskManagingWrapper} instances that are used to wrap the tasks.
     *
     * @return the {@link TaskManagingWrapper} instances that are used to wrap the tasks.
     */
    protected List<TaskManagingWrapper> createTaskManagingWrappers() {
        List<TaskManagingWrapper> managedWrappers = new ArrayList<>();
        if (threadCount != MAX_VALUE) {
            managedWrappers.add(wrapSemaphore(new Semaphore(threadCount, true), lockOnFork));
        }
        if (sharedThreadCount != MAX_VALUE) {
            managedWrappers.add(wrapSemaphore(applicationSemaphore, lockOnFork));
        }
        if (transactionTemplate != null) {
            managedWrappers.add(wrapTransactionTemplate(transactionTemplate));
        }
        return List.copyOf(managedWrappers);
    }

    /**
     * Wraps the given {@link TransactionTemplate} in a {@link TaskManagingWrapper}.
     *
     * @param semaphore the {@link Semaphore} to use.
     * @param lockOnFork whether to lock on fork or not.
     * @return the {@link TaskManagingWrapper} that wraps the given {@link Semaphore}.
     */
    private static TaskManagingWrapper wrapSemaphore(@Nonnull Semaphore semaphore, boolean lockOnFork) {
        if (lockOnFork) {
            AtomicBoolean closed = new AtomicBoolean();
            AtomicInteger acquiredPermits = new AtomicInteger();
            return new TaskManagingWrapper() {
                @Override
                public <U> Callable<U> wrap(@Nonnull Callable<U> task) throws InterruptedException {
                    semaphore.acquire();
                    if (closed.get()) {
                        semaphore.release();
                        return () -> {
                            throw new IllegalStateException("The scope is closed.");
                        };
                    }
                    acquiredPermits.incrementAndGet();
                    return () -> {
                        try {
                            return task.call();
                        } finally {
                            semaphore.release();
                        }
                    };
                }

                @Override
                public void close() {
                    closed.set(true);
                    // No tasks will be executed anymore. Cleanup outstanding permits.
                    semaphore.release(acquiredPermits.getAndSet(0));
                }
            };
        }
        return new TaskManagingWrapper() {
            @Override
            public <U> Callable<U> wrap(@Nonnull Callable<U> task) {
                return () -> {
                    semaphore.acquire();
                    try {
                        return task.call();
                    } finally {
                        semaphore.release();
                    }
                };

            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * Wraps the given {@link TransactionTemplate} in a {@link TaskManagingWrapper}.
     *
     * @param template the {@link TransactionTemplate} to use.
     * @return the {@link TaskManagingWrapper} that wraps the given {@link TransactionTemplate}.
     */
    private static TaskManagingWrapper wrapTransactionTemplate(@Nonnull TransactionTemplate template) {
        return new TaskManagingWrapper() {
            @Override
            public <U> Callable<U> wrap(@Nonnull Callable<U> task) {
                return () -> template.execute(_ -> {
                    try {
                        return task.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new UncheckedTaskException(t);
                    }
                });
            }

            @Override
            public void close() {
            }
        };
    }
}
