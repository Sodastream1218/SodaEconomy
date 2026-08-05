package de.sodaeconomy.transaction;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Bukkit event emitted after a successful wallet transaction has been committed by the active
 * storage backend. In local asynchronous YAML/SQLite mode this is deliberately delayed until the
 * queued write confirms the same immutable record; in MySQL mode it follows the database commit.
 */
public final class WalletTransactionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final TransactionRecord transaction;

    public WalletTransactionEvent(TransactionRecord transaction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    public TransactionRecord getTransaction() {
        return transaction;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
