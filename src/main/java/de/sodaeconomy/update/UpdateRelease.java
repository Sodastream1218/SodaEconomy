package de.sodaeconomy.update;

import java.net.URI;
import java.util.Objects;

/** Source-neutral published release metadata needed by the checker. */
public record UpdateRelease(String tagName, boolean prerelease, URI releasePage) {
    public UpdateRelease {
        Objects.requireNonNull(tagName, "tagName");
        Objects.requireNonNull(releasePage, "releasePage");
    }
}
