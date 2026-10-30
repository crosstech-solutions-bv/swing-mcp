package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** A robot chord never leaves keys held down, even when a press fails. */
class RobotChordTest {

    /** Records instead of sending OS input; rejects one key code like an unmappable key. */
    private static final class RecordingRobot extends Robot {
        final List<Integer> pressed = new ArrayList<>();
        final List<Integer> released = new ArrayList<>();
        private final int failOn;

        RecordingRobot(int failOn) throws AWTException {
            this.failOn = failOn;
        }

        @Override
        public synchronized void keyPress(int keycode) {
            if (keycode == failOn) {
                throw new IllegalArgumentException("Invalid key code");
            }
            pressed.add(keycode);
        }

        @Override
        public synchronized void keyRelease(int keycode) {
            released.add(keycode);
        }
    }

    @Test
    void pressedKeysAreReleasedInReverseWhenAPressFails() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "java.awt.Robot requires a display");
        RecordingRobot robot = new RecordingRobot(KeyEvent.VK_A);

        assertThrows(IllegalArgumentException.class, () -> ComponentScanner.robotChord(robot,
            List.of(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_A)));

        assertEquals(List.of(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT), robot.pressed);
        assertEquals(List.of(KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL), robot.released);
    }

    @Test
    void chordIsPressedInOrderAndReleasedInReverse() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "java.awt.Robot requires a display");
        RecordingRobot robot = new RecordingRobot(-1);

        ComponentScanner.robotChord(robot, List.of(KeyEvent.VK_CONTROL, KeyEvent.VK_S));

        assertEquals(List.of(KeyEvent.VK_CONTROL, KeyEvent.VK_S), robot.pressed);
        assertEquals(List.of(KeyEvent.VK_S, KeyEvent.VK_CONTROL), robot.released);
    }
}
