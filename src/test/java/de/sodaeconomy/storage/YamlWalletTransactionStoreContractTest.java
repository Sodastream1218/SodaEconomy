package de.sodaeconomy.storage;

class YamlWalletTransactionStoreContractTest extends WalletTransactionStoreContractTest {
    @Override
    protected Storage createStorage() {
        return new YamlStorage();
    }
}
