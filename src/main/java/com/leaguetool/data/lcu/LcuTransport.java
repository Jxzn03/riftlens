package com.leaguetool.data.lcu;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Optional;

/** Lesender Zugriff auf den League-Client. Es gibt absichtlich keine Methode zum Schreiben. */
public interface LcuTransport {

    /**
     * @return Antwort des Clients; leer, wenn er den Endpunkt gerade nicht anbietet (z. B. außerhalb der Auswahl)
     * @throws IOException wenn der Client nicht erreichbar ist oder den Zugang ablehnt
     */
    Optional<JsonNode> get(LcuEndpoint endpoint) throws IOException;
}
