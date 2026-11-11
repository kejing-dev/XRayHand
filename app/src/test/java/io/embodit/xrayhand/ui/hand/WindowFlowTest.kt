package io.embodit.xrayhand.ui.hand

import io.embodit.xrayhand.domain.BoneCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.4 system window: the TabBar pages are events of the state machine; a quiz under way survives tab switches and
 * a [Start] asks first (D44); Exit asks in the page and only the confirm calls the Stage's exit (D26); "重新打开面板"
 * goes to the Stage's window control.
 */
class WindowFlowTest {
    private fun vm(): XrViewModel {
        val f = listOf(File("src/main/assets/bones.json"), File("app/src/main/assets/bones.json")).first { it.exists() }
        return XrViewModel(BoneCatalog.parse(f.readText()), null, null, onEmulator = true).also {
            it.onEvent(XrEvent.SceneReady)
            it.onEvent(XrEvent.UseDemo)
        }
    }

    @Test
    fun tabsSwitchPagesAndKeepTheQuiz() {
        val v = vm()
        v.onEvent(XrEvent.TabPicked(Tab.QUIZ))
        assertEquals(Tab.QUIZ, v.state.value.tab)
        assertEquals("S10", v.state.value.designState)
        v.onEvent(XrEvent.QuizStart)
        val questions = v.state.value.quiz.questions
        assertTrue(v.state.value.quizUnderWay)
        v.onEvent(XrEvent.TabPicked(Tab.SETTINGS))
        assertEquals("S16", v.state.value.designState)
        v.onEvent(XrEvent.TabPicked(Tab.EXPLORE))
        assertEquals(Tab.EXPLORE, v.state.value.tab)
        v.onEvent(XrEvent.TabPicked(Tab.QUIZ))
        assertEquals("the run is kept", questions, v.state.value.quiz.questions)
        assertTrue(v.state.value.quizUnderWay)
    }

    @Test
    fun startMidQuizAsksFirst() {
        val v = vm()
        v.onEvent(XrEvent.QuizStart)
        val first = v.state.value.quiz
        v.onEvent(XrEvent.QuizStart)
        assertTrue(v.state.value.confirmRestart)
        assertEquals("nothing lost yet", first, v.state.value.quiz)
        v.onEvent(XrEvent.QuizRestartCancel)
        assertFalse(v.state.value.confirmRestart)
        assertEquals(first, v.state.value.quiz)
        v.onEvent(XrEvent.QuizStart)
        v.nextSeed = first.seed + 1
        v.onEvent(XrEvent.QuizRestartConfirm)
        assertFalse(v.state.value.confirmRestart)
        assertEquals(0, v.state.value.quiz.index)
        assertTrue(v.state.value.quiz.seed != first.seed)
    }

    @Test
    fun exitOnlyOnConfirmAndRecallGoesToTheWindow() {
        val v = vm()
        var exits = 0
        var recalls = 0
        v.onExit = { exits++ }
        v.onRecall = { recalls++ }
        v.onEvent(XrEvent.TabPicked(Tab.SETTINGS))
        v.onEvent(XrEvent.ExitAsk)
        assertTrue(v.state.value.confirmExit)
        v.onEvent(XrEvent.ExitCancel)
        assertEquals(0, exits)
        v.onEvent(XrEvent.ExitAsk)
        v.onEvent(XrEvent.ExitConfirm)
        assertEquals(1, exits)
        v.onEvent(XrEvent.RecallPanel)
        assertEquals(1, recalls)
        // leaving the settings tab drops a half-asked exit
        v.onEvent(XrEvent.ExitAsk)
        v.onEvent(XrEvent.TabPicked(Tab.EXPLORE))
        assertFalse(v.state.value.confirmExit)
    }
}
