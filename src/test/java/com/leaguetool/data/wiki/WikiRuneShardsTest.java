package com.leaguetool.data.wiki;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WikiRuneShardsTest {

    /** Ausschnitt der Wiki-Seite „Rune“ (Stand V26.x); auch für die Tests des Runen-Abgleichs. */
    public static final String TABLE = """
            === Shards ===
            {|class="article-table" width="100%" style="text-align:center;"
            !
            ! <center>Option 1</center>
            ! <center>Option 2</center>
            ! <center>Option 3</center>
            |-
            ! align="center" | Slot 1<br />{{sbc|Offense}}
            | [[File:Rune shard Adaptive Force.png|30px|link=]]<br />{{adaptive|9}}
            | [[File:Rune shard Attack Speed.png|30px|link=]]<br />{{as|10% '''bonus''' attack speed}}
            | [[File:Rune shard Cooldown Reduction.png|30px|link=]]<br />8 ability haste
            |-
            ! align="center" | Slot 2<br />{{sbc|Flex}}
            | [[File:Rune shard Adaptive Force.png|30px|link=]]<br />{{adaptive|9}}
            | [[File:Rune shard Movement Speed.png|30px|link=]]<br />{{as|{{fd|2.5}}% '''bonus''' movement speed}}
            | [[File:Rune shard Health Scaling.png|30px|link=]]<br />{{pp|10 * x for 20|color=health}} {{as|'''bonus''' health}}
            |-
            ! align="center" | Slot 3<br />{{sbc|Defense}}
            | [[File:Rune shard Health.png|30px|link=]]<br />{{as|65 '''bonus''' health}}
            | [[File:Rune shard Tenacity and Slow Resist.png|30px|link=]]<br />15% {{tip|tenacity}} and {{tip|slow resist}}
            | [[File:Rune shard Health Scaling.png|30px|link=]]<br />{{pp|10 * x for 20|color=health}} {{as|'''bonus''' health}}
            |}

            === Substitutions ===
            """;

    @Test
    void readsValuesAndRows() throws IOException {
        WikiRuneShards.Shards shards = WikiRuneShards.parse(TABLE);
        assertEquals(List.of(List.of(5008, 5005, 5007), List.of(5008, 5010, 5001), List.of(5011, 5013, 5001)), shards.rows());
        assertEquals(9.0, shards.values().get(5008));
        assertEquals(10.0, shards.values().get(5005));
        assertEquals(8.0, shards.values().get(5007));
        assertEquals(2.5, shards.values().get(5010));
        assertEquals(10.0, shards.values().get(5001), "Leben je Level");
        assertEquals(65.0, shards.values().get(5011));
        assertEquals(15.0, shards.values().get(5013));
    }

    @Test
    void changedTablesAreRejected() {
        assertThrows(IOException.class, () -> WikiRuneShards.parse("== Rune =="));
        assertThrows(IOException.class, () -> WikiRuneShards.parse(TABLE.replace("Rune shard Health.png", "Rune shard Mystery.png")));
    }
}
