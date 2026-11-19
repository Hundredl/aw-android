package net.activitywatch.android.ledger
import org.junit.Assert.*
import org.junit.Test
class SyncCadenceTest {
    @Test fun defaultsAndBoundary(){
        assertEquals(60,SyncCadence.normalized(0))
        assertFalse(SyncCadence.due(3_599_999,0,60))
        assertTrue(SyncCadence.due(3_600_000,0,60))
    }
    @Test fun customIntervalsAndClockRollback(){
        assertTrue(SyncCadence.due(900_000,0,15))
        assertFalse(SyncCadence.due(3_600_000,0,180))
        assertFalse(SyncCadence.due(0,100,60))
    }
}
