package io.embodit.xrayhand.ui.hand.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import io.embodit.xrayhand.R
import io.embodit.xrayhand.domain.BoneCatalog
import io.embodit.xrayhand.domain.Group
import io.embodit.xrayhand.domain.PoseName
import io.embodit.xrayhand.domain.QuestionKind
import io.embodit.xrayhand.domain.QuizMaker
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.ui.hand.Mode
import io.embodit.xrayhand.ui.hand.QuizPhase
import io.embodit.xrayhand.ui.hand.Source
import io.embodit.xrayhand.ui.hand.XrEvent
import io.embodit.xrayhand.ui.hand.XrUiState
import io.embodit.xrayhand.ui.hand.debug.probe
import io.embodit.xrayhand.ui.theme.XrColors
import io.embodit.xrayhand.ui.theme.XrType

private val RowH = 48.dp
internal val PagePad = 24.dp

/**
 * The explore and quiz pages of the system window (design `panels.main`; v0.1.4 the window replaces the Stage panel,
 * D8 / D21): the welcome (S01), the explore page (S02 / S03 / S05 - S09 / S18) and the quiz (S10 - S15, S17). The
 * window draws the glass and the corners; the TabBar switches 认骨 / 测验 / 设置, so the old mode segments are gone.
 * PicoTheme roles, SpatialUI controls only.
 */
@Composable
internal fun MainPanel(state: XrUiState, catalog: BoneCatalog, onEvent: (XrEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = PagePad, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            !state.loaded -> Loading()
            state.welcome -> Welcome(onEvent)
            else -> {
                Header(state, onEvent)
                when {
                    state.menuOpen -> MoreMenu(state, onEvent)
                    state.mode == Mode.QUIZ -> Quiz(state, catalog, onEvent)
                    state.designState == "S18" -> Unsupported(state, onEvent)
                    state.follow != null -> Follow(state, onEvent)
                    state.designState == "S02" -> Seek(onEvent)
                    else -> Explore(state, catalog, onEvent)
                }
            }
        }
    }
}

// ---- start ---------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(40.dp))
            Text(text = rs(R.string.loading), color = PicoTheme.colorScheme.labelSecondary, style = XrType.status, maxLines = 1, modifier = Modifier.probe("loading"))
        }
    }
}

@Composable
private fun ColumnScope.Welcome(onEvent: (XrEvent) -> Unit) {
    Spacer(modifier = Modifier.weight(1f))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(140.dp).clip(CircleShape).background(XrColors.LightboxSlate), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(R.drawable.ic_bone_hand), contentDescription = null, modifier = Modifier.size(100.dp), tint = Color.Unspecified)
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = rs(R.string.welcome_line1), color = PicoTheme.colorScheme.labelPrimary, style = XrType.big, maxLines = 3, modifier = Modifier.probe("welcome_line1"), onTextLayout = fits("main/welcome_line1"))
    Text(text = rs(R.string.welcome_line2), color = PicoTheme.colorScheme.labelSecondary, style = XrType.hint, maxLines = 3, onTextLayout = fits("main/welcome_line2"))
    Spacer(modifier = Modifier.weight(1f))
    Primary("use_mine", rs(R.string.use_my_hands)) { onEvent(XrEvent.WelcomeUseMine) }
    Secondary("use_demo", rs(R.string.use_demo)) { onEvent(XrEvent.UseDemo) }
}

// ---- header ---------------------------------------------------------------------------------

/** Page title (the TabBar shows icons only) with the speaker switch (D15) and the ⋯ menu. */
@Composable
private fun Header(state: XrUiState, onEvent: (XrEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = rs(if (state.mode == Mode.QUIZ) R.string.tab_quiz else R.string.tab_explore),
            color = PicoTheme.colorScheme.labelPrimary,
            style = XrType.title,
            maxLines = 1,
            modifier = Modifier.weight(1f).probe("page_title"),
            onTextLayout = fits("main/page_title"),
        )
        Key("mute", if (state.prefs.speech) R.drawable.ic_sound_on else R.drawable.ic_sound_off, rs(R.string.cd_mute), accent = state.prefs.speech) { onEvent(XrEvent.MuteToggle) }
        if (state.mode == Mode.EXPLORE) Key("more", R.drawable.ic_more, rs(R.string.cd_more), accent = state.menuOpen) { onEvent(XrEvent.MenuToggle) }
    }
}

/** ⋯ menu, drawn in the panel body (design `spatialUiMap.more`). */
@Composable
private fun ColumnScope.MoreMenu(state: XrUiState, onEvent: (XrEvent) -> Unit) {
    Spacer(modifier = Modifier.weight(1f))
    if (state.source != Source.DEMO) Secondary("menu_demo", rs(R.string.menu_demo)) { onEvent(XrEvent.UseDemo) }
    if (state.source == Source.DEMO) Secondary("menu_mine", rs(R.string.menu_mine)) { onEvent(XrEvent.UseMine) }
    Secondary("menu_marker", rs(R.string.menu_marker)) { onEvent(XrEvent.PlaceMarker) }
    Secondary("menu_bring", rs(R.string.bring_to_marker)) { onEvent(XrEvent.BringToMarker) }
    Secondary("menu_recall", rs(R.string.menu_recall)) { onEvent(XrEvent.RecallPanel) }
    Spacer(modifier = Modifier.weight(1f))
    StatusLine(state)
}

// ---- explore ---------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Explore(state: XrUiState, catalog: BoneCatalog, onEvent: (XrEvent) -> Unit) {
    BoneCard(state, catalog, onEvent, Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupChip(Group.CARPAL, R.string.group_carpal, state, onEvent)
        GroupChip(Group.METACARPAL, R.string.group_metacarpal, state, onEvent)
        GroupChip(Group.PHALANX, R.string.group_phalanx, state, onEvent)
    }
    if (state.source == Source.DEMO) {
        // gate:allow tabs-segment 同一页里切示范手的四个姿势（张开 / 握拳 / 捏合 / 翻掌），不是切页面
        Segments(items = PoseName.entries, selected = state.demoPose, width = (PanelSizes.WINDOW_W - 48f).dp, name = { "pose_" + it.key }, onSelect = { onEvent(XrEvent.DemoPose(it)) }) { p ->
            Text(
                text = rs(when (p) { PoseName.OPEN -> R.string.pose_open; PoseName.FIST -> R.string.pose_fist; PoseName.PINCH -> R.string.pose_pinch; PoseName.FLIP -> R.string.pose_flip }),
                style = XrType.segment,
                maxLines = 1,
                softWrap = false,
            )
        }
        MarkerRow(state, onEvent)
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.width(170.dp)) { Secondary("follow", rs(R.string.follow_me)) { onEvent(XrEvent.FollowStart) } }
            Spacer(modifier = Modifier.weight(1f))
            ZoomSwitch(state, onEvent)
        }
    }
    StatusLine(state)
}

/** "Place marker" / "Move to marker" (profile D1): the demo hand goes where the user put a marker, never in front of the head. */
@Composable
private fun MarkerRow(state: XrUiState, onEvent: (XrEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            Secondary("place_marker", rs(if (state.placing) R.string.cancel_marker else R.string.place_marker)) { onEvent(XrEvent.PlaceMarker) }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (state.hasMarker && !state.placing) Primary("bring_to_marker", rs(R.string.bring_to_marker)) { onEvent(XrEvent.BringToMarker) }
            // without a marker it still reads (no grey on grey, profile D13) and starts placing mode
            else Secondary("bring_to_marker", rs(R.string.bring_to_marker)) { onEvent(XrEvent.BringToMarker) }
        }
    }
}

@Composable
private fun ZoomSwitch(state: XrUiState, onEvent: (XrEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = rs(R.string.carpal_zoom), color = PicoTheme.colorScheme.labelPrimary, style = XrType.status, maxLines = 1, softWrap = false)
        Switch(checked = state.carpalZoomOn, onCheckedChange = { onEvent(XrEvent.CarpalZoom(it)) }, modifier = Modifier.probe("carpal_zoom"))
    }
}

/** The current-bone card: the named bone (S05), the group's rhyme (S06) or the hint (design `panels.main.content.explore`). */
@Composable
private fun BoneCard(state: XrUiState, catalog: BoneCatalog, onEvent: (XrEvent) -> Unit, modifier: Modifier = Modifier) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val grey = PicoTheme.colorScheme.labelSecondary
    val bone = state.selected?.let { catalog[it.boneId] }
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = 150.dp).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 14.dp, vertical = 10.dp).probe("bone_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (bone != null) {
            val (first, second) = boneName(bone)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(XrColors.group(bone.group)))
                Text(text = first, color = ink, style = XrType.big, maxLines = 2, modifier = Modifier.weight(1f).probe("bone_name"), onTextLayout = fits("main/bone_name"))
                Key("speak", R.drawable.ic_sound_on, rs(R.string.cd_speak)) { onEvent(XrEvent.SpeakAgain) }
                Key("card_close", R.drawable.ic_cross, rs(R.string.cd_close)) { onEvent(XrEvent.CardClose) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (second != null) Text(text = second, color = grey, style = XrType.title, maxLines = 2, modifier = Modifier.weight(1f, fill = false), onTextLayout = fits("main/bone_name2"))
                Pill(groupName(bone.group), XrColors.group(bone.group))
            }
            Text(text = boneFact(bone), color = grey, style = XrType.status, maxLines = 4, onTextLayout = fits("main/bone_fact"))
        } else if (state.group != null) {
            Text(text = groupName(state.group), color = ink, style = XrType.big, maxLines = 1)
            val rhyme =
                when (state.group) {
                    Group.CARPAL -> if (LocalLang.current == Lang.EN) catalog.mnemonicEn else catalog.mnemonicZh
                    Group.METACARPAL -> rs(R.string.mnemonic_metacarpal)
                    Group.PHALANX -> rs(R.string.mnemonic_phalanx)
                }
            Text(text = rhyme, color = grey, style = XrType.hint, maxLines = 4, modifier = Modifier.probe("mnemonic"), onTextLayout = fits("main/mnemonic"))
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = rs(R.string.hint_pick), color = ink, style = XrType.hint, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.probe("hint"))
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(modifier = Modifier.height(24.dp).clip(CircleShape).background(color).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = XrColors.OnAccent, style = XrType.label, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GroupChip(g: Group, label: Int, state: XrUiState, onEvent: (XrEvent) -> Unit) {
    val on = state.group == g
    val ink = if (on) XrColors.OnAccent else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = { onEvent(XrEvent.GroupClicked(g)) },
        modifier = Modifier.weight(1f).clip(CircleShape).spatialHoverEffect().probe("group_" + g.key),
        size = ButtonDefaults.Regular.copy(height = 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (on) XrColors.group(g) else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) { Text(text = rs(label), color = ink, style = XrType.segment, maxLines = 1, softWrap = false) }
}

@Composable
private fun StatusLine(state: XrUiState) {
    val text =
        when {
            state.placing -> rs(R.string.status_placing)
            state.source == Source.DEMO && !state.hasMarker && state.mode == Mode.EXPLORE -> rs(R.string.status_no_marker)
            state.source == Source.DEMO -> rs(R.string.status_demo)
            state.leftTracked && state.rightTracked -> rs(R.string.status_both)
            state.rightTracked -> rs(R.string.status_right_only)
            state.leftTracked -> rs(R.string.status_left_only)
            else -> rs(R.string.status_none)
        }
    // two lines: the marker hints must not lose their gaze + pinch half (QA #2 q2-2, D16 no truncation)
    Text(text = text, color = PicoTheme.colorScheme.labelSecondary, style = XrType.status, maxLines = 3, modifier = Modifier.probe("status"), onTextLayout = fits("main/status"))
}

@Composable
private fun ColumnScope.Seek(onEvent: (XrEvent) -> Unit) {
    Spacer(modifier = Modifier.weight(1f))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(140.dp).clip(CircleShape).background(PicoTheme.colorScheme.fillTertiary), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(R.drawable.ic_hand_outline), contentDescription = null, modifier = Modifier.size(96.dp), tint = PicoTheme.colorScheme.labelPrimary)
        }
    }
    Text(text = rs(R.string.seek_title), color = PicoTheme.colorScheme.labelPrimary, style = XrType.big, maxLines = 3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().probe("seek_title"), onTextLayout = fits("main/seek_title"))
    Spacer(modifier = Modifier.weight(1f))
    Secondary("seek_demo", rs(R.string.use_demo)) { onEvent(XrEvent.UseDemo) }
}

@Composable
private fun ColumnScope.Unsupported(state: XrUiState, onEvent: (XrEvent) -> Unit) {
    Spacer(modifier = Modifier.weight(1f))
    HeroIcon(R.drawable.ic_hand_outline)
    Text(text = rs(R.string.unsupported_title), color = PicoTheme.colorScheme.labelPrimary, style = XrType.big, maxLines = 3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().probe("unsupported_title"), onTextLayout = fits("main/unsupported_title"))
    Text(text = rs(R.string.unsupported_reason, state.unsupported ?: "-"), color = PicoTheme.colorScheme.labelSecondary, style = XrType.status, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), onTextLayout = fits("main/unsupported_reason"))
    Spacer(modifier = Modifier.weight(1f))
    Primary("unsupported_demo", rs(R.string.use_demo)) { onEvent(XrEvent.UseDemo) }
    Secondary("unsupported_retry", rs(R.string.retry)) { onEvent(XrEvent.RetrySupport) }
}

@Composable
private fun ColumnScope.Follow(state: XrUiState, onEvent: (XrEvent) -> Unit) {
    val step = state.follow ?: 1
    Spacer(modifier = Modifier.weight(1f))
    HeroIcon(R.drawable.ic_hand_outline)
    Text(text = rs(R.string.follow_step, step), color = PicoTheme.colorScheme.labelSecondary, style = XrType.label, maxLines = 1)
    Text(
        text = rs(when (step) { 1 -> R.string.follow_1; 2 -> R.string.follow_2; else -> R.string.follow_3 }),
        color = PicoTheme.colorScheme.labelPrimary,
        style = XrType.big,
        maxLines = 2,
        modifier = Modifier.probe("follow_title"),
    )
    Text(text = rs(R.string.follow_hint), color = PicoTheme.colorScheme.labelSecondary, style = XrType.status, maxLines = 2)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.weight(1f)) { Secondary("follow_end", rs(R.string.end)) { onEvent(XrEvent.FollowEnd) } }
        Box(modifier = Modifier.weight(1f)) { Primary("follow_next", rs(R.string.next)) { onEvent(XrEvent.FollowNext) } }
    }
    StatusLine(state)
}

// ---- quiz ------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Quiz(state: XrUiState, catalog: BoneCatalog, onEvent: (XrEvent) -> Unit) {
    val q = state.quiz
    val ink = PicoTheme.colorScheme.labelPrimary
    val grey = PicoTheme.colorScheme.labelSecondary
    if (q.paused) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = rs(R.string.paused), color = ink, style = XrType.big, maxLines = 2, modifier = Modifier.probe("paused"))
        Text(text = "${q.index + 1} / ${QuizMaker.COUNT}", color = grey, style = XrType.label)
        Spacer(modifier = Modifier.weight(1f))
        Secondary("continue_demo", rs(R.string.continue_demo)) { onEvent(XrEvent.UseDemo) }
        return
    }
    when (q.phase) {
        QuizPhase.INTRO -> {
            Spacer(modifier = Modifier.weight(1f))
            HeroIcon(R.drawable.ic_quiz)
            Text(text = rs(R.string.quiz_intro), color = ink, style = XrType.big, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().probe("quiz_intro"), onTextLayout = fits("quiz/intro"))
            Text(text = rs(R.string.quiz_rules), color = grey, style = XrType.status, maxLines = 3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), onTextLayout = fits("quiz/rules"))
            Text(text = bestLine(state), color = grey, style = XrType.hint, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), onTextLayout = fits("quiz/best"))
            Spacer(modifier = Modifier.weight(1f))
            Primary("quiz_start", rs(R.string.start)) { onEvent(XrEvent.QuizStart) }
        }
        QuizPhase.ASKING, QuizPhase.FEEDBACK -> {
            val cur = q.current ?: return
            val bone = catalog[cur.boneId] ?: return
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "${q.index + 1} / ${QuizMaker.COUNT}", color = grey, style = XrType.label, maxLines = 1, modifier = Modifier.weight(1f).probe("quiz_progress"))
                // D44: a new round mid-quiz asks first (the confirm below)
                if (!state.confirmRestart) Box(modifier = Modifier.width(150.dp)) { Secondary("quiz_restart", rs(R.string.quiz_restart)) { onEvent(XrEvent.QuizStart) } }
            }
            if (state.confirmRestart) RestartConfirm(onEvent)
            Text(
                text = if (cur.kind == QuestionKind.LOCATE) rs(R.string.locate, boneNameLine(bone)) else rs(R.string.identify),
                color = ink,
                style = XrType.big,
                maxLines = 3,
                modifier = Modifier.probe("quiz_question"),
                onTextLayout = fits("quiz/question"),
            )
            if (cur.kind == QuestionKind.LOCATE) {
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(16.dp).probe("locate_card"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Pill(groupName(bone.group), XrColors.group(bone.group))
                    Text(text = boneNameLine(bone), color = ink, style = XrType.score, maxLines = 2, textAlign = TextAlign.Center, onTextLayout = fits("quiz/locate_name"))
                    Text(text = rs(R.string.locate_hint), color = grey, style = XrType.status, maxLines = 2, textAlign = TextAlign.Center, onTextLayout = fits("quiz/locate_hint"))
                }
            }
            if (cur.kind == QuestionKind.IDENTIFY) {
                Spacer(modifier = Modifier.weight(1f))
                cur.options.forEach { id ->
                    val b = catalog[id] ?: return@forEach
                    val last = q.last
                    val mark =
                        when {
                            last == null -> null
                            id == last.boneId -> XrColors.Passable
                            id == last.picked -> XrColors.Error
                            else -> null
                        }
                    Option("option_$id", boneNameLine(b), mark, enabled = q.phase == QuizPhase.ASKING) { onEvent(XrEvent.QuizOption(id)) }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            val last = q.last
            if (q.phase == QuizPhase.FEEDBACK && last != null) {
                val right = catalog[last.boneId]!!
                // a filled pill with dark ink: green / red text on the light glass is only 1.3 / 2.0 : 1 (profile D13)
                Box(
                    modifier = Modifier.clip(CircleShape).background(if (last.correct) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.error).padding(horizontal = 14.dp, vertical = 6.dp).probe("quiz_feedback"),
                ) {
                    Text(text = if (last.correct) rs(R.string.right_answer) else rs(R.string.wrong_answer, boneNameLine(right)), color = XrColors.OnAccent, style = XrType.title, maxLines = 2, onTextLayout = fits("quiz/feedback"))
                }
                if (!last.correct) Text(text = boneFact(right), color = grey, style = XrType.status, maxLines = 3, onTextLayout = fits("quiz/feedback_fact"))
            } else {
                StatusLine(state)
            }
        }
        QuizPhase.RESULT -> {
            Spacer(modifier = Modifier.weight(1f))
            HeroIcon(R.drawable.ic_quiz)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(text = "${q.score} / ${QuizMaker.COUNT}", color = ink, style = XrType.score, maxLines = 1, modifier = Modifier.probe("quiz_score"))
            }
            Text(text = bestLine(state), color = grey, style = XrType.hint, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), onTextLayout = fits("quiz/result_best"))
            if (q.wrong.isEmpty()) Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pill(rs(R.string.all_right), PicoTheme.colorScheme.passable) }
            Spacer(modifier = Modifier.weight(1f))
            Primary("quiz_again", rs(R.string.again)) { onEvent(XrEvent.QuizStart) }
            if (q.wrong.isNotEmpty()) Secondary("quiz_review", rs(R.string.review_wrong)) { onEvent(XrEvent.ReviewWrong) }
        }
        QuizPhase.REVIEW -> {
            val a = q.wrong.getOrNull(q.reviewIndex) ?: return
            val b = catalog[a.boneId] ?: return
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 16.dp, vertical = 14.dp).probe("review_card"),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Text(text = rs(R.string.review_n, q.reviewIndex + 1, q.wrong.size), color = grey, style = XrType.label, maxLines = 1)
                Pill(groupName(b.group), XrColors.group(b.group))
                Text(text = boneNameLine(b), color = ink, style = XrType.score, maxLines = 2, modifier = Modifier.probe("review_name"), onTextLayout = fits("quiz/review_name"))
                Text(text = boneFact(b), color = grey, style = XrType.hint, maxLines = 5, onTextLayout = fits("quiz/review_fact"))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) { Secondary("review_back", rs(R.string.back_to_result)) { onEvent(XrEvent.BackToResult) } }
                Box(modifier = Modifier.weight(1f)) { Primary("review_next", rs(R.string.next)) { onEvent(XrEvent.ReviewNext) } }
            }
        }
    }
}

/** A large round icon that heads a short page (keeps the page's empty bands small, D44). */
@Composable
private fun HeroIcon(icon: Int) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(PicoTheme.colorScheme.fillTertiary), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(80.dp), tint = PicoTheme.colorScheme.labelPrimary)
        }
    }
}

/** "最好 8 / 10 · 最近 6 7 8" (the last five scores are kept in prefs). */
@Composable
private fun bestLine(state: XrUiState): String {
    val best = state.prefs.bestScore?.let { rs(R.string.best, it) } ?: rs(R.string.best_none)
    val recent = state.prefs.history
    return if (recent.isEmpty()) best else best + " · " + rs(R.string.recent, recent.joinToString("  "))
}

/** "放弃这一轮，重新出题？" in the page (no second panel, D8). */
@Composable
private fun RestartConfirm(onEvent: (XrEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(12.dp).probe("restart_confirm"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = rs(R.string.restart_confirm), color = PicoTheme.colorScheme.labelPrimary, style = XrType.status, maxLines = 2, onTextLayout = fits("quiz/restart_confirm"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) { Secondary("restart_keep", rs(R.string.restart_keep)) { onEvent(XrEvent.QuizRestartCancel) } }
            Box(modifier = Modifier.weight(1f)) { Primary("restart_yes", rs(R.string.restart_yes)) { onEvent(XrEvent.QuizRestartConfirm) } }
        }
    }
}

@Composable
private fun Option(name: String, text: String, mark: Color?, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (mark != null) XrColors.OnAccent else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().clip(CircleShape).spatialHoverEffect().probe(name),
        size = ButtonDefaults.Regular.copy(height = 48.dp),
        shape = CircleShape,
        enabled = true, // answers outside ASKING are ignored by the ViewModel; a disabled look is grey on grey (profile D13)
        colors = ButtonDefaults.buttonColors(containerColor = mark ?: PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) { Text(text = text, color = ink, maxLines = 2, textAlign = TextAlign.Center, onTextLayout = fits("quiz/$name")) }
}

// ---- shared controls -------------------------------------------------------------------------

@Composable
internal fun Primary(name: String, text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().clip(CircleShape).spatialHoverEffect().probe(name),
        size = ButtonDefaults.Regular.copy(height = RowH),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = XrColors.OnAccent),
    ) { Text(text = text, color = XrColors.OnAccent, maxLines = 1, onTextLayout = fits("key/$name")) }
}

@Composable
internal fun Secondary(name: String, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().clip(CircleShape).spatialHoverEffect().probe(name),
        size = ButtonDefaults.Regular.copy(height = RowH),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) { Text(text = text, color = ink, maxLines = 1, onTextLayout = fits("key/$name")) }
}

@Composable
internal fun Key(name: String, icon: Int, description: String, accent: Boolean = false, width: Dp = 48.dp, onClick: () -> Unit) {
    val ink = if (accent) XrColors.OnAccent else PicoTheme.colorScheme.labelPrimary
    IconButton(
        onClick = onClick,
        modifier = Modifier.clip(CircleShape).spatialHoverEffect().probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (accent) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        size = IconButtonDefaults.iconButtonSize(width = width, height = 48.dp),
    ) { Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp), tint = ink) }
}

@Composable
internal fun <T> Segments(items: List<T>, selected: T, width: Dp, name: (T) -> String, onSelect: (T) -> Unit, title: @Composable (T) -> Unit) {
    val itemW = (width - 8.dp) / items.size
    SegmentControl(modifier = Modifier.width(width).height(48.dp).alpha(1f), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEach { item ->
            SegmentItem(
                selected = item == selected,
                onClick = { onSelect(item) },
                modifier = Modifier.width(itemW).fillMaxHeight().clip(CircleShape).spatialHoverEffect().probe(name(item)),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { title(item) },
            )
        }
    }
}
