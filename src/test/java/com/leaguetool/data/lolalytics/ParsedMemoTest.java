package com.leaguetool.data.lolalytics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParsedMemoTest {

    @Test
    void sameTextIsParsedOnce() throws IOException {
        ParsedMemo memo = new ParsedMemo(4);
        AtomicInteger calls = new AtomicInteger();
        ParsedMemo.Parser<Object> parser = json -> {
            calls.incrementAndGet();
            return new Object();
        };
        Object first = memo.parse("a", "{\"x\":1}", parser);
        // Neu gelesener, gleicher Text (anderes String-Objekt): kein zweites Parsen.
        Object second = memo.parse("a", new String("{\"x\":1}"), parser);
        assertSame(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void changedTextIsParsedAgain() throws IOException {
        ParsedMemo memo = new ParsedMemo(4);
        assertEquals("1", memo.parse("a", "1", json -> json));
        assertEquals("2", memo.parse("a", "2", json -> json));
        assertEquals("2", memo.parse("a", "2", json -> "nicht erneut geparst"));
    }

    @Test
    void failuresAreNotRemembered() throws IOException {
        ParsedMemo memo = new ParsedMemo(4);
        assertThrows(IOException.class, () -> memo.parse("a", "kaputt", json -> {
            throw new IOException("unlesbar");
        }));
        assertEquals("ok", memo.parse("a", "kaputt", json -> "ok"));
    }

    @Test
    void keepsOnlyTheMostRecentlyUsedEntries() throws IOException {
        ParsedMemo memo = new ParsedMemo(2);
        memo.parse("a", "1", json -> json);
        memo.parse("b", "1", json -> json);
        memo.parse("a", "1", json -> json);
        memo.parse("c", "1", json -> json);
        assertEquals(2, memo.size());
        // "b" war am längsten unbenutzt und wurde verdrängt, "a" nicht.
        assertEquals("1", memo.parse("a", "1", json -> "neu"));
        assertEquals("neu", memo.parse("b", "1", json -> "neu"));
    }
}
