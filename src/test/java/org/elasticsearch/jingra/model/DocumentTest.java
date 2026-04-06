package org.elasticsearch.jingra.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTest {

    @Test
    void testPutAndGet() {
        Document doc = new Document();
        doc.put("name", "test");
        doc.put("count", 42);
        doc.put("active", true);

        assertEquals("test", doc.getString("name"));
        assertEquals(42, doc.getInteger("count"));
        assertEquals(true, doc.getBoolean("active"));
    }

    @Test
    void testGetFloatList() {
        Document doc = new Document();
        List<Float> vector = Arrays.asList(0.1f, 0.2f, 0.3f);
        doc.put("vector", vector);

        List<Float> retrieved = doc.getFloatList("vector");
        assertEquals(3, retrieved.size());
        assertEquals(0.1f, retrieved.get(0));
    }

    @Test
    void testContainsField() {
        Document doc = new Document();
        doc.put("field1", "value1");

        assertTrue(doc.containsField("field1"));
        assertFalse(doc.containsField("field2"));
    }

    @Test
    void testGetNonExistentField() {
        Document doc = new Document();

        assertNull(doc.getString("missing"));
        assertNull(doc.getInteger("missing"));
        assertNull(doc.getBoolean("missing"));
    }

    @Test
    void mapConstructorCopiesFields() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("a", 1);
        Document d = new Document(m);
        m.put("a", 2);
        assertEquals(1, d.getInteger("a"));
    }

    @Test
    void getLong_fromNumber() {
        Document d = new Document();
        d.put("n", 42L);
        assertEquals(42L, d.getLong("n"));
        d.put("n", 7);
        assertEquals(7L, d.getLong("n"));
    }

    @Test
    void getDouble_fromString() {
        Document d = new Document();
        d.put("n", "3.5");
        assertEquals(3.5, d.getDouble("n"), 0.001);
    }

    @Test
    void getDoubleList_nonNumberItemReturnsNull() {
        Document d = new Document();
        d.put("v", java.util.List.of(1.0, "x"));
        assertNull(d.getDoubleList("v"));
    }

    @Test
    void getFloatList_nonNumberItemReturnsNull() {
        Document d = new Document();
        d.put("v", java.util.List.of(1.0f, "x"));
        assertNull(d.getFloatList("v"));
    }

    @Test
    void testGetFields() {
        Document doc = new Document();
        doc.put("field1", "value1");
        doc.put("field2", 123);

        var fields = doc.getFields();
        assertEquals(2, fields.size());
        assertTrue(fields.containsKey("field1"));
        assertTrue(fields.containsKey("field2"));
    }
}
