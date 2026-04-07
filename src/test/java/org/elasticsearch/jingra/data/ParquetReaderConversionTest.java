package org.elasticsearch.jingra.data;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.util.Utf8;
import org.elasticsearch.jingra.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based tests for ParquetReader private Avro conversion. Deterministic and branch-oriented.
 */
class ParquetReaderConversionTest {

    private ParquetReader reader;
    private Method convertAvroValue;
    private Method convertAvroRecordToDocument;
    private Method convertBatch;

    @BeforeEach
    void setUp() throws Exception {
        reader = new ParquetReader("unused");
        convertAvroValue = ParquetReader.class.getDeclaredMethod("convertAvroValue", Object.class);
        convertAvroValue.setAccessible(true);
        convertAvroRecordToDocument = ParquetReader.class.getDeclaredMethod(
                "convertAvroRecordToDocument", GenericRecord.class);
        convertAvroRecordToDocument.setAccessible(true);
        convertBatch = ParquetReader.class.getDeclaredMethod("convertBatch", List.class, ExecutorService.class);
        convertBatch.setAccessible(true);
    }

    private Object cv(Object value) throws Exception {
        return convertAvroValue.invoke(reader, value);
    }

    private Document cr(GenericRecord record) throws Exception {
        return (Document) convertAvroRecordToDocument.invoke(reader, record);
    }

    @Test
    void convertAvroValue_nullReturnsNull() throws Exception {
        assertNull(cv(null));
    }

    @Test
    void convertAvroValue_genericDataArray_convertsElementsAndDropsNullConversions() throws Exception {
        Schema elementSchema = Schema.create(Schema.Type.STRING);
        Schema arraySchema = Schema.createArray(elementSchema);
        GenericData.Array<Object> arr = new GenericData.Array<>(2, arraySchema);
        arr.add(new Utf8("a"));
        arr.add(null);

        @SuppressWarnings("unchecked")
        List<Object> out = (List<Object>) cv(arr);
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals("a", out.get(0));
    }

    @Test
    void convertAvroValue_plainArrayList_notGenericDataArrayBranch() throws Exception {
        List<Object> in = new ArrayList<>();
        in.add(10);
        in.add(new Utf8("z"));

        @SuppressWarnings("unchecked")
        List<Object> out = (List<Object>) cv(in);
        assertEquals(2, out.size());
        assertEquals(10, out.get(0));
        assertEquals("z", out.get(1));
    }

    @Test
    void convertAvroValue_plainArrayList_dropsNullElements() throws Exception {
        List<Object> in = new ArrayList<>();
        in.add(null);

        @SuppressWarnings("unchecked")
        List<Object> out = (List<Object>) cv(in);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void convertAvroValue_singleFieldGenericRecord_unwraps() throws Exception {
        Schema inner = Schema.create(Schema.Type.INT);
        Schema recordSchema = Schema.createRecord("Wrap", null, null, false);
        recordSchema.setFields(List.of(new Schema.Field("element", inner, null, null)));

        GenericRecord rec = new GenericRecordBuilder(recordSchema).set("element", 42).build();

        assertEquals(42, cv(rec));
    }

    @Test
    void convertAvroValue_multiFieldGenericRecord_becomesMap() throws Exception {
        Schema s = new Schema.Parser().parse("""
                {"type":"record","name":"Pair","fields":[
                  {"name":"a","type":"int"},
                  {"name":"b","type":"string"}
                ]}
                """);
        GenericRecord rec = new GenericRecordBuilder(s).set("a", 1).set("b", new Utf8("x")).build();

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) cv(rec);
        assertEquals(2, m.size());
        assertEquals(1, m.get("a"));
        assertEquals("x", m.get("b"));
    }

    @Test
    void convertAvroValue_mapSingleKey_itemUnwraps() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("item", 99);
        assertEquals(99, cv(m));
    }

    @Test
    void convertAvroValue_mapSingleKey_elementUnwraps() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("element", new Utf8("u"));
        assertEquals("u", cv(m));
    }

    @Test
    void convertAvroValue_generalMap_recursiveKeysAndValues() throws Exception {
        Map<Object, Object> m = new HashMap<>();
        m.put("k", 1);
        m.put(2, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) cv(m);
        assertEquals(2, out.size());
        assertEquals(1, out.get("k"));
        assertEquals(true, out.get("2"));
    }

    @Test
    void convertAvroValue_mapSingleEntry_nonItemElementKeysDoesNotUnwrap() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("other", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) cv(m);
        assertEquals(1, out.size());
        assertEquals(3, out.get("other"));
    }

    @Test
    void convertAvroValue_charSequenceUtf8_becomesString() throws Exception {
        assertEquals("hello", cv(new Utf8("hello")));
    }

    @Test
    void convertAvroValue_primitivesPassThrough() throws Exception {
        assertEquals(7, cv(7));
        assertEquals(Boolean.FALSE, cv(Boolean.FALSE));
        assertEquals(3.5d, cv(3.5d));
    }

    @Test
    void convertAvroRecordToDocument_skipsNullFieldValues() throws Exception {
        Schema s = new Schema.Parser().parse("""
                {"type":"record","name":"N","fields":[
                  {"name":"present","type":["null","string"],"default":null},
                  {"name":"absent","type":["null","string"],"default":null}
                ]}
                """);
        GenericRecord rec = new GenericRecordBuilder(s)
                .set("present", "ok")
                .set("absent", null)
                .build();

        Document doc = cr(rec);
        assertTrue(doc.containsField("present"));
        assertEquals("ok", doc.get("present"));
        assertFalse(doc.containsField("absent"));
    }

    @Test
    void convertAvroRecordToDocument_putsConvertedNonNullValues() throws Exception {
        Schema s = new Schema.Parser().parse("""
                {"type":"record","name":"One","fields":[{"name":"x","type":"int"}]}
                """);
        GenericRecord rec = new GenericRecordBuilder(s).set("x", 5).build();

        Document doc = cr(rec);
        assertEquals(1, doc.getFields().size());
        assertEquals(5, doc.get("x"));
    }

    @Test
    void convertAvroValue_multiFieldGenericRecord_omitsNullNestedFieldsFromMap() throws Exception {
        Schema s = new Schema.Parser().parse("""
                {"type":"record","name":"Inner","fields":[
                  {"name":"a","type":"int"},
                  {"name":"b","type":["null","string"]}
                ]}
                """);
        GenericRecord rec = new GenericRecordBuilder(s).set("a", 2).set("b", null).build();

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) cv(rec);
        assertEquals(1, m.size());
        assertEquals(2, m.get("a"));
        assertFalse(m.containsKey("b"));
    }

    @Test
    void convertAvroRecordToDocument_skipsPutWhenConvertedValueIsNull_itemMapWithNullPayload() throws Exception {
        Schema nullString = Schema.createUnion(Schema.create(Schema.Type.NULL), Schema.create(Schema.Type.STRING));
        Schema mapSchema = Schema.createMap(nullString);
        Schema recordSchema = Schema.createRecord("MapField", null, null, false);
        recordSchema.setFields(List.of(new Schema.Field("f", mapSchema, null, null)));

        Map<String, String> payload = new HashMap<>();
        payload.put("item", null);
        GenericRecord rec = new GenericRecordBuilder(recordSchema).set("f", payload).build();

        Document doc = cr(rec);
        assertFalse(doc.containsField("f"));
    }

    @Test
    void convertAvroRecordToDocument_skipsPutWhenConvertedValueIsNull_nestedSingleFieldRecordUnwrapsToNull()
            throws Exception {
        Schema innerSchema = Schema.createRecord("Inner", null, null, false);
        innerSchema.setFields(
                List.of(new Schema.Field(
                        "x",
                        Schema.createUnion(Schema.create(Schema.Type.NULL), Schema.create(Schema.Type.INT)),
                        null,
                        null)));
        GenericRecord inner = new GenericRecordBuilder(innerSchema).set("x", null).build();

        Schema outerSchema = Schema.createRecord("Outer", null, null, false);
        outerSchema.setFields(List.of(new Schema.Field("wrap", innerSchema, null, null)));
        GenericRecord rec = new GenericRecordBuilder(outerSchema).set("wrap", inner).build();

        Document doc = cr(rec);
        assertFalse(doc.containsField("wrap"));
    }

    @Test
    void convertAvroValue_singleFieldGenericRecord_nullInner_returnsNull() throws Exception {
        Schema s = new Schema.Parser().parse("""
                {"type":"record","name":"OnlyNull","fields":[
                  {"name":"x","type":["null","int"]}
                ]}
                """);
        GenericRecord rec = new GenericRecordBuilder(s).set("x", null).build();
        assertNull(cv(rec));
    }

    @Test
    void convertAvroValue_generalMap_putsNullForNullEntryValue() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("k", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) cv(m);
        assertEquals(1, out.size());
        assertNull(out.get("k"));
    }

    @Test
    void convertAvroValue_mapWithUtf8ItemKey_stringEqualsUnwrapFalse_fallsThroughToGeneralMap() throws Exception {
        Map<Object, Object> m = new HashMap<>();
        m.put(new Utf8("item"), 7);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) cv(m);
        assertEquals(1, out.size());
        assertEquals(7, out.get("item"));
    }

    @Test
    void convertBatch_parallel_futureGetFailure_wrapsIOException() throws Exception {
        Schema s = new Schema.Parser().parse(
                "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}]}");
        GenericRecord rec = new GenericRecordBuilder(s).set("x", 1).build();

        ExecutorService pool = new AbstractExecutorService() {
            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return new Future<T>() {
                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        return false;
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }

                    @Override
                    public boolean isDone() {
                        return true;
                    }

                    @Override
                    public T get() throws ExecutionException {
                        throw new ExecutionException(new IllegalStateException("boom"));
                    }

                    @Override
                    public T get(long timeout, TimeUnit unit) throws ExecutionException {
                        return get();
                    }
                };
            }
        };

        try {
            InvocationTargetException wrap = assertThrows(InvocationTargetException.class,
                    () -> convertBatch.invoke(reader, List.of(rec), pool));
            assertInstanceOf(IOException.class, wrap.getCause());
            IOException ioe = (IOException) wrap.getCause();
            assertEquals("Failed to convert Avro records in parallel", ioe.getMessage());
            assertInstanceOf(ExecutionException.class, ioe.getCause());
            assertInstanceOf(IllegalStateException.class, ioe.getCause().getCause());
        } finally {
            pool.shutdown();
        }
    }
}
