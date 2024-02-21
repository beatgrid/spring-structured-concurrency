package com.beatgridmedia.concurrent;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Long.toHexString;
import static java.lang.System.identityHashCode;

/**
 * Abstract base class for {@link StructuredTaskScopeFactory} implementations.
 *
 * @author Leon van Zantvoort
 */
public abstract class AbstractStructuredTaskScopeFactory implements StructuredTaskScopeFactory {

    private final AtomicInteger scopeCounter = new AtomicInteger();
    private String name;
    private boolean virtual;

    public AbstractStructuredTaskScopeFactory() {
        this.virtual = true;    // Use virtual by default.
    }

    /**
     * Returns whether the {@link StructuredTaskScope} that is created by this factory is virtual or not.
     *
     * @return whether the {@link StructuredTaskScope} that is created by this factory is virtual or not.
     */
    public boolean isVirtual() {
        return virtual;
    }

    /**
     * Sets whether the {@link StructuredTaskScope} that is created by this factory is virtual or not.
     *
     * @param virtual whether the {@link StructuredTaskScope} that is created by this factory is virtual or not.
     */
    public void setVirtual(boolean virtual) {
        this.virtual = virtual;
    }

    /**
     * Returns the name of the {@link StructuredTaskScope} that is created by this factory.
     *
     * @return the name of the {@link StructuredTaskScope} that is created by this factory.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of the {@link StructuredTaskScope} that is created by this factory.
     *
     * @param name the name of the {@link StructuredTaskScope} that is created by this factory.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Creates a new {@link ThreadFactory} that is used to create threads for the {@link StructuredTaskScope} that is
     * created by this factory.
     *
     * The thread factory will use the name of the as a prefix for the thread name.
     *
     * @return A new {@link ThreadFactory} instance.
     */
    protected ThreadFactory getThreadFactory() {
        String name = STR."\{switch(getName()) {
            case null -> toHexString(identityHashCode(this));
            case String s -> s;
        }}-\{scopeCounter.getAndIncrement()}-";
        if (isVirtual()) {
            return Thread.ofVirtual().name(name, 0).factory();
        }
        return Thread.ofPlatform().name(name, 0).factory();
    }
}
