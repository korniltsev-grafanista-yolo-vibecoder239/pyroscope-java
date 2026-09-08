package io.pyroscope.labels.v2;

import io.pyroscope.labels.pb.JfrLabels;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * Encodes the {@code LabelsSnapshot} protobuf message defined in {@code agent/jfr_labels.proto}
 * without a protobuf runtime.
 *
 * <pre>
 * message Context { map&lt;int64,int64&gt; labels = 1; }
 * message LabelsSnapshot {
 *   map&lt;int64, Context&gt; contexts = 1;
 *   map&lt;int64, string&gt;  strings  = 2;
 * }
 * </pre>
 *
 * <p>The wire format is a frozen contract with Grafana Pyroscope's ingest path. Output is
 * byte-identical to protobuf-java's: minimal varints everywhere, map entries always emitting both
 * key and value (protobuf's map entries ignore proto3 default-value suppression), all of field 1
 * followed by all of field 2.
 *
 * <p>Contexts are written as they are walked, while the string table is being built, and the table
 * is appended at the end. Nested message lengths are computed exactly before writing rather than
 * back-patched, which keeps the encoding minimal and needs only one capacity check per context.
 *
 * <p>The buffer layout and the {@code numberOfLeadingZeros} varint sizing are adapted from
 * async-profiler's {@code one/proto/Proto.java} (Apache-2.0).
 *
 * <p>Not thread safe: one instance encodes one snapshot.
 */
final class LabelsSnapshotEncoder {

    // All tags below are a single byte: a tag is varint(field << 3 | wireType), which fits in one
    // byte only while field < 16. Every field number in jfr_labels.proto is 1 or 2. A field number
    // >= 16 would have to be written as a varint instead.
    private static final byte T_CONTEXTS = (1 << 3) | 2;   // 0x0A LabelsSnapshot.contexts
    private static final byte T_STRINGS = (2 << 3) | 2;    // 0x12 LabelsSnapshot.strings
    private static final byte T_LABELS = (1 << 3) | 2;     // 0x0A Context.labels
    private static final byte T_KEY = (1 << 3);            // 0x08 MapEntry.key   (varint)
    private static final byte T_VAL_VARINT = (2 << 3);     // 0x10 MapEntry.value (varint)
    private static final byte T_VAL_LEN = (2 << 3) | 2;    // 0x12 MapEntry.value (length delimited)

    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;
    private static final int MIN_TABLE_SIZE = 64;

    private byte[] buf;
    private int pos;

    /** strings[id - 1]; dense apart from holes left by seeded constant ids. */
    private String[] strings;
    /** Highest assigned string id. */
    private int count;
    /** Open-addressed string ids, 0 means empty. Power of two. */
    private int[] slots;
    private int mask;
    private int threshold;

    /** Scratch for one context's resolved string ids, alternating key, value. */
    private int[] ids = new int[64];

    LabelsSnapshotEncoder(int bufCapacity, int stringCapacity) {
        this.buf = new byte[Math.max(64, bufCapacity)];
        this.strings = new String[Math.max(16, stringCapacity)];
        setTableSize(tableSizeFor(stringCapacity));
    }

    int size() {
        return pos;
    }

    int stringCount() {
        return count;
    }

    /**
     * Pre-assigns the ids of globally registered constants, which must keep the ids they were
     * handed out under. Ids are taken from the map rather than reassigned, and the next id
     * continues past the highest one seen.
     */
    void seedStringTable(Map<String, Long> constants) {
        if (constants.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> it : constants.entrySet()) {
            Long boxed = it.getValue();
            if (boxed == null) {
                continue;
            }
            long id = boxed;
            if (id < 1 || id > MAX_CAPACITY) {
                continue;
            }
            int i = (int) id;
            growStrings(i);
            strings[i - 1] = it.getKey();
            if (i > count) {
                count = i;
            }
        }
        // Rebuilt in one pass, sized for the seeded ids, instead of inserting one by one.
        setTableSize(tableSizeFor(count));
        reindex();
    }

    /** Interns {@code s}, returning its 1-based id. */
    int stringId(String s) {
        int h = s.hashCode();
        h ^= h >>> 16;
        int i = h & mask;
        for (; ; i = (i + 1) & mask) {
            int id = slots[i];
            if (id == 0) {
                int newId = ++count;
                growStrings(newId);
                strings[newId - 1] = s;
                slots[i] = newId;
                if (count >= threshold) {
                    setTableSize(slots.length << 1);
                    reindex();
                }
                return newId;
            }
            String cur = strings[id - 1];
            // Identity first: keys and values come out of the same LabelsSet array, so the same
            // String object recurs for every repeated key.
            if (cur == s || cur.equals(s)) {
                return id;
            }
        }
    }

    /**
     * Appends one {@code LabelsSnapshot.contexts} entry:
     * {@code ContextsEntry{key = 1: contextId, value = 2: Context{labels: LabelsEntry{1: k, 2: v}}}}.
     *
     * <p>Keys are not deduplicated: a {@link LabelsSet} built with the same key twice emits two
     * entries, and the decoder's map merge keeps the last one, exactly as if the duplicate had
     * been collapsed here. Scanning for duplicates would cost a quadratic pass per context to
     * fix nothing but malformed input.
     *
     * @param args flat array of alternating label keys and values, see {@link LabelsSet#args()}
     */
    void writeContext(long contextId, String[] args) {
        int n = args.length;
        if (n > ids.length) {
            ids = new int[Math.max(n, ids.length * 2)];
        }
        int[] ids = this.ids;

        // Pass 1: intern the strings and compute the exact nested lengths.
        int body = 0;
        for (int i = 0; i < n; i += 2) {
            int k = stringId(args[i]);
            int v = stringId(args[i + 1]);
            ids[i] = k;
            ids[i + 1] = v;
            int entry = 1 + varintSize(k) + 1 + varintSize(v);
            body += 1 + varintSize(entry) + entry;
        }
        int value = 1 + varintSize(contextId) + 1 + varintSize(body) + body;
        int total = 1 + varintSize(value) + value;
        if (body < 0 || value < 0 || total < 0) {
            throw new IllegalStateException("labels of context " + contextId + " are too large");
        }

        // Pass 2: one capacity check, then plain stores.
        ensureCapacity(total);
        byte[] b = buf;
        int p = pos;
        b[p++] = T_CONTEXTS;
        p = putVarint(b, p, value);
        b[p++] = T_KEY;
        p = putVarint(b, p, contextId);
        b[p++] = T_VAL_LEN;
        p = putVarint(b, p, body);
        for (int i = 0; i < n; i += 2) {
            int k = ids[i];
            int v = ids[i + 1];
            b[p++] = T_LABELS;
            p = putVarint(b, p, 1 + varintSize(k) + 1 + varintSize(v));
            b[p++] = T_KEY;
            p = putVarint(b, p, k);
            b[p++] = T_VAL_VARINT;
            p = putVarint(b, p, v);
        }
        assert p == pos + total : "context size mismatch: wrote " + (p - pos) + ", computed " + total;
        pos = p;
    }

    /** Appends the whole string table as {@code LabelsSnapshot.strings} entries, id ascending. */
    void writeStringTable() {
        for (int id = 1; id <= count; id++) {
            String s = strings[id - 1];
            if (s != null) {
                writeStringEntry(id, s);
            }
        }
    }

    JfrLabels.LabelsSnapshot finish() {
        byte[] b = buf;
        int n = pos;
        // The buffer escapes into Snapshot.labels and is read on the exporter thread; drop our
        // reference to it rather than ever reusing it.
        buf = new byte[0];
        pos = 0;
        return new JfrLabels.LabelsSnapshot(b, n);
    }

    private void writeStringEntry(int id, String s) {
        int n = s.length();
        int i = 0;
        while (i < n && s.charAt(i) < 0x80) {
            i++;
        }
        if (i == n) {
            // ASCII, which is the overwhelmingly common case for label keys and values: write
            // straight into the buffer, no intermediate byte[].
            int body = 1 + varintSize(id) + 1 + varintSize(n) + n;
            ensureCapacity(1 + varintSize(body) + body);
            byte[] b = buf;
            int p = pos;
            b[p++] = T_STRINGS;
            p = putVarint(b, p, body);
            b[p++] = T_KEY;
            p = putVarint(b, p, id);
            b[p++] = T_VAL_LEN;
            p = putVarint(b, p, n);
            for (int j = 0; j < n; j++) {
                b[p++] = (byte) s.charAt(j);
            }
            pos = p;
            return;
        }
        // Anything else goes through the JDK encoder. That is deliberate: protobuf-java's own
        // encoder falls back to String.getBytes(UTF_8) for unpaired surrogates (which the JDK
        // turns into '?'), so delegating here is byte-identical for valid and malformed input
        // alike, with no surrogate handling of our own.
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        int len = utf8.length;
        int body = 1 + varintSize(id) + 1 + varintSize(len) + len;
        ensureCapacity(1 + varintSize(body) + body);
        byte[] b = buf;
        int p = pos;
        b[p++] = T_STRINGS;
        p = putVarint(b, p, body);
        b[p++] = T_KEY;
        p = putVarint(b, p, id);
        b[p++] = T_VAL_LEN;
        p = putVarint(b, p, len);
        System.arraycopy(utf8, 0, b, p, len);
        pos = p + len;
    }

    /** Byte count of the varint encoding of a non-negative int. */
    static int varintSize(int v) {
        return (38 - Integer.numberOfLeadingZeros(v | 1)) / 7;
    }

    /** Byte count of the varint encoding of a long; 10 for negative values. */
    static int varintSize(long v) {
        return (70 - Long.numberOfLeadingZeros(v | 1L)) / 7;
    }

    static int putVarint(byte[] b, int p, long v) {
        while ((v & ~0x7FL) != 0) {
            b[p++] = (byte) (v | 0x80);
            v >>>= 7;
        }
        b[p++] = (byte) v;
        return p;
    }

    private void ensureCapacity(int n) {
        if (pos + n > buf.length) {
            grow(n);
        }
    }

    private void grow(int n) {
        long min = (long) pos + n;
        if (min > MAX_CAPACITY) {
            throw new IllegalStateException("labels snapshot too large: " + min + " bytes");
        }
        int cap = buf.length;
        // Double while small, then grow by a quarter so a large buffer does not overshoot by 2x.
        long next = cap <= (8 << 20) ? (long) cap * 2 : cap + (cap >> 2);
        buf = Arrays.copyOf(buf, (int) Math.min(MAX_CAPACITY, Math.max(min, next)));
    }

    private void growStrings(int minLength) {
        if (minLength <= strings.length) {
            return;
        }
        long next = (long) strings.length * 2;
        strings = Arrays.copyOf(strings, (int) Math.min(MAX_CAPACITY, Math.max(minLength, next)));
    }

    private void setTableSize(int size) {
        slots = new int[size];
        mask = size - 1;
        threshold = size - (size >> 2);
    }

    /** Reinserts every assigned id into a freshly sized {@link #slots}. */
    private void reindex() {
        int[] slots = this.slots;
        int mask = this.mask;
        for (int id = 1; id <= count; id++) {
            String s = strings[id - 1];
            if (s == null) {
                continue;
            }
            int h = s.hashCode();
            h ^= h >>> 16;
            int i = h & mask;
            while (slots[i] != 0) {
                i = (i + 1) & mask;
            }
            slots[i] = id;
        }
    }

    private static int tableSizeFor(int entries) {
        long min = Math.max(MIN_TABLE_SIZE, (long) entries + (entries >> 1) + 1);
        int size = MIN_TABLE_SIZE;
        while (size < min && size < (1 << 30)) {
            size <<= 1;
        }
        return size;
    }
}
