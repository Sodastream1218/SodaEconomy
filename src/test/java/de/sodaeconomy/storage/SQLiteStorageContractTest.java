package de.sodaeconomy.storage;

class SQLiteStorageContractTest extends StorageContractTest {
    @Override
    protected Storage createStorage() {
        return new SQLiteStorage();
    }
}
