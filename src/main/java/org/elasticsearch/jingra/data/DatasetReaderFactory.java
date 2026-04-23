package org.elasticsearch.jingra.data;

/**
 * Creates the appropriate {@link DatasetReader} based on the file extension.
 *
 * <ul>
 *   <li>{@code .ndjson} → {@link NdjsonReader}</li>
 *   <li>anything else → {@link ParquetReader}</li>
 * </ul>
 */
public class DatasetReaderFactory {

    private DatasetReaderFactory() {}

    public static DatasetReader create(String path) {
        if (path != null && path.endsWith(".ndjson")) {
            return new NdjsonReader(path);
        }
        return new ParquetReader(path);
    }
}
