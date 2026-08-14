package de.sodaeconomy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CompatibilityMetadataTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void mavenCompilerUsesReleaseSeventeenInsteadOfSourceTargetOnly() throws IOException {
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertTrue(pom.contains("<maven.compiler.release>${java.version}</maven.compiler.release>"));
        assertTrue(pom.contains("<release>${maven.compiler.release}</release>"));
        assertTrue(!pom.contains("<source>17</source>"),
                "Maven must use --release 17 so newer JDK APIs are not linked accidentally.");
        assertTrue(!pom.contains("<target>17</target>"),
                "Maven must use --release 17 so newer JDK APIs are not linked accidentally.");
    }

    @Test
    void gradleCompilerUsesReleaseSeventeen() throws IOException {
        String gradle = readOptionalBuildFile("build.gradle.kts");

        assertTrue(gradle.contains("options.release.set(17)"),
                "Gradle must keep Java 17 API compatibility for the released plugin JAR.");
    }

    @Test
    void pluginMetadataDeclaresTheModernPaperApiFloor() throws IOException {
        String pluginYml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/plugin.yml"));

        assertTrue(pluginYml.contains("api-version: 1.20"),
                "The compatibility baseline is Paper/Purpur 1.20.x; lowering this requires a separate audit.");
    }

    @Test
    void floodgateIntegrationRemainsOptionalAndDocumented() throws IOException {
        String pluginYml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/plugin.yml"));
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        String gradle = readOptionalBuildFile("build.gradle.kts");

        assertTrue(pluginYml.contains("softdepend: [Vault, floodgate, PlaceholderAPI]"),
                "Vault and Floodgate must remain optional soft dependencies.");
        assertTrue(!pom.contains("org.geysermc.floodgate"),
                "The main Maven build must not introduce a hard Floodgate dependency.");
        assertTrue(!gradle.contains("org.geysermc.floodgate"),
                "The main Gradle build must not introduce a hard Floodgate dependency.");
        assertTrue(Files.isRegularFile(PROJECT_ROOT.resolve("docs/player-identities.md")));
    }


    @Test
    void vaultIntegrationRemainsOptionalProvidedAndDocumented() throws IOException {
        String pluginYml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/plugin.yml"));
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        String gradle = readOptionalBuildFile("build.gradle.kts");

        assertTrue(pluginYml.contains("softdepend: [Vault, floodgate, PlaceholderAPI]"));
        assertTrue(!pluginYml.contains("provides: [Vault]"));
        assertTrue(pom.contains("<artifactId>VaultAPI</artifactId>"));
        assertTrue(pom.contains("<vaultapi.version>1.7</vaultapi.version>"),
                "VaultAPI must use the documented two-part 1.7 API coordinate.");
        assertTrue(pom.contains("<scope>provided</scope>"));
        assertTrue(gradle.contains("compileOnly(\"com.github.MilkBowl:VaultAPI:1.7\")"));
        assertTrue(gradle.contains("testImplementation(\"com.github.MilkBowl:VaultAPI:1.7\")"),
                "Gradle test compilation must include VaultAPI because test source sets do not inherit compileOnly.");
        assertTrue(gradle.contains("net/milkbowl/vault/"),
                "The release-JAR verifier must reject accidentally shaded Vault API classes.");
        assertTrue(pom.contains("<artifactId>bukkit</artifactId>"),
                "VaultAPI's legacy transitive Bukkit artifact must stay excluded.");
        assertTrue(Files.isRegularFile(PROJECT_ROOT.resolve("docs/vault-integration.md")));

        String buildContracts = Files.readString(PROJECT_ROOT.resolve("docs/build-contracts.md"));
        assertTrue(buildContracts.contains("VaultAPI"));
        assertTrue(buildContracts.contains("Maven scope: provided"));
        assertTrue(buildContracts.contains("Gradle production configuration: compileOnly"));
        assertTrue(buildContracts.contains("Gradle test configuration: testImplementation"));
        assertTrue(buildContracts.contains("Transitive dependencies: disabled for both configurations"));
    }


    @Test
    void placeholderApiIntegrationIsOptionalProvidedAndNeverShaded() throws IOException {
        String pluginYml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/plugin.yml"));
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        String gradle = readOptionalBuildFile("build.gradle.kts");
        String workflow = Files.readString(PROJECT_ROOT.resolve(".github/workflows/test.yml"));

        assertTrue(pluginYml.contains("softdepend: [Vault, floodgate, PlaceholderAPI]"));
        assertTrue(pom.contains("<artifactId>placeholderapi</artifactId>"));
        assertTrue(pom.contains("<placeholderapi.version>2.12.3</placeholderapi.version>"));
        assertTrue(pom.contains("<scope>provided</scope>"));
        assertTrue(pom.contains("https://repo.helpch.at/releases/"));
        assertTrue(gradle.contains("compileOnly(\"me.clip:placeholderapi:2.12.3\")"));
        assertTrue(gradle.contains("me/clip/placeholderapi/"),
                "The release-JAR verifier must reject accidentally shaded PlaceholderAPI classes.");
        assertTrue(workflow.contains("me/clip/placeholderapi/"));
        assertTrue(workflow.contains("net/milkbowl/vault/"));
        assertTrue(Files.isRegularFile(PROJECT_ROOT.resolve("docs/placeholderapi-integration.md")));

        String bootstrap = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/integration/placeholderapi/PlaceholderApiIntegrationBootstrap.java"));
        String pluginMain = Files.readString(PROJECT_ROOT.resolve("src/main/java/de/sodaeconomy/SodaEconomy.java"));
        assertTrue(!bootstrap.contains("import me.clip.placeholderapi"),
                "The optional bootstrap must be loadable without PlaceholderAPI classes.");
        assertTrue(!pluginMain.contains("import me.clip.placeholderapi"),
                "The core plugin class must not link PlaceholderAPI types directly.");

        String notices = Files.readString(PROJECT_ROOT.resolve("THIRD_PARTY_NOTICES.md"));
        assertTrue(notices.contains("PlaceholderAPI 2.12.3"));
        assertTrue(notices.contains("GPL-3.0"));
        assertTrue(notices.contains("not bundled"));
    }

    @Test
    void jdbcDriverIsPaperProvidedMariaDbAndNeverShaded() throws IOException {
        String pluginYml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/plugin.yml"));
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        String gradle = readOptionalBuildFile("build.gradle.kts");

        assertTrue(pluginYml.contains("org.mariadb.jdbc:mariadb-java-client:3.5.9"));
        assertTrue(!pom.contains("<groupId>com.mysql</groupId>"));
        assertTrue(!pom.contains("<artifactId>mysql-connector-j</artifactId>"));
        assertTrue(pom.contains("<artifactId>mariadb-java-client</artifactId>"));
        assertTrue(pom.contains("<scope>test</scope>"));
        assertTrue(!pom.contains("<pattern>com.mysql</pattern>"));
        assertTrue(!gradle.contains("com.mysql:mysql-connector-j"));
        assertTrue(gradle.contains("testRuntimeOnly(\"org.mariadb.jdbc:mariadb-java-client:3.5.9\")"));
        assertTrue(!gradle.contains("relocate(\"com.mysql\""));
        assertTrue(Files.isRegularFile(PROJECT_ROOT.resolve("docs/jdbc-driver-migration.md")));
        Path notices = PROJECT_ROOT.resolve("THIRD_PARTY_NOTICES.md");
        Path packagedNotices = PROJECT_ROOT.resolve("src/main/resources/META-INF/THIRD_PARTY_NOTICES.md");
        assertTrue(Files.isRegularFile(notices));
        assertTrue(Files.isRegularFile(packagedNotices));
        assertTrue(Files.readString(notices).equals(Files.readString(packagedNotices)),
                "The packaged third-party notices must match the repository notice inventory.");
    }


    @Test
    void mysqlMariaDbSharedJdbcSqlAvoidsDialectSpecificPreparedStatementSyntax() throws IOException {
        String mysqlStorage = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/storage/MySQLStorage.java"));

        assertTrue(!mysqlStorage.contains("LIMIT ? FOR UPDATE"),
                "The legacy MySQL/MariaDB balance migration must avoid parameterized LIMIT ... FOR UPDATE syntax "
                        + "because supported database products differ on this prepared-statement edge case.");
        assertTrue(mysqlStorage.contains("LIMIT \" + BALANCE_MIGRATION_BATCH_SIZE"),
                "The balance migration batch size must remain an internal constant, not user-controlled SQL.");
        assertTrue(!mysqlStorage.contains(" FOR SHARE"),
                "The shared mutation-gate read must avoid MySQL-only FOR SHARE syntax so MariaDB 11.8 can "
                        + "initialize the schema.");
        assertTrue(mysqlStorage.contains("LOCK IN SHARE MODE"),
                "The shared mutation-gate read should keep shared-lock semantics through syntax accepted by "
                        + "both supported database products.");
    }


    @Test
    void mysqlMariaDbTransactionRetryPolicyIsSizedForCrossInstanceConcurrency() throws IOException {
        String mysqlStorage = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/storage/MySQLStorage.java"));

        assertTrue(mysqlStorage.contains("MYSQL_TRANSACTION_RETRY_ATTEMPTS = 12"),
                "The MySQL/MariaDB transaction retry budget must cover transient InnoDB deadlocks observed "
                        + "under cross-instance MariaDB concurrency.");
        assertTrue(mysqlStorage.contains("TRANSACTION_READ_COMMITTED"),
                "The shared JDBC storage path should use READ COMMITTED with explicit row locks to reduce "
                        + "MariaDB gap-lock contention while preserving wallet-row serialization.");
        assertTrue(mysqlStorage.contains("ThreadLocalRandom.current().nextLong"),
                "Retry backoff should include jitter so parallel storage instances do not repeatedly collide.");
    }

    @Test
    void gradleIsCanonicalAndMavenParityIsVerifiedInCi() throws IOException {
        String workflow = Files.readString(PROJECT_ROOT.resolve(".github/workflows/test.yml"));
        String readme = Files.readString(PROJECT_ROOT.resolve("README.md"));
        String contracts = Files.readString(PROJECT_ROOT.resolve("docs/build-contracts.md"));

        assertTrue(readme.contains("Gradle is the **canonical release build**"));
        assertTrue(contracts.contains("**Gradle is the canonical SodaEconomy release build.**"));
        assertTrue(workflow.contains("name: Maven build parity"));
        assertTrue(workflow.contains("./mvnw -B -ntp clean verify"));
        assertTrue(workflow.contains("./gradlew --no-daemon mysqlIntegrationTest"));
    }

    @Test
    void mavenAndGradleReleaseCriticalDependencyVersionsStayAligned() throws IOException {
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        String gradle = readOptionalBuildFile("build.gradle.kts");

        assertTrue(pom.contains("<version>1.0.0</version>") && gradle.contains("version = \"1.0.0\""));
        assertTrue(pom.contains("<version>1.20.2-R0.1-SNAPSHOT</version>")
                && gradle.contains("paper-api:1.20.2-R0.1-SNAPSHOT"));
        assertTrue(pom.contains("<placeholderapi.version>2.12.3</placeholderapi.version>")
                && gradle.contains("placeholderapi:2.12.3"));
        assertTrue(pom.contains("<vaultapi.version>1.7</vaultapi.version>")
                && gradle.contains("VaultAPI:1.7"));
        assertTrue(pom.contains("<version>3.46.1.3</version>")
                && gradle.contains("sqlite-jdbc:3.46.1.3"));
        assertTrue(pom.contains("<mariadb.connector.version>3.5.9</mariadb.connector.version>")
                && gradle.contains("mariadb-java-client:3.5.9"));
        assertTrue(pom.contains("<junit.version>5.10.1</junit.version>")
                && gradle.contains("junit-bom:5.10.1"));
        assertTrue(pom.contains("<mockbukkit.version>3.58.1</mockbukkit.version>")
                && gradle.contains("MockBukkit-v1.20:3.58.1"));
    }

    @Test
    void publicMetadataAndDefaultDatabaseAccountAreReleaseSafe() throws IOException {
        String pluginYml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/plugin.yml"));
        String config = Files.readString(PROJECT_ROOT.resolve("src/main/resources/config.yml"));
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertTrue(pluginYml.contains("description: Production-focused economy system"));
        assertTrue(!pluginYml.contains("Zeigt dein") && !pluginYml.contains("Erlaubt die Nutzung"));
        assertTrue(config.contains("user: sodaeconomy"));
        assertTrue(!config.contains("user: root"));
        String mysqlStorage = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/storage/MySQLStorage.java"));
        assertTrue(mysqlStorage.contains("getString(\"storage.mysql.user\", \"sodaeconomy\")"));
        assertTrue(pom.contains("<description>A production-focused economy system for Minecraft Paper</description>"));
    }

    @Test
    void preReleaseDeprecationMetadataAndExactMoneyApiAreFrozenForVersionOne() throws IOException {
        String economyManager = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/EconomyManager.java"));
        String pluginMain = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/SodaEconomy.java"));
        String storage = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/storage/Storage.java"));
        String storageManager = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/storage/StorageManager.java"));
        String api = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/de/sodaeconomy/transaction/EconomyTransactionApi.java"));

        assertTrue(!economyManager.contains("since = \"1.1\"")
                && !pluginMain.contains("since = \"1.1\"")
                && !storage.contains("since = \"1.1\"")
                && !storageManager.contains("since = \"1.1\""));
        assertTrue(economyManager.contains("@Deprecated(since = \"1.0\", forRemoval = true)"));
        assertTrue(api.contains("depositMinor(UUID targetPlayerId, long amountMinor"));
        assertTrue(api.contains("BigDecimal amount"));
    }

    @Test
    void compatibilityDocumentationExists() {
        Path documentation = PROJECT_ROOT.resolve("docs/compatibility.md");
        assertTrue(Files.isRegularFile(documentation),
                () -> "Cross-version release decisions must remain documented at " + documentation);
    }

    private static String readOptionalBuildFile(String fileName) throws IOException {
        Path path = PROJECT_ROOT.resolve(fileName);
        return Files.isRegularFile(path) ? Files.readString(path) : "";
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("src/main/resources"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the SodaEconomy project root from "
                + Path.of("").toAbsolutePath().normalize());
    }
}
