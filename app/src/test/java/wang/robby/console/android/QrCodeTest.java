package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** The device-login QR code must match a reference ISO 18004 encoder bit for bit (checked against python-qrcode). */
public final class QrCodeTest {
    /** python-qrcode 8.x, version 2, level M, mask 2, byte mode, no border: "https://god.robby.wang". */
    private static final String[] REFERENCE = {
            "1111111000110100001111111",
            "1000001000110111101000001",
            "1011101010010001001011101",
            "1011101010011110101011101",
            "1011101010101010101011101",
            "1000001010010111001000001",
            "1111111010101010101111111",
            "0000000010011010000000000",
            "1011111000110111001111100",
            "0110010110001000100100010",
            "0110111111000001010111011",
            "1110010100010010010100001",
            "0101101111000111011010111",
            "1111110110001100100101010",
            "1000111100100111111111011",
            "1000010100101000101110001",
            "1011111010001110111110100",
            "0000000011100001100011000",
            "1111111001111110101010111",
            "1000001010110010100011000",
            "1011101011010001111110111",
            "1011101011101101101011111",
            "1011101011100111000001101",
            "1000001001101100110111001",
            "1111111010001010000111111"
    };
    @Test public void matchesReferenceEncoderBitForBit() {
        QrCode q = QrCode.encode("https://god.robby.wang", 2);
        assertEquals(2, q.version); assertEquals(25, q.size); assertEquals(2, q.mask);
        for (int y = 0; y < q.size; y++) {
            StringBuilder row = new StringBuilder(); for (int x = 0; x < q.size; x++) row.append(q.dark(x, y) ? '1' : '0');
            assertEquals("row " + y, REFERENCE[y], row.toString());
        }
    }
    @Test public void verificationAddressFitsVersionFiveWithAChosenMask() {
        QrCode q = QrCode.encode("https://auth.god.robby.wang/consent/openid/device-authorization");
        assertEquals(5, q.version); assertEquals(37, q.size); assertTrue(q.mask >= 0 && q.mask <= 7);
        // Finder corners and the always-dark module stay in place whatever the mask.
        assertTrue(q.dark(0, 0) && q.dark(6, 6) && q.dark(36, 0) && q.dark(0, 36) && q.dark(8, 29));
        assertFalse(q.dark(7, 7));
    }
    @Test public void capacityAndErrorCorrectionTablesHold() {
        assertEquals(16, QrCode.dataCodewords(1)); assertEquals(86, QrCode.dataCodewords(5)); assertEquals(216, QrCode.dataCodewords(10));
        assertArrayEquals(new int[]{6, 22, 38}, QrCode.alignmentPositions(7));
        assertEquals(0, QrCode.gfMultiply(0, 0x53)); assertEquals(0x53, QrCode.gfMultiply(1, 0x53)); assertEquals(0x1D, QrCode.gfMultiply(0x80, 2));
        StringBuilder long300 = new StringBuilder(); for (int i = 0; i < 300; i++) long300.append('x');
        try { QrCode.encode(long300.toString()); fail("longer than version 10 must be refused"); } catch (IllegalArgumentException expected) {}
    }
}
