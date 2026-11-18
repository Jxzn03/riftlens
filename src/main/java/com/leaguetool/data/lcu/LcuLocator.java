package com.leaguetool.data.lcu;

import com.fasterxml.jackson.databind.JsonNode;

import com.leaguetool.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Findet die {@code lockfile} des laufenden League-Clients. Sie liegt im Installationsordner und existiert nur, solange
 * der Client läuft. Reihenfolge: von Hand gewählter Ordner, Riots Produkteinstellungen ({@code product_settings.yaml}
 * aller League-Versionen, Live zuerst), die Installationsliste des Riot-Clients, zuletzt die Standardordner.
 *
 * <p>Bewusst ohne Zugriff auf Prozesse: Javas {@code ProcessHandle.info()} öffnet unter Windows jeden Prozess mit
 * {@code 0x0040}, für Prozesse ist das {@code PROCESS_DUP_HANDLE}, auch beim laufenden Spiel und bei Vanguard.
 * Den Ordner nennen ohnehin Riots eigene Dateien; gelesen werden nur Dateien.
 */
public final class LcuLocator {

    static final String CLIENT_EXECUTABLE = "LeagueClient.exe";
    private static final String LEAGUE_FOLDER = "League of Legends";
    private static final Pattern INSTALL_PATH = Pattern.compile("(?m)^\\s*product_install_full_path:\\s*\"?([^\"\\r\\n]+?)\"?\\s*$");

    private final Supplier<String> manualFolder;
    private final Path riotData;
    private final List<Path> defaultInstalls;

    /** @param manualFolder von Hand gewählter Ordner, leer = automatisch */
    public LcuLocator(Supplier<String> manualFolder) {
        this(manualFolder, riotDataFolder(), defaultInstalls());
    }

    /** @param riotData Ordner mit {@code RiotClientInstalls.json} und {@code Metadata} */
    LcuLocator(Supplier<String> manualFolder, Path riotData, List<Path> defaultInstalls) {
        this.manualFolder = manualFolder;
        this.riotData = riotData;
        this.defaultInstalls = List.copyOf(defaultInstalls);
    }

    /** {@code %PROGRAMDATA%\Riot Games}; Windows kann auf einem anderen Laufwerk als C: liegen. */
    private static Path riotDataFolder() {
        String programData = System.getenv("PROGRAMDATA");
        Path base = programData == null || programData.isBlank() ? Path.of("C:\\ProgramData") : Path.of(programData);
        return base.resolve("Riot Games");
    }

    /** Riots Standardordner auf dem Systemlaufwerk und auf C:. */
    private static List<Path> defaultInstalls() {
        Set<Path> folders = new LinkedHashSet<>();
        String systemDrive = System.getenv("SystemDrive");
        if (systemDrive != null && systemDrive.matches("[A-Za-z]:")) {
            folders.add(Path.of(systemDrive + "\\Riot Games\\" + LEAGUE_FOLDER));
        }
        folders.add(Path.of("C:\\Riot Games\\" + LEAGUE_FOLDER));
        return new ArrayList<>(folders);
    }

    /** Zugang des laufenden Clients; leer, wenn keiner läuft. */
    public Optional<LcuLockfile> find() {
        for (Path folder : candidates()) {
            Path lockfile = folder.resolve("lockfile");
            if (Files.isRegularFile(lockfile)) {
                try {
                    Optional<LcuLockfile> parsed = LcuLockfile.parse(Files.readString(lockfile));
                    if (parsed.isPresent()) {
                        return parsed;
                    }
                } catch (IOException e) {
                    // Der Client schreibt die Datei gerade: beim nächsten Durchlauf erneut versuchen.
                }
            }
        }
        return Optional.empty();
    }

    /** Ohne den von Hand gewählten Ordner: was die automatische Suche findet. */
    public Optional<Path> detectedInstallFolder() {
        return automaticCandidates().stream().filter(LcuLocator::isInstallFolder).findFirst();
    }

    public static boolean isInstallFolder(Path folder) {
        return Files.isRegularFile(folder.resolve(CLIENT_EXECUTABLE));
    }

    /**
     * Macht aus einer Eingabe den League-Ordner: Eine angegebene Programmdatei zählt mit ihrem Ordner, ein
     * übergeordneter Riot-Ordner mit seinem Unterordner „League of Legends“.
     */
    public static Optional<Path> normalize(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        Path path;
        try {
            path = Path.of(input.trim().replaceAll("^\"|\"$", ""));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        if (Files.isRegularFile(path) && path.getParent() != null) {
            path = path.getParent();
        }
        if (!isInstallFolder(path) && isInstallFolder(path.resolve(LEAGUE_FOLDER))) {
            path = path.resolve(LEAGUE_FOLDER);
        }
        return Optional.of(path);
    }

    List<Path> candidates() {
        Set<Path> folders = new LinkedHashSet<>();
        normalize(manualFolder.get()).ifPresent(folders::add);
        folders.addAll(automaticCandidates());
        return new ArrayList<>(folders);
    }

    private List<Path> automaticCandidates() {
        Set<Path> folders = new LinkedHashSet<>(productSettingsFolders());
        folders.addAll(installedFolders());
        folders.addAll(defaultInstalls);
        return new ArrayList<>(folders);
    }

    /** {@code product_install_full_path} aus Riots Einstellungen zu jeder League-Version (Live vor PBE). */
    private List<Path> productSettingsFolders() {
        List<Path> folders = new ArrayList<>();
        Path metadata = riotData.resolve("Metadata");
        if (!Files.isDirectory(metadata)) {
            return folders;
        }
        try (Stream<Path> products = Files.list(metadata)) {
            products.filter(p -> p.getFileName().toString().startsWith("league_of_legends."))
                    .sorted(Comparator.comparing((Path p) -> !p.getFileName().toString().endsWith(".live"))
                            .thenComparing(p -> p.getFileName().toString()))
                    .forEach(product -> {
                        Path settings = product.resolve(product.getFileName() + ".product_settings.yaml");
                        installPath(settings).ifPresent(folders::add);
                    });
        } catch (IOException | RuntimeException e) {
            // Unlesbare Metadaten: die übrigen Orte bleiben.
        }
        return folders;
    }

    private static Optional<Path> installPath(Path settings) {
        if (!Files.isRegularFile(settings)) {
            return Optional.empty();
        }
        try {
            Matcher matcher = INSTALL_PATH.matcher(Files.readString(settings));
            return matcher.find() ? Optional.of(Path.of(matcher.group(1).trim())) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** League-Ordner aus der Installationsliste des Riot-Clients ({@code associated_client}). */
    private List<Path> installedFolders() {
        List<Path> folders = new ArrayList<>();
        Path installs = riotData.resolve("RiotClientInstalls.json");
        if (!Files.isRegularFile(installs)) {
            return folders;
        }
        try {
            JsonNode associated = Json.MAPPER.readTree(installs.toFile()).path("associated_client");
            associated.fieldNames().forEachRemaining(folder -> {
                if (folder.toLowerCase().contains("league of legends")) {
                    folders.add(Path.of(folder));
                }
            });
        } catch (IOException | RuntimeException e) {
            // Unlesbare Liste: die übrigen Orte bleiben.
        }
        return folders;
    }
}
