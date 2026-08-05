package de.sodaeconomy.storage;

import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StorageManagerTest extends MockBukkitTestBase {

    @Test
    void initializesTheSelectedStorageWithoutMigrationOnAFirstInstallation() throws Exception {
        plugin.getStorageManager().close();
        File dataFolder = plugin.getDataFolder();
        Files.deleteIfExists(new File(dataFolder, "last-storage-type.txt").toPath());
        Files.deleteIfExists(new File(dataFolder, "storage/economy.db").toPath());

        StorageManager manager = new StorageManager(plugin);
        try {
            manager.init(StorageType.YAML);

            assertEquals(StorageType.YAML, manager.getCurrentType());
            assertInstanceOf(YamlStorage.class, manager.getCurrent());
            assertEquals("YAML", Files.readString(new File(dataFolder, "last-storage-type.txt").toPath()).trim());
        } finally {
            manager.close();
        }
    }
}
