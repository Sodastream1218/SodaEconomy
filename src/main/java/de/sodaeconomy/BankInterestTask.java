package de.sodaeconomy;

import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.Objects;

/** Executes periodic interest processing for all bank accounts. */
public class BankInterestTask extends BukkitRunnable {

    private final BankManager bankManager;
    private final double rate;
    private final double maxInterest;
    private final Duration minimumInterval;

    public BankInterestTask(BankManager bankManager, double rate, double maxInterest) {
        this(bankManager, rate, maxInterest, Duration.ZERO);
    }

    public BankInterestTask(BankManager bankManager, double rate, double maxInterest, Duration minimumInterval) {
        this.bankManager = Objects.requireNonNull(bankManager, "bankManager");
        this.rate = rate;
        this.maxInterest = maxInterest;
        this.minimumInterval = Objects.requireNonNull(minimumInterval, "minimumInterval");
    }

    @Override
    public void run() {
        bankManager.applyInterest(rate, maxInterest, minimumInterval);
    }
}
