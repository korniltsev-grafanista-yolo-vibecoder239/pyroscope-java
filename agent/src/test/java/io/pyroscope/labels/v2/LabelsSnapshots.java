package io.pyroscope.labels.v2;

import com.google.protobuf.InvalidProtocolBufferException;
import io.pyroscope.labels.pbref.JfrLabels;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Decodes encoder output with the real protobuf implementation. */
final class LabelsSnapshots {

    private LabelsSnapshots() {
    }

    static JfrLabels.LabelsSnapshot parse(byte[] encoded) {
        try {
            return JfrLabels.LabelsSnapshot.parseFrom(encoded);
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("not a valid LabelsSnapshot", e);
        }
    }

    /**
     * Parses, and asserts the encoding is canonical.
     *
     * <p>protobuf re-serializes a parsed message back to the same bytes only if the input already
     * looked like its own output: minimal varints, both fields of every map entry present, fields
     * in order, map entries in wire order. That makes this the tripwire for the encoder drifting
     * into something merely parseable — a padded length varint, say, which protobuf's parser
     * accepts silently.
     */
    static JfrLabels.LabelsSnapshot parseCanonical(byte[] encoded) {
        JfrLabels.LabelsSnapshot parsed = parse(encoded);
        assertArrayEquals(encoded, parsed.toByteArray(), "encoding is not canonical protobuf");
        return parsed;
    }

    /** context id to its labels, with string ids resolved through the string table. */
    static Map<Long, Map<String, String>> flatten(JfrLabels.LabelsSnapshot snapshot) {
        Map<Long, String> strings = snapshot.getStringsMap();
        Map<Long, Map<String, String>> out = new LinkedHashMap<>();
        snapshot.getContextsMap().forEach((contextId, context) -> {
            Map<String, String> labels = new LinkedHashMap<>();
            context.getLabelsMap().forEach((k, v) -> labels.put(strings.get(k), strings.get(v)));
            out.put(contextId, labels);
        });
        return out;
    }
}
