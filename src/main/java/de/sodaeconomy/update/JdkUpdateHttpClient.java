package de.sodaeconomy.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Java 17 asynchronous HTTP implementation used by the GitHub release source. */
final class JdkUpdateHttpClient implements UpdateHttpClient {
    @Override
    public CompletableFuture<UpdateHttpResponse> get(URI uri, Map<String, String> headers,
                                                     Duration connectTimeout, Duration requestTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout);
        headers.forEach(request::header);
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new UpdateHttpResponse(response.statusCode(), response.body()));
    }
}
