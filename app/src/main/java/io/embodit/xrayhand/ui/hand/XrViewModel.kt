package io.embodit.xrayhand.ui.hand

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.embodit.xrayhand.domain.Answer
import io.embodit.xrayhand.domain.BoneCatalog
import io.embodit.xrayhand.domain.Group
import io.embodit.xrayhand.domain.QuestionKind
import io.embodit.xrayhand.domain.QuizMaker
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.platform.PrefsStore
import io.embodit.xrayhand.platform.Speaker
import io.embodit.xrayhand.platform.XrGraph
import io.embodit.xrayhand.platform.XrLog
import io.embodit.xrayhand.platform.tracking.Device
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The whole state machine of the design (states S00 - S18, interactions T1 - T17). Panels and the scene send
 * [XrEvent]s; the scene renders [state]. Per-frame data (joints, bone poses) never comes through here: the scene
 * only reports what changes rarely (which hands are tracked, after a 1 s debounce; a tapped bone).
 */
class XrViewModel(
    val catalog: BoneCatalog,
    private val store: PrefsStore?,
    private val speaker: Speaker?,
    onEmulator: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(XrUiState(prefs = store?.load() ?: XrUiState().prefs, onEmulator = onEmulator))
    val state: StateFlow<XrUiState> = _state

    private val _effects = MutableSharedFlow<XrEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<XrEffect> = _effects

    /** State before a detour that returns (S16 settings and S09 follow-me return to where they came from). */
    private var feedbackJob: Job? = null
    private var lastLogged: String? = null

    /** How long a quiz answer's feedback shows (S13); debug `feedback hold` keeps it up for QA screenshots. */
    var feedbackMs = FEEDBACK_MS

    /** Set by the Stage while it is up: "重新打开面板" and the confirmed exit (the window has no navigator of its own). */
    var onRecall: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    /** Debug `next`: leaves the feedback now. */
    fun debugNext() = advance()

    /** Seed of the next quiz; debug `quiz seed <n>` sets it. */
    var nextSeed: Long? = null

    fun onEvent(e: XrEvent) {
        val s = _state.value
        val next =
            when (e) {
                XrEvent.SceneReady -> {
                    val first = !s.prefs.welcomed
                    s.copy(loaded = true, welcome = first, source = if (first || s.onEmulator) Source.DEMO else Source.LIVE)
                }
                XrEvent.WelcomeUseMine -> {
                    // on the emulator the hand provider is not started (Spatial Ruler): the demo hand it is
                    val p = s.prefs.copy(welcomed = true)
                    if (s.onEmulator) s.copy(welcome = false, source = Source.DEMO, prefs = p) else s.copy(welcome = false, source = Source.LIVE, prefs = p)
                }
                XrEvent.UseDemo -> {
                    val q = if (s.quiz.paused) s.quiz.copy(paused = false) else s.quiz
                    s.copy(welcome = false, source = Source.DEMO, menuOpen = false, unsupported = null, follow = null, selected = s.selected?.takeIf { it.hand == HandId.DEMO }, quiz = q, prefs = s.prefs.copy(welcomed = true))
                }
                XrEvent.UseMine -> s.copy(source = Source.LIVE, menuOpen = false, selected = null, demoPose = io.embodit.xrayhand.domain.PoseName.OPEN)
                XrEvent.RecallPanel -> {
                    // v0.1.4: the system window is closed and opened again once the head is level (D9 / D44)
                    onRecall?.invoke()
                    s.copy(menuOpen = false)
                }
                XrEvent.PlaceMarker -> s.copy(placing = !s.placing, menuOpen = false)
                XrEvent.MarkerPlaced -> {
                    XrLog.i("MARKER placed (waiting for move-to-marker)")
                    s.copy(placing = false, hasMarker = true)
                }
                XrEvent.BringToMarker ->
                    if (!s.hasMarker) s.copy(placing = true, menuOpen = false)
                    else s.copy(menuOpen = false, placing = false, demoPlaceTick = s.demoPlaceTick + 1, source = if (s.follow != null) s.source else Source.DEMO)
                XrEvent.MenuToggle -> s.copy(menuOpen = !s.menuOpen)
                is XrEvent.Tapped -> tapped(s, e)
                XrEvent.CardClose -> s.copy(selected = null)
                XrEvent.SpeakAgain -> {
                    s.selected?.let { say(it.boneId, s, force = true) }
                    s
                }
                is XrEvent.GroupClicked -> s.copy(group = if (s.group == e.group) null else e.group, selected = null)
                is XrEvent.CarpalZoom -> s.copy(carpalZoom = e.on)
                XrEvent.FollowStart -> if (s.source == Source.DEMO) s else s.copy(follow = 1, selected = null, group = null)
                XrEvent.FollowNext -> s.copy(follow = s.follow?.let { if (it >= 3) null else it + 1 })
                XrEvent.FollowEnd -> s.copy(follow = null)
                is XrEvent.DemoPose -> s.copy(demoPose = e.pose)
                is XrEvent.ModeChanged -> modeChanged(s, e.mode)
                is XrEvent.TabPicked ->
                    when (e.tab) {
                        Tab.SETTINGS -> s.copy(popup = Popup.SETTINGS, confirmClear = false, confirmExit = false, menuOpen = false)
                        Tab.EXPLORE -> modeChanged(s.copy(popup = Popup.NONE, confirmClear = false, confirmExit = false), Mode.EXPLORE)
                        Tab.QUIZ -> modeChanged(s.copy(popup = Popup.NONE, confirmClear = false, confirmExit = false), Mode.QUIZ)
                    }
                // D44: a quiz under way is not thrown away by one stray [Start]: confirm first
                XrEvent.QuizStart -> if (s.quizUnderWay) s.copy(confirmRestart = true) else startQuiz(s)
                XrEvent.QuizRestartConfirm -> startQuiz(s)
                XrEvent.QuizRestartCancel -> s.copy(confirmRestart = false)
                is XrEvent.QuizOption -> answer(s, e.boneId)
                XrEvent.ReviewWrong -> if (s.quiz.wrong.isEmpty()) s else s.copy(quiz = s.quiz.copy(phase = QuizPhase.REVIEW, reviewIndex = 0))
                XrEvent.ReviewNext -> {
                    val n = s.quiz.reviewIndex + 1
                    if (n >= s.quiz.wrong.size) s.copy(quiz = s.quiz.copy(phase = QuizPhase.RESULT)) else s.copy(quiz = s.quiz.copy(reviewIndex = n))
                }
                XrEvent.BackToResult -> s.copy(quiz = s.quiz.copy(phase = QuizPhase.RESULT))
                XrEvent.SettingsOpen -> s.copy(popup = Popup.SETTINGS, confirmClear = false, confirmExit = false, menuOpen = false)
                XrEvent.SettingsClose -> s.copy(popup = Popup.NONE, confirmClear = false, confirmExit = false)
                XrEvent.ExitAsk -> s.copy(confirmExit = true, confirmClear = false)
                XrEvent.ExitCancel -> s.copy(confirmExit = false)
                XrEvent.ExitConfirm -> {
                    XrLog.i("EXIT confirmed")
                    onExit?.invoke()
                    s.copy(confirmExit = false)
                }
                is XrEvent.LangPicked -> s.copy(prefs = s.prefs.copy(lang = e.lang))
                is XrEvent.LookPicked -> s.copy(prefs = s.prefs.copy(look = e.look))
                XrEvent.ClearAsk -> s.copy(confirmClear = true)
                XrEvent.ClearCancel -> s.copy(confirmClear = false)
                XrEvent.ClearConfirm -> {
                    XrLog.i("QUIZ records cleared")
                    s.copy(confirmClear = false, prefs = s.prefs.copy(bestScore = null, history = emptyList()))
                }
                XrEvent.MuteToggle -> {
                    if (s.prefs.speech) speaker?.stop()
                    s.copy(prefs = s.prefs.copy(speech = !s.prefs.speech))
                }
                is XrEvent.TrackedChanged -> tracked(s, e.left, e.right)
                is XrEvent.Support -> s.copy(unsupported = e.state)
                XrEvent.RetrySupport -> s.copy(unsupported = null)
            }
        set(next)
        if (e is XrEvent.Tapped || e is XrEvent.QuizOption) Unit
    }

    /** Debug hooks only: replace the state directly (design `verification.debugHooks`). */
    fun debugSet(f: (XrUiState) -> XrUiState) = set(f(_state.value))

    private fun set(next: XrUiState) {
        val prev = _state.value
        val zoom =
            when {
                next.mode != Mode.QUIZ -> false
                next.quiz.phase == QuizPhase.REVIEW -> next.quiz.wrong.getOrNull(next.quiz.reviewIndex)?.let { isCarpal(it.boneId) } ?: false
                next.quiz.phase == QuizPhase.ASKING || next.quiz.phase == QuizPhase.FEEDBACK -> next.quiz.current?.let { isCarpal(it.boneId) } ?: false
                else -> false
            }
        val n = if (zoom != next.quizZoom) next.copy(quizZoom = zoom) else next
        _state.value = n
        if (n.prefs != prev.prefs) store?.save(n.prefs)
        val line = stateLine(n)
        if (line != lastLogged) {
            lastLogged = line
            XrLog.i(line)
        }
    }

    private fun isCarpal(id: String) = catalog[id]?.group == Group.CARPAL

    fun stateLine(s: XrUiState = _state.value): String =
        "STATE ${s.designState} mode=${s.mode} source=${s.source} hands=${if (s.leftTracked) "L" else "-"}${if (s.rightTracked) "R" else "-"} sel=${s.selected?.let { "${it.hand}:${it.boneId}" } ?: "-"} group=${s.group?.key ?: "-"} zoom=${s.carpalZoomOn} follow=${s.follow ?: "-"} pose=${s.demoPose.key} quiz=${s.quiz.phase}:${s.quiz.index}/${s.quiz.questions.size}:${s.quiz.score}${if (s.quiz.paused) ":paused" else ""} popup=${s.popup} marker=${s.hasMarker} placing=${s.placing} lang=${s.prefs.lang.key} look=${s.prefs.look.key} speech=${s.prefs.speech} best=${s.prefs.bestScore ?: "-"}"

    // ---- taps ------------------------------------------------------------------------------------------------

    private fun tapped(s: XrUiState, e: XrEvent.Tapped): XrUiState {
        // design `input.tapFilter`: only gaze / ray / pointer pinches name a bone; a finger inside its own bones is not one
        if (e.kind !in ACCEPTED_KINDS) {
            XrLog.i("TAP ignored kind=${e.kind} bone=${e.boneId} hand=${e.hand}")
            return s
        }
        // design `input.selfPinchGuard`: two tracked hands (or follow-me), and the tapped hand itself pinches
        val guard = e.hand != HandId.DEMO && (s.handsTracked == 2 || s.follow != null)
        if (guard && e.handPinch > SELF_PINCH) {
            XrLog.i("TAP ignored self-pinch bone=${e.boneId} hand=${e.hand} pinch=${"%.2f".format(e.handPinch)}")
            return s
        }
        if (s.welcome || s.popup != Popup.NONE) return s
        XrLog.i("TAP kind=${e.kind} bone=${e.boneId} hand=${e.hand} ignored=no")
        if (s.mode == Mode.QUIZ) {
            val q = s.quiz.current ?: return s
            if (s.quiz.phase != QuizPhase.ASKING || q.kind != QuestionKind.LOCATE || s.quiz.paused) return s
            return answer(s, e.boneId)
        }
        val sel = Selection(e.hand, e.boneId)
        if (s.selected == sel) return s.copy(selected = null)
        say(e.boneId, s)
        _effects.tryEmit(XrEffect.Feedback("select"))
        return s.copy(selected = sel)
    }

    private fun say(id: String, s: XrUiState, force: Boolean = false) {
        if (!s.prefs.speech && !force) {
            XrLog.i("SPEAK $id muted")
            return
        }
        val b = catalog[id] ?: return
        when (s.prefs.lang) {
            Lang.ZH -> speaker?.say(id, b.zh, null)
            Lang.EN -> speaker?.say(id, null, b.en)
            Lang.BOTH -> speaker?.say(id, b.zh, b.en)
        }
    }

    // ---- quiz --------------------------------------------------------------------------------------------------

    private fun modeChanged(s: XrUiState, m: Mode): XrUiState {
        if (m == s.mode) return s
        // v0.1.4: the quiz is a tab; leaving it mid-run keeps the run (D44: progress is never lost by a tab switch)
        if (s.quizUnderWay) return if (m == Mode.QUIZ) s.copy(mode = m, selected = null, group = null, follow = null) else s.copy(mode = m)
        feedbackJob?.cancel()
        return if (m == Mode.QUIZ) s.copy(mode = m, selected = null, group = null, follow = null, quiz = QuizState()) else s.copy(mode = m, quiz = QuizState())
    }

    private fun startQuiz(s: XrUiState): XrUiState {
        val seed = nextSeed ?: System.currentTimeMillis()
        nextSeed = null
        val qs = QuizMaker.make(catalog, seed)
        XrLog.i("QUIZ start seed=$seed questions=${qs.joinToString(" ") { "${it.kind.name.first()}:${it.boneId}" }}")
        feedbackJob?.cancel()
        return s.copy(mode = Mode.QUIZ, popup = Popup.NONE, confirmRestart = false, selected = null, group = null, follow = null, quiz = QuizState(questions = qs, phase = QuizPhase.ASKING, seed = seed))
    }

    /** Debug: a quiz with this seed, at its first question of [kind] (null: the first question). */
    fun debugQuiz(seed: Long, kind: QuestionKind?) {
        nextSeed = seed
        var s = startQuiz(_state.value)
        if (kind != null) {
            val i = s.quiz.questions.indexOfFirst { it.kind == kind }.coerceAtLeast(0)
            s = s.copy(quiz = s.quiz.copy(index = i))
        }
        set(s)
    }

    /** Debug `quiz finish <score>`: a finished run with [score] right answers (the rest wrong). */
    fun debugFinish(score: Int) {
        var s = _state.value
        if (s.quiz.questions.isEmpty()) s = startQuiz(s)
        val answers = s.quiz.questions.mapIndexed { i, q -> Answer(q.boneId, if (i < score) q.boneId else other(q.boneId), i < score) }
        set(finish(s.copy(quiz = s.quiz.copy(index = QuizMaker.COUNT, answers = answers))))
    }

    /** Debug `answer right|wrong` on the current question. */
    fun debugAnswer(right: Boolean) {
        val s = _state.value
        val q = s.quiz.current ?: return
        set(answer(s, if (right) q.boneId else other(q.boneId)))
    }

    private fun other(id: String): String {
        val g = catalog[id]?.group ?: Group.CARPAL
        return catalog.of(g).first { it.id != id }.id
    }

    private fun answer(s: XrUiState, picked: String): XrUiState {
        val q = s.quiz.current ?: return s
        if (s.quiz.phase != QuizPhase.ASKING) return s
        val ok = QuizMaker.check(q, picked)
        val a = Answer(q.boneId, picked, ok)
        XrLog.i("QUIZ answer n=${s.quiz.index + 1} bone=${q.boneId} picked=$picked correct=$ok")
        _effects.tryEmit(XrEffect.Feedback(if (ok) "right" else "wrong"))
        val next = s.copy(quiz = s.quiz.copy(answers = s.quiz.answers + a, phase = QuizPhase.FEEDBACK, last = a))
        feedbackJob?.cancel()
        feedbackJob =
            viewModelScope.launch {
                delay(feedbackMs)
                advance()
            }
        return next
    }

    private fun advance() {
        val s = _state.value
        if (s.quiz.phase != QuizPhase.FEEDBACK) return
        val n = s.quiz.index + 1
        if (n >= s.quiz.questions.size) set(finish(s.copy(quiz = s.quiz.copy(index = n)))) else set(s.copy(quiz = s.quiz.copy(index = n, phase = QuizPhase.ASKING, last = null)))
    }

    private fun finish(s: XrUiState): XrUiState {
        val score = s.quiz.score
        val best = maxOf(score, s.prefs.bestScore ?: 0)
        XrLog.i("QUIZ result score=$score best=$best wrong=${s.quiz.wrong.joinToString(",") { it.boneId }}")
        return s.copy(quiz = s.quiz.copy(phase = QuizPhase.RESULT, last = null), prefs = s.prefs.copy(bestScore = best, history = (s.prefs.history + score).takeLast(5)))
    }

    // ---- tracking -------------------------------------------------------------------------------------------------

    private fun tracked(s: XrUiState, left: Boolean, right: Boolean): XrUiState {
        val n = s.copy(leftTracked = left, rightTracked = right)
        // S17: both real (or injected) hands gone during a quiz (design T14); they come back -> the same question
        val paused = s.source != Source.DEMO && s.quizActive && s.quiz.phase != QuizPhase.RESULT && !left && !right
        val sel = s.selected?.takeIf { it.hand == HandId.DEMO || (it.hand == HandId.LEFT && left) || (it.hand == HandId.RIGHT && right) }
        return n.copy(quiz = n.quiz.copy(paused = paused), selected = sel)
    }

    override fun onCleared() {
        feedbackJob?.cancel()
        super.onCleared()
    }

    companion object {
        @Volatile private var instance: XrViewModel? = null

        /**
         * v0.1.4 (factory native-window-move §3.4): one per process, shared by the Stage and the system window (a
         * container's own viewModel() would end with that container).
         */
        fun shared(context: Context): XrViewModel =
            instance ?: synchronized(this) {
                instance ?: XrViewModel(XrGraph.catalog(context), XrGraph.prefs(context), XrGraph.speaker(context), Device.onEmulator).also { instance = it }
            }


        val ACCEPTED_KINDS = setOf("GazePinch", "RayBasedPinch", "Pointer")
        const val SELF_PINCH = 0.6f
        const val FEEDBACK_MS = 1500L
    }
}
