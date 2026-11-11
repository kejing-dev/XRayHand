package io.embodit.xrayhand.ui.hand

import io.embodit.xrayhand.domain.Answer
import io.embodit.xrayhand.domain.Group
import io.embodit.xrayhand.domain.PoseName
import io.embodit.xrayhand.domain.Question
import io.embodit.xrayhand.domain.QuestionKind
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.platform.Look
import io.embodit.xrayhand.platform.Prefs

enum class Mode { EXPLORE, QUIZ }

/** Where the bones get their pose (design `container.notes`: HandPoseSource). */
enum class Source { LIVE, DEMO, INJECTED }

/** Which of the (up to) three hands a bone belongs to. */
enum class HandId { LEFT, RIGHT, DEMO }

enum class Popup { NONE, SETTINGS }

/** Pages of the system window, switched by the SpatialUI TabBar (D8); settings is last (D26). */
enum class Tab { EXPLORE, QUIZ, SETTINGS }

enum class QuizPhase { INTRO, ASKING, FEEDBACK, RESULT, REVIEW }

data class Selection(val hand: HandId, val boneId: String)

data class QuizState(
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    val answers: List<Answer> = emptyList(),
    val phase: QuizPhase = QuizPhase.INTRO,
    /** The last answer while its feedback shows (S13). */
    val last: Answer? = null,
    val reviewIndex: Int = 0,
    val paused: Boolean = false,
    val seed: Long = 0L,
) {
    val current: Question?
        get() = questions.getOrNull(index)

    val score: Int
        get() = answers.count { it.correct }

    val wrong: List<Answer>
        get() = answers.filter { !it.correct }
}

data class XrUiState(
    val loaded: Boolean = false,
    val welcome: Boolean = false,
    val mode: Mode = Mode.EXPLORE,
    val source: Source = Source.DEMO,
    val demoPose: PoseName = PoseName.OPEN,
    val leftTracked: Boolean = false,
    val rightTracked: Boolean = false,
    /** The hand provider's support state while it is not SUPPORTED (S18), else null. */
    val unsupported: String? = null,
    val selected: Selection? = null,
    val group: Group? = null,
    val carpalZoom: Boolean = false,
    val follow: Int? = null,
    val quiz: QuizState = QuizState(),
    val prefs: Prefs = Prefs(Lang.ZH, true, Look.IVORY, null, emptyList(), false),
    val popup: Popup = Popup.NONE,
    val confirmClear: Boolean = false,
    val menuOpen: Boolean = false,
    /** Bumped by "move to the marker" (profile D1): the only thing that moves the demo hand's anchor. */
    val demoPlaceTick: Int = 0,
    /** Settings > Exit asks in the page first (D26). */
    val confirmExit: Boolean = false,
    /** [Start] while a quiz is under way asks first (D44). */
    val confirmRestart: Boolean = false,
    /** Marker placing mode: the floor takes a pinch / trigger and puts the marker there. */
    val placing: Boolean = false,
    /** A marker is on the floor (session-wide, kept by the scene). */
    val hasMarker: Boolean = false,
    val onEmulator: Boolean = false,
    /** Set by the ViewModel: the question / reviewed bone in the quiz is a carpal. */
    val quizZoom: Boolean = false,
) {
    val handsTracked: Int
        get() = (if (leftTracked) 1 else 0) + (if (rightTracked) 1 else 0)

    val quizActive: Boolean
        get() = mode == Mode.QUIZ && quiz.phase != QuizPhase.INTRO

    /** A quiz run that has questions left to answer (kept across tab switches, confirmed before a restart). */
    val quizUnderWay: Boolean
        get() = quiz.phase == QuizPhase.ASKING || quiz.phase == QuizPhase.FEEDBACK

    val tab: Tab
        get() = if (popup == Popup.SETTINGS) Tab.SETTINGS else if (mode == Mode.QUIZ) Tab.QUIZ else Tab.EXPLORE

    /** The carpals are drawn 1.5x and spread: the switch, a carpal group focus or a carpal question (design `spatialLayout.carpalZoom`). */
    val carpalZoomOn: Boolean
        get() = if (mode == Mode.QUIZ) quizZoom else group == Group.CARPAL || carpalZoom

    /** The design state (S00 - S18) this UI state stands for; QA screenshots and the `STATE` log use it. */
    val designState: String
        get() =
            when {
                !loaded -> "S00"
                welcome -> "S01"
                popup == Popup.SETTINGS -> "S16"
                source == Source.LIVE && unsupported != null && mode == Mode.EXPLORE -> "S18"
                mode == Mode.QUIZ ->
                    when {
                        quiz.paused -> "S17"
                        quiz.phase == QuizPhase.INTRO -> "S10"
                        quiz.phase == QuizPhase.ASKING -> if (quiz.current?.kind == QuestionKind.IDENTIFY) "S12" else "S11"
                        quiz.phase == QuizPhase.FEEDBACK -> "S13"
                        quiz.phase == QuizPhase.RESULT -> "S14"
                        else -> "S15"
                    }
                follow != null -> "S09"
                selected != null -> "S05"
                group != null -> "S06"
                source == Source.DEMO -> "S08"
                handsTracked == 0 -> "S02"
                handsTracked == 1 -> "S07"
                else -> "S03"
            }
}

sealed interface XrEvent {
    data object SceneReady : XrEvent
    data object WelcomeUseMine : XrEvent
    data object UseDemo : XrEvent
    data object UseMine : XrEvent
    /** Panel / menu "place a marker": toggles placing mode. */
    data object PlaceMarker : XrEvent

    /** The scene put the marker on the floor (from a pinch / trigger on the floor while placing). */
    data object MarkerPlaced : XrEvent

    /** Menu / settings "重新打开面板": the system window closes and opens again (D21 / D44). */
    data object RecallPanel : XrEvent

    /** Panel / menu "move to the marker". */
    data object BringToMarker : XrEvent
    data object MenuToggle : XrEvent
    data class Tapped(val hand: HandId, val boneId: String, val kind: String, val handPinch: Float) : XrEvent
    data object CardClose : XrEvent
    data object SpeakAgain : XrEvent
    data class GroupClicked(val group: Group) : XrEvent
    data class CarpalZoom(val on: Boolean) : XrEvent
    data object FollowStart : XrEvent
    data object FollowNext : XrEvent
    data object FollowEnd : XrEvent
    data class DemoPose(val pose: PoseName) : XrEvent
    data class ModeChanged(val mode: Mode) : XrEvent
    data class TabPicked(val tab: Tab) : XrEvent
    data object QuizRestartConfirm : XrEvent
    data object QuizRestartCancel : XrEvent
    data object ExitAsk : XrEvent
    data object ExitCancel : XrEvent
    data object ExitConfirm : XrEvent
    data object QuizStart : XrEvent
    data class QuizOption(val boneId: String) : XrEvent
    data object ReviewWrong : XrEvent
    data object ReviewNext : XrEvent
    data object BackToResult : XrEvent
    data object SettingsOpen : XrEvent
    data object SettingsClose : XrEvent
    data class LangPicked(val lang: Lang) : XrEvent
    data class LookPicked(val look: Look) : XrEvent
    data object ClearAsk : XrEvent
    data object ClearConfirm : XrEvent
    data object ClearCancel : XrEvent
    data object MuteToggle : XrEvent
    data class TrackedChanged(val left: Boolean, val right: Boolean) : XrEvent
    data class Support(val state: String?) : XrEvent
    data object RetrySupport : XrEvent
}

sealed interface XrEffect {
    data class Feedback(val kind: String) : XrEffect
}
