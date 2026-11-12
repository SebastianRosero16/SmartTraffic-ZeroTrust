package edu.smarttraffic.processor.domain.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.smarttraffic.contracts.TrafficStatus;
import org.junit.jupiter.api.Test;

class ZoneTrafficStateMachineTest {
    private final TrafficThresholds thresholds = new TrafficThresholds(5.0, 10.0);

    @Test
    void startsClearAndTransitionsAcrossAllStates() {
        var stateMachine = new ZoneTrafficStateMachine(thresholds);

        assertEquals(TrafficStatus.DESPEJADA, stateMachine.currentStatus());
        assertEquals(TrafficStatus.MODERADA, stateMachine.updateDensity(5.0));
        assertEquals(TrafficStatus.CONGESTIONADA, stateMachine.updateDensity(10.0));
        assertEquals(TrafficStatus.MODERADA, stateMachine.updateDensity(9.9));
        assertEquals(TrafficStatus.DESPEJADA, stateMachine.updateDensity(4.9));
    }

    @Test
    void allowsDirectTransitionFromClearToCongested() {
        var stateMachine = new ZoneTrafficStateMachine(thresholds);

        assertEquals(TrafficStatus.CONGESTIONADA, stateMachine.updateDensity(12.0));
    }

    @Test
    void rejectsInvalidThresholdsAndDensity() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficThresholds(5.0, 5.0));
        assertThrows(IllegalArgumentException.class, () -> new TrafficThresholds(-1.0, 5.0));

        var stateMachine = new ZoneTrafficStateMachine(thresholds);
        assertThrows(IllegalArgumentException.class, () -> stateMachine.updateDensity(-0.1));
        assertThrows(IllegalArgumentException.class, () -> stateMachine.updateDensity(Double.NaN));
    }
}