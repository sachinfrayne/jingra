package org.elasticsearch.jingra.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryResponse model class.
 */
class QueryResponseTest {

    @Test
    void testConstructorAndGetters() {
        // Arrange
        List<String> ids = List.of("doc1", "doc2", "doc3");
        Double clientLatency = 123.45;
        Long serverLatency = 50L;

        // Act
        QueryResponse response = new QueryResponse(ids, clientLatency, serverLatency);

        // Assert
        assertEquals(3, response.getResultCount());
        assertEquals(clientLatency, response.getClientLatencyMs());
        assertEquals(serverLatency, response.getServerLatencyMs());
        assertThat(response.getDocumentIds()).containsExactly("doc1", "doc2", "doc3");
    }

    @Test
    void testEmptyDocumentIds() {
        // Arrange
        List<String> emptyIds = List.of();
        Double clientLatency = 10.5;
        Long serverLatency = 5L;

        // Act
        QueryResponse response = new QueryResponse(emptyIds, clientLatency, serverLatency);

        // Assert
        assertEquals(0, response.getResultCount());
        assertThat(response.getDocumentIds()).isEmpty();
        assertNotNull(response.getDocumentIds()); // Should be empty list, not null
    }

    @Test
    void testNullDocumentIds() {
        // Arrange - null document IDs should be handled gracefully
        Double clientLatency = 10.5;
        Long serverLatency = 5L;

        // Act
        QueryResponse response = new QueryResponse(null, clientLatency, serverLatency);

        // Assert
        assertEquals(0, response.getResultCount());
        assertThat(response.getDocumentIds()).isEmpty();
        assertNotNull(response.getDocumentIds()); // Should convert null to empty list
    }

    @Test
    void testNullLatencies() {
        // Arrange - latencies are optional (can be null)
        List<String> ids = List.of("doc1", "doc2");

        // Act
        QueryResponse response = new QueryResponse(ids, null, null);

        // Assert
        assertEquals(2, response.getResultCount());
        assertNull(response.getClientLatencyMs());
        assertNull(response.getServerLatencyMs());
        assertThat(response.getDocumentIds()).hasSize(2);
    }

    @Test
    void testImmutability_modifyingInputList() {
        // Arrange
        List<String> ids = new ArrayList<>(List.of("doc1", "doc2"));
        QueryResponse response = new QueryResponse(ids, 10.0, 5L);

        // Act - modify the original list
        ids.add("doc3");
        ids.clear();

        // Assert - QueryResponse should be unaffected by modifications to input
        assertEquals(2, response.getResultCount());
        assertThat(response.getDocumentIds()).containsExactly("doc1", "doc2");
    }

    @Test
    void testImmutability_modifyingReturnedList() {
        // Arrange
        List<String> ids = List.of("doc1", "doc2");
        QueryResponse response = new QueryResponse(ids, 10.0, 5L);

        // Act - get the list
        List<String> returnedIds = response.getDocumentIds();

        // Assert - modifying returned list should not affect internal state
        assertDoesNotThrow(() -> {
            returnedIds.add("doc3"); // This should succeed (returned list is mutable)
        });

        // Getting a fresh copy should still have original data
        assertThat(response.getDocumentIds()).containsExactly("doc1", "doc2");
        assertEquals(2, response.getResultCount());
    }

    @Test
    void testToString() {
        // Arrange
        List<String> ids = List.of("doc1", "doc2", "doc3");
        Double clientLatency = 123.45;
        Long serverLatency = 50L;
        QueryResponse response = new QueryResponse(ids, clientLatency, serverLatency);

        // Act
        String result = response.toString();

        // Assert
        assertThat(result).contains("QueryResponse");
        assertThat(result).contains("resultCount=3");
        assertThat(result).contains("clientLatencyMs=123.45");
        assertThat(result).contains("serverLatencyMs=50");
    }

    @Test
    void testToString_withNullLatencies() {
        // Arrange
        List<String> ids = List.of("doc1");
        QueryResponse response = new QueryResponse(ids, null, null);

        // Act
        String result = response.toString();

        // Assert
        assertThat(result).contains("QueryResponse");
        assertThat(result).contains("resultCount=1");
        assertThat(result).contains("clientLatencyMs=null");
        assertThat(result).contains("serverLatencyMs=null");
    }

    @Test
    void testGetResultCount_withVariousSizes() {
        // Test with 0, 1, and many results
        assertEquals(0, new QueryResponse(List.of(), 1.0, 1L).getResultCount());
        assertEquals(1, new QueryResponse(List.of("doc1"), 1.0, 1L).getResultCount());
        assertEquals(100, new QueryResponse(
            List.of("d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9", "d10",
                    "d11", "d12", "d13", "d14", "d15", "d16", "d17", "d18", "d19", "d20",
                    "d21", "d22", "d23", "d24", "d25", "d26", "d27", "d28", "d29", "d30",
                    "d31", "d32", "d33", "d34", "d35", "d36", "d37", "d38", "d39", "d40",
                    "d41", "d42", "d43", "d44", "d45", "d46", "d47", "d48", "d49", "d50",
                    "d51", "d52", "d53", "d54", "d55", "d56", "d57", "d58", "d59", "d60",
                    "d61", "d62", "d63", "d64", "d65", "d66", "d67", "d68", "d69", "d70",
                    "d71", "d72", "d73", "d74", "d75", "d76", "d77", "d78", "d79", "d80",
                    "d81", "d82", "d83", "d84", "d85", "d86", "d87", "d88", "d89", "d90",
                    "d91", "d92", "d93", "d94", "d95", "d96", "d97", "d98", "d99", "d100"),
            1.0, 1L).getResultCount());
    }

    @Test
    void testLatencyTypes() {
        // Arrange - test different latency values
        QueryResponse response1 = new QueryResponse(List.of("doc1"), 0.0, 0L);
        QueryResponse response2 = new QueryResponse(List.of("doc1"), Double.MAX_VALUE, Long.MAX_VALUE);
        QueryResponse response3 = new QueryResponse(List.of("doc1"), 123.456789, 999L);

        // Assert
        assertEquals(0.0, response1.getClientLatencyMs());
        assertEquals(0L, response1.getServerLatencyMs());

        assertEquals(Double.MAX_VALUE, response2.getClientLatencyMs());
        assertEquals(Long.MAX_VALUE, response2.getServerLatencyMs());

        assertEquals(123.456789, response3.getClientLatencyMs(), 0.000001);
        assertEquals(999L, response3.getServerLatencyMs());
    }
}
