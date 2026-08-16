package de.sodaeconomy.update;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JSON reader for the four GitHub release fields SodaEconomy needs. It deliberately skips
 * every unrelated value, including nested arrays/objects, so the update checker does not require
 * an additional runtime JSON dependency.
 */
final class GitHubReleaseJsonParser {
    private GitHubReleaseJsonParser() {
    }

    static List<GitHubReleaseRecord> parse(String json) {
        Cursor cursor = new Cursor(json == null ? "" : json);
        cursor.skipWhitespace();
        cursor.expect('[');
        List<GitHubReleaseRecord> releases = new ArrayList<>();
        cursor.skipWhitespace();
        if (cursor.consume(']')) {
            cursor.ensureFinished();
            return List.of();
        }

        while (true) {
            releases.add(parseRelease(cursor));
            cursor.skipWhitespace();
            if (cursor.consume(']')) break;
            cursor.expect(',');
        }
        cursor.ensureFinished();
        return List.copyOf(releases);
    }

    private static GitHubReleaseRecord parseRelease(Cursor cursor) {
        cursor.skipWhitespace();
        cursor.expect('{');
        String tagName = null;
        String htmlUrl = null;
        boolean draft = false;
        boolean prerelease = false;

        cursor.skipWhitespace();
        if (cursor.consume('}')) {
            return new GitHubReleaseRecord(null, false, false, null);
        }

        while (true) {
            cursor.skipWhitespace();
            String key = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            cursor.skipWhitespace();
            switch (key) {
                case "tag_name" -> tagName = cursor.readNullableString();
                case "html_url" -> htmlUrl = cursor.readNullableString();
                case "draft" -> draft = cursor.readBoolean();
                case "prerelease" -> prerelease = cursor.readBoolean();
                default -> cursor.skipValue();
            }
            cursor.skipWhitespace();
            if (cursor.consume('}')) break;
            cursor.expect(',');
        }
        return new GitHubReleaseRecord(tagName, draft, prerelease, htmlUrl);
    }

    record GitHubReleaseRecord(String tagName, boolean draft, boolean prerelease, String htmlUrl) {
    }

    private static final class Cursor {
        private final String text;
        private int index;

        private Cursor(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        void expect(char expected) {
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        boolean consume(char value) {
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == value) {
                index++;
                return true;
            }
            return false;
        }

        String readNullableString() {
            skipWhitespace();
            if (startsWith("null")) {
                index += 4;
                return null;
            }
            return readString();
        }

        String readString() {
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != '"') throw error("Expected string");
            index++;
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char character = text.charAt(index++);
                if (character == '"') return value.toString();
                if (character == '\\') {
                    if (index >= text.length()) throw error("Incomplete string escape");
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(readUnicodeEscape());
                        default -> throw error("Unsupported string escape");
                    }
                } else {
                    if (character < 0x20) throw error("Control character in JSON string");
                    value.append(character);
                }
            }
            throw error("Unterminated JSON string");
        }

        private char readUnicodeEscape() {
            if (index + 4 > text.length()) throw error("Incomplete unicode escape");
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) throw error("Invalid unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        boolean readBoolean() {
            skipWhitespace();
            if (startsWith("true")) {
                index += 4;
                return true;
            }
            if (startsWith("false")) {
                index += 5;
                return false;
            }
            throw error("Expected boolean");
        }

        void skipValue() {
            skipWhitespace();
            if (index >= text.length()) throw error("Expected JSON value");
            char value = text.charAt(index);
            if (value == '"') {
                readString();
            } else if (value == '{') {
                skipObject();
            } else if (value == '[') {
                skipArray();
            } else if (startsWith("true")) {
                index += 4;
            } else if (startsWith("false")) {
                index += 5;
            } else if (startsWith("null")) {
                index += 4;
            } else if (value == '-' || Character.isDigit(value)) {
                skipNumber();
            } else {
                throw error("Unsupported JSON value");
            }
        }

        private void skipObject() {
            expect('{');
            skipWhitespace();
            if (consume('}')) return;
            while (true) {
                readString();
                expect(':');
                skipValue();
                if (consume('}')) return;
                expect(',');
            }
        }

        private void skipArray() {
            expect('[');
            skipWhitespace();
            if (consume(']')) return;
            while (true) {
                skipValue();
                if (consume(']')) return;
                expect(',');
            }
        }

        private void skipNumber() {
            if (text.charAt(index) == '-') index++;
            if (index >= text.length()) throw error("Invalid number");

            if (text.charAt(index) == '0') {
                index++;
                if (index < text.length() && Character.isDigit(text.charAt(index))) {
                    throw error("Invalid leading zero in number");
                }
            } else {
                int integerStart = index;
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
                if (index == integerStart) throw error("Invalid number");
            }

            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                int fractionStart = index;
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
                if (index == fractionStart) throw error("Invalid number fraction");
            }

            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                int exponentStart = index;
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
                if (index == exponentStart) throw error("Invalid number exponent");
            }
        }

        boolean startsWith(String literal) {
            return text.regionMatches(index, literal, 0, literal.length());
        }

        void ensureFinished() {
            skipWhitespace();
            if (index != text.length()) throw error("Unexpected trailing JSON content");
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON offset " + index);
        }
    }
}
