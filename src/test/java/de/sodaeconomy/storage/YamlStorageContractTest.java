package de.sodaeconomy.storage;

class YamlStorageContractTest extends StorageContractTest {
    @Override
    protected Storage createStorage() {
        return new YamlStorage();
    }
}
