package io.embodit.xrayhand.debug

import android.content.Context
import io.embodit.xrayhand.domain.Group
import io.embodit.xrayhand.domain.PoseName
import io.embodit.xrayhand.domain.QuestionKind
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.platform.Look
import io.embodit.xrayhand.platform.XrGraph
import io.embodit.xrayhand.platform.XrLog
import io.embodit.xrayhand.ui.hand.HandId
import io.embodit.xrayhand.ui.hand.Mode
import io.embodit.xrayhand.ui.hand.Source
import io.embodit.xrayhand.ui.hand.Tab
import io.embodit.xrayhand.ui.hand.components.TextFit
import io.embodit.xrayhand.ui.window.WindowCtl
import io.embodit.xrayhand.ui.hand.XrEvent
import io.embodit.xrayhand.ui.hand.debug.Probe

/**
 * Test hooks of the debug build (design `verification.debugHooks`). Every command answers with one `CMD` line
 * (tag `XrayHand`) and every state change logs `STATE Sxx ...`.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.xrayhand.DEBUG -p io.embodit.xrayhand --es cmd '<command>'"
 *
 * Commands (one string, words separated by spaces):
 *   state [Sxx]                    read the state line, or jump to a design state (S01 - S18, see AGENTS.md)
 *   handSource live|demo|injected  where the poses come from (live on the emulator starts the provider: avoid)
 *   pose <l>/<r>                   injected hands: open | fist | pinch | flip | none (switches to handSource injected)
 *   demopose open|fist|pinch|flip  the demo hand's pose
 *   select <boneId> [demo|left|right]   name a bone (as a GazePinch tap)
 *   tap <boneId> <kind>            a tap of that interaction kind on the active hand (GazePinch, DirectPinch, Poke, Pointer)
 *   selfpinch <0-1>                pinch value of the injected hands (self-pinch guard)
 *   group carpal|metacarpal|phalanx|none
 *   zoom on|off · follow 1-3|off · mode explore|quiz · quiz seed <n> [identify] · answer right|wrong · quiz finish <score>
 *   feedback hold|auto · next · review · settings open|close · lang zh|en|both · look ivory|xray · mute on|off · clear · menu
 *   support unsupported|<STATE>|ok · stick <x> [ms] · drag <dx> <dy> <dz> · marker <x> <z> · placing · bring · recall · recenter · welcome reset
 *   tab explore|quiz|settings · exit [yes|no] · start (quiz [Start], confirms mid-run) · window · fit [reset]
 *   dump bones | dump pose <name> | panels | labels | headpose | bounds
 */
internal object DebugCommands {
    fun run(context: Context, cmd: String) {
        val host = Probe.host
        if (host == null) {
            XrLog.w("CMD ignored: no Stage")
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val w = cmd.trim().split(Regex("\\s+"))
        val a1 = w.getOrNull(1)
        val a2 = w.getOrNull(2)
        val reply: String =
            when (w[0]) {
                "state" -> if (a1 == null) vm.stateLine() else jump(context, a1)
                "handSource" -> {
                    when (a1) {
                        "live" -> {
                            scene.liveOnEmulator = true
                            set(vm) { it.copy(source = Source.LIVE) }
                        }
                        "injected" -> set(vm) { it.copy(source = Source.INJECTED) }
                        else -> vm.onEvent(XrEvent.UseDemo)
                    }
                    "source=$a1"
                }
                "pose" -> {
                    val parts = (a1 ?: "none/none").split('/')
                    val r = scene.inject(PoseName.of(parts.getOrNull(0) ?: "none"), PoseName.of(parts.getOrNull(1) ?: "none"))
                    if (vm.state.value.source != Source.INJECTED) set(vm) { it.copy(source = Source.INJECTED, welcome = false) }
                    r
                }
                "demopose" -> {
                    PoseName.of(a1 ?: "open")?.let { vm.onEvent(XrEvent.DemoPose(it)) }
                    "demopose=$a1"
                }
                "select" -> {
                    val hand =
                        when (a2) {
                            "left" -> HandId.LEFT
                            "right" -> HandId.RIGHT
                            "demo" -> HandId.DEMO
                            else -> if (vm.state.value.source == Source.DEMO) HandId.DEMO else if (vm.state.value.rightTracked) HandId.RIGHT else HandId.LEFT
                        }
                    vm.onEvent(XrEvent.Tapped(hand, a1 ?: "scaphoid", "GazePinch", 0f))
                    "select $hand:$a1"
                }
                "tap" -> scene.debugTap(a1 ?: "scaphoid", a2 ?: "GazePinch")
                "selfpinch" -> {
                    scene.setInjectedPinch(a1?.toFloatOrNull() ?: 0f)
                    "selfpinch=$a1"
                }
                "group" -> {
                    val g = Group.entries.firstOrNull { it.key == a1 }
                    set(vm) { it.copy(group = g, selected = null) }
                    "group=${g?.key}"
                }
                "zoom" -> {
                    vm.onEvent(XrEvent.CarpalZoom(a1 == "on"))
                    "zoom=$a1"
                }
                "follow" -> {
                    val n = a1?.toIntOrNull()
                    set(vm) { it.copy(follow = n?.coerceIn(1, 3), selected = null, group = null) }
                    "follow=$n"
                }
                "mode" -> {
                    vm.onEvent(XrEvent.ModeChanged(if (a1 == "quiz") Mode.QUIZ else Mode.EXPLORE))
                    "mode=$a1"
                }
                "quiz" ->
                    when (a1) {
                        "seed" -> {
                            vm.debugQuiz(a2?.toLongOrNull() ?: 7L, if (w.getOrNull(3) == "identify") QuestionKind.IDENTIFY else null)
                            "quiz seed=$a2"
                        }
                        "finish" -> {
                            vm.debugFinish(a2?.toIntOrNull() ?: 8)
                            "quiz finish=$a2"
                        }
                        else -> "quiz ?"
                    }
                "answer" -> {
                    vm.debugAnswer(a1 == "right")
                    "answer=$a1"
                }
                "feedback" -> {
                    vm.feedbackMs = if (a1 == "hold") 600_000L else io.embodit.xrayhand.ui.hand.XrViewModel.FEEDBACK_MS
                    "feedbackMs=${vm.feedbackMs}"
                }
                "next" -> {
                    vm.debugNext()
                    "next"
                }
                "review" -> {
                    vm.onEvent(XrEvent.ReviewWrong)
                    "review"
                }
                "settings" -> {
                    vm.onEvent(if (a1 == "close") XrEvent.SettingsClose else XrEvent.SettingsOpen)
                    "settings=$a1"
                }
                "lang" -> {
                    vm.onEvent(XrEvent.LangPicked(Lang.of(a1) ?: Lang.ZH))
                    "lang=$a1"
                }
                "look" -> {
                    vm.onEvent(XrEvent.LookPicked(Look.of(a1)))
                    "look=$a1"
                }
                "mute" -> {
                    val wantOn = a1 == "on"
                    if (vm.state.value.prefs.speech == wantOn) vm.onEvent(XrEvent.MuteToggle)
                    "speech=${vm.state.value.prefs.speech}"
                }
                "clear" -> {
                    vm.onEvent(XrEvent.ClearAsk)
                    "clear asked"
                }
                "menu" -> {
                    vm.onEvent(XrEvent.MenuToggle)
                    "menu=${vm.state.value.menuOpen}"
                }
                "support" -> {
                    scene.forceSupport(if (a1 == "ok") null else if (a1 == "unsupported") "DEVICE_NOT_SUPPORTED" else a1)
                    if (a1 == "ok") vm.onEvent(XrEvent.Support(null)) else {
                        set(vm) { it.copy(source = Source.LIVE, welcome = false) }
                        vm.onEvent(XrEvent.Support(if (a1 == "unsupported") "DEVICE_NOT_SUPPORTED" else a1))
                    }
                    "support=$a1"
                }
                "stick" -> scene.debugStick(a1?.toFloatOrNull() ?: 0f, a2?.toLongOrNull() ?: 1000L)
                "drag" -> scene.debugDrag(a1?.toFloatOrNull() ?: 0f, a2?.toFloatOrNull() ?: 0f, w.getOrNull(3)?.toFloatOrNull() ?: 0f)
                "marker" -> scene.debugMarker(a1?.toFloatOrNull() ?: 0.5f, a2?.toFloatOrNull() ?: -1.3f)
                "placing" -> {
                    vm.onEvent(XrEvent.PlaceMarker)
                    "placing=${vm.state.value.placing}"
                }
                "bring" -> {
                    vm.onEvent(XrEvent.BringToMarker)
                    "bring tick=${vm.state.value.demoPlaceTick}"
                }
                "recall" -> {
                    vm.onEvent(XrEvent.RecallPanel)
                    "recall window=${WindowCtl.panelState}"
                }
                // v0.1.4: the same events as the TabBar and the settings / quiz buttons (no shortcut into the state)
                "tab" -> {
                    vm.onEvent(XrEvent.TabPicked(Tab.entries.firstOrNull { it.name.equals(a1, ignoreCase = true) } ?: Tab.EXPLORE))
                    "tab=${vm.state.value.tab}"
                }
                "exit" -> {
                    vm.onEvent(if (a1 == "yes") XrEvent.ExitConfirm else if (a1 == "no") XrEvent.ExitCancel else XrEvent.ExitAsk)
                    "exit $a1 confirm=${vm.state.value.confirmExit}"
                }
                "start" -> {
                    vm.onEvent(XrEvent.QuizStart)
                    "start confirm=${vm.state.value.confirmRestart}"
                }
                "window" -> "window=${WindowCtl.panelState} open=${WindowCtl.open} level=${WindowCtl.levelHint}"
                "fit" -> {
                    if (a1 == "reset") TextFit.reset()
                    TextFit.summary()
                }
                "recenter" -> {
                    scene.recenter()
                    "recenter"
                }
                "welcome" -> {
                    XrGraph.prefs(context).clearWelcome()
                    set(vm) { it.copy(welcome = true, source = Source.DEMO, prefs = it.prefs.copy(welcomed = false)) }
                    "welcome"
                }
                "dump" ->
                    when (a1) {
                        "pose" -> scene.writePose(a2 ?: "manual", scene.lastJointsOf(true) ?: scene.lastJointsOf(false))
                        else -> {
                            scene.dumpBones().forEach { XrLog.i(it) }
                            "dump bones"
                        }
                    }
                "panels" -> {
                    scene.panelLines().forEach { XrLog.i(it) }
                    "panels"
                }
                "labels" -> {
                    scene.labelLines().forEach { XrLog.i(it) }
                    "labels"
                }
                "headpose" -> scene.poseLine()
                "bounds" -> {
                    for (panel in listOf("window", "open")) {
                        val size = Probe.sizeOf(panel)
                        Probe.controlsOf(panel).forEach { (n, b) -> XrLog.i("BOUNDS panel=$panel name=$n cx=${b.cx} cy=${b.cy} w=${b.w} h=${b.h} panel=${size?.first}x${size?.second}") }
                    }
                    "bounds"
                }
                else -> "unknown"
            }
        XrLog.i("CMD '$cmd' -> $reply")
    }

    private fun set(vm: io.embodit.xrayhand.ui.hand.XrViewModel, f: (io.embodit.xrayhand.ui.hand.XrUiState) -> io.embodit.xrayhand.ui.hand.XrUiState) = vm.debugSet(f)

    /** Jumps to a design state the way QA screenshots it (design `states[].qaCapture`). */
    private fun jump(context: Context, id: String): String {
        val host = Probe.host ?: return "no host"
        val vm = host.viewModel()
        val scene = host.scene()
        fun base(source: Source) = vm.debugSet { it.copy(welcome = false, popup = io.embodit.xrayhand.ui.hand.Popup.NONE, menuOpen = false, confirmExit = false, confirmRestart = false, mode = Mode.EXPLORE, quiz = io.embodit.xrayhand.ui.hand.QuizState(), selected = null, group = null, follow = null, unsupported = null, source = source) }
        when (id.uppercase()) {
            "S01" -> run(context, "welcome reset")
            "S02" -> {
                base(Source.INJECTED)
                scene.inject(null, null)
            }
            "S03" -> {
                base(Source.INJECTED)
                scene.inject(PoseName.OPEN, PoseName.OPEN)
            }
            "S05" -> {
                base(Source.DEMO)
                vm.onEvent(XrEvent.Tapped(HandId.DEMO, "scaphoid", "GazePinch", 0f))
            }
            "S06" -> {
                base(Source.DEMO)
                vm.onEvent(XrEvent.GroupClicked(Group.CARPAL))
            }
            "S07" -> {
                base(Source.INJECTED)
                scene.inject(PoseName.OPEN, null)
            }
            "S08" -> base(Source.DEMO)
            "S09" -> {
                base(Source.INJECTED)
                scene.inject(null, PoseName.OPEN)
                vm.debugSet { it.copy(follow = 2) }
            }
            "S10" -> {
                base(Source.DEMO)
                vm.onEvent(XrEvent.ModeChanged(Mode.QUIZ))
            }
            "S11" -> {
                base(Source.DEMO)
                vm.debugQuiz(7L, QuestionKind.LOCATE)
            }
            "S12" -> {
                base(Source.DEMO)
                vm.debugQuiz(7L, QuestionKind.IDENTIFY)
            }
            "S13" -> {
                base(Source.DEMO)
                vm.feedbackMs = 600_000L
                vm.debugQuiz(7L, null)
                vm.debugAnswer(false)
            }
            "S14" -> {
                base(Source.DEMO)
                vm.debugFinish(8)
            }
            "S15" -> {
                base(Source.DEMO)
                vm.debugFinish(8)
                vm.onEvent(XrEvent.ReviewWrong)
            }
            "S16" -> vm.onEvent(XrEvent.SettingsOpen)
            "S17" -> {
                base(Source.INJECTED)
                scene.inject(PoseName.OPEN, PoseName.OPEN)
                vm.debugQuiz(7L, null)
                // the hands are seen first, then lost (the 1 s debounce makes it S17 about 4 s after the jump; the idle emulator draws ~1 fps)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ scene.inject(null, null) }, 3000L)
            }
            "S18" -> {
                base(Source.LIVE)
                run(context, "support unsupported")
            }
            else -> return "no jump for $id"
        }
        return "jump $id"
    }
}
