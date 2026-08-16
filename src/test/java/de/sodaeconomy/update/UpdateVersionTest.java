package de.sodaeconomy.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateVersionTest {
    @Test
    void comparesSemanticVersionsNumerically() {
        UpdateVersion oneTen = UpdateVersion.parse("1.10.0").orElseThrow();
        UpdateVersion oneNine = UpdateVersion.parse("1.9.0").orElseThrow();
        assertTrue(oneTen.compareTo(oneNine) > 0);
    }

    @Test
    void supportsVPrefixAndSemanticPrereleasePrecedence() {
        UpdateVersion stable = UpdateVersion.parse("v1.0.0").orElseThrow();
        UpdateVersion rc = UpdateVersion.parse("1.0.0-rc.1").orElseThrow();
        UpdateVersion beta = UpdateVersion.parse("1.0.0-beta.2").orElseThrow();
        UpdateVersion alpha = UpdateVersion.parse("1.0.0-alpha.9").orElseThrow();

        assertEquals("1.0.0", stable.display());
        assertTrue(stable.compareTo(rc) > 0);
        assertTrue(rc.compareTo(beta) > 0);
        assertTrue(beta.compareTo(alpha) > 0);
    }

    @Test
    void recognizesDevelopmentBuildsWithoutTreatingThemAsReleases() {
        assertTrue(UpdateVersion.parse("1.0.0-SNAPSHOT").orElseThrow().isDevelopment());
        assertTrue(UpdateVersion.parse("1.0.0-dev").orElseThrow().isDevelopment());
        assertTrue(UpdateVersion.parse("dev").orElseThrow().isDevelopment());
        assertTrue(UpdateVersion.parse("unknown").orElseThrow().isDevelopment());
    }

    @Test
    void rejectsInvalidOrUnsupportedReleaseLabels() {
        assertTrue(UpdateVersion.parse("1.0").isEmpty());
        assertTrue(UpdateVersion.parse("1.0.0-preview.1").isEmpty());
        assertTrue(UpdateVersion.parse("not-a-version").isEmpty());
        assertTrue(UpdateVersion.parse("1.0.0-alpha.01").isEmpty());
    }

    @Test
    void channelsIncludeOnlySameOrMoreMatureReleases() {
        assertTrue(UpdateChannel.STABLE.allows(UpdateChannel.STABLE));
        assertFalse(UpdateChannel.STABLE.allows(UpdateChannel.RC));
        assertTrue(UpdateChannel.RC.allows(UpdateChannel.STABLE));
        assertTrue(UpdateChannel.RC.allows(UpdateChannel.RC));
        assertFalse(UpdateChannel.RC.allows(UpdateChannel.BETA));
        assertTrue(UpdateChannel.ALPHA.allows(UpdateChannel.BETA));
    }
}
