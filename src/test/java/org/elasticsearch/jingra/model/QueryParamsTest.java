package org.elasticsearch.jingra.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryParamsTest {

    @Test
    void testPutAndGet() {
        QueryParams params = new QueryParams();
        params.put("size", 100);
        params.put("k", 1000);

        assertEquals(100, params.getInteger("size"));
        assertEquals(1000, params.getInteger("k"));
    }

    @Test
    void testConstructorWithMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("size", 100);
        map.put("name", "test");

        QueryParams params = new QueryParams(map);

        assertEquals(100, params.getInteger("size"));
        assertEquals("test", params.getString("name"));
    }

    @Test
    void testGetFloatList() {
        QueryParams params = new QueryParams();
        List<Float> vector = Arrays.asList(0.1f, 0.2f, 0.3f);
        params.put("query_vector", vector);

        List<Float> retrieved = params.getFloatList("query_vector");
        assertEquals(3, retrieved.size());
        assertEquals(0.1f, retrieved.get(0));
    }

    @Test
    void testGetMap() {
        QueryParams params = new QueryParams();
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("valid", true);
        params.put("meta_conditions", conditions);

        Map<String, Object> retrieved = params.getMap("meta_conditions");
        assertNotNull(retrieved);
        assertEquals(true, retrieved.get("valid"));
    }

    @Test
    void testGetAll() {
        QueryParams params = new QueryParams();
        params.put("size", 100);
        params.put("k", 1000);

        Map<String, Object> all = params.getAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("size"));
        assertTrue(all.containsKey("k"));
    }

    @Test
    void getLong_fromInteger() {
        QueryParams p = new QueryParams();
        p.put("n", 42);
        assertEquals(42L, p.getLong("n"));
    }

    @Test
    void getFloatList_nonNumberItemReturnsNull() {
        QueryParams p = new QueryParams();
        p.put("v", Arrays.asList(1.0f, "x"));
        assertNull(p.getFloatList("v"));
    }

    @Test
    void getMap_nonMapReturnsNull() {
        QueryParams p = new QueryParams();
        p.put("k", "not-a-map");
        assertNull(p.getMap("k"));
    }

    @Test
    void testFluentAPI() {
        QueryParams params = new QueryParams()
                .put("size", 100)
                .put("k", 1000);

        assertEquals(100, params.getInteger("size"));
        assertEquals(1000, params.getInteger("k"));
    }

    @Test
    void toString_containsParams() {
        QueryParams params = new QueryParams().put("size", 10);
        assertTrue(params.toString().contains("size"));
    }

    @Test
    void get_returnsStoredValueOrNull() {
        QueryParams p = new QueryParams().put("x", "raw");
        assertEquals("raw", p.get("x"));
        assertNull(p.get("missing"));
    }

    @Test
    void getString_missingKeyReturnsNull() {
        assertNull(new QueryParams().getString("none"));
    }

    @Test
    void getString_nullStoredValueReturnsNull() {
        QueryParams p = new QueryParams();
        p.put("n", null);
        assertNull(p.getString("n"));
    }

    @Test
    void getInteger_fromNonIntegerNumber_andNonNumberReturnsNull() {
        QueryParams p = new QueryParams();
        p.put("long", 12L);
        assertEquals(12, p.getInteger("long"));
        p.put("dbl", 3.7);
        assertEquals(3, p.getInteger("dbl"));
        p.put("bad", "7");
        assertNull(p.getInteger("bad"));
    }

    @Test
    void getLong_directLong_andNonNumberReturnsNull() {
        QueryParams p = new QueryParams();
        p.put("l", 99L);
        assertEquals(99L, p.getLong("l"));
        p.put("bad", "99");
        assertNull(p.getLong("bad"));
    }

    @Test
    void getFloatList_nonListReturnsNull() {
        QueryParams p = new QueryParams();
        p.put("v", "nope");
        assertNull(p.getFloatList("v"));
    }

    @Test
    void getAll_isDefensiveCopy() {
        QueryParams p = new QueryParams().put("a", 1);
        p.getAll().put("b", 2);
        assertEquals(1, p.getAll().size());
        assertFalse(p.getAll().containsKey("b"));
    }
}
