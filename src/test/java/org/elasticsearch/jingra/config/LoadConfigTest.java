package org.elasticsearch.jingra.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadConfigTest {

    @Test
    void defaultsWhenNullOrNonPositive() {
        LoadConfig c = new LoadConfig();
        assertEquals(10_000, c.batchSizeOrDefault());
        assertEquals(10, c.threadsOrDefault());
        assertEquals(20, c.queueCapacityOrDefault());

        c.setBatchSize(0);
        c.setThreads(-1);
        c.setQueueCapacity(0);
        assertEquals(10_000, c.batchSizeOrDefault());
        assertEquals(10, c.threadsOrDefault());
        assertEquals(20, c.queueCapacityOrDefault());
    }

    @Test
    void usesPositiveOverrides() {
        LoadConfig c = new LoadConfig();
        c.setBatchSize(500);
        c.setThreads(4);
        c.setQueueCapacity(100);
        assertEquals(500, c.batchSizeOrDefault());
        assertEquals(4, c.threadsOrDefault());
        assertEquals(100, c.queueCapacityOrDefault());
    }
}
