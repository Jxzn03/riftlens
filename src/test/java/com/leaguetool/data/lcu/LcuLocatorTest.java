package com.leaguetool.data.lcu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcuLocatorTest {

    @TempDir
    Path home;

    private Path riotData() throws Exception {
        return Files.createDirectories(home.resolve("ProgramData/Riot Games"));
    }

    private LcuLocator locator(String manual) throws Exception {
        return new LcuLocator(() -> manual, riotData(), List.of(home.resolve("default/League of Legends")));
    }

    /** Ordner mit Client; mit {@code port > 0} läuft er (lockfile vorhanden). */
    private Path install(String relative, int port) throws Exception {
        Path folder = Files.createDirectories(home.resolve(relative));
        Files.writeString(folder.resolve("LeagueClient.exe"), "");
        if (port > 0) {
            Files.writeString(folder.resolve("lockfile"), "LeagueClient:1:" + port + ":pw" + port + ":https");
        }
        return folder;
    }

    private void productSettings(String product, Path install) throws Exception {
        Path folder = Files.createDirectories(riotData().resolve("Metadata/" + product));
        Files.writeString(folder.resolve(product + ".product_settings.yaml"),
                "product_install_full_path: \"" + install.toString().replace('\\', '/') + "\"\nproduct_install_root: \"x\"\n");
    }

    @Test
    void parsesTheLockfile() {
        Optional<LcuLockfile> lockfile = LcuLockfile.parse("LeagueClient:12345:54321:Abc-12_x:https\n");
        assertEquals(Optional.of(new LcuLockfile(54321, "Abc-12_x")), lockfile);
        assertFalse(lockfile.get().toString().contains("Abc-12_x"), "Das Passwort erscheint nie in Logs");
        assertTrue(LcuLockfile.parse("kaputt").isEmpty());
        assertTrue(LcuLockfile.parse("LeagueClient:1:abc:pw:https").isEmpty());
        assertTrue(LcuLockfile.parse("LeagueClient:1:2:pw:http").isEmpty());
    }

    @Test
    void findsAnyInstallFolderThroughRiotsProductSettings() throws Exception {
        Path install = install("D-Laufwerk/Spiele/Riot Games/League of Legends", 4000);
        productSettings("league_of_legends.live", install);

        LcuLocator locator = locator("");

        assertEquals(Optional.of(new LcuLockfile(4000, "pw4000")), locator.find());
        assertEquals(Optional.of(install), locator.detectedInstallFolder());
    }

    @Test
    void liveComesBeforePbe() throws Exception {
        Path pbe = install("PBE/League of Legends (PBE)", 4100);
        Path live = install("Live/League of Legends", 4200);
        productSettings("league_of_legends.pbe", pbe);
        productSettings("league_of_legends.live", live);

        LcuLocator locator = locator("");

        assertEquals(List.of(live, pbe), locator.candidates().subList(0, 2));
        assertEquals(Optional.of(new LcuLockfile(4200, "pw4200")), locator.find());

        Files.delete(live.resolve("lockfile"));
        assertEquals(Optional.of(new LcuLockfile(4100, "pw4100")), locator.find(), "Läuft nur der PBE-Client, gilt er");
    }

    @Test
    void usesTheRiotInstallList() throws Exception {
        Path install = install("Riot Games/League of Legends", 4001);
        Files.writeString(riotData().resolve("RiotClientInstalls.json"), "{\"associated_client\": {\""
                + install.toString().replace('\\', '/') + "/\": \"x/RiotClientServices.exe\", \"C:/Games/VALORANT/live/\": \"x\"}}");

        LcuLocator locator = locator("");

        assertEquals(Optional.of(new LcuLockfile(4001, "pw4001")), locator.find());
        assertTrue(locator.candidates().stream().noneMatch(p -> p.toString().contains("VALORANT")));
    }

    @Test
    void manualFolderComesFirst() throws Exception {
        Path auto = install("Auto/League of Legends", 4300);
        productSettings("league_of_legends.live", auto);
        Path manual = install("Eigener Ordner/League of Legends", 4400);

        LcuLocator locator = locator(manual.toString());

        assertEquals(Optional.of(new LcuLockfile(4400, "pw4400")), locator.find());
        assertEquals(Optional.of(auto), locator.detectedInstallFolder(), "Die Anzeige nennt weiter den automatischen Fund");
    }

    @Test
    void manualInputMayNameTheExecutableOrTheRiotFolder() throws Exception {
        Path install = install("X/Riot Games/League of Legends", 0);
        assertEquals(Optional.of(install), LcuLocator.normalize(install.resolve("LeagueClient.exe").toString()));
        assertEquals(Optional.of(install), LcuLocator.normalize(install.getParent().toString()));
        assertEquals(Optional.of(install), LcuLocator.normalize("\"" + install + "\""));
        assertTrue(LcuLocator.normalize("  ").isEmpty());
        assertTrue(LcuLocator.isInstallFolder(install));
        assertFalse(LcuLocator.isInstallFolder(install.getParent()));
    }

    @Test
    void noRunningClientMeansNoLockfile() throws Exception {
        install("default/League of Legends", 0);
        LcuLocator locator = locator("");
        assertTrue(locator.find().isEmpty(), "Ohne lockfile läuft kein Client");
        assertEquals(Optional.of(home.resolve("default/League of Legends")), locator.detectedInstallFolder());
    }
}
