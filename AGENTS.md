# X-ray Hand (X 光手)

PICO OS 6 app, Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space: hand tracking only exists there),
package `io.embodit.xrayhand`, v0.1.3. Raise your hands: the 27 real bones of a human hand (NIH 3D 3DPX-016815,
CT segmentation by My Segmenter, CC BY 4.0) sit 1:1 inside your own hands and move with them; look at one and
pinch, it is named (card + 3D label + text-to-speech); group focus with labels; follow-me; a 10-question quiz.
Without hand tracking (emulator, controllers, not supported) a demo hand of the same bones does everything but
follow-me.

Design package (binding): `../spatial-app-factory/apps/xray-hand/design-package.json` (states S00 - S18,
interactions T1 - T17, `bones.asset.runtimeAlign`), sample `../spatial-app-factory/apps/xray-hand/sample.html`.
Bone assets and their pipeline: `../spatial-app-factory/apps/xray-hand/assets/` (+ `assets-src/`, Blender).
Skeleton D, scaffolding from Light Planner (`../light-planner`: panel pool, Stage root, theme, fonts, debug receiver,
thumbstick); hand provider use from Spatial Ruler / Access Check.

## Structure

```
app/src/main/assets/        hand_bones_right.glb, hand_bones_left.glb, hand_rig.json (copied from the factory assets),
                            bones.json (tools/make_bones_json.py: names, groups, facts + mesh bounds per bone)
app/src/main/java/io/embodit/xrayhand/
  domain/                   pure Kotlin, unit-tested (app/src/test)
    Bones.kt                Bone, Group, BoneCatalog (bones.json)
    HandRig.kt              Joint (26, SDK order), HandJoints, RigBone, Mat3, HandRig (hand_rig.json, rest joints)
    BoneSolver.kt           joints -> 27 BonePose (node centre, axes, mesh offset / scale, collider radius)
    DemoPoses.kt            open / fist / pinch (opposition) / flip as joint angles on the CT rest hand; HandJoints.placed
    Quiz.kt                 QuizMaker (4 + 3 + 3 bones, 6 locate + 4 identify, seedable)
    Vec3.kt Geometry.kt Billboard.kt   maths, HeadPose, PanelRest, yaw-only label facing
  platform/                 XrLog, Speaker (TextToSpeech), Prefs (SharedPreferences) + XrGraph (services),
                            tracking/HandTracker (HandTrackingProvider; Device.onEmulator), HeadTracker, ControllerStick
  ui/hand/                  XrUiState (+ designState S00..S18), XrEvent, XrViewModel (the state machine), XrScreen
  ui/hand/scene/            XrScene (one ECS System: hands, looks, labels, demo hand, panels, taps, drags, spike logs),
                            HandView (27 bone nodes per hand), SceneKit (materials, label plates, lead lines), PanelRig
  ui/hand/components/       MainPanel, SettingsPanel, XrStage (SpatialView + 2 pooled panels), Strings, GlassRefresh
  ui/theme/                 XrayTheme (PicoTheme + 4 roles + Noto Sans / Noto Sans SC subset), XrColors, XrType
app/src/debug/              DebugCommandReceiver + DebugCommands
tools/                      dev.sh, shot.sh, turn.sh, harness/ (emulator input), make_icon.py, make_bones_json.py,
                            subset_cjk_font.py (NOTO_SC_VF=<google/fonts NotoSansSC[wght].ttf>)
```

Data flows one way: panel controls and scene taps -> `XrViewModel.onEvent` -> `XrUiState` -> `XrScene.render` and
the panels. Joints never go through Compose: `XrScene.onFrame` reads `HandTrackingProvider.latestData` (or the
injected / demo joints), solves and writes the 54 bone transforms in the same frame. The scene only reports rare
changes back (tracked hands after a 1 s debounce, support state, a tapped bone).

## How the bones sit on the hand (design `bones.asset.runtimeAlign`)

- Hand frame from joints: O = MIDDLE_METACARPAL, Y -> MIDDLE_PROXIMAL, Z = h * normalize(across x Y) with across =
  INDEX_METACARPAL - LITTLE_METACARPAL (h = +1 right, -1 left: palmar for both), X = Y x Z. The same formula on the rig's
  rest joints gives the rig frame; the rig is carried over by R = F_tracked * F_rig^T. (The design's
  (INDEX - O) x (LITTLE - O) is ill-conditioned: those three joints are nearly on a line.)
- Hand size s = |MIDDLE_PROXIMAL - MIDDLE_METACARPAL| / 0.0636 m, the rig's own joint-centre length (the CT leaves
  2 - 6 mm of joint space between bones; joints are the middle of that gap). The rig's `modelHandLengthM` (0.0607) is
  the bare metacarpal and would make every hand 4.5 % too big.
- Long bones: node at the middle of its two joints, +Y along them, +Z = hand Z made square; mesh child at
  (0, -len/2, 0), scale (s, len / L0, s). Distal phalanges stop 3 mm short of the TIP joint on real hands.
- Carpals: CT rest pose in the hand frame, scaled by s; carpal zoom = 1.8x spread from their centre, 1.5x size.
- Left hand: `hand_bones_left.glb`. Its export baked each mesh as the point mirror (-v) of the right one in the node's
  frame, so the mesh child gets a 180 degree turn about X: the result is the X-mirror of the right bone in a proper
  rotation. Verified: `dump bones` mirrors exactly, pisiform and hamate palmar on both hands (unit tests + emulator).

## Runtime facts (emulator, SDK 6.1.9, 2026-10-27)

- Loading the right GLB takes 4 - 11 s, the left 2 - 10 s at the idle emulator's 1 fps; the demo hand is a clone of the
  right template (40 ms). `SCENE built` comes 8 - 37 s after launch: **wait for it** (`tools/dev.sh waitready`) before
  sending debug commands, or `SceneReady` overrides them (it sets the source).
- The hand provider is never started on the emulator (Spatial Ruler: it broke gaze + pinch until a restart); the emulator
  runs on the demo hand. `handSource live` starts it anyway (avoid).
- Gaze + pinch taps on the demo bones arrive as `GazePinch` with the bone node as target (`TAP raw kind=GazePinch
  target=bone:demo:mc3`). The harness clicks in 2160-px framebuffer coordinates while the HD screenshots are 2560 px:
  divide screenshot pixels by 1.185.
- No TTS engine on the emulator (`TTS init status=-1`): `SPEAK` lines are logged, nothing is heard.
- `LIMBS set Hidden result=...Success` then `LIMBS mode=Hidden` (manifest `pico.spatial.stage.upperlimb` = 2 as well).
- Stage root sits 0.256 m off in z (as in every sibling); panel pool, stow, glass refresh as Light Planner.

## Debug commands (debug builds)

```
adb -s emulator-5570 shell "am broadcast -a io.embodit.xrayhand.DEBUG -p io.embodit.xrayhand --es cmd '<command>'"
tools/dev.sh cmd '<command>'          (prints the CMD reply and the last STATE line)
tools/shot.sh NAME '<command>' ...    (commands, settle, console screenshot -> .scratch/shots/NAME.png)
```

`state [Sxx]` read / jump · `handSource live|demo|injected` · `pose <l>/<r>` (open|fist|pinch|flip|none, injected hands
0.38 m ahead, palms to you) · `demopose open|fist|pinch|flip` · `select <bone> [demo|left|right]` · `tap <bone> <kind>`
(GazePinch, RayBasedPinch, Pointer, DirectPinch, Poke) · `selfpinch <0-1>` · `group carpal|metacarpal|phalanx|none` ·
`zoom on|off` · `follow 1-3|off` · `mode explore|quiz` · `quiz seed <n> [identify]` · `answer right|wrong` ·
`quiz finish <score>` · `feedback hold|auto` · `next` · `review` · `settings open|close` · `lang zh|en|both` ·
`look ivory|xray` · `mute on|off` · `clear` · `menu` · `support unsupported|<STATE>|ok` · `stick <x> [ms]` ·
`drag <dx> <dy> <dz>` · `marker <x> <z>` · `placing` · `bring` · `recenter` · `welcome reset` · `dump bones` · `dump pose <name>` · `panels` ·
`labels` · `headpose` (includes demo hand centre and yaw) · `bounds`.

Log prefixes (tag `XrayHand`): STATE, CMD, TAP (raw / ignored kind= / ignored self-pinch / kind=...ignored=no), SPEAK,
QUIZ, HANDS, TRACK, LIMBS, MODEL, DEMO, DRAG, STICK, FOLLOW, BONES, POSE, JITTER, FRAME (bones_us = solve + write time).

## Screenshot list (QA, design `states[].qaCapture`)

S00 launch + 1 s · S01 `welcome reset` then relaunch (or `state S01`) · S02 `state S02` (+2 s) · S03 `state S03` (+2 s) ·
S04 harness `move` onto a demo bone (system hover) · S05 `state S05` · S06 `state S06` · S07 `state S07` (+2 s) ·
S08 `state S08` · S09 `state S09` (+2 s) · S10 `state S10` · S11 `state S11` · S12 `state S12` · S13 `state S13`
(feedback held; `feedback auto` after) · S14 `state S14` · S15 `state S15` · S16 `state S16`, then `lang en` ·
S17 `state S17` (+5 s: hands seen, then lost) · S18 `state S18` (`support ok` after). Demo poses: `demopose fist|pinch|flip`.
Turning: `tools/turn.sh 90 0` (camera rotation button on); the demo hand stays at its anchor. Current evidence:
`../spatial-app-factory/apps/xray-hand/build/` (first-screen, launcher, states/).

## Where the demo hand stands (v0.1.1, taste profile D1 / D14 / D20)

- Never from the head pose. "Place marker" (panel row / ⋯) turns on a see-through floor collider; a controller trigger
  (ray ∩ floor, a ghost ring follows the ray) or a gaze + pinch on the floor puts the cyan marker ring there
  (`MARKER at= via=ray|tap`). "Move to marker" puts the hand 1.05 m above it, palm turned to the user (yaw only).
- Before any marker the hand parks beside and beyond the main panel: the panel's bearing turned 55 degrees, 0.5 m
  farther than the panel (`DEMO anchor why=park`), and the status line asks for a marker.
- The anchor (`SessionAnchor`, process-wide) holds for the whole session: poses, quiz, follow-me (S09 uses it too),
  background, Stage re-entry. Only a new marker + "Move to marker", a drag, or the stick (turn only) change it.
- Every frame `stepFade` checks the eye -> panel cone (main panel and settings popup): while the hand stands between
  the eyes and a panel it is drawn at 30 % (`DEMO fade=0.3`). D14 still pushes it out to 0.6 m from the head.
- Grep gate and the reason for every head-pose use: `../spatial-app-factory/apps/xray-hand/build/ui-audit.md` (top).
- Debug: `marker <x> <z>` (as if the floor were pinched there), `placing`, `bring`; `front` is gone.

## Panel and eye + hand (v0.1.3, taste profile D21 / D22)

- The main panel is put down once (24 degrees left, 15 down, 0.8 m, facing the user) and then stays locked in the room:
  no lazy follow, no per-frame turning (`PanelRig.setViewer` only records the viewer; a panel turns to the user
  when it is placed, dragged or called back). A white drag bar under the panel (`panel:handle`, its own collider +
  hover) moves it with a system drag (`PANEL drag start/end`). The ⋯ menu "Bring the panel in front of me" re-places it
  straight ahead, 15 degrees down, 0.9 m (`PANEL main recalled`). Debug: `recall`, `paneldrag dx dy dz`.
- Eye + hand: panel controls are clicked by the system's gaze + pinch (controller ray when there is no eye tracking);
  every control carries `spatialHoverEffect`. Real-hand bones that stand between the eyes and a shown panel take no
  pinches (`HAND left yield=n`), so looking at a control behind your own hand and pinching clicks the control; a pinch
  while looking at a bone names the bone; a pinch while looking at nothing does nothing.
- Emulator: `tools/harness/emu_mode.ps1 -Mode eye -Avd PICO_6_1_Factory4 -Serial emulator-5566` switches to Eye Gesture
  Mode, but can leave its drop-down menu open; then `emu.sh fast on` never reaches 50 fps and every click is refused.
  Check `emu.sh wins | grep Popup` and post Esc to that window (or invoke the mode again).

## Rules kept

SpatialUI + PicoTheme only (Material grep 0 hits); 3D in ECS, joints never through Compose; every start has a stop
(`XrScene.detach` / `pause`); no head-forward ray anywhere (taps come from the system: gaze + pinch, hand ray, controller
ray); demo hand at the user's marker, fixed for the session, faded inside the eye -> panel cone, >= 0.6 m from the head horizontally (drag is pushed out), stick turns it about the vertical
(dead zone 0.15, 120 deg/s); one speaker switch in the title bar mutes names and sounds (saved).
