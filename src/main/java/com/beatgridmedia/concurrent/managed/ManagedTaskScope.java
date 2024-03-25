package com.beatgridmedia.concurrent.managed;

import com.beatgridmedia.concurrent.UncheckedTaskException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.StructureViolationException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * A base task scope that manages the execution of tasks.
 *
 * @author Leon van Zantvoort
 */
public abstract class ManagedTaskScope<T> extends StructuredTaskScope<T> {

    private final List<TaskManagingWrapper> taskManagingWrappers;

    /**
     * Interface for wrapping tasks, providing a way to manage tasks.
     */
    public interface TaskManagingWrapper {

        /**
         * Wrap a task.
         *
         * @apiNote Implementations must not call the {@code task} from this method.
         *
         * @param task task to wrap.
         * @param <U> type of the result of the task.
         * @return wrapped task.
         * @throws Exception if the task could not be wrapped.
         */
        <U> Callable<U> wrap(@Nonnull Callable<U> task) throws Exception;

        /**
         * Close the task managing wrapper.
         */
        void close();
    }

    /**
     * Creates a structured task scope with the given name and thread factory. The task
     * scope is optionally named for the purposes of monitoring and management. The thread
     * factory is used to {@link ThreadFactory#newThread(Runnable) create} threads when
     * subtasks are {@linkplain #fork(Callable) forked}. The task scope is owned by the
     * current thread.
     *
     * <p>Construction captures the current thread's {@linkplain ScopedValue scoped value}
     * bindings for inheritance by threads started in the task scope. The
     * <a href="#TreeStructure">Tree Structure</a> section in the class description details
     * how parent-child relations are established implicitly for the purpose of inheritance
     * of scoped value bindings.</p>
     *
     * @param name the name of the task scope, can be null.
     * @param factory the thread factory.
     * @param wrappers task wrappers.
     */
    public ManagedTaskScope(@Nullable String name,
                            @Nonnull ThreadFactory factory,
                            @Nonnull List<TaskManagingWrapper> wrappers) {
        super(name, factory);
        this.taskManagingWrappers = requireNonNull(wrappers);
    }

    /**
     * Invoked by a subtask when it completes successfully or fails in this task scope.
     * This method is not invoked if a subtask completes after the task scope is
     * {@linkplain #shutdown() shut down}.
     *
     * @apiNote The {@code handleComplete} method should be thread safe. It may be
     * invoked by several threads concurrently.
     *
     * @param subtask the subtask.
     * @throws IllegalArgumentException if called with a subtask that has not completed.
     */
    @Override
    protected void handleComplete(Subtask<? extends T> subtask) {
        super.handleComplete(subtask);
    }

    @Override
    public void shutdown() {
        try {
            super.shutdown();
        } finally {
            taskManagingWrappers.forEach(TaskManagingWrapper::close);
        }
    }

    /**
     * Wrap a task with all task wrappers, effectively managing the task.
     *
     * @param task task to wrap.
     * @return wrapped task.
     * @param <U> type of the result of the task.
     */
    private <U> Callable<U> wrapAll(@Nonnull Callable<U> task) {
        Callable<U> wrapped = task;
        RejectedExecutionException rejected = null;
        for (TaskManagingWrapper wrapper : taskManagingWrappers) {
            try {
                wrapped = wrapper.wrap(wrapped);
            } catch (Throwable t) {
                if (rejected == null) {
                    rejected = t instanceof RejectedExecutionException re ? re : new RejectedExecutionException(t);
                } else {
                    rejected.addSuppressed(t);
                }
            }
        }
        if (rejected != null) {
            throw rejected;
        }
        return wrapped;
    }

    /**
     * Starts a new thread in this task scope to execute a value-returning task, thus
     * creating a <em>subtask</em> of this task scope.
     *
     * <p>The value-returning task is provided to this method as a {@link Callable}, the
     * thread executes the task's {@link Callable#call() call} method. The thread is
     * created with the task scope's {@link ThreadFactory}. It inherits the current thread's
     * {@linkplain ScopedValue scoped value} bindings. The bindings must match the bindings
     * captured when the task scope was created.</p>
     *
     * <p>This method returns a {@link Subtask Subtask} to represent the <em>forked
     * subtask</em>. The {@code Subtask} object can be used to obtain the result when
     * the subtask completes successfully, or the exception when the subtask fails. To
     * ensure correct usage, the {@link Subtask#get() get()} and {@link Subtask#exception()
     * exception()} methods may only be called by the task scope owner after it has waited
     * for all threads to finish with the {@link #join() join} or {@link #joinUntil(Instant)}
     * methods. When the subtask completes, the thread invokes the {@link
     * #handleComplete(Subtask) handleComplete} method to consume the completed subtask.
     * If the task scope is {@linkplain #shutdown() shut down} before the subtask completes
     * then the {@code handleComplete} method will not be invoked.</p>
     *
     * <p>If this task scope is {@linkplain #shutdown() shutdown} (or in the process of
     * shutting down) then the subtask will not run and the {@code handleComplete} method
     * will not be invoked.</p>
     *
     * <p> This method may only be invoked by the task scope owner or threads contained
     * in the task scope.</p>
     *
     * @param task the value-returning task for the thread to execute.
     * @param <U> the result type.
     * @return the subtask.
     * @throws IllegalStateException if this task scope is closed.
     * @throws WrongThreadException if the current thread is not the task scope owner or a
     * thread contained in the task scope.
     * @throws StructureViolationException if the current scoped value bindings are not
     * the same as when the task scope was created.
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the subtask.
     */
    @Override
    public <U extends T> Subtask<U> fork(Callable<? extends U> task) {
        return super.fork(wrapAll(task));
    }

    /**
     * A {@code StructuredTaskScope} that captures the result of the first subtask to
     * complete {@linkplain Subtask.State#SUCCESS successfully}. Once captured, it
     * {@linkplain #shutdown() shuts down} the task scope to interrupt unfinished threads
     * and wakeup the task scope owner. The policy implemented by this class is intended
     * for cases where the result of any subtask will do ("invoke any") and where the
     * results of other unfinished subtasks are no longer needed.
     *
     * <p>Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.</p>
     *
     * @apiNote This class implements a policy to shut down the task scope when a subtask
     * completes successfully. There shouldn't be any need to directly shut down the task
     * scope with the {@link #shutdown() shutdown} method.
     *
     * @param <T> the result type.
     */
    public static final class ShutdownOnSuccess<T> extends ManagedTaskScope<T> {
        private static final Object RESULT_NULL = new Object();
        private static final VarHandle FIRST_RESULT;
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_RESULT = l.findVarHandle(ManagedTaskScope.ShutdownOnSuccess.class, "firstResult", Object.class);
                FIRST_EXCEPTION = l.findVarHandle(ManagedTaskScope.ShutdownOnSuccess.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Object firstResult;
        private volatile Throwable firstException;

        /**
         * Constructs a new {@code ShutdownOnSuccess} with the given name and thread factory.
         * The task scope is optionally named for the purposes of monitoring and management.
         * The thread factory is used to {@link ThreadFactory#newThread(Runnable) create}
         * threads when subtasks are {@linkplain #fork(Callable) forked}. The task scope
         * is owned by the current thread.
         *
         * <p> Construction captures the current thread's {@linkplain ScopedValue scoped
         * value} bindings for inheritance by threads started in the task scope. The
         * <a href="#TreeStructure">Tree Structure</a> section in the class description
         * details how parent-child relations are established implicitly for the purpose
         * of inheritance of scoped value bindings.
         *
         * @param name the name of the task scope, can be null
         * @param factory the thread factory
         * @param wrappers task wrappers.
         */
        public ShutdownOnSuccess(String name, ThreadFactory factory, List<TaskManagingWrapper> wrappers) {
            super(name, factory, wrappers);
        }

        @Override
        protected void handleComplete(Subtask<? extends T> subtask) {
            if (firstResult != null) {
                // already captured a result
                return;
            }
            if (subtask.state() == Subtask.State.SUCCESS) {
                // task succeeded
                T result = subtask.get();
                Object r = (result != null) ? result : RESULT_NULL;
                if (FIRST_RESULT.compareAndSet(this, null, r)) {
                    shutdown();
                }
            } else if (firstException == null) {
                // capture the exception thrown by the first subtask that failed
                FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception());
            }
        }

        /**
         * Wait for a subtask started in this task scope to complete {@linkplain
         * Subtask.State#SUCCESS successfully} or all subtasks to complete.
         *
         * <p>This method waits for all subtasks by waiting for all threads {@linkplain
         * #fork(Callable) started} in this task scope to finish execution. It stops waiting
         * when all threads finish, a subtask completes successfully, or the current
         * thread is {@linkplain Thread#interrupt() interrupted}. It also stops waiting
         * if the {@link #shutdown() shutdown} method is invoked directly to shut down
         * this task scope.</p>
         *
         * <p>This method may only be invoked by the task scope owner.</p>
         *
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ManagedTaskScope.ShutdownOnSuccess<T> join() throws InterruptedException {
            super.join();
            return this;
        }

        /**
         * Wait for a subtask started in this task scope to complete {@linkplain
         * Subtask.State#SUCCESS successfully} or all subtasks to complete, up to the
         * given deadline.
         *
         * <p>This method waits for all subtasks by waiting for all threads {@linkplain
         * #fork(Callable) started} in this task scope to finish execution. It stops waiting
         * when all threads finish, a subtask completes successfully, the deadline is
         * reached, or the current thread is {@linkplain Thread#interrupt() interrupted}.
         * It also stops waiting if the {@link #shutdown() shutdown} method is invoked
         * directly to shut down this task scope.</p>
         *
         * <p>This method may only be invoked by the task scope owner.</p>
         *
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ManagedTaskScope.ShutdownOnSuccess<T> joinUntil(Instant deadline)
                throws InterruptedException, TimeoutException {
            super.joinUntil(deadline);
            return this;
        }

        /**
         * {@return the result of the first subtask that completed {@linkplain
         * Subtask.State#SUCCESS successfully}}
         *
         * <p> When no subtask completed successfully, but a subtask {@linkplain
         * Subtask.State#FAILED failed} then {@code ExecutionException} is thrown with
         * the subtask's exception as the {@linkplain Throwable#getCause() cause}.
         *
         * @throws ExecutionException if no subtasks completed successfully but at least
         * one subtask failed.
         * @throws IllegalStateException if no subtasks completed or the task scope owner
         * did not join after forking.
         * @throws WrongThreadException if the current thread is not the task scope owner.
         */
        public T result() throws ExecutionException {
            return result(ExecutionException::new);
        }

        /**
         * Returns the result of the first subtask that completed {@linkplain
         * Subtask.State#SUCCESS successfully}, otherwise throws an exception produced
         * by the given exception supplying function.
         *
         * <p> When no subtask completed successfully, but a subtask {@linkplain
         * Subtask.State#FAILED failed}, then the exception supplying function is invoked
         * with subtask's exception.
         *
         * @param esf the exception supplying function
         * @param <X> type of the exception to be thrown
         * @return the result of the first subtask that completed with a result.
         * @throws X if no subtasks completed successfully but at least one subtask failed.
         * @throws IllegalStateException if no subtasks completed or the task scope owner
         * did not join after forking.
         * @throws WrongThreadException if the current thread is not the task scope owner.
         */
        public <X extends Throwable> T result(Function<Throwable, ? extends X> esf) throws X {
            Objects.requireNonNull(esf);
            ensureOwnerAndJoined();
            Object result = firstResult;
            if (result == RESULT_NULL) {
                return null;
            } else if (result != null) {
                @SuppressWarnings("unchecked")
                T r = (T) result;
                return r;
            }
            Throwable exception = firstException;
            if (exception != null) {
                X ex = esf.apply(exception);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }
            throw new IllegalStateException("No completed subtasks");
        }
    }

    /**
     * A {@code StructuredTaskScope} that captures the exception of the first subtask to
     * {@linkplain Subtask.State#FAILED fail}. Once captured, it {@linkplain #shutdown()
     * shuts down} the task scope to interrupt unfinished threads and wakeup the task
     * scope owner. The policy implemented by this class is intended for cases where the
     * results for all subtasks are required ("invoke all"); if any subtask fails then the
     * results of other unfinished subtasks are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @apiNote This class implements a policy to shut down the task scope when a subtask
     * fails. There shouldn't be any need to directly shut down the task scope with the
     * {@link #shutdown() shutdown} method.
     */
    public static final class ShutdownOnFailure extends ManagedTaskScope<Object> {
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_EXCEPTION = l.findVarHandle(ManagedTaskScope.ShutdownOnFailure.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Throwable firstException;

        /**
         * Constructs a new {@code ShutdownOnFailure} with the given name and thread factory.
         * The task scope is optionally named for the purposes of monitoring and management.
         * The thread factory is used to {@link ThreadFactory#newThread(Runnable) create}
         * threads when subtasks are {@linkplain #fork(Callable) forked}. The task scope
         * is owned by the current thread.
         *
         * <p> Construction captures the current thread's {@linkplain ScopedValue scoped
         * value} bindings for inheritance by threads started in the task scope. The
         * <a href="#TreeStructure">Tree Structure</a> section in the class description
         * details how parent-child relations are established implicitly for the purpose
         * of inheritance of scoped value bindings.
         *
         * @param name the name of the task scope, can be null.
         * @param factory the thread factory.
         * @param wrappers task wrappers.
         */
        public ShutdownOnFailure(String name, ThreadFactory factory, List<TaskManagingWrapper> wrappers) {
            super(name, factory, wrappers);
        }

        @Override
        protected void handleComplete(Subtask<?> subtask) {
            if (subtask.state() == Subtask.State.FAILED
                    && firstException == null
                    && FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception())) {
                shutdown();
            }
        }

        /**
         * Wait for all subtasks started in this task scope to complete or for a subtask
         * to {@linkplain Subtask.State#FAILED fail}.
         *
         * <p>This method waits for all subtasks by waiting for all threads {@linkplain
         * #fork(Callable) started} in this task scope to finish execution. It stops waiting
         * when all threads finish, a subtask fails, or the current thread is {@linkplain
         * Thread#interrupt() interrupted}. It also stops waiting if the {@link #shutdown()
         * shutdown} method is invoked directly to shut down this task scope.
         *
         * <p>This method may only be invoked by the task scope owner.</p>
         *
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ManagedTaskScope.ShutdownOnFailure join() throws InterruptedException {
            super.join();
            return this;
        }
        /**
         * Wait for all subtasks started in this task scope to complete or for a subtask
         * to {@linkplain Subtask.State#FAILED fail}.
         *
         * <p>This method waits for all subtasks by waiting for all threads {@linkplain
         * #fork(Callable) started} in this task scope to finish execution. It stops waiting
         * when all threads finish, a subtask fails, or the current thread is {@linkplain
         * Thread#interrupt() interrupted}. It also stops waiting if the {@link #shutdown()
         * shutdown} method is invoked directly to shut down this task scope.</p>
         *
         * <p>This method may only be invoked by the task scope owner.</p>
         *
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         * @throws UncheckedTaskException if a subtask failed, or in case of interruption.
         */
        public ManagedTaskScope.ShutdownOnFailure joinAndThrowIfFailed() {
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
        public ManagedTaskScope.ShutdownOnFailure joinUntil(Instant deadline)
                throws InterruptedException, TimeoutException {
            super.joinUntil(deadline);
            return this;
        }

        /**
         * Wait for all subtasks started in this task scope to complete or for a subtask
         * to {@linkplain Subtask.State#FAILED fail}, up to the given deadline.
         *
         * <p>This method waits for all subtasks by waiting for all threads {@linkplain
         * #fork(Callable) started} in this task scope to finish execution. It stops waiting
         * when all threads finish, a subtask fails, the deadline is reached, or the current
         * thread is {@linkplain Thread#interrupt() interrupted}. It also stops waiting
         * if the {@link #shutdown() shutdown} method is invoked directly to shut down
         * this task scope.
         *
         * <p>This method may only be invoked by the task scope owner.</p>
         *
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         * @throws UncheckedTaskException if a subtask failed, in case of interruption, or when the deadline is reached.
         */
        public ManagedTaskScope.ShutdownOnFailure joinAndThrowIfFailed(Instant deadline) {
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
            Objects.requireNonNull(esf);
            Throwable exception = firstException;
            if (exception != null) {
                X ex = esf.apply(exception);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }
        }
    }

    /**
     * A {@code StructuredTaskScope} that captures the exception of the first subtask to
     * {@linkplain Subtask.State#FAILED fail}. Once captured, it {@linkplain #shutdown()
     * shuts down} the task scope to interrupt unfinished threads and wakeup the task
     * scope owner. The policy implemented by this class is intended for cases where the
     * results for all subtasks are required ("invoke all"); if any subtask fails then the
     * results of other unfinished subtasks are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @apiNote This class implements a policy to shut down the task scope when a subtask
     * fails. There shouldn't be any need to directly shut down the task scope with the
     * {@link #shutdown() shutdown} method.
     */
    public static final class CollectingScope<T> extends ManagedTaskScope<T> {
        private static final Object RESULT_NULL = new Object();
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_EXCEPTION = l.findVarHandle(CollectingScope.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Throwable firstException;
        private final Queue<Object> results;
        private final Queue<Subtask<? extends T>> successSubtasks;
        private final Queue<Subtask<? extends T>> failedSubtasks;

        /**
         * Constructs a new {@code ShutdownOnFailure} with the given name and thread factory.
         * The task scope is optionally named for the purposes of monitoring and management.
         * The thread factory is used to {@link ThreadFactory#newThread(Runnable) create}
         * threads when subtasks are {@linkplain #fork(Callable) forked}. The task scope
         * is owned by the current thread.
         *
         * <p> Construction captures the current thread's {@linkplain ScopedValue scoped
         * value} bindings for inheritance by threads started in the task scope. The
         * <a href="#TreeStructure">Tree Structure</a> section in the class description
         * details how parent-child relations are established implicitly for the purpose
         * of inheritance of scoped value bindings.
         *
         * @param name the name of the task scope, can be null.
         * @param factory the thread factory.
         * @param wrappers task wrappers.
         */
        public CollectingScope(String name, ThreadFactory factory, List<TaskManagingWrapper> wrappers) {
            super(name, factory, wrappers);
            this.results = new LinkedTransferQueue<>();
            this.successSubtasks = new LinkedTransferQueue<>();
            this.failedSubtasks = new LinkedTransferQueue<>();
        }

        @Override
        protected void handleComplete(Subtask<? extends T> subtask) {
            if (subtask.state() == Subtask.State.SUCCESS) {
                // task succeeded
                T result = subtask.get();
                Object r = (result != null) ? result : RESULT_NULL;
                results.add(r);
                successSubtasks.add(subtask);
            } else if (subtask.state() == Subtask.State.FAILED) {
                // Just set exception, but don't shutdown
                if (firstException == null) {
                    FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception());
                }
                failedSubtasks.add(subtask);
            }
        }

        /**
         * Returns all results of subtasks that completed successfully. Null results will be excluded.
         *
         * @return all results of subtasks that completed successfully.
         * @throws IllegalStateException if no subtasks completed or the task scope owner did not join after forking.
         * @throws WrongThreadException if the current thread is not the task scope owner.
         */
        public Stream<T> results() {
            return results(false);
        }

        /**
         * Returns all results of subtasks that completed successfully. Null results will be included depending on the
         * value of the {@code includeNulls} parameter.
         *
         * @param includeNulls whether to include null results.
         * @throws IllegalStateException if no subtasks completed or the task scope owner did not join after forking.
         * @throws WrongThreadException if the current thread is not the task scope owner.
         */
        public Stream<T> results(boolean includeNulls) {
            super.ensureOwnerAndJoined();
            return results.stream()
                    .filter(r -> includeNulls || r != RESULT_NULL)
                    .map(r -> {
                        if (r == RESULT_NULL) {
                            return null;
                        } else {
                            @SuppressWarnings("unchecked")
                            T t = (T) r;
                            return t;
                        }
                    });
        }

        /**
         * Returns all tasks that successfully completed.
         *
         * @return all tasks that successfully completed.
         * @throws IllegalStateException if no subtasks completed or the task scope owner did not join after forking.
         * @throws WrongThreadException if the current thread is not the task scope owner.
         */
        public Stream<Subtask<? extends T>> successfulTasks() {
            super.ensureOwnerAndJoined();
            return successSubtasks.stream();
        }

        /**
         * Returns all tasks that failed completed.
         *
         * @return all tasks that failed completed.
         * @throws IllegalStateException if no subtasks completed or the task scope owner did not join after forking.
         * @throws WrongThreadException if the current thread is not the task scope owner.
         */
        public Stream<Subtask<? extends T>> failedTasks() {
            super.ensureOwnerAndJoined();
            return failedSubtasks.stream();
        }

        /**
         * Wait for all subtasks started in this task scope to complete or for a subtask
         * to {@linkplain Subtask.State#FAILED fail}.
         *
         * <p>This method waits for all subtasks by waiting for all threads {@linkplain
         * #fork(Callable) started} in this task scope to finish execution. It stops waiting
         * when all threads finish, a subtask fails, or the current thread is {@linkplain
         * Thread#interrupt() interrupted}. It also stops waiting if the {@link #shutdown()
         * shutdown} method is invoked directly to shut down this task scope.
         *
         * <p>This method may only be invoked by the task scope owner.</p>
         *
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public CollectingScope<T> join() throws InterruptedException {
            super.join();
            return this;
        }

        /**
         * Wait for all subtasks started in this task scope to complete or for a subtask
         * to {@linkplain Subtask.State#FAILED fail}.
         *
         * <p>This method waits for all subtasks by waiting for all threads {@linkplain
         * #fork(Callable) started} in this task scope to finish execution. It stops waiting
         * when all threads finish, a subtask fails, or the current thread is {@linkplain
         * Thread#interrupt() interrupted}. It also stops waiting if the {@link #shutdown()
         * shutdown} method is invoked directly to shut down this task scope.</p>
         *
         * <p>This method may only be invoked by the task scope owner.</p>
         *
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         * @throws UncheckedTaskException if a subtask failed, or in case of interruption.
         */
        public CollectingScope<T> joinAndThrowIfFailed() {
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
        public CollectingScope<T> joinUntil(Instant deadline)
                throws InterruptedException, TimeoutException {
            super.joinUntil(deadline);
            return this;
        }

        /**
         * Wait for all subtasks started in this task scope to complete or for a subtask
         * to {@linkplain Subtask.State#FAILED fail}, up to the given deadline.
         *
         * <p>This method waits for all subtasks by waiting for all threads {@linkplain
         * #fork(Callable) started} in this task scope to finish execution. It stops waiting
         * when all threads finish, a subtask fails, the deadline is reached, or the current
         * thread is {@linkplain Thread#interrupt() interrupted}. It also stops waiting
         * if the {@link #shutdown() shutdown} method is invoked directly to shut down
         * this task scope.
         *
         * <p>This method may only be invoked by the task scope owner.</p>
         *
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         * @throws UncheckedTaskException if a subtask failed, in case of interruption, or when the deadline is reached.
         */
        public CollectingScope<T> joinAndThrowIfFailed(Instant deadline) {
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
            Objects.requireNonNull(esf);
            Throwable exception = firstException;
            if (exception != null) {
                X ex = esf.apply(exception);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }
        }
    }
}
