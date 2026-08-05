package de.sodaeconomy.transaction;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.util.Set;

/** Internal guard for concrete TransactionService methods that are not part of the public API. */
final class TransactionServiceAccessGuard {
    private static final StackWalker WALKER = StackWalker.getInstance(Set.of(Option.RETAIN_CLASS_REFERENCE));
    private static final String INTERNAL_PACKAGE = "de.sodaeconomy";
    private static final ThreadLocal<Integer> INTERNAL_DEPTH = ThreadLocal.withInitial(() -> 0);

    private TransactionServiceAccessGuard() {
    }

    static AutoCloseable enterInternalServicePath() {
        INTERNAL_DEPTH.set(INTERNAL_DEPTH.get() + 1);
        return () -> {
            int depth = INTERNAL_DEPTH.get() - 1;
            if (depth <= 0) {
                INTERNAL_DEPTH.remove();
            } else {
                INTERNAL_DEPTH.set(depth);
            }
        };
    }

    static void requireInternalCaller(String operation, Class<?> protectedType) {
        if (INTERNAL_DEPTH.get() > 0) {
            return;
        }
        String caller = WALKER.walk(frames -> frames
                .map(StackFrame::getDeclaringClass)
                .filter(type -> isRelevantCaller(type, protectedType))
                .map(Class::getName)
                .findFirst()
                .orElse("<unknown>"));
        if (!isInternalCaller(caller)) {
            throw new UnsupportedTransactionServiceAccessException("Concrete TransactionService operation '"
                    + operation + "' is internal to SodaEconomy and is not part of the supported public API. "
                    + "Use EconomyTransactionApi from Bukkit's ServicesManager instead. Caller: " + caller);
        }
    }

    private static boolean isRelevantCaller(Class<?> type, Class<?> protectedType) {
        if (type == TransactionServiceAccessGuard.class || type == protectedType
                || type.getName().startsWith(protectedType.getName() + "$")) {
            return false;
        }
        String name = type.getName();
        return !isInfrastructureFrame(name);
    }

    private static boolean isInfrastructureFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("org.junit.")
                || className.startsWith("org.opentest4j.")
                || className.startsWith("org.apache.maven.")
                || className.startsWith("org.gradle.");
    }

    private static boolean isInternalCaller(String className) {
        return className.equals(INTERNAL_PACKAGE) || className.startsWith(INTERNAL_PACKAGE + ".");
    }
}
