package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppWindowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AppWindow.PositionCheck ANYWHERE = (x, y, w, h) -> true;
    private static final AppWindow.PositionCheck NOWHERE = (x, y, w, h) -> false;

    private static JsonNode window(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void startsMaximizedWithoutSavedWindow() {
        assertEquals(List.of("--start-maximized"), AppWindow.placementArgs(null, ANYWHERE));
    }

    @Test
    void startsMaximizedWhenLastClosedMaximized() throws Exception {
        assertEquals(List.of("--start-maximized"),
                AppWindow.placementArgs(window("{\"maximized\":true,\"x\":10,\"y\":10,\"width\":1200,\"height\":800}"), ANYWHERE));
    }

    @Test
    void restoresSavedSizeAndPosition() throws Exception {
        assertEquals(List.of("--window-size=1280,860", "--window-position=-1500,40"),
                AppWindow.placementArgs(window("{\"maximized\":false,\"x\":-1500,\"y\":40,\"width\":1280,\"height\":860}"), ANYWHERE));
    }

    @Test
    void dropsPositionThatIsNoLongerOnAnyScreen() throws Exception {
        assertEquals(List.of("--window-size=1280,860"),
                AppWindow.placementArgs(window("{\"maximized\":false,\"x\":4000,\"y\":40,\"width\":1280,\"height\":860}"), NOWHERE));
    }

    @Test
    void tinyOrBrokenSizesFallBackToMaximized() throws Exception {
        assertEquals(List.of("--start-maximized"),
                AppWindow.placementArgs(window("{\"maximized\":false,\"x\":0,\"y\":0,\"width\":200,\"height\":100}"), ANYWHERE));
        assertEquals(List.of("--start-maximized"), AppWindow.placementArgs(window("\"kaputt\""), ANYWHERE));
    }
}
