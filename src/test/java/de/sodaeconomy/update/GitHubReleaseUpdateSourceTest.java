package de.sodaeconomy.update;

import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GitHubReleaseUpdateSourceTest {
    @Test
    void parsesPublishedReleasesAndIgnoresDraftsWithoutRealNetworkAccess() {
        String body = """
                [
                  {"tag_name":"v1.1.0","draft":false,"prerelease":false,
                   "html_url":"https://github.com/Sodastream1218/SodaEconomy/releases/tag/v1.1.0",
                   "body":"notes with { nested-looking text }","assets":[{"name":"plugin.jar"}]},
                  {"tag_name":"v1.2.0-beta.1","draft":false,"prerelease":true,
                   "html_url":"https://github.com/Sodastream1218/SodaEconomy/releases/tag/v1.2.0-beta.1"},
                  {"tag_name":"v9.9.9","draft":true,"prerelease":false,
                   "html_url":"https://example.invalid/draft"},
                  {"tag_name":"v8.8.8","draft":false,"prerelease":false,
                   "html_url":"https://example.invalid/not-official"}
                ]
                """;
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        UpdateHttpClient client = (uri, requestHeaders, connect, request) -> {
            headers.set(requestHeaders);
            return CompletableFuture.completedFuture(new UpdateHttpResponse(200, body));
        };

        List<UpdateRelease> releases = new GitHubReleaseUpdateSource(client)
                .fetchReleases(UpdateCheckerSettings.defaults()).join();

        assertEquals(2, releases.size());
        assertEquals("v1.1.0", releases.get(0).tagName());
        assertTrue(releases.get(1).prerelease());
        assertEquals("application/vnd.github+json", headers.get().get("Accept"));
        assertEquals("SodaEconomy-UpdateChecker", headers.get().get("User-Agent"));
        assertEquals("2022-11-28", headers.get().get("X-GitHub-Api-Version"));
        assertFalse(headers.get().containsKey("Authorization"));
    }

    @Test
    void malformedJsonFailsTheCheckInsteadOfInventingARelease() {
        UpdateHttpClient client = (uri, headers, connect, request) ->
                CompletableFuture.completedFuture(new UpdateHttpResponse(200, "[{not-json]"));
        CompletableFuture<List<UpdateRelease>> future = new GitHubReleaseUpdateSource(client)
                .fetchReleases(UpdateCheckerSettings.defaults());
        assertThrows(CompletionException.class, future::join);
    }

    @Test
    void networkTimeoutIsPropagatedAsAFailedFuture() {
        UpdateHttpClient client = (uri, headers, connect, request) ->
                CompletableFuture.failedFuture(new HttpTimeoutException("synthetic timeout"));
        CompletableFuture<List<UpdateRelease>> future = new GitHubReleaseUpdateSource(client)
                .fetchReleases(UpdateCheckerSettings.defaults());
        assertThrows(CompletionException.class, future::join);
    }
    @Test
    void nonSuccessfulHttpStatusFailsTheCheck() {
        UpdateHttpClient client = (uri, headers, connect, request) ->
                CompletableFuture.completedFuture(new UpdateHttpResponse(403, "rate limited"));
        CompletableFuture<List<UpdateRelease>> future = new GitHubReleaseUpdateSource(client)
                .fetchReleases(UpdateCheckerSettings.defaults());
        assertThrows(CompletionException.class, future::join);
    }

}
