package com.leaguetool.draft;

import com.leaguetool.data.lolalytics.LolalyticsBuild;
import com.leaguetool.data.lolalytics.LolalyticsBuild.Damage;
import com.leaguetool.data.lolalytics.LolalyticsBuild.LengthBucket;
import com.leaguetool.data.lolalytics.LolalyticsPairs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamProfileTest {

    private static final int PRIOR = 1000;

    private static LolalyticsBuild champion(double physical, double magic, LengthBucket... buckets) {
        return new LolalyticsBuild(1000, 0.5, 0.1, 0.1, List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), new LolalyticsPairs(Map.of()), new Damage(physical, magic, 0), List.of(buckets), List.of());
    }

    @Test
    void emptyTeamHasNoProfile() {
        TeamProfile profile = TeamProfile.of(List.of(), PRIOR);
        assertEquals(0, profile.champions());
        assertTrue(profile.ratingByLength().isEmpty());
    }

    @Test
    void damageIsSummedAcrossChampions() {
        TeamProfile profile = TeamProfile.of(List.of(champion(100, 10), champion(50, 20)), PRIOR);
        assertEquals(2, profile.champions());
        assertEquals(150, profile.physical(), 1e-9);
        assertEquals(30, profile.magic(), 1e-9);
    }

    @Test
    void addingAnEquallyStrongChampionDoesNotChangeTheCurve() {
        LengthBucket[] curve = {new LengthBucket(3000, 5000), new LengthBucket(2000, 5000)};
        TeamProfile one = TeamProfile.of(List.of(champion(1, 1, curve)), PRIOR);
        TeamProfile five = TeamProfile.of(List.of(champion(1, 1, curve), champion(1, 1, curve), champion(1, 1, curve),
                champion(1, 1, curve), champion(1, 1, curve)), PRIOR);
        assertEquals(one.ratingByLength().get(0), five.ratingByLength().get(0), 1e-9, "Die Teamgröße darf die Kurve nicht verschieben");
        assertEquals(one.ratingByLength().get(1), five.ratingByLength().get(1), 1e-9);
    }
}
