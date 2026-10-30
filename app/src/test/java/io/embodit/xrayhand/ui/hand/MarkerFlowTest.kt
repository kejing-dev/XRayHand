package io.embodit.xrayhand.ui.hand

import io.embodit.xrayhand.domain.BoneCatalog
import io.embodit.xrayhand.domain.PoseName
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile D1 "move to the marker" in the state machine: the demo hand's anchor only moves on "move to the marker"
 * (the scene acts on `demoPlaceTick`); entering demo mode, pose changes, the quiz and follow-me never bump it.
 */
class MarkerFlowTest {
    private fun vm(): XrViewModel {
        val f = listOf(File("src/main/assets/bones.json"), File("app/src/main/assets/bones.json")).first { it.exists() }
        return XrViewModel(BoneCatalog.parse(f.readText()), null, null, onEmulator = true)
    }

    @Test
    fun onlyMoveToMarkerMovesTheAnchor() {
        val v = vm()
        v.onEvent(XrEvent.SceneReady)
        v.onEvent(XrEvent.UseDemo)
        assertEquals(0, v.state.value.demoPlaceTick)
        // without a marker, "move to the marker" starts placing instead
        v.onEvent(XrEvent.BringToMarker)
        assertTrue(v.state.value.placing)
        assertEquals(0, v.state.value.demoPlaceTick)
        v.onEvent(XrEvent.MarkerPlaced)
        assertFalse(v.state.value.placing)
        assertTrue(v.state.value.hasMarker)
        v.onEvent(XrEvent.BringToMarker)
        assertEquals(1, v.state.value.demoPlaceTick)
        // five "next"-like steps: poses, selections, quiz switch, demo mode again: the anchor tick does not move
        for (p in listOf(PoseName.FIST, PoseName.PINCH, PoseName.FLIP, PoseName.OPEN, PoseName.FIST)) v.onEvent(XrEvent.DemoPose(p))
        v.onEvent(XrEvent.Tapped(HandId.DEMO, "lunate", "GazePinch", 0f))
        v.onEvent(XrEvent.ModeChanged(Mode.QUIZ))
        v.onEvent(XrEvent.ModeChanged(Mode.EXPLORE))
        v.onEvent(XrEvent.UseDemo)
        assertEquals(1, v.state.value.demoPlaceTick)
    }

    @Test
    fun placeMarkerToggles() {
        val v = vm()
        v.onEvent(XrEvent.SceneReady)
        v.onEvent(XrEvent.PlaceMarker)
        assertTrue(v.state.value.placing)
        v.onEvent(XrEvent.PlaceMarker)
        assertFalse(v.state.value.placing)
    }
}
