package de.sodaeconomy.language;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

public class LanguageFileCreator {

    public static void createDefaultLanguageFile(JavaPlugin plugin) {
        try {
            File folder = new File(plugin.getDataFolder(), "language");
            if (!folder.exists()) folder.mkdirs();

            File target = new File(folder, "messages_en.yml");
            if (!target.exists()) {
                try (InputStream inputStream = plugin.getResource("messages_en.yml")) {
                    if (inputStream != null) {
                        Files.copy(inputStream, target.toPath());
                        plugin.getLogger().info("[Language] Default English messages file created.");
                    }
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().severe("[Language] Failed to create default messages_en.yml: " + exception.getMessage());
        }
    }
}
