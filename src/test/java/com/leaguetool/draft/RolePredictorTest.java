package com.leaguetool.draft;

import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.draft.RolePredictor.Prediction;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePredictorTest {

    private final Map<Pick, Double> games = new HashMap<>();

    private Prediction predict(Slot... slots) {
        return RolePredictor.predict(List.of(slots), p -> games.getOrDefault(p, 0.0));
    }

    @Test
    void emptyTeamHasNoRoles() {
        assertTrue(predict().roles().isEmpty());
    }

    @Test
    void championGoesToItsMainRole() {
        games.put(new Pick(1, Role.MIDDLE), 9000.0);
        games.put(new Pick(1, Role.TOP), 1000.0);
        Prediction prediction = predict(new Slot(1, null));
        assertEquals(Role.MIDDLE, prediction.roles().get(1));
        assertTrue(prediction.probabilities().get(1).get(Role.MIDDLE) > 0.85);
    }

    @Test
    void flexChampionYieldsToTheOtherChampionsRole() {
        games.put(new Pick(1, Role.MIDDLE), 6000.0);
        games.put(new Pick(1, Role.TOP), 4000.0);
        games.put(new Pick(2, Role.MIDDLE), 10000.0);
        assertEquals(Map.of(1, Role.TOP, 2, Role.MIDDLE), predict(new Slot(1, null), new Slot(2, null)).roles());
    }

    @Test
    void forcedRoleWinsOverStatistics() {
        games.put(new Pick(1, Role.MIDDLE), 10000.0);
        games.put(new Pick(2, Role.MIDDLE), 100.0);
        games.put(new Pick(2, Role.UTILITY), 50.0);
        Prediction prediction = predict(new Slot(1, Role.TOP), new Slot(2, null));
        assertEquals(Role.TOP, prediction.roles().get(1));
        assertEquals(Role.MIDDLE, prediction.roles().get(2));
        assertEquals(1.0, prediction.probabilities().get(1).get(Role.TOP), 1e-9);
    }

    @Test
    void championWithoutDataStillGetsARole() {
        assertEquals(1, predict(new Slot(1, null)).roles().size());
    }

    @Test
    void fiveChampionsGetFiveDifferentRoles() {
        Prediction prediction = predict(new Slot(1, null), new Slot(2, null), new Slot(3, null), new Slot(4, null), new Slot(5, null));
        assertEquals(5, prediction.roles().values().stream().distinct().count());
    }

    @Test
    void doubleForcedRoleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> predict(new Slot(1, Role.TOP), new Slot(2, Role.TOP)));
    }
}
