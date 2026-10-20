package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SignatureTest {
    @Test public void keepsOneShortLine() {
        assertEquals("", Signature.clean(null));
        assertEquals("", Signature.clean("  \n\t "));
        assertEquals("保持好奇 每天进步", Signature.clean("  保持好奇\n\n每天进步  "));
        assertEquals("a b", Signature.clean("a\u0000 b"));
    }
    @Test public void cutsAtThirtyCharactersWithoutSplittingEmoji() {
        String long40 = "一二三四五六七八九十".repeat(4);
        assertEquals(30, Signature.clean(long40).length());
        String emoji = "😀".repeat(35);
        String cut = Signature.clean(emoji);
        assertEquals(30, cut.codePointCount(0, cut.length()));
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }
}
