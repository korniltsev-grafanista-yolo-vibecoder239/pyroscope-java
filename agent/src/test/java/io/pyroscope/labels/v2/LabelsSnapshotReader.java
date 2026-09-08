package io.pyroscope.labels.v2;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strict hand-written decoder for the {@code LabelsSnapshot} wire format defined in
 * {@code agent/jfr_labels.proto}. Test-only: it exists to verify what the encoder emits.
 *
 * <p>Strict means it rejects anything the encoder is not supposed to produce: unknown field
 * numbers, unexpected wire types, truncated or trailing bytes, duplicate map keys, and
 * <em>non-minimally encoded varints</em>. The last one matters: a padded length varint is
 * accepted by real protobuf parsers, so only an explicit check keeps the encoder from silently
 * drifting away from byte-compatibility with protobuf-java.
 */
final class LabelsSnapshotReader {

    static final class Parsed {
        /** context id -&gt; (string id -&gt; string id) */
        final Map<Long, Map<Long, Long>> contexts = new LinkedHashMap<>();
        /** string id -&gt; string */
        final Map<Long, String> strings = new LinkedHashMap<>();
    }

    private final byte[] buf;
    private int pos;
    private int end;

    private LabelsSnapshotReader(byte[] buf, int off, int len) {
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
        if (off < 0 || len < 0 || this.end > buf.length) {
            throw new IllegalArgumentException("bad bounds");
        }
    }

    static Parsed parse(byte[] buf) {
        return parse(buf, 0, buf.length);
    }

    static Parsed parse(byte[] buf, int off, int len) {
        LabelsSnapshotReader r = new LabelsSnapshotReader(buf, off, len);
        Parsed out = new Parsed();
        while (r.pos < r.end) {
            int tag = r.tag();
            switch (tag) {
                case (1 << 3) | 2: // LabelsSnapshot.contexts
                    r.contextsEntry(out);
                    break;
                case (2 << 3) | 2: // LabelsSnapshot.strings
                    r.stringsEntry(out);
                    break;
                default:
                    throw r.fail("unexpected tag " + tag + " in LabelsSnapshot");
            }
        }
        return out;
    }

    /** map&lt;int64, Context&gt; entry: key = 1 (varint), value = 2 (LEN) */
    private void contextsEntry(Parsed out) {
        int limit = enterMessage();
        long id = 0;
        Map<Long, Long> labels = null;
        while (pos < end) {
            int tag = tag();
            if (tag == ((1 << 3) | 0)) {
                id = varint();
            } else if (tag == ((2 << 3) | 2)) {
                labels = context();
            } else {
                throw fail("unexpected tag " + tag + " in ContextsEntry");
            }
        }
        leaveMessage(limit);
        if (labels == null) {
            throw fail("ContextsEntry without a value (map entries must always emit both fields)");
        }
        if (out.contexts.put(id, labels) != null) {
            throw fail("duplicate context id " + id);
        }
    }

    /** Context: repeated map&lt;int64, int64&gt; labels = 1 */
    private Map<Long, Long> context() {
        int limit = enterMessage();
        Map<Long, Long> labels = new LinkedHashMap<>();
        while (pos < end) {
            int tag = tag();
            if (tag != ((1 << 3) | 2)) {
                throw fail("unexpected tag " + tag + " in Context");
            }
            int entryLimit = enterMessage();
            long key = 0;
            long value = 0;
            boolean sawValue = false;
            while (pos < end) {
                int t = tag();
                if (t == ((1 << 3) | 0)) {
                    key = varint();
                } else if (t == ((2 << 3) | 0)) {
                    value = varint();
                    sawValue = true;
                } else {
                    throw fail("unexpected tag " + t + " in LabelsEntry");
                }
            }
            leaveMessage(entryLimit);
            if (!sawValue) {
                throw fail("LabelsEntry without a value (map entries must always emit both fields)");
            }
            // Repeated keys are legal on the wire; proto map merge semantics keep the last one.
            labels.put(key, value);
        }
        leaveMessage(limit);
        return labels;
    }

    /** map&lt;int64, string&gt; entry: key = 1 (varint), value = 2 (LEN) */
    private void stringsEntry(Parsed out) {
        int limit = enterMessage();
        long id = 0;
        String value = null;
        while (pos < end) {
            int tag = tag();
            if (tag == ((1 << 3) | 0)) {
                id = varint();
            } else if (tag == ((2 << 3) | 2)) {
                int len = len();
                value = new String(buf, pos, len, StandardCharsets.UTF_8);
                pos += len;
            } else {
                throw fail("unexpected tag " + tag + " in StringsEntry");
            }
        }
        leaveMessage(limit);
        if (value == null) {
            throw fail("StringsEntry without a value (map entries must always emit both fields)");
        }
        if (out.strings.put(id, value) != null) {
            throw fail("duplicate string id " + id);
        }
    }

    /** Reads a length prefix and narrows {@link #end} to it. Returns the outer end to restore. */
    private int enterMessage() {
        int len = len();
        int outer = end;
        end = pos + len;
        return outer;
    }

    private void leaveMessage(int outer) {
        if (pos != end) {
            throw fail("nested message not fully consumed");
        }
        end = outer;
    }

    private int len() {
        long len = varint();
        if (len < 0 || pos + len > end) {
            throw fail("length " + len + " overruns the enclosing message");
        }
        return (int) len;
    }

    private int tag() {
        long tag = varint();
        if (tag <= 0 || tag > 0x7f) {
            // Every field number in jfr_labels.proto is 1 or 2, so every tag is a single byte.
            throw fail("tag " + tag + " is out of range for this schema");
        }
        return (int) tag;
    }

    private long varint() {
        long value = 0;
        int shift = 0;
        int start = pos;
        for (; ; shift += 7) {
            if (pos >= end) {
                throw fail("truncated varint");
            }
            if (shift > 63) {
                throw fail("varint longer than 10 bytes");
            }
            int b = buf[pos++];
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                if (pos - start > 1 && (b & 0x7f) == 0) {
                    throw fail("non-minimal varint (padded length or key) at offset " + start);
                }
                return value;
            }
        }
    }

    private IllegalStateException fail(String message) {
        return new IllegalStateException(message + " (at offset " + pos + ")");
    }
}
