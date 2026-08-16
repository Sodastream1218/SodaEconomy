package de.sodaeconomy.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small SemVer-compatible version value used only by the update subsystem. */
public final class UpdateVersion implements Comparable<UpdateVersion> {
    private static final Pattern SEMVER = Pattern.compile(
            "^[vV]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    );

    private final String display;
    private final long major;
    private final long minor;
    private final long patch;
    private final List<String> prerelease;
    private final UpdateChannel channel;
    private final boolean development;

    private UpdateVersion(String display, long major, long minor, long patch, List<String> prerelease,
                          UpdateChannel channel, boolean development) {
        this.display = display;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = List.copyOf(prerelease);
        this.channel = channel;
        this.development = development;
    }

    /**
     * Parses supported SodaEconomy release tags and recognized development labels. Unsupported
     * prerelease names are intentionally rejected instead of being guessed into a release channel.
     */
    public static Optional<UpdateVersion> parse(String rawValue) {
        if (rawValue == null) return Optional.empty();
        String raw = rawValue.trim();
        if (raw.isEmpty()) return Optional.empty();

        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.equals("dev") || lower.equals("unknown") || lower.endsWith("-snapshot") || lower.endsWith("-dev")) {
            return Optional.of(new UpdateVersion(raw, 0L, 0L, 0L, List.of(), null, true));
        }

        Matcher matcher = SEMVER.matcher(raw);
        if (!matcher.matches()) return Optional.empty();

        long major;
        long minor;
        long patch;
        try {
            major = Long.parseLong(matcher.group(1));
            minor = Long.parseLong(matcher.group(2));
            patch = Long.parseLong(matcher.group(3));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }

        List<String> prerelease = new ArrayList<>();
        UpdateChannel channel = UpdateChannel.STABLE;
        String prereleaseText = matcher.group(4);
        if (prereleaseText != null) {
            for (String identifier : prereleaseText.split("\\.")) {
                if (identifier.isEmpty() || isInvalidNumericIdentifier(identifier)) return Optional.empty();
                prerelease.add(identifier);
            }
            String family = prerelease.get(0).toLowerCase(Locale.ROOT);
            channel = switch (family) {
                case "alpha" -> UpdateChannel.ALPHA;
                case "beta" -> UpdateChannel.BETA;
                case "rc" -> UpdateChannel.RC;
                default -> null;
            };
            if (channel == null) return Optional.empty();
        }

        String display = raw.startsWith("v") || raw.startsWith("V") ? raw.substring(1) : raw;
        int buildMetadataIndex = display.indexOf('+');
        if (buildMetadataIndex >= 0) display = display.substring(0, buildMetadataIndex);
        return Optional.of(new UpdateVersion(display, major, minor, patch, prerelease, channel, false));
    }

    private static boolean isInvalidNumericIdentifier(String identifier) {
        if (!identifier.chars().allMatch(Character::isDigit)) return false;
        return identifier.length() > 1 && identifier.charAt(0) == '0';
    }

    public String display() {
        return display;
    }

    public boolean isDevelopment() {
        return development;
    }

    public Optional<UpdateChannel> channel() {
        return Optional.ofNullable(channel);
    }

    @Override
    public int compareTo(UpdateVersion other) {
        Objects.requireNonNull(other, "other");
        if (development || other.development) {
            throw new IllegalStateException("Development versions do not participate in release precedence");
        }
        int comparison = Long.compare(major, other.major);
        if (comparison != 0) return comparison;
        comparison = Long.compare(minor, other.minor);
        if (comparison != 0) return comparison;
        comparison = Long.compare(patch, other.patch);
        if (comparison != 0) return comparison;

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0;
        if (prerelease.isEmpty()) return 1;
        if (other.prerelease.isEmpty()) return -1;

        int length = Math.max(prerelease.size(), other.prerelease.size());
        for (int index = 0; index < length; index++) {
            if (index >= prerelease.size()) return -1;
            if (index >= other.prerelease.size()) return 1;
            comparison = comparePrereleaseIdentifier(prerelease.get(index), other.prerelease.get(index));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static int comparePrereleaseIdentifier(String left, String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);
        if (leftNumeric && rightNumeric) {
            try {
                return Long.compare(Long.parseLong(left), Long.parseLong(right));
            } catch (NumberFormatException ignored) {
                return compareLargeNumericIdentifier(left, right);
            }
        }
        if (leftNumeric) return -1;
        if (rightNumeric) return 1;
        return left.compareTo(right);
    }

    private static int compareLargeNumericIdentifier(String left, String right) {
        int lengthComparison = Integer.compare(left.length(), right.length());
        return lengthComparison != 0 ? lengthComparison : left.compareTo(right);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof UpdateVersion other)) return false;
        return development == other.development && major == other.major && minor == other.minor && patch == other.patch
                && display.equals(other.display) && prerelease.equals(other.prerelease) && channel == other.channel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(display, major, minor, patch, prerelease, channel, development);
    }

    @Override
    public String toString() {
        return display;
    }
}
