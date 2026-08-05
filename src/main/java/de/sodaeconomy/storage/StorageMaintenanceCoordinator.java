package de.sodaeconomy.storage;

/**
 * Optional storage capability used to coordinate destructive maintenance such as full snapshot
 * replacement across multiple plugin instances. Implementations must prevent new mutations after
 * the lease was acquired and wait for already active mutations to finish before returning.
 */
interface StorageMaintenanceCoordinator {

    MaintenanceLease acquireMaintenanceLease(String reason) throws Exception;

    interface MaintenanceLease extends AutoCloseable {
        @Override
        void close() throws Exception;
    }
}
