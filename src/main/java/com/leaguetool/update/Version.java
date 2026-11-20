package com.leaguetool.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Programmversion {@code MAJOR.MINOR.PATCH}, wie in der {@code pom.xml} und in Release-Tags ({@code v2.0.1}). */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    private static final Pattern FORMAT = Pattern.compile("v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})");

    /** Liest {@code 2.0.1} oder {@code v2.0.1}; alles andere (auch Zusätze wie {@code -SNAPSHOT}) ergibt leer. */
    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Version o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c != 0 ? c : Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
