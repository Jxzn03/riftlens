package com.leaguetool.config;

import com.leaguetool.util.AtomicFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Persistente Einstellungen des Tools ({@code <home>/settings.properties}).
 *
 * <p>Das Home-Verzeichnis ist standardmäßig {@code ~/.leaguetool}; überschreibbar per System-Property
 * {@code -Dleaguetool.home=...} oder Umgebungsvariable {@code LEAGUETOOL_HOME}. Der Riot-API-Key wird
 * nur hier oder in der Umgebungsvariable {@value #ENV_RIOT_KEY} gehalten, niemals im Repository.
 */
public final class AppSettings {

    public static final String ENV_RIOT_KEY = "RIOT_API_KEY";
    public static final String FILE_NAME = "settings.properties";
    /** Wählbare Größen des Draft-Archivs. */
    public static final java.util.List<Integer> DRAFT_ARCHIVE_LIMITS = java.util.List.of(10, 25, 50, 100, 250, 500, 1000);
    /** Standardgröße des Draft-Archivs ohne gespeicherte Einstellung oder bei unbekanntem Wert. */
    public static final int DRAFT_ARCHIVE_DEFAULT = 100;
    /**
     * Wählbare Zahl beendeter Fearless-Serien. Eigene Grenze, weil jede Fearless-Aktion den ganzen Serienstand überträgt
     * (eine Serie wiegt 3 bis 11 KB); die Games bleiben zusätzlich als Drafts im Archiv.
     */
    public static final java.util.List<Integer> FEARLESS_SERIES_LIMITS = java.util.List.of(10, 25, 50, 100);
    /** Standardzahl beendeter Fearless-Serien. */
    public static final int FEARLESS_SERIES_DEFAULT = 50;

    private static final String KEY_LOCALE = "ddragon.locale";
    private static final String KEY_OFFLINE = "offline";
    private static final String KEY_WIKI = "wiki.enabled";
    private static final String KEY_RIOT = "riot.enabled";
    private static final String KEY_RIOT_API_KEY = "riot.apiKey";
    private static final String KEY_PLATFORM = "riot.platform";

    private final Path home;
    private final Properties props = new Properties();

    private AppSettings(Path home) {
        this.home = home;
    }

    /** Standard-Home-Verzeichnis (siehe Klassenkommentar). */
    public static Path defaultHome() {
        String prop = System.getProperty("leaguetool.home");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("LEAGUETOOL_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".leaguetool");
    }

    /** Lädt die Einstellungen; fehlt die Datei, gelten die Standardwerte. */
    public static AppSettings load(Path home) throws IOException {
        AppSettings settings = new AppSettings(home);
        Path file = settings.file();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                settings.props.load(in);
            }
        }
        return settings;
    }

    /** Speichert atomar (temporäre Datei + Move). */
    public synchronized void save() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        props.store(out, "RiftLens");
        AtomicFiles.write(file(), out.toByteArray());
    }

    public Path home() {
        return home;
    }

    public Path file() {
        return home.resolve(FILE_NAME);
    }

    /** Wurzel des Datei-Caches (Data Dragon, Wiki, Bilder). */
    public Path cacheDir() {
        return home.resolve("cache");
    }

    /** Data-Dragon-Sprache, z. B. {@code en_US} oder {@code de_DE}. */
    public synchronized String locale() {
        return props.getProperty(KEY_LOCALE, "en_US");
    }

    public synchronized void setLocale(String locale) {
        props.setProperty(KEY_LOCALE, locale);
    }

    /** Offline-Modus: keine Netzwerkzugriffe, nur Cache. */
    public synchronized boolean offline() {
        return Boolean.parseBoolean(props.getProperty(KEY_OFFLINE, "false"));
    }

    public synchronized void setOffline(boolean offline) {
        props.setProperty(KEY_OFFLINE, Boolean.toString(offline));
    }

    /** Zusatzdaten aus dem LoL-Wiki laden. */
    public synchronized boolean wikiEnabled() {
        return Boolean.parseBoolean(props.getProperty(KEY_WIKI, "true"));
    }

    public synchronized void setWikiEnabled(boolean enabled) {
        props.setProperty(KEY_WIKI, Boolean.toString(enabled));
    }

    /** Winrates/Pickrates von Lolalytics anzeigen (öffentliche Tierliste, gecacht). */
    public synchronized boolean lolalyticsEnabled() {
        return Boolean.parseBoolean(props.getProperty("lolalytics.enabled", "true"));
    }

    public synchronized void setLolalyticsEnabled(boolean enabled) {
        props.setProperty("lolalytics.enabled", Boolean.toString(enabled));
    }

    /** Schalter „Mit League-Client abgleichen“ im Draft-Tab, bleibt über Neustarts erhalten. Standard: aus. */
    public synchronized boolean lcuSyncEnabled() {
        return Boolean.parseBoolean(props.getProperty("lcu.sync", "false"));
    }

    public synchronized void setLcuSyncEnabled(boolean enabled) {
        props.setProperty("lcu.sync", Boolean.toString(enabled));
    }

    /** Beim Programmstart auf GitHub nach einer neuen RiftLens-Version suchen. Standard: an. */
    public synchronized boolean updateCheckEnabled() {
        return Boolean.parseBoolean(props.getProperty("update.check", "true"));
    }

    public synchronized void setUpdateCheckEnabled(boolean enabled) {
        props.setProperty("update.check", Boolean.toString(enabled));
    }

    /**
     * Testschalter: Update-Suche berücksichtigt auch Pre-Releases. Nur von Hand in {@code settings.properties}
     * ({@code update.prerelease=true}), keine Oberfläche. Standard: aus.
     */
    public synchronized boolean updatePrereleaseEnabled() {
        return Boolean.parseBoolean(props.getProperty("update.prerelease", "false"));
    }

    public synchronized void setUpdatePrereleaseEnabled(boolean enabled) {
        props.setProperty("update.prerelease", Boolean.toString(enabled));
    }

    /** Wie viele vollständige Drafts das Archiv behält (10, 25, 50, 100, 250, 500 oder 1000; Standard 100). */
    public synchronized int draftArchiveLimit() {
        try {
            return draftArchiveLimit(Integer.parseInt(props.getProperty("draft.archive.limit", "")));
        } catch (NumberFormatException e) {
            return DRAFT_ARCHIVE_DEFAULT;
        }
    }

    public synchronized void setDraftArchiveLimit(int limit) {
        props.setProperty("draft.archive.limit", Integer.toString(draftArchiveLimit(limit)));
    }

    /** Erlaubte Archivgröße; unbekannte Werte ergeben den Standard ({@link #DRAFT_ARCHIVE_DEFAULT}). */
    public static int draftArchiveLimit(int requested) {
        return DRAFT_ARCHIVE_LIMITS.contains(requested) ? requested : DRAFT_ARCHIVE_DEFAULT;
    }

    /** Wie viele beendete Fearless-Serien behalten werden (10, 25, 50 oder 100; Standard 50). */
    public synchronized int fearlessSeriesLimit() {
        try {
            return fearlessSeriesLimit(Integer.parseInt(props.getProperty("fearless.series.limit", "")));
        } catch (NumberFormatException e) {
            return FEARLESS_SERIES_DEFAULT;
        }
    }

    public synchronized void setFearlessSeriesLimit(int limit) {
        props.setProperty("fearless.series.limit", Integer.toString(fearlessSeriesLimit(limit)));
    }

    /** Erlaubte Zahl beendeter Serien; unbekannte Werte ergeben den Standard ({@link #FEARLESS_SERIES_DEFAULT}). */
    public static int fearlessSeriesLimit(int requested) {
        return FEARLESS_SERIES_LIMITS.contains(requested) ? requested : FEARLESS_SERIES_DEFAULT;
    }

    /** Von Hand gewählter League-Ordner für den Client-Abgleich; leer = automatisch suchen. */
    public synchronized String lcuFolder() {
        return props.getProperty("lcu.folder", "");
    }

    public synchronized void setLcuFolder(String folder) {
        props.setProperty("lcu.folder", folder == null ? "" : folder.trim());
    }

    /** Standard-Rang für Lolalytics, wenn kein Rang-Filter gesetzt ist (z. B. {@code emerald_plus}). */
    public synchronized String lolalyticsTier() {
        return props.getProperty("lolalytics.tier", "emerald_plus");
    }

    public synchronized void setLolalyticsTier(String tier) {
        props.setProperty("lolalytics.tier", tier);
    }

    /** Riot-API (Summoner-Tab, Verifizierung) verwenden. */
    public synchronized boolean riotEnabled() {
        return Boolean.parseBoolean(props.getProperty(KEY_RIOT, "false"));
    }

    public synchronized void setRiotEnabled(boolean enabled) {
        props.setProperty(KEY_RIOT, Boolean.toString(enabled));
    }

    /** Plattform-Routing-Wert, z. B. {@code euw1}. */
    public synchronized String platform() {
        return props.getProperty(KEY_PLATFORM, "euw1");
    }

    public synchronized void setPlatform(String platform) {
        props.setProperty(KEY_PLATFORM, platform);
    }

    /** In den Einstellungen gespeicherter Key (kann leer sein). */
    public synchronized String storedRiotApiKey() {
        return props.getProperty(KEY_RIOT_API_KEY, "");
    }

    public synchronized void setStoredRiotApiKey(String key) {
        if (key == null || key.isBlank()) {
            props.remove(KEY_RIOT_API_KEY);
        } else {
            props.setProperty(KEY_RIOT_API_KEY, key.trim());
        }
    }

    /** Effektiver Riot-API-Key: Einstellungen, sonst Umgebungsvariable {@value #ENV_RIOT_KEY}. */
    public Optional<String> riotApiKey() {
        String stored = storedRiotApiKey();
        if (!stored.isBlank()) {
            return Optional.of(stored);
        }
        String env = System.getenv(ENV_RIOT_KEY);
        return env == null || env.isBlank() ? Optional.empty() : Optional.of(env.trim());
    }
}
