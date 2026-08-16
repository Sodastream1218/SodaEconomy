package de.sodaeconomy.update;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Official GitHub Releases source for SodaEconomy's public repository. */
public final class GitHubReleaseUpdateSource implements UpdateCheckSource {
    static final URI RELEASES_URI = URI.create(
            "https://api.github.com/repos/Sodastream1218/SodaEconomy/releases?per_page=100&page=1");
    private static final Map<String, String> HEADERS = Map.of(
            "Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2022-11-28",
            "User-Agent", "SodaEconomy-UpdateChecker"
    );

    private final UpdateHttpClient httpClient;

    public GitHubReleaseUpdateSource() {
        this(new JdkUpdateHttpClient());
    }

    GitHubReleaseUpdateSource(UpdateHttpClient httpClient) {
        this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String id() {
        return UpdateCheckerSettings.GITHUB_SOURCE;
    }

    @Override
    public CompletableFuture<List<UpdateRelease>> fetchReleases(UpdateCheckerSettings settings) {
        return httpClient.get(RELEASES_URI, HEADERS, settings.connectTimeout(), settings.requestTimeout())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new java.util.concurrent.CompletionException(new IOException(
                                "GitHub Releases returned HTTP " + response.statusCode()));
                    }
                    List<GitHubReleaseJsonParser.GitHubReleaseRecord> parsed =
                            GitHubReleaseJsonParser.parse(response.body());
                    List<UpdateRelease> releases = new ArrayList<>();
                    for (GitHubReleaseJsonParser.GitHubReleaseRecord release : parsed) {
                        if (release.draft()) continue;
                        if (release.tagName() == null || release.tagName().isBlank()
                                || release.htmlUrl() == null || release.htmlUrl().isBlank()) {
                            continue;
                        }
                        URI releasePage;
                        try {
                            releasePage = URI.create(release.htmlUrl());
                        } catch (IllegalArgumentException exception) {
                            continue;
                        }
                        if (!isOfficialGitHubReleasePage(releasePage)) continue;
                        releases.add(new UpdateRelease(release.tagName(), release.prerelease(), releasePage));
                    }
                    return List.copyOf(releases);
                });
    }
    private static boolean isOfficialGitHubReleasePage(URI uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && "github.com".equalsIgnoreCase(uri.getHost())
                && uri.getPath() != null
                && uri.getPath().regionMatches(true, 0, "/Sodastream1218/SodaEconomy/releases/", 0,
                        "/Sodastream1218/SodaEconomy/releases/".length());
    }

}
