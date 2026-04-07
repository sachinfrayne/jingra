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
    void get_returnsStoredValueOrNull() {
        Document d = new Document();
        d.put("x", 42);
        assertEquals(42, d.get("x"));
        assertNull(d.get("missing"));
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

    @Test
    void getInteger_fromNonIntegerNumber_andString_andNonNumberReturnsNull() {
        Document d = new Document();
        d.put("long", 5L);
        assertEquals(5, d.getInteger("long"));
        d.put("str", "99");
        assertEquals(99, d.getInteger("str"));
        d.put("bad", new Object());
        assertNull(d.getInteger("bad"));
    }

    @Test
    void getLong_fromString_andNonConvertibleReturnsNull() {
        Document d = new Document();
        d.put("s", "100");
        assertEquals(100L, d.getLong("s"));
        d.put("bad", List.of(1));
        assertNull(d.getLong("bad"));
    }

    @Test
    void getDouble_directDouble_floatNumber_andNonNumberReturnsNull() {
        Document d = new Document();
        d.put("boxed", Double.valueOf(1.25));
        assertEquals(1.25, d.getDouble("boxed"), 0.0001);
        d.put("float", 2.5f);
        assertEquals(2.5, d.getDouble("float"), 0.0001);
        d.put("bad", new Object());
        assertNull(d.getDouble("bad"));
    }

    @Test
    void getBoolean_fromString_andNonBooleanReturnsNull() {
        Document d = new Document();
        d.put("t", "true");
        assertTrue(d.getBoolean("t"));
        d.put("f", "false");
        assertFalse(d.getBoolean("f"));
        d.put("n", 0);
        assertNull(d.getBoolean("n"));
    }

    @Test
    void getFloatList_nonListReturnsNull() {
        Document d = new Document();
        d.put("x", "not-a-list");
        assertNull(d.getFloatList("x"));
    }

    @Test
    void getDoubleList_allNumericElements_emptyList_andNonList() {
        Document d = new Document();
        d.put("v", List.of(1.0, 2f, 3L));
        List<Double> out = d.getDoubleList("v");
        assertEquals(3, out.size());
        assertEquals(1.0, out.get(0), 0.001);
        assertEquals(2.0, out.get(1), 0.001);
        assertEquals(3.0, out.get(2), 0.001);
        d.put("e", List.of());
        List<Double> empty = d.getDoubleList("e");
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
        d.put("n", "x");
        assertNull(d.getDoubleList("n"));
    }

    @Test
    void getFields_isDefensiveCopy() {
        Document d = new Document();
        d.put("a", 1);
        Map<String, Object> copy = d.getFields();
        copy.put("b", 2);
        assertFalse(d.containsField("b"));
    }

    @Test
    void toString_containsDocumentPrefixAndFieldKeys() {
        Document d = new Document();
        d.put("k", 1);
        String s = d.toString();
        assertTrue(s.startsWith("Document{fields="));
        assertTrue(s.contains("k"));
    }
}
