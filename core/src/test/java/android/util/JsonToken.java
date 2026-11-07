package android.util;

/**
 * JVM-test stand-in for the platform's {@code JsonToken}: same constants, same order. Exists only
 * because android.jar's copy is a stub; see {@link JsonReader}.
 */
public enum JsonToken {
    BEGIN_ARRAY,
    END_ARRAY,
    BEGIN_OBJECT,
    END_OBJECT,
    NAME,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    END_DOCUMENT
}
