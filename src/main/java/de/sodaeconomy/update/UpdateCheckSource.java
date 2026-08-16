package de.sodaeconomy.update;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Release-source boundary. It has no Bukkit dependency and performs no notification work. */
public interface UpdateCheckSource {
    String id();

    CompletableFuture<List<UpdateRelease>> fetchReleases(UpdateCheckerSettings settings);
}
