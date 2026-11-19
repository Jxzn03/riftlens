package com.leaguetool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DtoShardLabelTest {

    @Test
    void matchStatsUseShortShardNames() {
        assertEquals("Adaptive", Dto.shardLabel(5008));
        assertEquals("HP-Scaling", Dto.shardLabel(5001));
        assertEquals("Tenacity", Dto.shardLabel(5013));
        assertEquals("HP-Flat", Dto.shardLabel(5011));
    }

    @Test
    void otherShardsKeepTheirNames() {
        assertEquals("Attack Speed", Dto.shardLabel(5005));
        assertEquals("Shard 9999", Dto.shardLabel(9999));
    }
}
