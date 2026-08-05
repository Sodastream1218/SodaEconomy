package de.sodaeconomy.storage;

class SQLiteWalletTransactionStoreContractTest extends WalletTransactionStoreContractTest {
    @Override
    protected Storage createStorage() {
        return new SQLiteStorage();
    }
}
