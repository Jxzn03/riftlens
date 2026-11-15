package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Desktop;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Rectangle;
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

    /** Kleinere gespeicherte Fenster gelten als unbrauchbar; dann startet das Fenster maximiert. */
    static final int MIN_WIDTH = 640;
    static final int MIN_HEIGHT = 420;
    private static final String MAXIMIZED = "--start-maximized";

    /**
     * Öffnet das Fenster maximiert oder in der zuletzt gespeicherten Größe und Lage.
     *
     * @param uiState {@code ui-state.json} der Oberfläche; ihr Feld {@code window} schreibt {@code core.js}
     * @return {@code false}, wenn weder ein passender Browser noch ein Standard-Browser startbar war
     */
    public static boolean open(String url, Path profile, Path uiState) {
        List<String> placement = placementArgs(readWindow(uiState), AppWindow::onScreen);
        for (Path exe : candidates()) {
            if (Files.isExecutable(exe)) {
                try {
                    Files.createDirectories(profile);
                    List<String> command = new ArrayList<>(List.of(exe.toString(),
                            "--app=" + url,
                            "--user-data-dir=" + profile,
                            "--no-first-run",
                            "--no-default-browser-check"));
                    command.addAll(placement);
                    new ProcessBuilder(command)
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

    /**
     * Startparameter für die Fensterlage: maximiert, wenn nichts Brauchbares gespeichert ist oder das Fenster maximiert
     * war; sonst die gespeicherte Größe und, falls sie auf einem Bildschirm liegt, die Position.
     */
    static List<String> placementArgs(JsonNode window, PositionCheck onScreen) {
        if (window == null || !window.isObject() || window.path("maximized").asBoolean(false)) {
            return List.of(MAXIMIZED);
        }
        int width = window.path("width").asInt(0);
        int height = window.path("height").asInt(0);
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return List.of(MAXIMIZED);
        }
        List<String> args = new ArrayList<>(List.of("--window-size=" + width + "," + height));
        if (window.path("x").isNumber() && window.path("y").isNumber()) {
            int x = window.path("x").asInt();
            int y = window.path("y").asInt();
            if (onScreen.visible(x, y, width, height)) {
                args.add("--window-position=" + x + "," + y);
            }
        }
        return args;
    }

    /** Ob ein Fenster an dieser Stelle sichtbar wäre (z. B. nach Abziehen eines zweiten Bildschirms nicht mehr). */
    @FunctionalInterface
    interface PositionCheck {
        boolean visible(int x, int y, int width, int height);
    }

    private static JsonNode readWindow(Path uiState) {
        try {
            return Files.isRegularFile(uiState) ? new ObjectMapper().readTree(uiState.toFile()).get("window") : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Mindestens die Titelleiste (oberer Streifen) muss auf einem Bildschirm liegen, damit man das Fenster greifen kann. */
    private static boolean onScreen(int x, int y, int width, int height) {
        try {
            Rectangle titleBar = new Rectangle(x, y, width, 40);
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle overlap = device.getDefaultConfiguration().getBounds().intersection(titleBar);
                if (!overlap.isEmpty() && overlap.width >= 100) {
                    return true;
                }
            }
            return false;
        } catch (HeadlessException | SecurityException e) {
            return false;
        }
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
