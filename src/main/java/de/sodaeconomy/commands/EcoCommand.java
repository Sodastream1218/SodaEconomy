package de.sodaeconomy.commands;

import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.Money;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.storage.RuntimeConfigReloadException;
import de.sodaeconomy.storage.RuntimeConfigSnapshot;
import de.sodaeconomy.transaction.EconomyAnalytics;
import de.sodaeconomy.transaction.EconomyStatistics;
import de.sodaeconomy.transaction.PlayerTransactionStatistics;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionPage;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.TransactionService;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.update.UpdateCheckResult;
import de.sodaeconomy.update.UpdateCheckStatus;
import de.sodaeconomy.update.UpdateCheckerService;
import de.sodaeconomy.update.UpdateNotificationService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Handles localized economy administration, ledger, audit, rollback, and statistic commands. */
public final class EcoCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN_PERMISSION = "sodaeconomy.admin";
    private static final String HISTORY_SELF_PERMISSION = "sodaeconomy.history.self";
    private static final String HISTORY_OTHERS_PERMISSION = "sodaeconomy.history.others";
    private static final String TRANSACTION_PERMISSION = "sodaeconomy.transaction";
    private static final String ROLLBACK_PERMISSION = "sodaeconomy.rollback";
    private static final String AUDIT_PERMISSION = "sodaeconomy.audit";
    private static final String STATISTICS_PERMISSION = "sodaeconomy.stats";
    private static final String RELOAD_PERMISSION = "sodaeconomy.admin.reload";
    private static final String UPDATE_PERMISSION = UpdateNotificationService.UPDATE_PERMISSION;
    private static final long UPDATE_CHECK_COOLDOWN_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60L);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final EconomyManager economyManager;
    private final SodaEconomy plugin;
    private final TransactionService transactionService;
    private final PlayerIdentityApi playerIdentityApi;
    private final UpdateCheckerService updateCheckerService;
    private final UpdateNotificationService updateNotificationService;
    private long lastManualUpdateCheckNanos = Long.MIN_VALUE;

    private record PlayerStatisticsView(double balance, PlayerTransactionStatistics statistics) {
    }

    private record BatchRecipient(UUID playerId, String playerName) {
    }

    private record BatchExecution(List<BatchRecipient> successfulRecipients, int totalRecipients) {
    }

    @SuppressWarnings("removal")
    public EcoCommand(EconomyManager economyManager) {
        this(economyManager, economyManager.getTransactionService());
    }

    public EcoCommand(EconomyManager economyManager, TransactionService transactionService) {
        this(economyManager, transactionService, SodaEconomy.getInstance().getPlayerIdentityApi());
    }

    public EcoCommand(EconomyManager economyManager, TransactionService transactionService,
                      PlayerIdentityApi playerIdentityApi) {
        this(economyManager, transactionService, playerIdentityApi, null, null);
    }

    public EcoCommand(EconomyManager economyManager, TransactionService transactionService,
                      PlayerIdentityApi playerIdentityApi, UpdateCheckerService updateCheckerService,
                      UpdateNotificationService updateNotificationService) {
        this.economyManager = economyManager;
        this.transactionService = transactionService;
        this.playerIdentityApi = java.util.Objects.requireNonNull(playerIdentityApi, "playerIdentityApi");
        this.updateCheckerService = updateCheckerService;
        this.updateNotificationService = updateNotificationService;
        plugin = SodaEconomy.getInstance();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.debugCommand(sender.getName() + " executed /" + label);
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "set", "give", "remove" -> {
                if (requirePermission(sender, ADMIN_PERMISSION)) handleSetGiveRemove(sender, subcommand, args);
            }
            case "reset" -> {
                if (requirePermission(sender, ADMIN_PERMISSION)) handleReset(sender, args);
            }
            case "balance" -> {
                if (requirePermission(sender, ADMIN_PERMISSION)) handleBalance(sender, args);
            }
            case "stats" -> {
                if (requirePermission(sender, STATISTICS_PERMISSION)) handleStats(sender, args);
            }
            case "payall" -> {
                if (requirePermission(sender, ADMIN_PERMISSION)) handlePayAll(sender, args);
            }
            case "multipay" -> {
                if (requirePermission(sender, ADMIN_PERMISSION)) handleMultiPay(sender, args);
            }
            case "history" -> handleHistory(sender, args);
            case "transaction" -> {
                if (requirePermission(sender, TRANSACTION_PERMISSION)) handleTransaction(sender, args);
            }
            case "rollback" -> {
                if (requirePermission(sender, ROLLBACK_PERMISSION)) handleRollback(sender, args);
            }
            case "audit" -> {
                if (requirePermission(sender, AUDIT_PERMISSION)) handleAudit(sender, args);
            }
            case "version" -> {
                if (requireUpdatePermission(sender)) handleVersion(sender, args);
            }
            case "reload" -> {
                if (requirePermission(sender, RELOAD_PERMISSION)) handleReload(sender, args);
            }
            default -> {
                sendMessage(sender, "unknown-subcommand");
                showHelp(sender);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterCompletions(availableSubcommands(sender), args[0]);
        }
        if (args.length == 2 && "version".equalsIgnoreCase(args[0])
                && updateCheckerService != null && sender.hasPermission(UPDATE_PERMISSION)) {
            return filterCompletions(List.of("check"), args[1]);
        }
        if (args.length == 2 && acceptsPlayerName(args[0]) && hasPlayerArgumentPermission(sender, args[0])) {
            return playerIdentityApi.suggestKnownNames(args[1], 50);
        }
        return List.of();
    }

    private void showHelp(CommandSender sender) {
        sendMessage(sender, "eco-help-header");
        if (hasPermission(sender, HISTORY_SELF_PERMISSION)) sendMessage(sender, "show-history");
        if (hasPermission(sender, HISTORY_OTHERS_PERMISSION)) sendMessage(sender, "show-player-history");
        if (hasPermission(sender, TRANSACTION_PERMISSION)) sendMessage(sender, "show-transaction");
        if (hasPermission(sender, ROLLBACK_PERMISSION)) sendMessage(sender, "rollback-transaction");
        if (hasPermission(sender, AUDIT_PERMISSION)) sendMessage(sender, "show-audit");
        if (hasPermission(sender, STATISTICS_PERMISSION)) sendMessage(sender, "show-stats");
        if (hasPermission(sender, RELOAD_PERMISSION)) sendMessage(sender, "reload-config");
        if (updateCheckerService != null && sender.hasPermission(UPDATE_PERMISSION)) sendMessage(sender, "show-version");
        if (!hasPermission(sender, ADMIN_PERMISSION)) return;

        sendMessage(sender, "set-player-balance");
        sendMessage(sender, "give-player-money");
        sendMessage(sender, "remove-player-money");
        sendMessage(sender, "reset-player-balance");
        sendMessage(sender, "show-player-balance");
        sendMessage(sender, "pay-all");
        sendMessage(sender, "multi-pay");
    }

    private void handleVersion(CommandSender sender, String[] args) {
        if (updateCheckerService == null) {
            sendMessage(sender, "update-checker-unavailable");
            return;
        }
        if (args.length == 1) {
            showVersionResult(sender, updateCheckerService.lastResult());
            return;
        }
        if (args.length != 2 || !"check".equalsIgnoreCase(args[1])) {
            sendMessage(sender, "eco-version-usage");
            return;
        }

        if (!updateCheckerService.settings().enabled()) {
            showVersionResult(sender, updateCheckerService.lastResult());
            return;
        }

        long now = System.nanoTime();
        if (lastManualUpdateCheckNanos != Long.MIN_VALUE) {
            long elapsed = now - lastManualUpdateCheckNanos;
            if (elapsed >= 0L && elapsed < UPDATE_CHECK_COOLDOWN_NANOS) {
                long remainingNanos = UPDATE_CHECK_COOLDOWN_NANOS - elapsed;
                long seconds = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(remainingNanos) + 1L);
                sendMessage(sender, "eco-version-check-cooldown", "seconds", seconds);
                return;
            }
        }
        lastManualUpdateCheckNanos = now;

        sendMessage(sender, "eco-version-check-started");
        updateCheckerService.deliverOnMainThread(updateCheckerService.checkNowForced(),
                result -> showVersionResult(sender, result));
    }

    private void showVersionResult(CommandSender sender, UpdateCheckResult result) {
        sendMessage(sender, "eco-version-header");
        sendMessage(sender, "eco-version-installed", "version", result.installedVersion());
        sendMessage(sender, "eco-version-latest", "version", result.latest()
                .map(version -> version.display())
                .orElse(plugin.getLanguageManager().getMessage("transaction-not-applicable")));
        sendMessage(sender, "eco-version-status", "status", localizedUpdateStatus(result.status()));
        sendMessage(sender, "eco-version-channel", "channel", result.channel().configValue());
        sendMessage(sender, "eco-version-source", "source", result.source());
        if (result.releasePageOptional().isPresent()) {
            if (updateNotificationService != null) {
                updateNotificationService.sendReleaseLink(sender, result.releasePageOptional().orElseThrow());
            } else {
                sendMessage(sender, "update-release-url", "url", result.releasePageOptional().orElseThrow());
            }
        }
    }

    private String localizedUpdateStatus(UpdateCheckStatus status) {
        String key = switch (status) {
            case NOT_CHECKED -> "update-status-not-checked";
            case UP_TO_DATE -> "update-status-up-to-date";
            case UPDATE_AVAILABLE -> "update-status-update-available";
            case DEVELOPMENT_BUILD -> "update-status-development-build";
            case CHECK_DISABLED -> "update-status-check-disabled";
            case CHECK_FAILED -> "update-status-check-failed";
            case NO_RELEASES_FOUND -> "update-status-no-releases";
            case INVALID_REMOTE_VERSION -> "update-status-invalid-remote-version";
        };
        return plugin.getLanguageManager().getMessage(key);
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (args.length != 1) {
            showHelp(sender);
            return;
        }
        try {
            RuntimeConfigSnapshot snapshot = plugin.reloadSafeRuntimeConfiguration();
            sendMessage(sender, "eco-reload-success",
                    "language", plugin.getLanguageManager().getLanguageCode(),
                    "topEntries", snapshot.leaderboard().topEntries(),
                    "maxEntries", snapshot.leaderboard().maxEntries());
        } catch (RuntimeConfigReloadException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "[Config] Safe runtime reload failed; the previous runtime settings remain active.", exception);
            sendMessage(sender, "eco-reload-failed");
        }
    }

    private void handleSetGiveRemove(CommandSender sender, String subcommand, String[] args) {
        if (args.length < 3) {
            showHelp(sender);
            return;
        }

        Player target = playerIdentityApi.findOnlinePlayer(args[1]).orElse(null);
        if (target == null) {
            sendMessage(sender, "player-not-online", "player", args[1]);
            return;
        }

        double amount = parseAmount(args[2]);
        if (!Money.isValid(amount) || amount < 0D || (!"set".equals(subcommand) && !Money.isPositive(amount))) {
            sendMessage(sender, "invalid-amount");
            return;
        }

        TransactionOrigin origin = transactionOrigin(sender);
        CompletableFuture<TransactionResult> resultFuture = switch (subcommand) {
            case "set" -> transactionService.setBalanceAsynchronously(target.getUniqueId(), amount,
                    TransactionType.ADMIN_SET, origin, "Administrator balance set", Map.of("command", "eco set"));
            case "give" -> transactionService.depositAsynchronously(target.getUniqueId(), amount,
                    TransactionType.ADMIN_GIVE, origin, "Administrator balance credit", Map.of("command", "eco give"));
            case "remove" -> transactionService.withdrawAsynchronously(target.getUniqueId(), amount,
                    TransactionType.ADMIN_TAKE, origin, "Administrator balance debit", Map.of("command", "eco remove"));
            default -> throw new IllegalArgumentException("Unsupported economy mutation: " + subcommand);
        };
        resultFuture.whenComplete((result, throwable) -> runOnMainThread(() -> sendMutationResult(sender, target,
                subcommand, result, throwable)));
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            showHelp(sender);
            return;
        }

        Player target = playerIdentityApi.findOnlinePlayer(args[1]).orElse(null);
        if (target == null) {
            sendMessage(sender, "player-not-online", "player", args[1]);
            return;
        }

        transactionService.setBalanceAsynchronously(target.getUniqueId(),
                economyManager.getStartingBalance(), TransactionType.ADMIN_RESET, transactionOrigin(sender),
                "Administrator balance reset", Map.of("command", "eco reset"))
                .whenComplete((result, throwable) -> runOnMainThread(() -> {
                    if (throwable != null || result == null || !result.isSuccessful()) {
                        sendMutationFailure(sender, target, result);
                        return;
                    }
                    sendMessage(sender, "eco-reset-success", "player", target.getName());
                    notifyTarget(sender, target, "eco-reset-target");
                }));
    }

    private void handleBalance(CommandSender sender, String[] args) {
        if (args.length < 2) {
            showHelp(sender);
            return;
        }

        Player target = playerIdentityApi.findOnlinePlayer(args[1]).orElse(null);
        if (target == null) {
            sendMessage(sender, "player-not-online", "player", args[1]);
            return;
        }

        transactionService.getBalanceAsynchronously(target.getUniqueId())
                .whenComplete((balance, throwable) -> runOnMainThread(() -> {
                    if (throwable != null || balance == null) {
                        sendMessage(sender, "economy-read-unavailable");
                        return;
                    }
                    sendMessage(sender, "eco-show-balance", "player", target.getName(),
                            "balance", economyManager.formatCurrency(balance));
                }));
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sendMessage(sender, "eco-stats-usage");
            return;
        }
        if (args.length == 2) {
            withKnownIdentity(sender, args[1], target -> reportReadCompletion(sender, transactionService
                    .getStoredBalanceAsynchronously(target.playerId())
                    .thenCombine(transactionService.getPlayerStatistics(target.playerId()), PlayerStatisticsView::new)
                    .thenAccept(view -> runOnMainThread(() -> sendPlayerStatistics(sender,
                            target.lastKnownName(), view.balance(), view.statistics())))));
            return;
        }
        reportReadCompletion(sender, transactionService.getAnalytics().thenCompose(analytics -> playerIdentityApi
                .resolveDisplayNames(analytics.statistics().richestPlayerId() == null
                        ? List.of() : List.of(analytics.statistics().richestPlayerId()))
                .thenAccept(names -> runOnMainThread(() -> sendServerStatistics(sender, analytics, names)))));
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (!hasPermission(sender, HISTORY_SELF_PERMISSION)) {
            sendMessage(sender, "no-permission");
            return;
        }

        if (args.length == 1 || (args.length == 2 && isPositiveInteger(args[1]))) {
            if (!(sender instanceof Player player)) {
                sendMessage(sender, "eco-history-console-usage");
                return;
            }
            int page = args.length == 2 ? parsePage(sender, args[1]) : 1;
            if (page < 0) return;
            requestHistory(sender, player.getUniqueId(), player.getName(), page, "/eco history");
            return;
        }

        if (!requirePermission(sender, HISTORY_OTHERS_PERMISSION)) return;
        if (args.length > 3) {
            sendMessage(sender, "eco-history-usage");
            return;
        }
        int page = args.length == 3 ? parsePage(sender, args[2]) : 1;
        if (page < 0) return;
        withKnownIdentity(sender, args[1], target -> requestHistory(sender, target.playerId(),
                target.lastKnownName(), page, "/eco history " + target.lastKnownName()));
    }

    private void requestHistory(CommandSender sender, UUID playerId, String playerName, int pageNumber,
                                String pageCommandPrefix) {
        int pageSize = plugin.getConfigManager().getTransactionHistoryPageSize();
        int offset;
        try {
            offset = Math.multiplyExact(pageNumber - 1, pageSize);
        } catch (ArithmeticException exception) {
            sendMessage(sender, "eco-history-invalid-page");
            return;
        }
        TransactionQuery query = new TransactionQuery(null, playerId, null, null, null, null, null, null,
                offset, pageSize);
        reportReadCompletion(sender, transactionService.findTransactions(query).thenCompose(result -> playerIdentityApi
                .resolveDisplayNames(participantIds(result.records()))
                .thenAccept(names -> runOnMainThread(() -> sendHistory(sender, playerName, pageNumber,
                        pageCommandPrefix, result, names)))));
    }

    private void handleTransaction(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sendMessage(sender, "eco-transaction-usage");
            return;
        }
        Optional<UUID> transactionId = parseTransactionId(args[1]);
        if (transactionId.isEmpty()) {
            sendMessage(sender, "eco-transaction-invalid-id");
            return;
        }
        TransactionQuery query = new TransactionQuery(transactionId.get(), null, null, null, null, null,
                null, null, 0, 1);
        reportReadCompletion(sender, transactionService.findTransactions(query).thenCompose(page -> {
            if (page.records().isEmpty()) {
                return CompletableFuture.completedFuture(Map.<UUID, String>of()).thenAccept(ignored ->
                        runOnMainThread(() -> sendMessage(sender, "eco-transaction-not-found",
                                "id", transactionId.get())));
            }
            TransactionRecord record = page.records().get(0);
            return playerIdentityApi.resolveDisplayNames(participantIds(List.of(record)))
                    .thenAccept(names -> runOnMainThread(() -> sendTransactionDetails(sender, record, names)));
        }));
    }

    private void handleRollback(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sendMessage(sender, "eco-rollback-usage");
            return;
        }
        Optional<UUID> transactionId = parseTransactionId(args[1]);
        if (transactionId.isEmpty()) {
            sendMessage(sender, "eco-transaction-invalid-id");
            return;
        }

        // The service performs all existence, reversibility, and duplicate-reversal checks under
        // its transaction lock. A command-side pre-check would introduce a check-then-act race.
        transactionService.rollbackAsynchronously(transactionId.get(), transactionOrigin(sender),
                "Administrator rollback").thenAccept(result -> runOnMainThread(() -> sendRollbackResult(sender,
                transactionId.get(), result)));
    }

    private void handleAudit(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sendMessage(sender, "eco-audit-usage");
            return;
        }
        withKnownIdentity(sender, args[1], target -> reportReadCompletion(sender, transactionService
                .getPlayerStatistics(target.playerId())
                .thenAccept(statistics -> runOnMainThread(() -> sendAudit(sender,
                        target.lastKnownName(), statistics)))));
    }

    private void sendHistory(CommandSender sender, String playerName, int pageNumber, String pageCommandPrefix,
                             TransactionPage page, Map<UUID, String> displayNames) {
        sendMessage(sender, "eco-history-header", "player", playerName, "page", pageNumber);
        if (page.records().isEmpty()) {
            sendMessage(sender, "eco-history-empty");
            return;
        }
        for (TransactionRecord record : page.records()) {
            sendMessage(sender, "eco-history-entry",
                    "id", shortId(record.id()),
                    "time", formatTimestamp(record),
                    "type", localizedType(record),
                    "amount", formatMinorAmount(record.appliedAmountMinor()),
                    "source", playerName(record.sourcePlayerId(), displayNames),
                    "target", playerName(record.targetPlayerId(), displayNames),
                    "status", localizedStatus(record),
                    "reason", displayValue(record.reason()));
        }
        if (page.hasMore()) {
            sendMessage(sender, "eco-history-next-page", "command", pageCommandPrefix + " " + (pageNumber + 1));
        }
    }

    private void sendTransactionDetails(CommandSender sender, TransactionRecord record,
                                        Map<UUID, String> displayNames) {
        sendMessage(sender, "eco-transaction-header", "id", record.id());
        sendMessage(sender, "eco-transaction-time", "time", formatTimestamp(record));
        sendMessage(sender, "eco-transaction-type", "type", localizedType(record));
        sendMessage(sender, "eco-transaction-status", "status", localizedStatus(record));
        sendMessage(sender, "eco-transaction-operation", "operation", localizedOperation(record));
        sendMessage(sender, "eco-transaction-amount", "amount", formatMinorAmount(record.appliedAmountMinor()),
                "requestedAmount", formatMinorAmount(record.requestedAmountMinor()));
        sendMessage(sender, "eco-transaction-source", "source",
                playerName(record.sourcePlayerId(), displayNames));
        sendMessage(sender, "eco-transaction-source-balance", "before", formatMinorAmount(record.sourceBalanceBeforeMinor()),
                "after", formatMinorAmount(record.sourceBalanceAfterMinor()));
        sendMessage(sender, "eco-transaction-target", "target",
                playerName(record.targetPlayerId(), displayNames));
        sendMessage(sender, "eco-transaction-target-balance", "before", formatMinorAmount(record.targetBalanceBeforeMinor()),
                "after", formatMinorAmount(record.targetBalanceAfterMinor()));
        sendMessage(sender, "eco-transaction-origin", "origin", record.origin().identifier());
        sendMessage(sender, "eco-transaction-reason", "reason", displayValue(record.reason()));
        sendMessage(sender, "eco-transaction-metadata", "metadata", formatMetadata(record.metadata()));
        sendMessage(sender, "eco-transaction-reversal", "id", displayValue(record.reversalOfTransactionId()));
        sendMessage(sender, "eco-transaction-batch", "id", displayValue(record.batchId()));
        sendMessage(sender, "eco-transaction-idempotency", "key", displayValue(record.idempotencyKey()));
        sendMessage(sender, "eco-transaction-failure", "reason", localizedFailureReason(record));
        sendMessage(sender, "eco-transaction-failure-detail", "detail", displayValue(record.failureDetail()));
    }

    private void sendRollbackResult(CommandSender sender, UUID transactionId, TransactionResult result) {
        if (result.isSuccessful()) {
            sendMessage(sender, "eco-rollback-success", "id", transactionId, "rollbackId", result.transaction().id());
            return;
        }
        if (result.failureReason() == TransactionFailureReason.DUPLICATE_ROLLBACK) {
            sendMessage(sender, "eco-rollback-already-completed", "id", transactionId);
        } else if (result.failureReason() == TransactionFailureReason.NOT_REVERSIBLE) {
            sendMessage(sender, "eco-rollback-not-reversible", "id", transactionId);
        } else {
            sendMessage(sender, "eco-rollback-failed", "id", transactionId);
        }
    }

    private void sendServerStatistics(CommandSender sender, EconomyAnalytics analytics,
                                      Map<UUID, String> displayNames) {
        EconomyStatistics statistics = analytics.statistics();
        sendMessage(sender, "eco-stats-header");
        sendMessage(sender, "eco-stats-accounts", "count", statistics.walletAccountCount());
        sendMessage(sender, "eco-stats-total-money", "amount", formatMinorAmount(statistics.totalBalanceMinor()));
        sendMessage(sender, "eco-stats-average-balance", "amount", formatMinorAmount(statistics.averageBalanceMinor()));
        sendMessage(sender, "eco-stats-richest-player", "player",
                playerName(statistics.richestPlayerId(), displayNames),
                "amount", formatMinorAmount(statistics.richestBalanceMinor()));
        sendMessage(sender, "eco-stats-transactions", "count", statistics.totalTransactionCount());
        sendMessage(sender, "eco-stats-largest-transaction", "amount", formatMinorAmount(analytics.largestTransactionMinor()));
        sendMessage(sender, "eco-stats-transfer-volume", "amount", formatMinorAmount(analytics.totalTransferVolumeMinor()));
        sendMessage(sender, "eco-stats-total-received", "amount", formatMinorAmount(statistics.totalCreditsMinor()));
        sendMessage(sender, "eco-stats-total-sent", "amount", formatMinorAmount(statistics.totalDebitsMinor()));
    }

    private void sendPlayerStatistics(CommandSender sender, String playerName, double balance,
                                      PlayerTransactionStatistics statistics) {
        sendMessage(sender, "eco-stats-player-header", "player", playerName);
        sendMessage(sender, "eco-stats-player-balance", "amount", economyManager.formatCurrency(balance));
        sendAuditValues(sender, statistics);
    }

    private void sendAudit(CommandSender sender, String playerName, PlayerTransactionStatistics statistics) {
        sendMessage(sender, "eco-audit-header", "player", playerName);
        sendAuditValues(sender, statistics);
    }

    private void sendAuditValues(CommandSender sender, PlayerTransactionStatistics statistics) {
        sendMessage(sender, "eco-audit-transactions", "count", statistics.transactionCount());
        sendMessage(sender, "eco-audit-received", "amount", formatMinorAmount(statistics.totalReceivedMinor()));
        sendMessage(sender, "eco-audit-sent", "amount", formatMinorAmount(statistics.totalSentMinor()));
        sendMessage(sender, "eco-audit-net", "amount", formatSignedMinorAmount(statistics.netChangeMinor()));
        sendMessage(sender, "eco-audit-largest-received", "amount", formatMinorAmount(statistics.largestReceivedMinor()));
        sendMessage(sender, "eco-audit-largest-sent", "amount", formatMinorAmount(statistics.largestSentMinor()));
        sendMessage(sender, "eco-audit-first", "time", formatTimestamp(statistics.firstTransactionAt()));
        sendMessage(sender, "eco-audit-last", "time", formatTimestamp(statistics.lastTransactionAt()));
    }

    private void handlePayAll(CommandSender sender, String[] args) {
        if (args.length < 2) {
            showHelp(sender);
            return;
        }

        double amount = parseAmount(args[1]);
        if (!Money.isPositive(amount)) {
            sendMessage(sender, "invalid-amount");
            return;
        }

        List<BatchRecipient> recipients = Bukkit.getOnlinePlayers().stream()
                .map(player -> new BatchRecipient(player.getUniqueId(), player.getName()))
                .toList();
        submitBatchCredit(sender, recipients, amount, "Administrator pay-all credit", "eco payall",
                "eco-payall-target", "eco-payall-success");
    }

    private void handleMultiPay(CommandSender sender, String[] args) {
        if (args.length < 3) {
            showHelp(sender);
            return;
        }

        double amount = parseAmount(args[1]);
        if (!Money.isPositive(amount)) {
            sendMessage(sender, "invalid-amount");
            return;
        }

        List<BatchRecipient> recipients = new ArrayList<>();
        for (int index = 2; index < args.length; index++) {
            Player target = playerIdentityApi.findOnlinePlayer(args[index]).orElse(null);
            if (target == null) {
                sendMessage(sender, "player-not-online", "player", args[index]);
                continue;
            }
            recipients.add(new BatchRecipient(target.getUniqueId(), target.getName()));
        }
        submitBatchCredit(sender, recipients, amount, "Administrator multi-pay credit", "eco multipay",
                "eco-multipay-target", "eco-multipay-success");
    }

    /**
     * Executes administrator bulk credits sequentially on the transaction executor. This keeps a
     * shared batch auditable while never holding the Paper main thread for database I/O.
     */
    private void submitBatchCredit(CommandSender sender, List<BatchRecipient> recipients, double amount, String reason,
                                   String command, String targetMessageKey, String completionMessageKey) {
        UUID batchId = UUID.randomUUID();
        TransactionOrigin origin = transactionOrigin(sender);
        CompletableFuture<BatchExecution> chain = CompletableFuture.completedFuture(new BatchExecution(List.of(),
                recipients.size()));
        for (BatchRecipient recipient : recipients) {
            chain = chain.thenCompose(execution -> transactionService.depositAsynchronously(
                    recipient.playerId(), amount, TransactionType.ADMIN_GIVE, origin, reason,
                    Map.of("command", command), batchId).handle((result, throwable) -> {
                if (throwable != null || result == null || !result.isSuccessful()) {
                    return execution;
                }
                List<BatchRecipient> updated = new ArrayList<>(execution.successfulRecipients());
                updated.add(recipient);
                return new BatchExecution(List.copyOf(updated), execution.totalRecipients());
            }));
        }
        chain.whenComplete((execution, throwable) -> runOnMainThread(() -> {
            if (throwable != null || execution == null) {
                sendMessage(sender, "transaction-failed");
                return;
            }
            String formattedAmount = economyManager.formatCurrency(amount);
            for (BatchRecipient recipient : execution.successfulRecipients()) {
                Player target = playerIdentityApi.findOnlinePlayer(recipient.playerId()).orElse(null);
                if (target != null && target.isOnline()) {
                    sendMessage(target, targetMessageKey, "amount", formattedAmount);
                }
            }
            sendBatchResult(sender, completionMessageKey, execution.successfulRecipients().size(),
                    execution.totalRecipients());
        }));
    }

    private void reportReadCompletion(CommandSender sender, CompletableFuture<?> future) {
        future.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                runOnMainThread(() -> sendMessage(sender, "economy-read-unavailable"));
            }
        });
    }

    private boolean requireUpdatePermission(CommandSender sender) {
        if (sender.hasPermission(UPDATE_PERMISSION)) return true;
        sendMessage(sender, "no-permission");
        return false;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (hasPermission(sender, permission)) return true;
        sendMessage(sender, "no-permission");
        return false;
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(permission);
    }

    private List<String> availableSubcommands(CommandSender sender) {
        List<String> subcommands = new ArrayList<>();
        if (hasPermission(sender, HISTORY_SELF_PERMISSION)) subcommands.add("history");
        if (hasPermission(sender, TRANSACTION_PERMISSION)) subcommands.add("transaction");
        if (hasPermission(sender, ROLLBACK_PERMISSION)) subcommands.add("rollback");
        if (hasPermission(sender, AUDIT_PERMISSION)) subcommands.add("audit");
        if (hasPermission(sender, STATISTICS_PERMISSION)) subcommands.add("stats");
        if (hasPermission(sender, RELOAD_PERMISSION)) subcommands.add("reload");
        if (updateCheckerService != null && sender.hasPermission(UPDATE_PERMISSION)) subcommands.add("version");
        if (hasPermission(sender, ADMIN_PERMISSION)) {
            subcommands.addAll(List.of("set", "give", "remove", "reset", "balance", "payall", "multipay"));
        }
        return subcommands;
    }

    private boolean acceptsPlayerName(String subcommand) {
        return switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "set", "give", "remove", "reset", "balance", "audit", "stats", "history" -> true;
            default -> false;
        };
    }

    private boolean hasPlayerArgumentPermission(CommandSender sender, String subcommand) {
        return switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "history" -> hasPermission(sender, HISTORY_OTHERS_PERMISSION);
            case "audit" -> hasPermission(sender, AUDIT_PERMISSION);
            case "stats" -> hasPermission(sender, STATISTICS_PERMISSION);
            default -> hasPermission(sender, ADMIN_PERMISSION);
        };
    }

    private static List<String> filterCompletions(Collection<String> options, String partial) {
        String normalizedPartial = partial.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalizedPartial))
                .sorted(Comparator.naturalOrder()).toList();
    }

    private int parsePage(CommandSender sender, String input) {
        OptionalInt page = parsePositiveInteger(input);
        if (page.isEmpty()) {
            sendMessage(sender, "eco-history-invalid-page");
            return -1;
        }
        return page.getAsInt();
    }

    private static boolean isPositiveInteger(String value) {
        return parsePositiveInteger(value).isPresent();
    }

    private static OptionalInt parsePositiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    private static Optional<UUID> parseTransactionId(String input) {
        try {
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private double parseAmount(String input) {
        try {
            return Money.fromMinorUnits(Money.parseMinorUnits(input));
        } catch (Exception exception) {
            return -1D;
        }
    }

    private void sendMutationResult(CommandSender sender, Player target, String subcommand, TransactionResult result,
                                    Throwable throwable) {
        if (throwable != null || result == null || !result.isSuccessful()) {
            sendMutationFailure(sender, target, result);
            return;
        }
        switch (subcommand) {
            case "set" -> {
                double balance = Money.fromMinorUnits(result.transaction().targetBalanceAfterMinor());
                sendMessage(sender, "eco-set-success", "player", target.getName(),
                        "amount", economyManager.formatCurrency(balance));
                notifyTarget(sender, target, "eco-set-target", "amount", economyManager.formatCurrency(balance));
            }
            case "give" -> {
                String formattedAmount = formatMinorAmount(result.transaction().appliedAmountMinor());
                sendMessage(sender, "eco-give-success", "player", target.getName(), "amount", formattedAmount);
                notifyTarget(sender, target, "eco-give-target", "amount", formattedAmount);
            }
            case "remove" -> {
                String formattedAmount = formatMinorAmount(result.transaction().appliedAmountMinor());
                sendMessage(sender, "eco-remove-success", "player", target.getName(), "amount", formattedAmount);
                notifyTarget(sender, target, "eco-remove-target", "amount", formattedAmount);
            }
            default -> throw new IllegalStateException("Unexpected economy mutation: " + subcommand);
        }
    }

    private void sendMutationFailure(CommandSender sender, Player target, TransactionResult result) {
        if (result == null) {
            sendMessage(sender, "transaction-failed");
        } else if (result.failureReason() == TransactionFailureReason.INSUFFICIENT_FUNDS) {
            sendMessage(sender, "not-enough-money-target");
        } else if (result.failureReason() == TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED) {
            sendMessage(sender, "target-max-balance", "player", target.getName());
        } else {
            sendMessage(sender, "transaction-failed");
        }
    }

    private void sendBatchResult(CommandSender sender, String completeKey, int successful, int total) {
        if (successful == total) {
            sendMessage(sender, completeKey);
            return;
        }
        sendMessage(sender, "eco-batch-result", "successful", successful, "total", total);
    }

    private TransactionOrigin transactionOrigin(CommandSender sender) {
        if (sender instanceof Player player) return TransactionOrigin.player(player.getUniqueId(), player.getName());
        return TransactionOrigin.console();
    }

    private void runOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled()) action.run();
        });
    }

    private String localizedType(TransactionRecord record) {
        return plugin.getLanguageManager().getMessage("transaction-type-" + record.type().name()
                .toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private String localizedStatus(TransactionRecord record) {
        return plugin.getLanguageManager().getMessage("transaction-status-" + record.status().name().toLowerCase(Locale.ROOT));
    }

    private String localizedOperation(TransactionRecord record) {
        return plugin.getLanguageManager().getMessage("transaction-operation-" + record.operation().name()
                .toLowerCase(Locale.ROOT));
    }

    private String localizedFailureReason(TransactionRecord record) {
        return plugin.getLanguageManager().getMessage("transaction-failure-" + record.failureReason().name()
                .toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private String playerName(UUID playerId, Map<UUID, String> displayNames) {
        if (playerId == null) return plugin.getLanguageManager().getMessage("transaction-not-applicable");
        return displayNames.getOrDefault(playerId, economyManager.getPlayerName(playerId));
    }

    private Collection<UUID> participantIds(Collection<TransactionRecord> records) {
        java.util.LinkedHashSet<UUID> playerIds = new java.util.LinkedHashSet<>();
        for (TransactionRecord record : records) {
            if (record.sourcePlayerId() != null) playerIds.add(record.sourcePlayerId());
            if (record.targetPlayerId() != null) playerIds.add(record.targetPlayerId());
        }
        return playerIds;
    }

    private void withKnownIdentity(CommandSender sender, String suppliedName,
                                   java.util.function.Consumer<PlayerIdentity> action) {
        playerIdentityApi.resolve(suppliedName).whenComplete((identity, throwable) -> {
            if (throwable != null) {
                runOnMainThread(() -> sendMessage(sender, "economy-read-unavailable"));
                return;
            }
            if (identity.isEmpty()) {
                runOnMainThread(() -> sendMessage(sender, "player-not-known", "player", suppliedName));
                return;
            }
            action.accept(identity.get());
        });
    }

    private String formatMinorAmount(long amountMinor) {
        return economyManager.formatCurrency(Money.fromMinorUnits(amountMinor));
    }

    private String formatMinorAmount(Long amountMinor) {
        return amountMinor == null ? plugin.getLanguageManager().getMessage("transaction-not-applicable")
                : formatMinorAmount(amountMinor.longValue());
    }

    private String formatSignedMinorAmount(long amountMinor) {
        if (amountMinor >= 0L) {
            return (amountMinor > 0L ? "+" : "") + formatMinorAmount(amountMinor);
        }
        return economyManager.formatCurrency(Money.fromMinorUnits(amountMinor));
    }

    private String formatTimestamp(TransactionRecord record) {
        return formatTimestamp(record.timestamp());
    }

    private String formatTimestamp(java.time.Instant timestamp) {
        return timestamp == null ? plugin.getLanguageManager().getMessage("transaction-not-applicable")
                : TIMESTAMP_FORMATTER.format(timestamp);
    }

    private String formatMetadata(Map<String, String> metadata) {
        if (metadata.isEmpty()) return plugin.getLanguageManager().getMessage("transaction-not-applicable");
        return metadata.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    private String displayValue(Object value) {
        return value == null || value.toString().isBlank() ? plugin.getLanguageManager().getMessage("transaction-not-applicable")
                : value.toString();
    }

    private static String shortId(UUID transactionId) {
        return transactionId.toString().substring(0, 8);
    }

    private void notifyTarget(CommandSender sender, Player target, String key, Object... replacements) {
        if (!target.equals(sender)) sendMessage(target, key, replacements);
    }

    private void sendMessage(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(plugin.getLanguageManager().getFormattedMessage(key, replacements));
    }
}
