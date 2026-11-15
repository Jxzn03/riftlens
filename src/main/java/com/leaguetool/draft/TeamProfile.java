package com.leaguetool.draft;

import com.leaguetool.data.lolalytics.LolalyticsBuild;

import java.util.ArrayList;
import java.util.List;

/**
 * Schadensverteilung und Stärke eines Teams über die Spieldauer.
 *
 * @param champions      Champions, aus denen das Profil besteht
 * @param ratingByLength mittlere Rating-Punkte je Champion und Spieldauer-Abschnitt von kurz nach lang, relativ zur üblichen
 *                       Stärke der Champions; als Mittelwert auch bei unterschiedlich großen Teams vergleichbar
 */
public record TeamProfile(int champions, double physical, double magic, double trueDamage, List<Double> ratingByLength) {

    public TeamProfile {
        ratingByLength = List.copyOf(ratingByLength);
    }

    /** @param priorGames Glättung wie in {@link DraftAnalyzer.Risk} */
    public static TeamProfile of(List<LolalyticsBuild> champions, int priorGames) {
        double physical = 0;
        double magic = 0;
        double trueDamage = 0;
        int buckets = champions.stream().mapToInt(c -> c.gameLength().size()).max().orElse(0);
        double[] totals = new double[buckets];
        int[] contributors = new int[buckets];
        for (LolalyticsBuild champion : champions) {
            physical += champion.damage().physical();
            magic += champion.damage().magic();
            trueDamage += champion.damage().trueDamage();
            int games = champion.gameLength().stream().mapToInt(LolalyticsBuild.LengthBucket::games).sum();
            int wins = champion.gameLength().stream().mapToInt(LolalyticsBuild.LengthBucket::wins).sum();
            double baseWinRate = (wins + priorGames * 0.5) / (games + (double) priorGames);
            double baseRating = DraftAnalyzer.rating(baseWinRate);
            for (int i = 0; i < champion.gameLength().size(); i++) {
                LolalyticsBuild.LengthBucket bucket = champion.gameLength().get(i);
                double winRate = (bucket.wins() + priorGames * baseWinRate) / (bucket.games() + (double) priorGames);
                totals[i] += DraftAnalyzer.rating(winRate) - baseRating;
                contributors[i]++;
            }
        }
        List<Double> ratings = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            ratings.add(totals[i] / contributors[i]);
        }
        return new TeamProfile(champions.size(), physical, magic, trueDamage, ratings);
    }
}
