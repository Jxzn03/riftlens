package com.leaguetool.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Schreibt Dateien über eine temporäre Datei und einen (möglichst atomaren) Move. */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Schreibt {@code data} nach {@code target}. Leser sehen entweder den alten oder den neuen Inhalt.
     * Unterstützt das Dateisystem keinen atomaren Move, wird ein normaler Move verwendet.
     */
    public static Path write(Path target, byte[] data) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }
}
