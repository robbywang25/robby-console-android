package wang.robby.console.android;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR Code encoder (ISO/IEC 18004) for one short HTTPS address: byte mode, error correction level M,
 * versions 1-10, mask chosen by the standard penalty rules. Pure Java so the device-login screen needs no library
 * and the JVM tests can compare it bit for bit with a reference encoder.
 */
final class QrCode {
    final int version, size, mask;
    private final boolean[][] modules, function;

    // Level M, index = version (0 unused): error correction codewords per block and number of blocks.
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_M = 0;

    /** Encodes {@code text} as UTF-8 in the smallest version that fits, or throws when it is longer than version 10 holds. */
    static QrCode encode(String text) { return encode(text, -1); }
    /** As {@link #encode(String)} with a fixed mask 0-7, or -1 to choose by penalty. */
    static QrCode encode(String text, int fixedMask) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= 10; v++) {
            int capacityBits = dataCodewords(v) * 8, countBits = v <= 9 ? 8 : 16;
            if (4 + countBits + data.length * 8 <= capacityBits) return new QrCode(v, data, fixedMask);
        }
        throw new IllegalArgumentException("text too long");
    }

    private QrCode(int version, byte[] data, int fixedMask) {
        this.version = version; size = version * 4 + 17;
        modules = new boolean[size][size]; function = new boolean[size][size];
        drawFunctionPatterns();
        byte[] all = withErrorCorrection(dataCodewords(version, data));
        drawCodewords(all);
        int chosen = fixedMask;
        if (chosen < 0) {
            int best = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                applyMask(m); drawFormatBits(m);
                int p = penalty(); if (p < best) { best = p; chosen = m; }
                applyMask(m);
            }
        }
        mask = chosen; applyMask(mask); drawFormatBits(mask);
    }

    /** Dark module at column x, row y. */
    boolean dark(int x, int y) { return modules[y][x]; }

    // ---- Data and error correction ----
    static int rawModules(int v) {
        int result = (16 * v + 128) * v + 64;
        if (v >= 2) { int n = v / 7 + 2; result -= (25 * n - 10) * n - 55; if (v >= 7) result -= 36; }
        return result;
    }
    static int dataCodewords(int v) { return rawModules(v) / 8 - ECC_PER_BLOCK[v] * BLOCKS[v]; }

    private static byte[] dataCodewords(int v, byte[] data) {
        int capacity = dataCodewords(v) * 8; BitBuffer bb = new BitBuffer();
        bb.append(0x4, 4); bb.append(data.length, v <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xff, 8);
        bb.append(0, Math.min(4, capacity - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacity; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] out = new byte[capacity / 8];
        for (int i = 0; i < bb.length; i++) if (bb.bit(i)) out[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
        return out;
    }

    private byte[] withErrorCorrection(byte[] data) {
        int blocks = BLOCKS[version], eccLen = ECC_PER_BLOCK[version], raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = rsDivisor(eccLen); byte[][] out = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int datLen = shortLen - eccLen + (i < shortBlocks ? 0 : 1);
            byte[] dat = new byte[datLen]; System.arraycopy(data, k, dat, 0, datLen); k += datLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            // Short blocks keep a placeholder at datLen so every block has the same length when interleaving.
            System.arraycopy(dat, 0, block, 0, datLen);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            out[i] = block;
        }
        byte[] result = new byte[raw]; int n = 0;
        for (int i = 0; i < out[0].length; i++)
            for (int j = 0; j < blocks; j++)
                if (i != shortLen - eccLen || j >= shortBlocks) result[n++] = out[j][i];
        return result;
    }

    static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree]; result[degree - 1] = 1; int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMultiply(result[j] & 0xff, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMultiply(root, 0x02);
        }
        return result;
    }
    static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xff;
            System.arraycopy(result, 1, result, 0, result.length - 1); result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMultiply(divisor[i] & 0xff, factor);
        }
        return result;
    }
    static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11D); z ^= ((y >>> i) & 1) * x; }
        return z & 0xff;
    }

    // ---- Function patterns ----
    private void set(int x, int y, boolean dark) { modules[y][x] = dark; function[y][x] = true; }
    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        int[] align = alignmentPositions(version); int n = align.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) alignment(align[i], align[j]);
        drawFormatBits(0);
        if (version >= 7) {
            int rem = version; for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) { boolean b = ((bits >>> i) & 1) != 0; int a = size - 11 + i % 3, c = i / 3; set(a, c, b); set(c, a, b); }
        }
    }
    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
    }
    private void alignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }
    static int[] alignmentPositions(int v) {
        if (v == 1) return new int[0];
        int n = v / 7 + 2, step = (v * 8 + n * 3 + 5) / (n * 4 - 4) * 2, size = v * 4 + 17;
        int[] result = new int[n]; result[0] = 6;
        for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }
    private void drawFormatBits(int m) {
        int data = FORMAT_M << 3 | m, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }
    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j; boolean upward = ((right + 1) & 2) == 0; int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) { modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0; i++; }
                }
        }
    }
    private void applyMask(int m) {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (m) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!function[y][x] && invert) modules[y][x] = !modules[y][x];
            }
    }

    /** ISO 18004 penalty: runs of five or more, 2x2 blocks, finder-like 1:1:3:1:1 patterns with light space, dark balance. */
    private int penalty() {
        int result = 0, dark = 0;
        for (int a = 0; a < size; a++)
            for (int horizontal = 0; horizontal < 2; horizontal++) {
                int run = 0; boolean color = false; StringBuilder line = new StringBuilder("0000");
                for (int b = 0; b < size; b++) {
                    boolean c = horizontal == 1 ? modules[a][b] : modules[b][a]; line.append(c ? '1' : '0');
                    if (b > 0 && c == color) { run++; if (run == 5) result += 3; else if (run > 5) result++; }
                    else { color = c; run = 1; }
                }
                String full = line.append("0000").toString();
                for (int k = full.indexOf("1011101"); k >= 0; k = full.indexOf("1011101", k + 1))
                    if (full.startsWith("0000", k - 4) || full.startsWith("0000", k + 7)) result += 40;
            }
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x + 1 < size && y + 1 < size) { boolean c = modules[y][x]; if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3; }
            }
        int total = size * size, k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + Math.max(0, k) * 10;
    }

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet(); int length;
        void append(int value, int count) { for (int i = count - 1; i >= 0; i--) { if (((value >>> i) & 1) != 0) bits.set(length); length++; } }
        boolean bit(int i) { return bits.get(i); }
    }
}
