package de.sodaeconomy.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Isolated MySQL integration-test settings. Credentials are only read from environment variables. */
final class MySqlTestEnvironment {
    private static final String RESOURCE = "/mysql-integration.properties";

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final Map<String, String> parameters;

    private MySqlTestEnvironment(String host, int port, String database, String user, String password,
                                 Map<String, String> parameters) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.parameters = Map.copyOf(parameters);
    }

    static MySqlTestEnvironment load() {
        Properties properties = new Properties();
        try (InputStream input = MySqlTestEnvironment.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing test configuration resource " + RESOURCE);
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read MySQL integration test configuration", exception);
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        for (String property : properties.stringPropertyNames()) {
            if (property.startsWith("param.")) parameters.put(property.substring("param.".length()), properties.getProperty(property));
        }
        return new MySqlTestEnvironment(
                environmentValue(properties, "host.environment"),
                Integer.parseInt(environmentValue(properties, "port.environment")),
                environmentValue(properties, "database.environment"),
                environmentValue(properties, "user.environment"),
                environmentValue(properties, "password.environment"),
                parameters
        );
    }

    private static String environmentValue(Properties properties, String property) {
        String environmentName = properties.getProperty(property);
        String value = environmentName == null ? null : System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required test environment variable: " + environmentName);
        }
        return value;
    }

    void applyTo(JavaPlugin plugin) {
        plugin.getConfig().set("storage.type", "MYSQL");
        plugin.getConfig().set("storage.mysql.host", host);
        plugin.getConfig().set("storage.mysql.port", port);
        plugin.getConfig().set("storage.mysql.database", database);
        plugin.getConfig().set("storage.mysql.user", user);
        plugin.getConfig().set("storage.mysql.password", password);
        plugin.getConfig().set("storage.mysql.params", null);
        parameters.forEach((key, value) -> plugin.getConfig().set("storage.mysql.params." + key, value));
    }

    Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), user, password);
    }

    MySqlTestEnvironment withCredentials(String replacementUser, String replacementPassword) {
        return new MySqlTestEnvironment(host, port, database, replacementUser, replacementPassword, parameters);
    }

    MySqlTestEnvironment withEndpoint(String replacementHost, int replacementPort, int connectTimeoutMillis) {
        Map<String, String> replacementParameters = new LinkedHashMap<>(parameters);
        replacementParameters.put("connectTimeout", Integer.toString(connectTimeoutMillis));
        return new MySqlTestEnvironment(replacementHost, replacementPort, database, user, password, replacementParameters);
    }

    MySqlTestEnvironment withSocketTimeout(int socketTimeoutMillis) {
        Map<String, String> replacementParameters = new LinkedHashMap<>(parameters);
        replacementParameters.put("socketTimeout", Integer.toString(socketTimeoutMillis));
        return new MySqlTestEnvironment(host, port, database, user, password, replacementParameters);
    }

    String jdbcUrl() {
        StringBuilder result = new StringBuilder("jdbc:mysql://")
                .append(host).append(':').append(port).append('/').append(database);
        if (parameters.isEmpty()) return result.toString();
        result.append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!first) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            result.append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return result.toString();
    }
}
