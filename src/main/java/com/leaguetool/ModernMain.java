package com.leaguetool;

import com.leaguetool.config.AppSettings;
import com.leaguetool.web.AppWindow;
import com.leaguetool.web.SmokeTest;
import com.leaguetool.web.WebContext;
import com.leaguetool.web.WebServer;

import java.nio.file.Path;
import java.util.List;

/**
 * Einstiegspunkt der App: lokaler Server (nur 127.0.0.1) plus ein eigenes Edge-/Chrome-App-Fenster.
 *
 * <p>Parameter: {@code --no-window} (nur Server, URL auf stdout, kein automatisches Beenden),
 * {@code --port=N} (Standard: freier Port), {@code --smoke-test} (prüft alle Endpunkte und beendet sich).
 */
public final class ModernMain {

    private ModernMain() {
    }

    public static void main(String[] args) throws Exception {
        List<String> options = List.of(args);
        boolean smoke = options.contains("--smoke-test");
        boolean window = !smoke && !options.contains("--no-window");
        int port = options.stream().filter(o -> o.startsWith("--port="))
                .map(o -> Integer.parseInt(o.substring("--port=".length())))
                .findFirst().orElse(0);

        AppSettings settings = AppSettings.load(AppSettings.defaultHome());
        WebContext ctx = new WebContext(settings);
        WebServer server = WebServer.start(ctx, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            ctx.close();
        }, "modern-shutdown"));
        ctx.reload();

        if (smoke) {
            int code = SmokeTest.run(server);
            System.exit(code);
        }
        System.out.println("RiftLens: " + server.entryUrl());
        // Nach dem Start des Updaters: Fenster schließen und beenden, der Updater tauscht dann den App-Ordner.
        ctx.updates().onRestart(() -> {
            AppWindow.closeOpened();
            System.exit(0);
        });
        ctx.updates().checkOnStartup();
        if (window) {
            server.exitWhenClosed(() -> System.exit(0));
            Path ui = settings.home().resolve("modern-ui");
            if (!AppWindow.open(server.entryUrl(), ui.resolve("browser-profile"), ui.resolve("ui-state.json"))) {
                System.out.println("Kein Browser gefunden. Bitte die URL oben manuell öffnen.");
            }
        }
    }
}
