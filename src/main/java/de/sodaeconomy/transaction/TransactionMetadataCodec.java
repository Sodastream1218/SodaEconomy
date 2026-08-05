package de.sodaeconomy.transaction;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-free codec for bounded transaction metadata stored in SQL and YAML. */
public final class TransactionMetadataCodec {
    private TransactionMetadataCodec() {
    }

    public static String encode(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (encoded.length() > 0) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            encoded.append('=');
            encoded.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return encoded.toString();
    }

    public static Map<String, String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : encoded.split("&", -1)) {
            int separator = part.indexOf('=');
            if (separator < 1) {
                continue;
            }
            String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return Map.copyOf(result);
    }
}
