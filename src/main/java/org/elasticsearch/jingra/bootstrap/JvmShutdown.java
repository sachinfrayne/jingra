package org.elasticsearch.jingra.bootstrap;

/**
 * Single JVM exit hook for {@link org.elasticsearch.jingra.Main}'s {@code
 * nonZeroExitAction}. Excluded from JaCoCo in {@code pom.xml} because {@link System#exit(int)}
 * is not meaningfully coverable in unit tests without halting the JVM.
 */
public final class JvmShutdown {

    private JvmShutdown() {}

    public static void exit(int status) {
        System.exit(status);
    }
}
