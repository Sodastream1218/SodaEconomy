package de.sodaeconomy.update;

import java.util.Locale;
import java.util.Optional;

/** Release maturity selected by the server administrator. */
public enum UpdateChannel {
    ALPHA(0),
    BETA(1),
    RC(2),
    STABLE(3);

    private final int maturity;

    UpdateChannel(int maturity) {
        this.maturity = maturity;
    }

    /**
     * A selected channel includes releases at the same or a more mature level. For example,
     * BETA accepts beta, release-candidate and stable releases, while STABLE accepts only stable
     * releases.
     */
    public boolean allows(UpdateChannel releaseChannel) {
        return releaseChannel != null && releaseChannel.maturity >= maturity;
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<UpdateChannel> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
