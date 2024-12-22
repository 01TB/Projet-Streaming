package com.videostreaming.conf;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

public class ConfigLoader {
    private Properties properties;

    public ConfigLoader(Path path) {
        properties = new Properties();
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            properties.load(fis);
        } catch (IOException e) {
            System.err.println("Erreur de chargement du fichier de configuration: " + e.getMessage());
        }
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }
}
