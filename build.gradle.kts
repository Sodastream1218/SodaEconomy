import java.util.zip.ZipFile

plugins {
    java
    jacoco
    id("com.gradleup.shadow") version "8.3.11"
}

group = "de.sodaeconomy"
version = "1.0.0"

java {
    // The CI runtime is Java 21; the released plugin remains Java 17 compatible.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.2-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        isTransitive = false
    }
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // Paper supplies the JDBC driver in production via plugin.yml libraries.
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.9")

    // The server API is provided by Paper in production, but MockBukkit tests compile and run against it.
    testImplementation("io.papermc.paper:paper-api:1.20.2-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.github.seeseemelk:MockBukkit-v1.20:3.58.1") {
        exclude(group = "io.papermc.paper", module = "paper-api")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
}

tasks.test {
    useJUnitPlatform {
        excludeTags("mysql-integration")
    }
}

val testSourceSet = sourceSets.named("test")
val mysqlIntegrationTest by tasks.registering(Test::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the MySQL integration tests against the isolated test database."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("mysql-integration")
    }
    shouldRunAfter(tasks.test)
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/mysqlIntegrationTest"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/mysqlIntegrationTest"))
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    // sqlite-jdbc binds native methods to the original org.sqlite JNI class names.
    // Relocating this package produces UnsatisfiedLinkError at runtime.
    mergeServiceFiles()
    exclude("META-INF/MANIFEST.MF")
    manifest {
        attributes["Main-Class"] = "de.sodaeconomy.SodaEconomy"
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

val verifyNoEmbeddedDatabaseServerDriver by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that the release JAR contains neither MySQL nor MariaDB Connector/J classes."
    dependsOn(tasks.shadowJar)

    doLast {
        val releaseJar = tasks.shadowJar.get().archiveFile.get().asFile
        val forbiddenPrefixes = listOf(
            "com/mysql/",
            "de/sodaeconomy/libs/mysql/",
            "org/mariadb/jdbc/",
            "com/google/protobuf/"
        )
        // Inspect the archive directly so package entry names are deterministic across Gradle versions.
        ZipFile(releaseJar).use { archive ->
            val matches = archive.entries().asSequence()
                .map { it.name }
                .filter { entry -> forbiddenPrefixes.any(entry::startsWith) }
                .toList()
            check(matches.isEmpty()) {
                "Release JAR contains an embedded database-server JDBC driver: ${matches.joinToString()}"
            }
            check(archive.getEntry("META-INF/THIRD_PARTY_NOTICES.md") != null) {
                "Release JAR does not contain META-INF/THIRD_PARTY_NOTICES.md"
            }
        }
    }
}

tasks.jacocoTestReport {
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/*.exec")
    })
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test, mysqlIntegrationTest)
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/*.exec")
    })
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf(
                "de.sodaeconomy.Money",
                "de.sodaeconomy.EconomyManager",
                "de.sodaeconomy.BankManager",
                "de.sodaeconomy.BankInterestTask",
                "de.sodaeconomy.storage.ConfigManager",
                "de.sodaeconomy.storage.StorageType",
                "de.sodaeconomy.language.LanguageManager",
                "de.sodaeconomy.language.LanguageFileCreator"
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(mysqlIntegrationTest)
    dependsOn(verifyNoEmbeddedDatabaseServerDriver)
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test, mysqlIntegrationTest)
}
