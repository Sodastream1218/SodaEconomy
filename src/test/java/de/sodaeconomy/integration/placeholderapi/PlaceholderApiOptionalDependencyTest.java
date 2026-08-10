package de.sodaeconomy.integration.placeholderapi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import de.sodaeconomy.SodaEconomy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlaceholderApiOptionalDependencyTest {

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void sodaEconomyStartsNormallyWhenPlaceholderApiPluginIsAbsent() {
        MockBukkit.mock();

        SodaEconomy plugin = MockBukkit.load(SodaEconomy.class);

        assertTrue(plugin.isEnabled());
    }
}
