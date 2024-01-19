package com.beatgridmedia.concurrent;

import jakarta.annotation.Nonnull;

import static java.util.Objects.requireNonNull;

/**
 * Exception that is thrown when a task fails.
 *
 * @author Leon van Zantvoort
 */
public class UncheckedTaskException extends RuntimeException {

    /**
     * Constructs a new {@code UncheckedTaskException} with the specified {@code cause}.
     *
     * @param cause the {@code Throwable} which is the cause of this exception.
     */
    public UncheckedTaskException(@Nonnull Throwable cause) {
        super(requireNonNull(cause));
    }
}
