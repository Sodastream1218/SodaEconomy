package de.sodaeconomy.storage;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.util.Set;

/** Internal guard for legacy low-level storage methods that remain public for compatibility. */
final class StorageAccessGuard {
    private static final StackWalker WALKER = StackWalker.getInstance(Set.of(Option.RETAIN_CLASS_REFERENCE));
    private static final String STORAGE_PACKAGE = "de.sodaeconomy.storage";
    private static final String TRANSACTION_PACKAGE = "de.sodaeconomy.transaction";
    private static final String IDENTITY_PACKAGE = "de.sodaeconomy.identity";

    private StorageAccessGuard() {
    }

    static void requireInternalCaller(String operation, Class<?> protectedType) {
        String caller = WALKER.walk(frames -> frames
                .map(StackFrame::getDeclaringClass)
                .filter(type -> isRelevantCaller(type, protectedType))
                .map(Class::getName)
                .findFirst()
                .orElse("<unknown>"));
        if (!isInternalCaller(caller)) {
            throw new UnauthorizedStorageAccessException("Low-level storage operation '" + operation
                    + "' is internal to SodaEconomy and is not part of the supported public API. "
                    + "Use EconomyTransactionApi for balance mutations and PlayerIdentityApi for identity lookups. Caller: " + caller);
        }
    }

    private static boolean isRelevantCaller(Class<?> type, Class<?> protectedType) {
        if (type == StorageAccessGuard.class || type == protectedType
                || type.getName().startsWith(protectedType.getName() + "$")) {
            return false;
        }
        return !isInfrastructureFrame(type.getName());
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
        return className.equals(STORAGE_PACKAGE)
                || className.startsWith(STORAGE_PACKAGE + ".")
                || className.equals(TRANSACTION_PACKAGE)
                || className.startsWith(TRANSACTION_PACKAGE + ".")
                || className.equals(IDENTITY_PACKAGE)
                || className.startsWith(IDENTITY_PACKAGE + ".");
    }
}
