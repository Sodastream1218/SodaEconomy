package de.sodaeconomy.transaction;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal handle for a mutation which may be cancelled only while it is still waiting in the
 * transaction executor. Once execution starts, callers must observe the definitive durable result.
 */
public final class DurableOperation<T> {
    private enum State { QUEUED, RUNNING, COMPLETED, CANCELLED }

    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private final CompletableFuture<T> completion = new CompletableFuture<>();

    boolean tryStart() {
        return state.compareAndSet(State.QUEUED, State.RUNNING);
    }

    void complete(T value) {
        state.set(State.COMPLETED);
        completion.complete(value);
    }

    void completeExceptionally(Throwable failure) {
        state.set(State.COMPLETED);
        completion.completeExceptionally(Objects.requireNonNull(failure, "failure"));
    }

    /** Cancels the operation only when no storage mutation has started yet. */
    public boolean cancelBeforeStart() {
        if (!state.compareAndSet(State.QUEUED, State.CANCELLED)) return false;
        completion.completeExceptionally(new CancellationException("Operation cancelled before execution"));
        return true;
    }

    public boolean hasStarted() {
        State current = state.get();
        return current == State.RUNNING || current == State.COMPLETED;
    }

    public CompletableFuture<T> completion() {
        return completion;
    }
}
