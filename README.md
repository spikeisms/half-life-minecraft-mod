# Half-Life Character Mod
**For Minecraft 1.23.6**

HLCM (Half-Life Character Mod) adds a toggle to the game that lets any player turn into a Half-Life character! The movement code from Half-Life 1 is translated over as accurately as possible, along with multiple playermodels, a fully translated in-game HUD, and plenty of server CVars that let you change how it all feels! This mod should be fully compatible with other mods: but for other character-changing gameplay mods, general discretion is advised: be smart, don't try to stack player mods. You can't be Mario and Gordon simultaneously. Don't be greedy.

## Half-Life mode

Press **G** (or use the switch in the settings menu, **H**) to go in or out. It's your own choice, remembered
between sessions (`hlMode` in your config), and you start in it by default. Your client tells the server,
and the server tells everyone else, so all sides agree on who has the Half-Life duck hitbox, fall damage,
sounds and model.

Out of the mode you are plain Minecraft in every respect: movement, sprinting, sneaking, creative and
spectator flight, sounds, HUD and skin. Everything below describes players who are in it.

## What's ported

| Half-Life | Behaviour |
|---|---|
| `PM_Accelerate` / `PM_Friction` | Ground accel `sv_accelerate 10`, friction `sv_friction 4`, `sv_stopspeed 100`, run speed `sv_maxspeed 320` |
| `sv_edgefriction 2` | Double friction when about to run off a drop (same hull trace as HL) |
| `PM_AirAccelerate` | `sv_airaccelerate 10` with the 30 u/s wishspeed cap → air-strafing |
| `PM_Jump` | 45-unit jump (268 u/s), no friction on the landing frame → bunnyhopping. Jump must be re-pressed (no pogo) |
| `PM_PreventMegaBunnyJumping` | Jumping above 1.7× maxspeed scales you back down (toggle: `bunnyhopCap`) |
| `PM_CheckParamters` + `PM_Duck` | Move command clamped to maxspeed, *then* ×0.333 when ducked → 106.6 u/s |
| `PM_Duck` / `PM_UnDuck` | Ducking takes 0.4 s on the ground, with the view sinking smoothly from the moment the key goes down; instant in the air, where the hull shrinks around its center (crouch-jump: 63 units). Half-height duck hull (0.9 blocks) fits through 1-block gaps |
| `+speed` | Walk key (default **Left Alt**) ×0.3 |
| `PM_LadderMove` | View-relative climbing at 200 u/s, look down to descend, jump pushes you off |
| `PM_WaterMove` / `PM_CheckWaterJump` | 3D swimming, slow sink, jump swims up, auto-hop out at ledges |
| `PM_NoClip` | **Creative flight**: flies along your view at 320 u/s, through walls, no gravity, stops dead when you let go. Double-tap jump to toggle; jump/sneak move up/down |
| `PM_SpectatorMove` | **Spectator mode**: accelerates up to `sv_spectatormaxspeed` 500 (400 u/s with one key held), through walls, stops dead when you let go. The scroll wheel still scales the speed |
| Gravity | `sv_gravity 800`, split half before/half after the move like HL |
| Fall damage | Safe below 580 u/s impact, lethal at 1024 u/s (HL formula, scaled to 20 HP) |

Sprinting and auto-jump don't exist. Half-Life has no equivalent of elytra gliding, riding, sleeping,
riptide or levitation (while walking), so those keep Minecraft's movement.

**Scale:** 40 units = 1 block (HL's 72-unit player hull = MC's 1.8-block player). Each 50 ms tick is
simulated in 5 sub-steps (100 fps) with view yaw interpolated between them, so air-strafing responds
to mouse movement like it does in GoldSrc.

## Sounds

Mono Ogg Vorbis conversions in `src/client/resources/assets/hlmovement/sounds`, from `Soundbase/player`
and the Half-Life install (`common/bodysplat`).

- **Footsteps** (`PM_UpdateStepSound`/`PM_PlayStepSound`): 300 ms running, 400 ms walking, +100 ms
  ducked or on ladders, alternating feet, HL volumes (35% when ducked), a step on every jump and a landing
  step (`PM_CheckFalling`), plus `pl_fallpain3` on damaging landings and `bodysplat` on fatal ones.
- **Materials** from the block you stand on: concrete, metal, dirt, vent (hoppers, cauldrons, metal
  trapdoors), grate (iron bars, copper grates), tile, wood, snow, slosh (slime, honey, mud); slosh/wade in
  shallow water, ladder sounds when climbing.
- **Water** (`PM_PlayWaterSounds`, `PM_Jump`, `CBasePlayer::WaterMove`): `pl_wade` entering/leaving
  water and when swimming up (max once a second), `pl_swim` bubbles that get more frequent as your air
  runs out, and a gasp when surfacing after a long time under.
- **Hurt / death** (`CBasePlayer::Pain` / `DeathSound`): `pl_pain5-7`; deaths are silent 2 times in 5, like HL.

Each client plays its own player's sounds immediately; the server runs the same code for everyone and
sends the results to the other players. Not used by Half-Life, so not wired up: `pl_pain2`, `pl_pain4`,
`pl_fallpain1-2`, `pl_jump1-2`, `pl_jumpland2`, `pl_step4b`. (`pl_wood*`/`pl_snow*` aren't from
Half-Life 1 but are used for wood and snow blocks.)

## HUD

While you're in Half-Life mode, Minecraft's hotbar, hearts, armor, hunger bar and crosshair are replaced with Half-Life's HUD, ported
from the SDK's `cl_dll` (`health.cpp`, `battery.cpp`, `ammo.cpp`, `hud_redraw.cpp`), blended additively in
HL amber (255,160,0). It follows Minecraft's GUI scale (health digits are about 10 GUI pixels tall), using
whichever of the 25th anniversary update's 640/1280/2560 sprite sets is sharpest at that size.
`hudScale` in your config (personal, not taken from the server) makes it bigger or smaller on top of that.

- **Health** (bottom-left): hearts ×5 (20 HP = 100), flashes when it changes, red at 25 and below.
- **HEV suit**: armor points ×5, with the suit icon filling up.
- **Hunger**: food ×5, laid out like health, with a drumstick drawn in the style of the health cross
  (HL has no hunger icon). Bucket labels 6–9 are also generated, in HL's 5×5 digit font on the "1" box.
- **Weapon selection** (top-left) is the hotbar: nine numbered buckets, the selected one widened with
  the selection frame, Minecraft item icons in full colour, and HL's green bars for stack size or
  durability. Fast switch: number keys and the scroll wheel switch immediately and the menu shows for
  2 seconds, with HL's `wpn_hudon`/`wpn_moveselect` sounds. (Real `hud_fastswitch 1` shows no menu at
  all for single-item buckets; this keeps it visible.)
- **Ammo** (bottom-right): a stack in hand reads like a clip ("in hand | rest of inventory"); a bow or
  crossbow shows its arrows; the offhand is the secondary ammo line; tools show nothing (like the crowbar).
- **Damage**: HL's red arcs showing the direction a hit came from, and the flashing damage icons up the
  left side (drowning, fire, freezing, lightning, poison, wither, dragon's breath, suffocation).
- **Crosshair**: the 9mm handgun's, always `crosshairSize` screen pixels (default 32) whatever the window
  size or GUI scale. The visible marks fill about half of that.

**Settings** (default key **H**): the Half-Life mode switch, your character, HUD scale, crosshair size and the speedometer,
with the HUD and crosshair left visible around the menu so changes preview live. Saved to your config when
it closes.

The XP bar, air bubbles, potion effects, boss bars, chat and so on are Minecraft's. HUD sprites live in
`src/client/resources/assets/hlmovement/textures/hud` with their layout in `hud.json`; regenerate them from
a Half-Life install with `java tools/SprConvert.java "<Half-Life>/valve" src/client/resources/assets/hlmovement`.

## Player model

Every player in Half-Life mode is drawn as a Half-Life character instead of their skin: the original
**scientist**, **Gordon** or **Barney**, chosen with the Character button in the settings menu (**H**). The choice
is personal (`character` in your config), remembered between sessions, and sent through the server so
everyone sees it. Each model is scaled to exactly the 1.8-block hitbox and animated with the deathmatch
player animations the way Half-Life's player code does it (`CBasePlayer::SetAnimation`, `StudioProcessGait`):

- The body plays `idle` (one arm held out), or `crouch_idle` while ducked (1.05 blocks, near the 0.9-block
  duck hull).
- While moving, the pelvis and legs are taken over by a gait sequence: `run2` above 220 u/s, `walk2handed`
  below (the walk key), `crawl` while ducked. The gait advances with distance covered, not time, so the
  feet don't slide.
- The legs face the way you're moving (`StudioEstimateGait`) and run backwards when that's more than 120°
  from where you're looking, so strafing and backing up look right. The spine twists the torso and head back
  round to the view, a quarter per bone like Half-Life's four spine controllers. Standing still, the legs
  follow once the torso is twisted 60°.
- Looking up and down bends the spine, neck and head (Half-Life leans the whole model by a third instead).
- Held items are drawn in his hands (main hand and offhand) with Minecraft's own item rendering, and an
  attack swings that arm from the shoulder.
- Jumping plays `jump` once on the whole body and holds the end until landing (walking off a ledge doesn't
  count; a bunnyhop restarts it). It's triggered by starting to rise in mid-air, which works the same for
  other players as for yourself.
- The crouch animation starts as the duck key goes down, with the view, not 0.4 s later with the hitbox.
- Changes cross-fade over 0.2 s.

The scientist NPC has no player animations, so the converter borrows them from the multiplayer scientist
(`models/player/scientist`): both are 3ds Max bipeds, so each NPC bone takes the matching player bone's
orientation while keeping its own length. Swimming, climbing and creative flight reuse the standing
animations for now. Armor, capes and other things that hang off Steve's limbs aren't drawn on him; held
items and name tags are.

The model is converted from a Half-Life install with `tools/MdlConvert.java` (GoldSrc MDL v10: skeleton,
mesh, palette textures as PNGs, and each sequence as per-frame bone rotations). With `$dm` as
`<Half-Life>/valve/models/player/scientist/scientist.mdl`:
`java tools/MdlConvert.java "<Half-Life>/valve/models/scientist.mdl" src/client/resources/assets/hlmovement scientist 1.8 0,0,0 "idle=$dm#idle" "walk=$dm#walk2handed" "run=$dm#run2" "jump=$dm#jump" "crouch_idle=$dm#crouch_idle" "crawl=$dm#crawl"`
Gordon and Barney are the deathmatch player models, which carry these animations themselves (submodel 1 is
the high-detail mesh); with `<name>` as `gordon` or `barney`:
`java tools/MdlConvert.java "<Half-Life>/valve/models/player/<name>/<name>.mdl" src/client/resources/assets/hlmovement <name> 1.8 1 idle=idle walk=walk2handed run=run2 jump=jump crouch_idle=crouch_idle crawl=crawl`
Adding a character is converting its model and adding its name to `HLMode.CHARACTERS` and the language file.
(`--list` instead of the output arguments prints a model's bones, bodyparts, textures and sequences). The
`0,0,0` picks a submodel per bodypart: here the "nerd" head and no needle. Each `name=sequence` is one
animation the mod looks up by name; `other.mdl#sequence` borrows it from another biped model.

## Controls

- **G** – Half-Life mode on/off
- **Left Alt** – walk
- **H** – settings
- Speedometer toggle – unbound by default (Options → Controls → Half-Life Movement)

## Config

The **server's** `config/hlmovement.properties` holds every cvar above plus behaviour switches
(`autoBhop`, `bunnyhopCap`, `airDuckTuck`, `hlDuckHull`, `stepDown`, `blockFriction`,
`disableSneakEdgeSafety`, `multiplayerFootsteps`, `spectatorMaxSpeed`, `substeps`, `unitsPerBlock`, ...).
It's sent to every client on join, so everyone in Half-Life mode simulates identical physics. A client's own
file only matters in singleplayer, except the personal settings, which are never taken from the server:
`hlMode`, `character`, `hudScale`, `crosshairSize` and `showSpeedometer`.

Movement itself is simulated by each player's own client (Minecraft trusts the client for player
movement), with the server checking the result as usual.

## Testing

`gradlew runClientGameTest` opens the game, builds a small course in a fresh world and checks the
numbers against Half-Life (run 320 u/s, duck 106.6 u/s, jump 45 units, duck-jump 63, climb 200 u/s,
noclip 320 u/s through a wall, spectator 400 u/s, duck hull and noclip agreed by client and server,
sounds), then switches Half-Life mode off and checks that movement, sneaking, footsteps, HUD and model are
Minecraft's again on both sides, and back on. Results are logged with the prefix `[HL TEST]`.

## Showcase recording

`gradlew runShowcase` plays a scripted demo on a small stage in a fresh world (`Showcase.java`): the same moves
every time, so a take can be reshot exactly. Each segment logs `[SHOWCASE] <name> <wall-clock ms>` for cutting.
It waits 8 seconds after `[SHOWCASE] ready` for a recorder to start. The bunnyhop segment turns on `autoBhop`
for its duration, because a script ticking 20 times a second can't time jumps to the landing frame.
