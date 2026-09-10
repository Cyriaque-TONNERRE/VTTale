package org.vttale.vttale.api.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenPosition normalises its inputs in the constructor, which means two positions built from
 * different arguments can be equal. These tests pin that contract down.
 */
class TokenPositionTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("yaw is wrapped into [0, 360)")
    void yawIsNormalised() {
        assertEquals(10f, new TokenPosition(0, 0, 0, 370f, 0f).getYaw(), EPS);
        assertEquals(270f, new TokenPosition(0, 0, 0, -90f, 0f).getYaw(), EPS);
        assertEquals(0f, new TokenPosition(0, 0, 0, 360f, 0f).getYaw(), EPS);
        assertEquals(0f, new TokenPosition(0, 0, 0, 720f, 0f).getYaw(), EPS);
    }

    @Test
    @DisplayName("pitch is clamped to [-90, 90]")
    void pitchIsClamped() {
        assertEquals(90f, new TokenPosition(0, 0, 0, 0f, 120f).getPitch(), EPS);
        assertEquals(-90f, new TokenPosition(0, 0, 0, 0f, -120f).getPitch(), EPS);
        assertEquals(45f, new TokenPosition(0, 0, 0, 0f, 45f).getPitch(), EPS);
    }

    @Test
    @DisplayName("distanceTo is 3D, horizontalDistanceTo ignores Y")
    void distances() {
        TokenPosition a = new TokenPosition(0, 0, 0);
        assertEquals(5.0, a.distanceTo(new TokenPosition(3, 4, 0)), EPS);
        assertEquals(5.0, a.distanceTo(new TokenPosition(0, 3, 4)), EPS);

        TokenPosition high = new TokenPosition(0, 100, 0);
        assertEquals(5.0, high.horizontalDistanceTo(new TokenPosition(3, -50, 4)), EPS);
        assertEquals(0.0, a.distanceTo(a), EPS);
    }

    @Test
    @DisplayName("mutators return a new instance and never touch the receiver")
    void mutatorsAreCopyOnWrite() {
        TokenPosition origin = new TokenPosition(1, 2, 3, 90f, 45f);

        TokenPosition moved = origin.add(1, 1, 1);
        assertNotSame(origin, moved);
        assertEquals(2.0, moved.getX(), EPS);
        assertEquals(90f, moved.getYaw(), EPS, "add() must preserve rotation");
        assertEquals(45f, moved.getPitch(), EPS);
        assertEquals(1.0, origin.getX(), EPS, "add() must not mutate the receiver");

        TokenPosition recoloured = origin.withCoordinates(9, 9, 9);
        assertEquals(90f, recoloured.getYaw(), EPS, "withCoordinates() must preserve rotation");

        TokenPosition turned = origin.withRotation(180f, -10f);
        assertEquals(1.0, turned.getX(), EPS, "withRotation() must preserve coordinates");
        assertEquals(180f, turned.getYaw(), EPS);
    }

    @Test
    @DisplayName("toBlockCoordinates floors, including on negative axes")
    void blockCoordinatesFloor() {
        int[] b = new TokenPosition(-0.5, 1.9, -10.1).toBlockCoordinates();
        assertEquals(-1, b[0]);
        assertEquals(1, b[1]);
        assertEquals(-11, b[2]);
    }

    @Test
    @DisplayName("fromBlock centres X and Z but keeps Y at the block floor")
    void fromBlockCentresHorizontallyOnly() {
        TokenPosition p = TokenPosition.fromBlock(1, 2, 3);
        assertEquals(1.5, p.getX(), EPS);
        assertEquals(2.0, p.getY(), EPS, "Y stays on the block surface, it is not centred");
        assertEquals(3.5, p.getZ(), EPS);
    }

    @Test
    @DisplayName("equality compares normalised values, so 370 deg equals 10 deg")
    void equalityUsesNormalisedValues() {
        TokenPosition a = new TokenPosition(0, 0, 0, 370f, 200f);
        TokenPosition b = new TokenPosition(0, 0, 0, 10f, 90f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(new TokenPosition(1, 0, 0, 10f, 90f), a);
        assertEquals(new TokenPosition(0, 0, 0), TokenPosition.origin());
    }
}
