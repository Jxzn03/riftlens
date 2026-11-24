package com.leaguetool.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppSettingsOwnAccountsTest {

    @TempDir
    Path home;

    @Test
    void singleEntryFromTheFirstVersionBecomesTheFirstAccount() throws Exception {
        Files.writeString(home.resolve("settings.properties"),
                "riot.own.riotId=Alt#EUW\nriot.own.platform=euw1\nriot.own.puuid=p-alt\n");
        AppSettings settings = AppSettings.load(home);
        assertEquals(List.of(new AppSettings.OwnAccount("Alt#EUW", "euw1", "p-alt")), settings.ownAccounts());

        settings.setOwnAccounts(List.of(settings.ownAccounts().getFirst(), new AppSettings.OwnAccount("Zweit#EUW", "euw1", "p2")));
        settings.save();

        AppSettings read = AppSettings.load(home);
        assertEquals(2, read.ownAccounts().size());
        assertEquals("Zweit#EUW", read.ownAccounts().get(1).riotId());
        assertFalse(Files.readString(home.resolve("settings.properties")).contains("riot.own.riotId="),
                "alter Einzeleintrag wird beim Speichern umgeschrieben");
    }
}
