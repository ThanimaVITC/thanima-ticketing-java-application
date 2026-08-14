package com.legitcoconut.thanimaticketing.card;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The only part of the Bluetooth reader that can be checked without the hardware, and the
 * part most likely to be wrong: what counts as a card ID and what is just chatter.
 */
public class UidLineTest {

    @Test
    public void uppercasesAndKeepsHex() {
        assertEquals("04A1B2C3", UidLine.parse("04a1b2c3", false));
    }

    @Test
    public void dropsTheSeparatorsReadersLikeToAdd() {
        assertEquals("04A1B2C3", UidLine.parse(" 04:A1-B2 C3\r", false));
    }

    @Test
    public void reversesWholeBytesNotCharacters() {
        assertEquals("C3B2A104", UidLine.parse("04A1B2C3", true));
    }

    @Test
    public void acceptsSevenAndTenByteIds() {
        assertEquals("04A1B2C3D4E5F6", UidLine.parse("04A1B2C3D4E5F6", false));
        assertEquals("04A1B2C3D4E5F60718", UidLine.parse("04A1B2C3D4E5F60718", false));
    }

    @Test
    public void rejectsChatter() {
        assertNull(UidLine.parse("READY", false));
        assertNull(UidLine.parse("RFID reader online", false));
        assertNull(UidLine.parse("", false));
        assertNull(UidLine.parse(null, false));
    }

    @Test
    public void rejectsWrongLengths() {
        assertNull(UidLine.parse("04A1B2", false));
        assertNull(UidLine.parse("04A1B2C3D", false));
        assertNull(UidLine.parse("04A1B2C3D4E5F607182930", false));
    }

    @Test
    public void repeatWindowSwallowsTheSecondReadOfOneCard() {
        UidLine.Repeat repeat = new UidLine.Repeat();
        assertEquals(false, repeat.isRepeat("04A1B2C3"));
        assertEquals(true, repeat.isRepeat("04A1B2C3"));
        assertEquals(false, repeat.isRepeat("11223344"));
    }
}
