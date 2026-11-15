package com.leaguetool.web;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Öffnet die Oberfläche als eigenes Fenster ohne Browser-Leisten (App-Modus von Edge/Chrome) mit einem
 * eigenen Profil, damit sie vom normalen Browser getrennt bleibt.
 */
public final class AppWindow {

    private AppWindow() {
    }

    /** @return {@code false}, wenn weder ein passender Browser noch ein Standard-Browser startbar war */
    public static boolean open(String url, Path profile) {
        for (Path exe : candidates()) {
            if (Files.isExecutable(exe)) {
                try {
                    Files.createDirectories(profile);
                    new ProcessBuilder(exe.toString(),
                            "--app=" + url,
                            "--user-data-dir=" + profile,
                            "--window-size=1500,940",
                            "--no-first-run",
                            "--no-default-browser-check")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
                    return true;
                } catch (IOException e) {
                    // nächsten Kandidaten versuchen
                }
            }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
        return false;
    }

    private static List<Path> candidates() {
        List<Path> result = new ArrayList<>();
        for (String env : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String base = System.getenv(env);
            if (base != null && !base.isBlank()) {
                result.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"));
                result.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
            }
        }
        result.add(Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
        result.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
        for (String name : List.of("microsoft-edge", "google-chrome", "chromium", "chromium-browser")) {
            result.add(Path.of("/usr/bin", name));
        }
        return result;
    }
}
