package io.pyroscope.labels.v2;

import java.util.Arrays;

/**
 * Append-only growable byte buffer with the protobuf varint primitives.
 *
 * <p>The write contract is deliberately not one call per byte. A writer computes the exact size of
 * a record, {@link #reserve(int) reserves} it in one go, stores the bytes straight into
 * {@link #array()}, then {@link #commit(int) commits} the new position — so a record costs one
 * bounds check and one field write rather than one of each per byte.
 *
 * <p>The buffer is handed off to the caller by {@link #take()} rather than copied, because the
 * encoded snapshot escapes to the exporter thread and is written to the network from there.
 *
 * <p>The layout and the {@code numberOfLeadingZeros} varint sizing are adapted from
 * async-profiler's {@code one/proto/Proto.java} (Apache-2.0).
 *
 * <p>Not thread safe.
 */
final class ProtoBuffer {

    private static final byte[] EMPTY = new byte[0];
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;
    /** Doubling above this would overshoot by too much, so growth slows to a quarter. */
    private static final int DOUBLE_UNTIL = 8 << 20;

    private byte[] buf;
    private int pos;

    ProtoBuffer(int capacity) {
        this.buf = new byte[Math.max(64, capacity)];
    }

    /** Bytes written so far. */
    int size() {
        return pos;
    }

    /**
     * Makes room for {@code n} more bytes and returns the position to write them at.
     *
     * <p>Call {@link #array()} <em>after</em> this, never before: growing replaces the array.
     */
    int reserve(int n) {
        if (pos + n > buf.length) {
            grow(n);
        }
        return pos;
    }

    /** The backing array. Only valid until the next {@link #reserve(int)}. */
    byte[] array() {
        return buf;
    }

    /** Publishes the bytes written up to {@code end}, which must not move backwards. */
    void commit(int end) {
        assert end >= pos && end <= buf.length : "commit(" + end + ") out of range " + pos + ".." + buf.length;
        pos = end;
    }

    /** Hands the buffer over and drops our reference to it, so it is never written to again. */
    byte[] take() {
        byte[] taken = buf;
        buf = EMPTY;
        pos = 0;
        return taken;
    }

    /** Byte count of the varint encoding of a non-negative int. */
    static int varintSize(int v) {
        return (38 - Integer.numberOfLeadingZeros(v | 1)) / 7;
    }

    /** Byte count of the varint encoding of a long; 10 for negative values. */
    static int varintSize(long v) {
        return (70 - Long.numberOfLeadingZeros(v | 1L)) / 7;
    }

    /** Writes {@code v} at {@code p} and returns the position after it. */
    static int putVarint(byte[] b, int p, long v) {
        while ((v & ~0x7FL) != 0) {
            b[p++] = (byte) (v | 0x80);
            v >>>= 7;
        }
        b[p++] = (byte) v;
        return p;
    }

    private void grow(int n) {
        long min = (long) pos + n;
        if (min > MAX_CAPACITY) {
            throw new IllegalStateException("labels snapshot too large: " + min + " bytes");
        }
        int cap = buf.length;
        long next = cap <= DOUBLE_UNTIL ? (long) cap * 2 : cap + (cap >> 2);
        buf = Arrays.copyOf(buf, (int) Math.min(MAX_CAPACITY, Math.max(min, next)));
    }
}
