package com.beatgridmedia.concurrent;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.StructureViolationException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * A task scope that manages the execution of tasks in a thread pool.
 */
public final class ManagedTaskScope extends StructuredTaskScope<Object> {

    private static final VarHandle FIRST_EXCEPTION;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            FIRST_EXCEPTION = l.findVarHandle(ManagedTaskScope.class, "firstException", Throwable.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private volatile Throwable firstException;

    private final List<TaskWrapper> taskWrappers;

    @FunctionalInterface
    public interface TaskWrapper {
        <U> Callable<U> wrap(@Nonnull Callable<U> task) throws Exception;
    }
    public static class UncheckedTaskException extends RuntimeException {
        UncheckedTaskException(Throwable cause) {
            super(cause);
        }
    }

    public ManagedTaskScope(@Nullable String name,
                            @Nonnull ThreadFactory factory,
                            @Nonnull List<TaskWrapper> wrappers) {
        super(name, factory);
        this.taskWrappers = requireNonNull(wrappers);
    }

    @Override
    protected void handleComplete(Subtask<?> subtask) {
        if (subtask.state() == Subtask.State.FAILED
                && firstException == null
                && FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception())) {
            super.shutdown();
        }
    }

    public <U> Callable<U> wrapAll(@Nonnull Callable<U> task) {
        return taskWrappers.stream()
                .reduce(task,
                        (currentTask, wrapper) -> {
                            try {
                                return wrapper.wrap(currentTask);
                            } catch (RejectedExecutionException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new RejectedExecutionException(e);
                            }
                        },
                        (_, _) -> { throw new UnsupportedOperationException("Parallel Stream not supported."); }
                );
    }

    /**
     * Starts a new thread in this task scope to execute a value-returning task, thus
     * creating a <em>subtask</em> of this task scope.
     *
     * <p> The value-returning task is provided to this method as a {@link Callable}, the
     * thread executes the task's {@link Callable#call() call} method. The thread is
     * created with the task scope's {@link ThreadFactory}. It inherits the current thread's
     * {@linkplain ScopedValue scoped value} bindings. The bindings must match the bindings
     * captured when the task scope was created.
     *
     * <p> This method returns a {@link Subtask Subtask} to represent the <em>forked
     * subtask</em>. The {@code Subtask} object can be used to obtain the result when
     * the subtask completes successfully, or the exception when the subtask fails. To
     * ensure correct usage, the {@link Subtask#get() get()} and {@link Subtask#exception()
     * exception()} methods may only be called by the task scope owner after it has waited
     * for all threads to finish with the {@link #join() join} or {@link #joinUntil(Instant)}
     * methods. When the subtask completes, the thread invokes the {@link
     * #handleComplete(Subtask) handleComplete} method to consume the completed subtask.
     * If the task scope is {@linkplain #shutdown() shut down} before the subtask completes
     * then the {@code handleComplete} method will not be invoked.
     *
     * <p> If this task scope is {@linkplain #shutdown() shutdown} (or in the process of
     * shutting down) then the subtask will not run and the {@code handleComplete} method
     * will not be invoked.
     *
     * <p> This method may only be invoked by the task scope owner or threads contained
     * in the task scope.
     *
     * @implSpec This method may be overridden for customization purposes, wrapping tasks
     * for example. If overridden, the subclass must invoke {@code super.fork} to start a
     * new thread in this task scope.
     *
     * @param task the value-returning task for the thread to execute
     * @param <U> the result type
     * @return the subtask
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner or a
     * thread contained in the task scope
     * @throws StructureViolationException if the current scoped value bindings are not
     * the same as when the task scope was created
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the subtask
     */
    @Override
    public <U> Subtask<U> fork(Callable<? extends U> task) {
        return super.fork(wrapAll(task));
    }

    /**
     * Wait for all subtasks started in this task scope to complete or for a subtask
     * to {@linkplain Subtask.State#FAILED fail}.
     *
     * <p> This method waits for all subtasks by waiting for all threads {@linkplain
     * #fork(Callable) started} in this task scope to finish execution. It stops waiting
     * when all threads finish, a subtask fails, or the current thread is {@linkplain
     * Thread#interrupt() interrupted}. It also stops waiting if the {@link #shutdown()
     * shutdown} method is invoked directly to shut down this task scope.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @throws IllegalStateException {@inheritDoc}
     * @throws WrongThreadException {@inheritDoc}
     */
    @Override
    public ManagedTaskScope join() throws InterruptedException {
        super.join();
        return this;
    }

    /**
     * Wait for all subtasks started in this task scope to complete or for a subtask
     * to {@linkplain Subtask.State#FAILED fail}.
     *
     * <p> This method waits for all subtasks by waiting for all threads {@linkplain
     * #fork(Callable) started} in this task scope to finish execution. It stops waiting
     * when all threads finish, a subtask fails, or the current thread is {@linkplain
     * Thread#interrupt() interrupted}. It also stops waiting if the {@link #shutdown()
     * shutdown} method is invoked directly to shut down this task scope.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @throws IllegalStateException {@inheritDoc}
     * @throws WrongThreadException {@inheritDoc}
     * @throws UncheckedTaskException if a subtask failed or in case of interruption.
     */
    public ManagedTaskScope joinAndThrowIfFailed() {
        try {
            super.join();
        } catch (InterruptedException e) {
            throw new UncheckedTaskException(e);
        }
        throwIfFailed(UncheckedTaskException::new);
        return this;
    }

    /**
     * Wait for all subtasks started in this task scope to complete or for a subtask
     * to {@linkplain Subtask.State#FAILED fail}, up to the given deadline.
     *
     * <p> This method waits for all subtasks by waiting for all threads {@linkplain
     * #fork(Callable) started} in this task scope to finish execution. It stops waiting
     * when all threads finish, a subtask fails, the deadline is reached, or the current
     * thread is {@linkplain Thread#interrupt() interrupted}. It also stops waiting
     * if the {@link #shutdown() shutdown} method is invoked directly to shut down
     * this task scope.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @throws IllegalStateException {@inheritDoc}
     * @throws WrongThreadException {@inheritDoc}
     */
    @Override
    public ManagedTaskScope joinUntil(Instant deadline)
            throws InterruptedException, TimeoutException
    {
        super.joinUntil(deadline);
        return this;
    }

    /**
     * Wait for all subtasks started in this task scope to complete or for a subtask
     * to {@linkplain Subtask.State#FAILED fail}, up to the given deadline.
     *
     * <p> This method waits for all subtasks by waiting for all threads {@linkplain
     * #fork(Callable) started} in this task scope to finish execution. It stops waiting
     * when all threads finish, a subtask fails, the deadline is reached, or the current
     * thread is {@linkplain Thread#interrupt() interrupted}. It also stops waiting
     * if the {@link #shutdown() shutdown} method is invoked directly to shut down
     * this task scope.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @throws IllegalStateException {@inheritDoc}
     * @throws WrongThreadException {@inheritDoc}
     * @throws UncheckedTaskException if a subtask failed, in case of interruption, or when the deadline is reached.
     */
    public ManagedTaskScope joinAndThrowIfFailed(Instant deadline) {
        try {
            super.joinUntil(deadline);
        } catch (InterruptedException | TimeoutException e) {
            throw new UncheckedTaskException(e);
        }
        throwIfFailed(UncheckedTaskException::new);
        return this;
    }

    /**
     * Returns the exception of the first subtask that {@linkplain Subtask.State#FAILED
     * failed}. If no subtasks failed then an empty {@code Optional} is returned.
     *
     * @return the exception for the first subtask to fail or an empty optional if no
     * subtasks failed
     *
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws IllegalStateException if the task scope owner did not join after forking
     */
    public Optional<Throwable> exception() {
        ensureOwnerAndJoined();
        return Optional.ofNullable(firstException);
    }

    /**
     * Throws if a subtask {@linkplain Subtask.State#FAILED failed}.
     * If any subtask failed with an exception then {@code ExecutionException} is
     * thrown with the exception of the first subtask to fail as the {@linkplain
     * Throwable#getCause() cause}. This method does nothing if no subtasks failed.
     *
     * @throws ExecutionException if a subtask failed
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws IllegalStateException if the task scope owner did not join after forking
     */
    public void throwIfFailed() throws ExecutionException {
        throwIfFailed(ExecutionException::new);
    }

    /**
     * Throws the exception produced by the given exception supplying function if a
     * subtask {@linkplain Subtask.State#FAILED failed}. If any subtask failed with
     * an exception then the function is invoked with the exception of the first
     * subtask to fail. The exception returned by the function is thrown. This method
     * does nothing if no subtasks failed.
     *
     * @param esf the exception supplying function
     * @param <X> type of the exception to be thrown
     *
     * @throws X produced by the exception supplying function
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws IllegalStateException if the task scope owner did not join after forking
     */
    public <X extends Throwable>
    void throwIfFailed(Function<Throwable, ? extends X> esf) throws X {
        ensureOwnerAndJoined();
        requireNonNull(esf);
        Throwable exception = firstException;
        if (exception != null) {
            X ex = esf.apply(exception);
            requireNonNull(ex, "esf returned null");
            throw ex;
        }
    }
}
