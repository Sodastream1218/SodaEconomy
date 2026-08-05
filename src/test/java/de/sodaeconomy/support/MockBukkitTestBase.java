package de.sodaeconomy.support;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import de.sodaeconomy.SodaEconomy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/** Creates and releases MockBukkit's global server state for each integration test. */
public abstract class MockBukkitTestBase {
    protected ServerMock server;
    protected SodaEconomy plugin;

    @BeforeEach
    void startPlugin() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(SodaEconomy.class);
    }

    @AfterEach
    void stopPlugin() {
        if (MockBukkit.isMocked()) MockBukkit.unmock();
    }

    /**
     * Waits until an asynchronous command has scheduled its Bukkit-thread completion callback,
     * then executes that callback on the MockBukkit scheduler. Production commands deliberately
     * keep storage work off the main thread, so tests must not assert immediately after dispatch.
     */
    protected void awaitAsyncCommandResult() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        long quietSince = -1L;
        boolean executedScheduledWork = false;
        while (System.nanoTime() < deadline) {
            if (!server.getScheduler().getPendingTasks().isEmpty()) {
                server.getScheduler().performOneTick();
                executedScheduledWork = true;
                quietSince = -1L;
            } else if (executedScheduledWork) {
                if (quietSince < 0L) {
                    quietSince = System.nanoTime();
                } else if (System.nanoTime() - quietSince >= TimeUnit.MILLISECONDS.toNanos(50L)) {
                    return;
                }
            }
            Thread.sleep(5L);
        }
        fail("The asynchronous command did not finish its Bukkit completion callbacks within 5 seconds");
    }
}
