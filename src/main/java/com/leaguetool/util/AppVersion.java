package com.leaguetool.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Programmversion aus {@code version.properties}, die Maven beim Build aus der pom.xml füllt. */
public final class AppVersion {

    private static final String VERSION = load();

    private AppVersion() {
    }

    /** Version wie "2.0.0"; leer, falls die Datei fehlt oder ungefiltert ist (z. B. Start aus der IDE ohne Maven). */
    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/com/leaguetool/version.properties")) {
            if (in == null) {
                return "";
            }
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version", "").trim();
            return version.startsWith("${") ? "" : version;
        } catch (IOException e) {
            return "";
        }
    }
}
