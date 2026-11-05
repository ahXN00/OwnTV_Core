package android.util;

import com.google.gson.Strictness;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/**
 * JVM-test stand-in for the platform's {@code JsonReader}, which in android.jar is a stub whose every
 * method returns a default under {@code isReturnDefaultValues} — so parsers built on it could not be
 * tested at all. Test classes come before android.jar on the classpath, so this one wins. It delegates
 * to Gson's streaming reader, which the platform class was derived from and matches call for call.
 */
public final class JsonReader implements Closeable {
    private final com.google.gson.stream.JsonReader delegate;
    private boolean lenient;

    public JsonReader(Reader in) {
        delegate = new com.google.gson.stream.JsonReader(in);
        delegate.setStrictness(Strictness.LEGACY_STRICT);
    }

    public void setLenient(boolean lenient) {
        this.lenient = lenient;
        delegate.setStrictness(lenient ? Strictness.LENIENT : Strictness.LEGACY_STRICT);
    }

    public boolean isLenient() { return lenient; }

    public void beginArray() throws IOException { delegate.beginArray(); }
    public void endArray() throws IOException { delegate.endArray(); }
    public void beginObject() throws IOException { delegate.beginObject(); }
    public void endObject() throws IOException { delegate.endObject(); }
    public boolean hasNext() throws IOException { return delegate.hasNext(); }

    public JsonToken peek() throws IOException {
        return JsonToken.valueOf(delegate.peek().name());
    }

    public String nextName() throws IOException { return delegate.nextName(); }
    public String nextString() throws IOException { return delegate.nextString(); }
    public boolean nextBoolean() throws IOException { return delegate.nextBoolean(); }
    public void nextNull() throws IOException { delegate.nextNull(); }
    public double nextDouble() throws IOException { return delegate.nextDouble(); }
    public long nextLong() throws IOException { return delegate.nextLong(); }
    public int nextInt() throws IOException { return delegate.nextInt(); }
    public void skipValue() throws IOException { delegate.skipValue(); }

    @Override
    public void close() throws IOException { delegate.close(); }
}
