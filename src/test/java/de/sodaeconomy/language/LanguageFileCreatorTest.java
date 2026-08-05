package de.sodaeconomy.language;

import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageFileCreatorTest extends MockBukkitTestBase {

    @Test
    void recreatesTheBundledEnglishFileWhenTheLanguageDirectoryIsMissing() throws Exception {
        File languageDirectory = new File(plugin.getDataFolder(), "language");
        File messagesFile = new File(languageDirectory, "messages_en.yml");
        Files.deleteIfExists(messagesFile.toPath());
        Files.deleteIfExists(languageDirectory.toPath());

        LanguageFileCreator.createDefaultLanguageFile(plugin);

        assertTrue(messagesFile.isFile());
    }

    @Test
    void handlesAnUnavailableLanguageDirectoryWithoutPropagatingAnException() throws Exception {
        File languageDirectory = new File(plugin.getDataFolder(), "language");
        File messagesFile = new File(languageDirectory, "messages_en.yml");
        Files.deleteIfExists(messagesFile.toPath());
        Files.deleteIfExists(languageDirectory.toPath());
        Files.createFile(languageDirectory.toPath());

        Level previousLevel = plugin.getLogger().getLevel();
        plugin.getLogger().setLevel(Level.OFF);
        try {
            assertDoesNotThrow(() -> LanguageFileCreator.createDefaultLanguageFile(plugin));
        } finally {
            plugin.getLogger().setLevel(previousLevel);
        }

        assertFalse(messagesFile.exists());
    }
}
