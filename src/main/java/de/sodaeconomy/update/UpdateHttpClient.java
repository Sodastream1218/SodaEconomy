package de.sodaeconomy.update;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Tiny HTTP boundary so update tests never need to contact GitHub. */
interface UpdateHttpClient {
    CompletableFuture<UpdateHttpResponse> get(URI uri, Map<String, String> headers,
                                              Duration connectTimeout, Duration requestTimeout);
}
