# Neo Launcher — kontext projektu

> Tenhle soubor si Claude Code načte automaticky při otevření projektu.
> Obsahuje všechno, co je potřeba vědět, aby se navázalo tam, kde se skončilo.
> Stručné shrnutí pro nový chat (i bez přístupu k repu) je v `docs/POKRACOVANI.md` –
> při větší změně stavu ho aktualizuj taky.

## Co to je

Vlastní launcher pro **Meta Quest 3S**. Od verze **2.0 (2026-09-27)** je to
**samostatný projekt napsaný od nuly** ve složce `app/` — už to není patch nad
[Lightning Launcherem](https://github.com/threethan/LightningLauncher).
Z Lightning Launcheru (threethan, GPL-3.0) je převzatá jen logika, která tam
fungovala dobře: rozpoznání typu aplikací, seznam vyloučených balíčků,
spouštění přes vrshell a online zdroje bannerů.

Package: `com.neolauncher.v1` (stejné jako starý fork — addon pro Meta
tlačítko ho zná a otevírá prioritně). Uživatel má na headsetu i původní
Lightning Launcher jako zálohu, oba jdou nainstalovat vedle sebe.

Starý fork (v1.x) se dá pořád postavit ručně přes
`.github/workflows/legacy-fork-build.yml` — hlavně kvůli addonu
**RedirectServices** (Meta tlačítko), který se staví jen tam.

## Zlatý standard vzhledu

**Dialogy (nastavení, rychlé menu, menu karty, aktualizace)** mají od
2026-09-27 styl skla z visionOS podle dvou předloh od uživatele (smart-home
dashboardy): světle šedé matné sklo s bílým okrajem a světlou horní hranou,
uvnitř světlejší dlaždice, ikony v kulatém skle, pilulky (vybraná
= bílá s tmavým textem), přepínač bílý s tmavou tečkou, velké bílé nadpisy.
Barvy jsou v `Glass` (`GLASS_TOP/BOTTOM`, `TILE`, `INK`). Hlavní panel má
volbu „Styl skla“: Tmavé (preview, výchozí) / Světlé (světlejší šedé).

**Mezery a zaoblení (od 2026-09-27, uživateli vadily „náhodné“ mezery):**
všechno bere tokeny z `Glass` — vnitřní okraj panelu `PAD` 20 (ze všech
stran stejný), nadpis → obsah `SECTION` 20, mezi dlaždicemi `GAP` 12
(vodorovně i svisle), malé prvky vedle sebe `GAP_S` 8, text v dlaždici
`TILE_PAD` 16, hlavní tlačítko `BUTTON_H` 48. Rohy jsou **soustředné**
(vnitřní radius = vnější − odsazení): dlaždice `R_TILE` 20, panel
`R_PANEL` = 20 + 20 = 40, náhled v dlaždici odsazený `INSET` 8 má `R_INNER`
12, kolečko ikony v kapsli má stejný okraj ze všech stran (hledání: kapsle
52, kolečko 40, okraj 6). Ornament: položky 40 v bublině 52 (okraj 6),
oddělovač má z obou stran stejnou mezeru. Nová okna stavět jen z těchto
tokenů, žádná „od oka“ čísla. Rolovací plochy mají `Glass.fadeEdges`
(obsah se u okraje rozplyne, uživateli vadila ostrá „zakousnutá“ hrana).
Hodiny v rychlém menu mají nahoře víc místa (`HEADER_TOP` 30) — u rohu
s radiusem 40 působily namačkaně.

**Barvy, sklo a ikony (od 2026-09-27, uživatel: „dobrý design = barevná
paleta a ikonky“):**
- **Paleta** `ui/Palette.java` (vybral uživatel z dsgn.house): Void Black
  `#06070A`, Electric Cobalt `#3D5AFE` (akcent: NOVÉ, pokrok, výběr),
  Synth Magenta `#FF2FA3` (upozornění, baterie ≤ 20 %), Toxic Amber
  `#FF8A1E` (baterie ≤ 50 %, hvězda oblíbených), Titanium Fog `#AEB6C2`
  (vedlejší text, světlé sklo), Holographic Pearl `#F5F7FF` (text, vybraná
  pilulka, hlavní tlačítko – s jemným přechodem do fialova/modra). Žádné
  jiné barvy „od oka“. Vedlejší text přes `Palette.text2()/text3()` (na
  světlém skle je perlový, šedá by zanikla).
- **Sklo** = jeden recept `ui/GlassSurface.java` pro panel, ornament,
  lištu, rychlé menu, dialogy (`GlassDrawable`) i dlazdice: průhledná
  tónovaná výplň (nahoře světlejší), měkký odlesk vlevo nahoře, **okraj
  s přechodem** (nahoře jasný, po stranách slábne, dole jemný odraz –
  jednobarevný okraj působí jako plast). Za dialogem `ModalDepth`: blur
  10 dp + sytost 1,3 (barvy her prosvítají sklem, žádné šedé „zakalení“).
- **Hover = sklo, ne neon:** dlaždice se zesvětlí, zjasní okraj a pod
  laserem je měkké bílé světlo (rychlé menu). **Žádná barevná záře**
  (uživateli vadila modrá záře u Wi-Fi). Vybraná záložka = světlejší
  skleněná kapsle.
- **Ikony** = sada **Lucide** (ISC, `THIRD_PARTY_NOTICES.md`):
  `tools/icons/gen_icons.py <lucide/icons>` vygeneruje `ui/IconPaths.java`
  (SVG cesty 24×24), `ui/SvgPath.java` je parsuje (včetně oblouků),
  `Icons.draw(...)` kreslí tahem 2/24 se zaoblenými konci. Novou ikonu
  přidat do seznamu `ICONS` v generátoru a znovu vygenerovat (Lucide:
  `git clone --depth 1 --filter=blob:none --sparse
  https://github.com/lucide-icons/lucide.git` + `sparse-checkout set icons`).
  Logo Neo je kreslené zvlášť.
- Velké nadpisy (Nastavení, hodiny v rychlém menu) mají nahoře
  `Glass.TITLE_TOP` 30 (u rohu R 40 jinak působí namačkaně).

**`docs/preview_neo.html`** — interaktivní HTML prototyp. Uživatel ho
schválil větou "přesně takhle to chci v tom Questu". Když je spor o to,
jak se má něco chovat, rozhoduje tenhle soubor. Rozměry v kódu (dp) jsou
1:1 CSS pixely z preview.

`docs/HISTORIE_A_VIZE_NEO_LAUNCHER.txt` zmiňovaný v dřívějších verzích
tohohle souboru v repu není (zůstal u uživatele na PC).

## Architektura v2.0

Celý launcher je **jeden vlastní `View`** (`ui/NeoLauncherView.java`), žádný
RecyclerView. Důvod: ve forku hover závisel na `ACTION_HOVER_ENTER/EXIT`
jednotlivých karet a ty Quest při přejíždění mezi sousedními kartami
neposílá spolehlivě (bug 1b z minulé session). Teď se karta pod ukazatelem
počítá z pozice ukazatele při každém pohybu i každém snímku → jeden zdroj
pravdy (`focused`), žádné callbacky, žádná recyklace.

| Soubor | Co dělá |
|---|---|
| `NeoApp.java` | Drží jediné instance `Prefs`, `AppRepository`, `ArtworkLoader` |
| `LauncherActivity.java` | Jediná aktivita: skládá `NeoLauncherView` + `OverlayHost`, přijímače (baterie, čas, balíčky), výběr vlastního obrázku |
| `ShortcutStateProvider.java` | Odpovídá addonu Meta tlačítka (`isOpen`, `shouldBlur`, `allowShortcuts`) |
| `data/AppRepository.java` | Seznam aplikací (cache `apps.json` → okamžitý start, pak sken na pozadí), typ VR/2D/panel, filtrování a řazení záložek |
| `data/Prefs.java` | Všechna nastavení + ruční pořadí, skryté aplikace, přejmenování, naposledy spuštěné |
| `data/Platform.java` | Quest? Verze Horizon OS, podpora blend efektů / chain launch |
| `art/ArtworkLoader.java` | Bannery: vlastní → cache na disku → TV banner → náhradní (ikona na rozmazaném pozadí) + stažení online. Bitmapy jsou předem oříznuté na přesnou velikost karty |
| `launch/AppLauncher.java` | Spouštění (vrshell broadcast na v71+, panely, 2D), info o aplikaci, odinstalace |
| `update/Updater.java`, `InstallReceiver.java` | Aktualizace z GitHub Releases: najde nejnovější build, stáhne, nainstaluje přes PackageInstaller |
| `ui/NeoLauncherView.java` | Sklo, horní lišta (logo, záložky, hodiny, baterie), mřížka, hover pop-out, hloubka ostrosti, gumové rolování, přesouvání |
| `ui/CarouselView.java` | Testovací karusel (5× klepnout na logo): karty na šířku ve vějíři, hloubka ostrosti do stran, štítky se statistikami |
| `ui/QuickMenuView.java` | Rychlé menu (náhrada systémového menu Questu): jas, hlasitost, Wi-Fi, baterie, úložiště, dlaždice funkcí Questu |
| `ui/Spring.java` | Fyzikální pružina (jako SwiftUI spring) — při změně cíle drží rychlost; čistě z času |
| `ui/ModalDepth.java` | Launcher za otevřeným dialogem plynule ustoupí (rozmazání + zmenšení) |
| `ui/PeepholeAnimation.java`, `ui/LaunchLens.java` | Animace spuštění „kukátko“ (sdílí mřížka i karusel) + AGSL čočka |
| `data/UsageInfo.java` | Herní čas a poslední spuštění z UsageStats (jako plugin Playtime v LL) |
| `ui/Eased.java` | Animovaná hodnota jako CSS transition (retarget z aktuální hodnoty, bez callbacků) — už jen pro prolínání a hover lišty |
| `ui/DepthBlur.java` | Radiální rozmazání: AGSL shader (Android 13+), jinak obyčejný blur |
| `ui/LiquidGlass.java` | Tekuté sklo horní bubliny: AGSL lom u okraje + rozklad barev, řetězený za blur |
| `ui/ShadowSprite.java` | Předpočítané rozmazané stíny karet (box-shadow) |
| `ui/Glass.java`, `OverlayHost.java`, `SettingsSheet.java`, `AppMenu.java`, `UpdateSheet.java`, `PowerSheet.java`, `ConfirmSheet.java` | Dialogy ve stylu skla visionOS (normální Android Views). `OverlayHost` je nechá „vyrůst“ na pružině z místa, odkud se otevřely. Nastavení = dashboard s dlaždicemi |
| `ui/GlassWidgets.java`, `ui/Cascade.java` | Přepínač, posuvník a ikona v kulatém skle, postupný nástup dlaždic |
| `ui/Palette.java`, `ui/GlassSurface.java`, `ui/GlassDrawable.java` | Barevná paleta, jeden recept na sklo (Canvas i Drawable) |
| `ui/Icons.java`, `ui/IconPaths.java`, `ui/SvgPath.java` | Ikony Lucide: vygenerované SVG cesty + parser (viz „Barvy, sklo a ikony“) |
| `MetaAddon.java` + modul `metaaddon/` | Meta tlačítko: samostatný doplněk „Neo – Meta tlačítko“ (služba přístupnosti `meta/MetaService`), Neo ho má přibalený a instaluje ho samo (viz níže) |
| `data/AppSizes.java`, `data/Backup.java`, `update/ApkInstaller.java` | Velikost her, záloha nastavení (zip), instalace APK |
| `data/BatteryEstimate.java` | Odhad výdrže baterie (učí se z hraní, záložně okamžitý odběr) |

### Rozložení okna (od 2026-09-27, podle předloh z visionOS od uživatele)
- **Skleněný panel** jen s mřížkou karet (všechny rohy zaoblené).
- **Ornament nahoře** — plovoucí „bublina“ se záložkami (Hry / Aplikace /
  Vše), časem (bez data – to je v rychlém menu, přání uživatele) a baterií,
  **napůl zanořená do horní hrany panelu** (střed na `frame.top`).
  Klepnutí na čas/baterii = rychlé menu.
- **Levá lišta** (jako tab bar ve visionOS) — logo Neo a ikony Knihovna
  (klepnutí odroluje nahoru), Hledat, Karusel, Rychlé menu, Nastavení. Po najetí
  laserem se na pružině rozbalí a ukáže popisky. Uživatel chtěl viditelné
  tlačítko nastavení („průměrný uživatel neví, že má klepnout na logo“).
- **Rychlé menu** vyjede jako boční panel vpravo přes celou výšku panelu
  (`OverlayHost.showSide`): nahoře hodiny + celé datum, vpravo v řadě
  pilulky Wi-Fi a baterie, pak karta „Naposledy hráno“, posuvníky, dlaždice
  (vyplní zbytek výšky) a dole tlačítko „Nastavení Nea“ — mezi vším `GAP`.
- Okno 1176×664 dp: vlevo 88 dp na lištu, nahoře 36 dp (půlka ornamentu),
  vpravo 24 / dole 30 dp průhledný okraj pro „vyskočení“ karty (vypínatelné).

### Vrstvy kreslení (odspodu)
1. Sklo panelu (výplň s nastavitelným krytím, okraj, světlá horní hrana,
   světlo laseru na hraně `drawRimLight`) — **bez** tvaru ornamentu (`ornHole`,
   clipOut), pod ornamentem je jen jeho sklo
2. `contentNode` (RenderNode): všechny karty kromě hovernuté, oříznuté
   panelem se zaoblenými rohy (a bez `ornHole`). **Jeden** `RenderEffect`
   hloubky ostrosti na celou vrstvu.
3. `backdropNode`: kopie panelu a karet pod ornamentem, rozmazaná 14 dp +
   **tekuté sklo** (`ui/LiquidGlass.java`, AGSL lom u okraje bubliny
   + rozklad barev, řetězený za blur; bez shaderu jen blur)
4. Levá lišta a ornament (sklo, stín jen vně tvaru, obsah). Ornament má
   průhlednější styl `GlassSurface.LIQUID_*`. Do 2026-09-29 kreslil stín
   neprůhledné tělo, takže matné sklo pod ním nebylo vůbec vidět.
5. Hovernutá karta — mimo ořez, může přesahovat panel
6. Tažená karta

## Streamovat do prohlížeče (od 2026-10-12, balíček `cast/`)

Dlaždice **Streamovat** v rychlém menu otevře `ui/CastSheet` (sklo jako PowerSheet): stav, adresa
**quest.jendovyapky.eu**, Spustit vysílání / Zastavit, a „Sdílení Questu“ = původní akce (Fotoaparát
`com.oculus.metacam`, na VrOS < 81 `systemux://sharing`) – `LauncherActivity.openSystemSharing`.
Když vysílání běží, dlaždice má akcentovou ikonu a název „Vysílá“ (`QuickMenuView.setCastActive`).

- Vše kolem nahrávání běží v **odděleném procesu `:cast`** (`CastStartActivity` – neviditelná, povolení
  RECORD_AUDIO jednou + systémový dialog nahrávání; `CaptureService` – foreground `mediaProjection`).
  Pád streamování tak launcher neshodí. `NeoApp.onCreate` v procesu `:cast` nic nenačítá.
  Hlavní proces se ptá jen přes `cast/Cast`: `isRunning` (getRunningServices) a `fetchStatus`
  (`http://127.0.0.1:8080/stats`, proto `res/xml/network_security_config.xml` povoluje cleartext jen na localhost).
- Obraz: MediaProjection → H.264 MediaCodec (60 fps, enkodér kreslí přímo z virtuálního displeje).
  Android 14: `createVirtualDisplay` jen JEDNOU na projekci, změna kvality = `resize` + `setSurface`.
  Quest dává **jeden obraz** (ne obě oči), které oko, nejde ovlivnit.
- Zvuk: `AudioPlaybackCapture` (PCM 48 kHz po 20 ms), jen když někdo na webu poslouchá. Časová razítka
  obrazu i zvuku jsou ve stejných hodinách → prohlížeč zvuk srovná s obrazem (posun, Bluetooth).
- `HttpServer` na portu 8080 servíruje `assets/cast/index.html` (přehrávač, jMuxer/MSE, maskot `quba.js`),
  `/video`, `/audio`, `/stats`, `/set`. `Discovery` se každých 25 s ohlásí na quest.jendovyapky.eu
  (přes IPv4 i IPv6), web pak najde Quest ve stejné Wi-Fi podle veřejné IP.
- Lišta streamu: baterka Questu (`/stats` → `battery`, `charging`; appka čte max 1× za 5 s) a **špendlík** vpravo
  (localStorage `qc.pin`): připnuto + myš v klidu → lišta zmizí, v rohu obrazu zůstane pilulka `#chip` s baterkou
  (16 px od pravého i spodního kraje obrazu, `placeChip()`). Honza chce souměrné okraje – měřit, ne od oka.
- `StallGuard`: každé spojení `/video` a `/audio` hlídá; když se do něj 6 s nepodaří nic zapsat (počítač usnul,
  vypadla Wi-Fi), zavře socket → zaseknutý zápis spadne a divák se odečte. Dřív visela mrtvá spojení desítky minut
  a okno Streamovat ukazovalo třeba 25 diváků (2026-10-15).
- Web, protokol a maskot jsou v repu **`jendovyapky/quest`** (Cloudflare Worker + `public/`, poznámky
  v jeho CLAUDE.md). `assets/cast/quba.js` a `index.html` jsou kopie odtamtud – měnit oba.
  Samostatná appka Quest Cast (`com.jendovyapky.questcast`, `jendovyapky/quest/android`) je tímhle nahrazená;
  obě naráz nespouštět (port 8080).
- Neověřeno na headsetu (2026-10-12): okno Streamovat a dlaždice. Samotné streamování (obraz, zvuk,
  web) Honza otestoval v samostatné appce – „10/10“.

## Klíčová omezení (naučeno tvrdě)

1. **Žádný blur na jednotlivých kartách.** Snapdragon XR2 Gen 2 to neutáhne
   (předchozí pokus shodil headset). V2.0: jeden efekt na celou vrstvu
   (`contentNode`), stíny jsou předpočítané bitmapy, žádné `saveLayer` na kartu.
2. **Nikdy nespoléhat na `withEndAction()` / konec animace pro stav.**
   V2.0: `Eased` i `Spring` se vždy dopočítají z času (pružina v uzavřeném
   tvaru, žádné kroky po snímcích), stav se nikde nenastavuje v callbacku.
   Zavírání dialogu mění stav hned; animace je jen „duch“ bez dotyků.
3. **Žádný per-holder stav.** V2.0: není RecyclerView; stav karty (`Card`) je
   klíčovaný balíčkem a přežije i obnovu seznamu.
4. **Fokus = jeden zdroj pravdy.** `updateFocus()` se volá při každé události
   ukazatele a každém snímku během rolování. Při ztrátě fokusu okna
   (`onWindowFocusChanged`) tvrdý reset. Pojistka: bez jakékoliv události
   ukazatele 6 s → reset (kdyby Quest nepostal HOVER_EXIT).
5. **HOVER_EXIT chodí i těsně před stiskem spouště** → reset hoveru se
   odkládá o 90 ms, jinak by karta při kliknutí blikla.
7. **AGSL: zápis `content.eval(p)`** (Android 13+). Kompiluje se přes
   `ui/Agsl.compile()`, který při chybě zkusí starý zápis `sample(content, p)`
   — ten zná jen starší Skia v Robolectricu. Náhled tak ověří, že shader je
   jinak platný; Robolectric ale neumí `RenderEffect.createRuntimeShaderEffect`
   (UnsatisfiedLinkError), takže **vizuál shaderů (hloubka ostrosti, čočka
   kukátka) je vidět až na headsetu** — v náhledu jede záložní cesta.
6. **3D náklon karty jen přes `RenderNode.setRotationX/Y`**, ne přes vlastní
   perspektivní matici z `android.graphics.Camera` + `canvas.concat()`. Ta se
   po odrolování vůbec nevykreslila (karta pod laserem zmizela) — odhaleno
   Robolectric snímkem 2026-09-27. Nakloněná karta má vlastní `Card.node`;
   ten smí být v jednom snímku jen v jednom rodiči, proto kopie pod lištou
   (backdrop) kreslí karty vždy naplocho.

## Stav — co je hotové (v2.0)

**2026-09-29 první test na headsetu: „funguje až podivuhodně super“.**
Zpětná vazba z testu (vyřešeno ve v2.0.4x): Meta tlačítko Neo neotevíralo
(→ vestavěná služba, od 2026-09-30 doplněk `metaaddon/`, viz níže), karusel ukazoval
„Spuštěno 0×“ vedle hodin hraní (počítá jen spuštění z Nea → štítek pryč,
datum instalace taky, nápověda dole = volba „Nápověda v karuselu“, výchozí
vypnuto). Přání: silnější barevné světlo hry na skle (`drawGameLight`),
nástup karet při otevření (`playIntro`). Hudba v rychlém menu: spíš ne
(jen kdyby šla ukázat jen když něco hraje). Připomínka pauzy: ne.
Detaily níže psané před testem nemusí všechny sedět – ověřuj s uživatelem.

**Pátý test 2026-09-30:** otevření po zapnutí Questu i Uspat fungují. Ve hře ale
Meta pořád otevíralo Neo (uživatel by to snesl – „bylo to i u LL“ – ale volba má
fungovat), 3× Meta nic a lišta běžící hry chyběla → Beat Saber běžel na pozadí
a vybil baterii. Uživatel teď **chce, aby Neo samo vědělo, co běží na pozadí**
(dřív nechtěl odhad ze statistik). Přání: oznámení o baterii i mimo Neo (jako
Quest Game Optimizer: 20 %, 100 %), hezčí Vypnout (systémová nabídka Androidu
je ošklivá; jako menu Questu Vypnout / Restartovat / Spánek). Do budoucna možná:
Neo jako výchozí launcher (HOME) v Android nastavení. Opraveno v doplňku v1.2
(viz „Meta tlačítko — doplněk“). Pravděpodobná příčina: Quest hru při otevření
menu rovnou zastaví (STOPPED), takže `vrPkg` byl prázdný a kontrola „po hře“
navíc brala menu jako konec hry; možná taky neaktualizovaný doplněk nebo
nepovolený Přístup k využití → Neo to teď jednou za verzi samo nabídne
(`maybePromptAddon`, `ConfirmSheet`). **Ověřit podle logu `NeoMeta`.**

**Třetí test 2026-09-30:** nástup karet, bez paralaxy, Streamovat a Android
nastavení fungují. Meta tlačítko ne: služba v Neu nešla zapnout (tlačítko
Zapnout spadlo, Omezené nastavení) a navíc hledala špatné okno (systemux
místo `com.oculus.panelapp.library`) → přepsáno na doplněk jako u LL (viz
„Meta tlačítko — doplněk“).

**Druhý test na headsetu 2026-09-29 v noci („víceméně všechno funguje fajn“) → opraveno:**
- **Quest přesměruje systémové intenty nastavení do svého Nastavení Questu**
  (`ACTION_ACCESSIBILITY_SETTINGS`, `ACTION_APPLICATION_DETAILS_SETTINGS`…),
  kde nic není. Řešení jako Lightning Launcher: `setPackage("com.android.settings")`
  (`AppLauncher.ANDROID_SETTINGS`, `appInfoIntent`, `inAndroidSettings`,
  `MetaAddon.openSettings` = seznam Přístupnosti v Android nastavení). Týká se i force stop (Ukončit) a Info.
- **„Omezené nastavení“ (Android 13+ restricted settings)**: ručně instalovaná
  aplikace nesmí zapnout službu přístupnosti. Uživatel: Informace o aplikaci
  (Android) → ⋮ → Povolit omezená nastavení → znovu zapnout (tlačítko „Info
  o Neu“ vedle Meta tlačítka v Nastavení → Quest). LL to řeší stejně (a jeho
  addon se instaluje session-based přes PackageInstaller, takže omezený není).
  Z PC jde obejít: `adb -P 5038 shell appops set com.neolauncher.v1
  ACCESS_RESTRICTED_SETTINGS allow`, nebo `adb -P 5038 shell pm grant
  com.neolauncher.v1 android.permission.WRITE_SECURE_SETTINGS` → Neo si
  službu zapne samo (`MetaAddon.selfEnable`, zapisuje
  `enabled_accessibility_services`).
- **Android nastavení** (`AppLauncher.openAndroidSettings`): `ACTION_SETTINGS`
  s balíčkem, když nejde → Informace o aplikaci Nastavení (tlačítko Otevřít),
  jako LL. Dlaždice „Android“ v rychlém menu (místo Nastavení Questu)
  a v Nastavení → Quest.
- **Streamovat**: `systemux://sharing` funguje jen do VrOS 80 (LL:
  `questVersionedIncludedApps`), od v81 je sdílení v aplikaci Fotoaparát
  `com.oculus.metacam` → ta první, pak sharing (starý OS), pak quick_settings.
- **Paralaxa odstraněna** (uživateli „divná“) — kód, volba i přepínač pryč.
- **Nástup karet nebyl vidět**: hodiny se spouštěly v `onResume`, dřív než
  Quest okno ukáže. Teď `playIntro` jen „natáhne“ (`introArmed`), start je
  první vykreslený snímek + `INTRO_DELAY_MS` 180, obsah do té doby průhledný
  (`introAlpha`), výraznější (spread 0,34, zoom 0,22, 900 ms, kubický ease-out)
  a spouští se i v `onNewIntent` (Meta tlačítko, když Neo nebylo zastavené).

**Přidáno 2026-09-29 (po testu, na přání uživatele, NEOVĚŘENO na headsetu):**
- **Instalace APK** (`update/ApkInstaller`): Nastavení → Aplikace → „Nainstalovat
  APK“ → systémový výběr souboru (`ACTION_OPEN_DOCUMENT`, `*/*` + typ apk) →
  PackageInstaller → Quest se zeptá. Výsledek v `InstallReceiver` (extra
  `neo.install.kind=apk` → „Nainstalováno: …“). Jestli Quest výběr souborů
  nemá, ukáže se hláška – pak by to chtělo vlastní seznam APK ve složce Download.
- **Velikost her** (`data/AppSizes`, `StorageStatsManager`, stejné povolení jako
  herní čas): v menu karty „VR hra · 14,2 GB“, řazení `Prefs.SORT_SIZE`
  („Velikost“, největší první, přes `Signals.sizeBytes`). Počítá se na pozadí,
  obnova max. 1× za 10 min.
- **Záloha** (`data/Backup`): Nastavení → O Neo → Zálohovat / Obnovit. Zip se
  `neo-zaloha.json` (SharedPreferences `neo`, `neo_recents`, `neo_launch_counts`
  s typy) + `art-custom/*`. Po obnově `Prefs.reloadAfterRestore()` +
  `ArtworkLoader.clearMemory()`.
- ~~Paralaxa~~ — po testu odstraněna (viz výše).

**Přidáno 2026-09-29 večer (NEOVĚŘENO na headsetu):**
- **Po zapnutí Questu otevřít Neo** (`Prefs.openOnBoot`, výchozí zapnuto,
  Nastavení → Quest): doplněk `MetaService` se po startu připojí do
  `BOOT_WINDOW_MS` (10 min od zapnutí) → při první události domova/systemux
  po 2,5 s otevře Neo; když mezitím běží jiná aplikace, nic. Záložně po 25 s.
- **Živá bublina** (`NeoLauncherView.showLive(icon, text, color, progress, ms)`,
  `clearLive()`): stav v ornamentu (čas, baterie) se na pružině roztáhne
  a prolne na ikonu s kroužkem průběhu (`LIVE_SPINNER` = točí se,
  `LIVE_ICON_ONLY` = jen ikona) a text. Stav jen z času (`liveUntilNs`),
  průběh bez další zprávy zmizí po 2 min. Zdroje v `LauncherActivity`
  (`showLive`/`notice`): instalace APK (`ApkInstaller.Listener`, výsledek přes
  `InstallReceiver.setListener`), stahování aktualizace Nea, záloha/obnova,
  nabíjení (`announceBattery`: začátek + `computeChargeTimeRemaining`,
  100 %, slabá baterie 20/10 %) a dřívější Toasty (oblíbené, skrytí,
  obrázek, řazení). V karuselu a když Neo není vidět → obyčejný Toast.
- **Tekuté sklo ornamentu** (`LiquidGlass`, `LIQUID_BAND` 16 / `LIQUID_STRENGTH`
  11 dp): vidět jen když pod bublinu zajedou karty (odrolovat). V náhledu
  (Robolectric) jen blur, lom je vidět až na headsetu (logcat `NeoLiquidGlass`).
- **Světlo laseru na hraně skla** (`drawRimLight`, `RIM_*`): radiální přechod
  jako tah okraje panelu (+ měkká záře dovnitř), ornamentu a lišty.
- **3× Meta + lišta běžící aplikace** (`Prefs.metaTriple`, výchozí zapnuto):
  `MetaService.onMenuOpened` (doplněk) počítá „otevření menu Questu“ = dávky
  událostí systemux (události do `BURST_MS` 350 ms = jedno otevření); dvě
  otevření do `TRIPLE_WINDOW_MS` 2,2 s (do v1.2 1,6 s) = otevřít–zavřít–otevřít = 3× Meta.
  Ve hře/aplikaci → `runningPkg` = aplikace v popředí + otevře Neo; v Neu
  (běží-li něco) → intent `EXTRA_RESUME` = zpět do aplikace. Neo ukáže dole
  na panelu lištu (`NeoLauncherView.setRunningApp`, `drawRunBar`, tekuté sklo
  jako ornament, `runBackdropNode`): náhled, název, „Běží na pozadí“,
  Pokračovat (perlové) / Ukončit / ✕. `runningPkg` se maže, když se aplikace
  vrátí do popředí nebo se spustí jiná VR hra. **Ukončit** =
  `MetaAddon.forceStop` → doplněk `MetaService.startForceStop`: otevře `ACTION_APPLICATION_DETAILS_SETTINGS`,
  pollingem (150 ms, max 8 s) najde tlačítko podle textu (`FORCE_STOP_LABELS`),
  klepne, potvrdí `android:id/button1`, BACK a otevře Neo s `EXTRA_STOPPED`
  (bublina „Ukončeno“). Bez služby: otevře informace o aplikaci + hláška.
  Riziko: jedno otevření menu může poslat události rozložené přes > 350 ms →
  falešné 3× (otevře jen Neo, nic neukončí). Ladit podle `NeoMeta`.
- **Rychlé menu v2**: dlaždice ve 3 sloupcích (ikona nad názvem): Wi-Fi,
  Bluetooth, **Streamovat** (Fotoaparát `com.oculus.metacam`, na VrOS < 81
  `systemux://sharing`), **Android** (Android nastavení), Menu Questu, Soubory,
  Prohlížeč, **Uspat** (`GLOBAL_ACTION_LOCK_SCREEN`), **Vypnout**
  (`GLOBAL_ACTION_POWER_DIALOG` = systémová nabídka vypnout/restartovat;
  přímo vypnout ani restartovat obyčejná aplikace nesmí). Uspat/Vypnout jdou
  jen přes doplněk (`MetaAddon.sleep()` / `powerMenu()` → broadcast).
  Fotoaparát vypadl (nahrávání je ve Sdílení).
- **Odhad výdrže** (`data/BatteryEstimate`): při odchodu z Nea (`onStop`) si
  uloží baterii a čas, při návratu spočítá %/h za dobu provozu (uptime, ≥ 10 min,
  ≥ 3 %, bez nabíjení) → klouzavý průměr = spotřeba při hraní. Dokud není,
  okamžitý odběr `CURRENT_NOW` / `CHARGE_COUNTER`. V rychlém menu vpravo na
  řádku s datem („vydrží asi 1 h 50 min“ / „nabito za 40 min“) a v bublině
  slabé baterie.
- **Oprava:** `Icons.draw` nechával sdílený `Paint` ve stylu STROKE → další
  výplně stejným Paintem (hover v liště, stíny, jamka…) byly jen obrys. Teď
  vrací FILL. Levá lišta má zase krycí tělo (`drawChromeGlass(..., liquid=false)`).
- **Přesouvání karet**: live přeskládání už bylo; nově ostatní karty třesení
  (`WIGGLE_*`, začne až po pohybu, aby nerušilo podržení pro menu), zmenšení
  o 3,5 %, uhnutí od tažené karty (gauss, 12 dp) a skleněná „jamka“ na cílovém
  místě (`drawDropSlot`, pružiny `ghostX/Y`).

- Mřížka 4 sloupce (nastavitelné 3–6), karty 1.6:1, radius 16, title pill
- Hover: zvětšení 1.18 × perspektiva (translateZ 32 px v perspective 900 px),
  náklon ±6° za kurzorem, 380 ms `cubic-bezier(0.16,1,0.3,1)`, bílý okraj,
  velký stín + záře, karta nad lištou a mimo panel
- Hloubka ostrosti: radiální blur 2.2 → 4.4 dp (Jemná) / 2× (Silná) / vypnuto
  (pak jen ztmavení okolí)
- Gumové rolování: lerp 0.16, rubber band 0.38, squish max 4.5 % (konstanty
  z preview), setrvačnost po tažení, thumbstick (ACTION_SCROLL)
- Ornament: záložky Hry / Aplikace / Vše, hodiny, baterie (barvy
  dle %, 1 blesk = nabíjení, 2 = rychlé > 7.5 W). Logo Neo je v levé liště
  (klik = nastavení, 5× = karusel).
- Přesouvání: podržet kartu 450 ms → zvedne se → táhnout (auto-rolování
  u okraje) → pustit. Pořadí se ukládá per záložka.
- Menu karty: držet kartu **bez pohybu 1 s** (nastavitelné 0,7 / 1 / 1,5 s,
  pod kartou běží modrý ukazatel). Pustit dřív = nic, karta jen dosedne.
- Menu karty: spustit, přejmenovat, vlastní obrázek, stáhnout obrázek znovu,
  skrýt, info, odinstalovat
- Animace spuštění "kukátko" (1,15 s), fáze: **stisk** (karta se zmenší na
  90 %, 130 ms) → **kukátko** (pružný návrat karty, tma se stáhne do kruhu,
  rámeček/název karty zmizí, rybí oko, 320 ms) → **průlet** (kruh se roztáhne,
  banner se přiblíží přes celé okno, radiální "zoom blur" do stran, 400 ms)
  → **setmění do černa** a zavření launcheru (nebo rozplynutí zpět, když je
  zavírání vypnuté). Easingy navazují bez skoků. Aplikace se spouští v 48 %.
  Kód je v `ui/PeepholeAnimation.java`. Čočka (rybí oko + měkký okraj) je
  JEDEN AGSL efekt na vrstvu animace (`ui/LaunchLens.java`); bez shaderu
  záložní kreslení (kruhový ořez). Stav odvozený jen z času, respektuje
  systémové měřítko animací (0 = bez animace), vypínatelná v nastavení.
  Omezení: 2D panel nemůže kreslit mimo své okno — "přiblížení k očím" se
  odehrává v okně (včetně průhledného okraje), panel sám se k hráči nepohne.
  Rozmazání do stran při průletu jsou vrstvené zvětšené kopie banneru (jede
  i bez shaderu, je vidět i v náhledu).
- Po spuštění se launcher zavře (`finish()` na konci animace, zapnuté
  v nastavení "Po spuštění zavřít launcher"): poslední fáze animace ztmaví do
  černa, takže pod ní launcher neprosvítá. Zavře se jen když se aplikace
  opravdu spustila; kdyby se okno nezavřelo, po 2,5 s se launcher zase ukáže.
- Prémiový vzhled: záře hovernuté karty v barvě obrázku hry (průměrná barva
  banneru vytažená do syta — `ArtworkLoader.ambientColor`), jemná modrá záře
  okolo skla (jen v průhledném okraji, `setShadowLayer` + `clipOutPath`),
  ploché ostré stíny pod kartami, pilulkami, záložkami a stavem, modrá záře
  indikátoru záložky, puls tečky loga při klepnutí.
- **Fyzika a prostorová návaznost (jako iOS/visionOS)** — hover, náklon,
  indikátor záložek, přeskládání karet, zvednutí tažené karty a karusel jedou
  na pružinách (`Spring`: response + tlumení, hover lehce překmitne, rychlé
  změny cíle navazují bez cuknutí, fling v karuselu předá rychlost laseru).
  Dialogy vyrůstají z místa, odkud se otevřely (nastavení z loga, menu karty
  z karty, rychlé menu z hodin) a launcher za nimi ustoupí do hloubky.
- **Karusel (testovací režim)** — 5× rychle klepnout na logo Neo (jedno
  klepnutí otevře nastavení se zpožděním 380 ms), nebo přepínač v nastavení.
  Úplně průhledný, jen karty **na šířku** (bannery jako v mřížce, uživatel
  2026-09-27 chtěl šířku místo výšky) ve „vějíři“: prostřední velká a ostrá,
  boční menší, přes sebe a natočené 18° k hráči, **čím dál, tím rozmazanější**
  (jeden `DepthBlur` na vrstvu bočních karet; v náhledu Robolectricu jen
  rovnoměrný blur). Dokola od 5 položek, pořadí "3 / 13" v zářezu karty,
  záře v barvě hry pod prostřední kartou, pod ní název, typ a štítky: herní
  čas (UsageStats — potřebuje "Přístup k využití", štítek "Herní čas –
  povolit" otevře nastavení), naposledy spuštěno, počet spuštění z Nea
  (počítá se od v2.0.25), datum instalace. Ovládání: joystick ←/→
  (ACTION_SCROLL, drží-li se, opakuje se), klepnutí na boční kartu ji přinese
  doprostřed, klepnutí na prostřední spustí (kukátko), tažení laserem roluje
  se setrvačností, podržení otevře menu karty. Šipka vlevo nahoře = zpět.
  Velké karty chtějí větší bannery → `ArtworkLoader.requestTargetSize`
  bere největší požadavek (karusel žádá jen když je vidět).
- **Rychlé menu** (vlastní obdoba systémového menu Questu, cíl: časem plně
  nahradit výchozí launcher) — klepnutí na hodiny/baterii (mřížka i karusel)
  nebo tlačítko menu na ovladači (`KEYCODE_MENU`, pokud ho Quest do aplikace
  pošle). Hodiny + datum, baterie, Wi-Fi (signál z `NetworkCapabilities`),
  posuvník **jasu** (`Settings.System.SCREEN_BRIGHTNESS` 10–255, potřebuje
  "Úprava systémových nastavení" = `WRITE_SETTINGS`, stejně jako QuestDim;
  bez povolení posuvník nabídne povolení) a **hlasitosti** (`AudioManager`,
  bez oprávnění), joystick nad posuvníkem = ±5 %. Dlaždice: Wi-Fi, Bluetooth
  (Android nastavení, záložně Nastavení Questu), Nastavení Questu, Menu Questu
  (`systemux://quick_settings` — systémové rychlé nastavení na vše ostatní:
  průchod, sdílení…), Soubory, Prohlížeč, Fotoaparát, Neo (nastavení).
  Úložiště je v nastavení (widget). Addon Meta tlačítka zůstává beze změny.
- Nastavení jako dashboard (visionOS): nadpis, kategorie pilulkami (Vzhled,
  Aplikace, Quest, Aktualizace, O Neo), dlaždice s ikonou a přepínačem
  (klepnutí kamkoliv na dlaždici přepne = velký cíl pro laser) nebo volbami,
  vpravo widgety (datum, čas, Neo + aktualizace, počet her/aplikací
  a úložiště) a „Hotovo“. Dlaždice nastoupí kaskádou. Obsah: hloubka
  ostrosti, styl a krytí skla, sloupce, kukátko, zavření po spuštění, přesah
  karet, karusel, řazení, prodleva menu karty, obrázky z internetu, stáhnout
  obrázky znovu, herní čas (povolení), skryté aplikace, rozmazání prostředí,
  Meta tlačítko, Nastavení Questu, ovládání jasu (povolení), aktualizace
  a testovací verze, o aplikaci
- **Pohodlí (QoL, 2026-09-27 podle přání uživatele):**
  - „Co je nového“ po aktualizaci (jednou; `WhatsNewSheet`, text z
    `assets/novinky.txt`; jde otevřít i v Nastavení → Aktualizace).
  - Pamatuje si pozici rolování mřížky pro každou záložku (i po návratu ze
    hry, kdy se launcher zavřel) a vybranou kartu karuselu (`Prefs`).
  - Oblíbené: hvězdička v menu karty, oblíbené jsou vždy nahoře (i ve
    vlastním pořadí), na kartě zlatá hvězda vpravo nahoře.
  - Řazení: Vlastní / Abecedně / Naposledy / **Nejhranější** (herní čas
    z UsageStats) / **Chytré** (nové první, pak herní čas, pak naposledy).
  - Štítek **NOVÉ** (instalace < 14 dní, ještě nespuštěná – ani z knihovny
    Questu podle UsageStats; `AppEntry.installTime`).
  - V rychlém menu karta „Naposledy hráno“ s tlačítkem Hrát.
  - Slabá baterie (≤ 20 %, nenabíjí) svítí v ornamentu červeně (8 s pulzuje).
  - Hledání (lupa v levé liště, klávesnice Questu, bez ohledu na diakritiku,
    Enter spustí první výsledek) – `SearchSheet`.
  - V ornamentu ikonka posuvníků = nápověda, že čas/baterie otevírá rychlé menu.
  - Sklo dialogů se řídí „Stylem skla“ (tmavé jako launcher / světlé visionOS),
    pozadí za dialogem jen lehce rozmazané (uživateli vadilo „zakalení“).
  - Uživatel NEchce: testovací funkce (bezpečný režim, diagnostika, FPS),
    „vrátit zpět“ po skrytí, vlastní kategorie, weby jako karty, denní
    přehled herního času. Zvuky/haptika a počasí možná později.
- Aktualizace přímo v launcheru: CI po každém pushi vytvoří GitHub Release
  `v2.0.<číslo běhu>` (mimo `main` jako prerelease = testovací). Launcher při
  otevření (max 1× za 6 h) nebo tlačítkem v nastavení zkontroluje
  `api.github.com/repos/lichicks/neo-launcher/releases`, ukáže poznámky
  (zpráva commitu) a po potvrzení nainstaluje. `versionCode = 2000 + run_number`.
  Funguje jen s **veřejným** repem (GitHub bez přihlášení vrací u soukromého
  404, launcher pak hlásí "repozitář je soukromý"). Uživatel se 2026-09-27
  rozhodl repo zveřejnit (přepíná sám v GitHub Settings). Záložní varianta:
  APK do zvláštního veřejného repa `lichicks/neo-launcher-releases`
  (launcher ho zkouší jako druhý zdroj; CI by potřebovalo token v secrets).
  Změny jen v `.md`/`docs/` build ani release nespouští. Poznámky k releasu =
  první sekce `app/src/main/assets/novinky.txt`.
- Průhledné okno + `com.oculus.vrshell.supports_blend_effects` (Quest 3/3S
  rozmaže prostředí za panelem)
- Pevný podpisový klíč `keystore/neo-debug.keystore` → nové buildy jdou
  instalovat přes staré (`adb install -r`) bez ztráty nastavení
- **Repo se 2026-10-12 přesunulo z `lichicks/neo-launcher` pod `jendovyapky/neo-launcher`** (GitHub staré
  jméno přesměruje, `Updater.FEEDS` má obě). Vývoj je ve větvi `claude/funny-ramanujan-xp8ba6`, **ne v `main`**
  (`main` = starý fork Lightning Launcheru, needitovat). `build.yml` je na obou větvích stejná cesta → sdílené
  `run_number`, takže pushe do jiných větví zvyšují číslo verze (nevadí, jen roste).

## Co otestovat na headsetu jako první

0. **Meta tlačítko (v2.0.4x):** zapnout službu „Neo – Meta tlačítko“,
   v Přístupnosti vypnout službu Lightning Launcheru. Doma Meta → Neo?
   Znovu Meta → menu Questu? Ve hře Meta → menu Questu (ne Neo)? Po
   ukončení hry se otevře Neo – a neotevře se při pouhé pauze? Při
   problému `adb -P 5038 logcat -s NeoMeta`.

0. **Pátý test → doplněk v1.2 (v2.0.65+):** Neo nabídne „Nová verze Meta
   tlačítka“ → Aktualizovat. Pak `adb -P 5038 logcat -s NeoMeta` a: spustit hru →
   Meta (má zůstat menu Questu; řádek „Navigator … hra=…“) → 3× Meta (Neo s lištou;
   „Menu Questu otevřeno (2. za … ms)“) → Ukončit hru v menu Questu (otevře se Neo?
   neukáže lištu ukončené hry?). Lišta hry na pozadí i po otevření Nea z knihovny?
   Upozornění baterie 20 % ve hře (oznámení / hláška – co z toho Quest ukáže?
   Nejsou dvakrát?). Vypnout v rychlém menu → okno Nea → Restartovat / Vypnout
   (doplněk klepne v systémové nabídce sám; „Nabidka vypnuti: klepnuto na …“).

0. **Čtvrtý test 2026-09-30:** Meta doma a otevření po zapnutí FUNGUJÍ.
   Ve hře se otevíralo Neo (i s volbou menu Questu) a chyběla lišta → opraveno
   (UsageStats, doplněk v1.1 – v Neu „Aktualizovat“). Ověřit: ve hře Meta =
   menu Questu, 3× Meta = Neo s lištou, po ukončení hry Neo.

0. **Doplněk (v2.0.59+):** Nastavení → Quest → Meta tlačítko →
   Nainstalovat → potvrdit → otevře se Android Přístupnost → zapnout
   „Neo – Meta tlačítko“ (bez Omezeného nastavení?). Službu Lightning
   Launcheru vypnout. Meta doma → Neo? Pak vše, co na doplňku stojí (3× Meta,
   Ukončit, Uspat, Vypnout, otevření po zapnutí Questu). `NeoMeta` v logcatu.

0b. **Nové 29. 9.:** instalace APK (otevře se výběr souborů?), velikost v menu
   karty a řazení „Velikost“ (po povolení herního času), záloha → obnova.

0d. **3× Meta ve hře** → otevře se Neo s lištou dole? Pokračovat vrátí do hry?
   Ukončit hru opravdu zavře (Informace o aplikaci → Vynutit ukončení,
   `NeoMeta`)? Neotevírá se Neo při jednom stisku Meta ve hře (falešné 3×)?
   Rychlé menu: Streamovat otevře sdílení Questu? Uspat uspí? Vypnout ukáže
   nabídku vypnutí/restartu? Odhad výdrže po první hře (≥ 10 min).

0c. **Nové 29. 9. večer:** otevře se Neo po zapnutí Questu (`NeoMeta` v logcatu)?
   Živá bublina při nabíjení / instalaci APK. Tekuté sklo: odrolovat, aby
   karty zajely pod horní bublinu – lomí se u okraje (`NeoLiquidGlass`)?
   Není ornament teď moc průhledný (styl `LIQUID_*`)? Světlo laseru na hraně
   skla, třesení karet při přesouvání (nepůsobí v brýlích nepříjemně?).

1. Hover: najet na kartu, pak **přímo** na sousední (to byl bug 1b). Zvětšení +
   náklon musí fungovat vždy.
2. Směr náklonu: strana pod kurzorem by se měla "zamáčknout" dozadu. Když je to
   obráceně → `TILT_SIGN = -1f` v `NeoLauncherView`.
3. Rychlost rolování thumbstickem → `WHEEL_GAIN` v `NeoLauncherView`.
4. Průhlednost: je vidět prostředí za panelem? Je rozmazané (blend effects)?
   Jak vypadá průhledný okraj okolo panelu (měl by být neviditelný)?
   Pokud je okraj vidět jako matné sklo, vypnout "Karty vyskakují z panelu".
5. Hloubka ostrosti — plynulost (fps) a jestli AGSL shader funguje. V logcatu
   tag `NeoDepthBlur` hlásí, když shader selže a jede se na záložní blur.
6. Spuštění VR hry i 2D aplikace, Nastavení Questu (`systemux://settings`).
7. Meta tlačítko přes addon (musí být nainstalovaný z legacy workflow).
8. Kukátko: zavře se launcher hned po spuštění hry (bez probliknutí mřížky)?
9. Karusel: 5× klepnout na logo. Směr joysticku (doprava = další karta; když
   obráceně → prohodit znaménko v `CarouselView.onGenericMotionEvent`),
   rychlost opakování (`STICK_*`). Je vidět postupné rozmazání do stran
   (AGSL, `NeoDepthBlur` v logcatu)? Natočení bočních karet (vnější hrana
   k hráči; obráceně → znaménko `sl.ry` v `drawCards`). Otevře štítek
   "Herní čas – povolit" nastavení přístupu k využití? Záložně z PC:
   `adb -P 5038 shell appops set com.neolauncher.v1 GET_USAGE_STATS allow`.
10. Záře: je vidět barevná záře hovernuté karty a modrá záře okolo skla?
    Není moc? (alfa v `drawCardBody` / `drawGlass`)
11. Rychlé menu: otevře se klepnutím na hodiny? Tlačítkem menu na levém
    ovladači? Mění posuvník jas Questu (po povolení "Úprava systémových
    nastavení"; záložně `adb -P 5038 shell appops set com.neolauncher.v1
    WRITE_SETTINGS allow`)? Hlasitost? Co otevřou dlaždice?
12. Pružiny: nepůsobí hover/záložky moc "gumově"? (`HOVER_SPRING` atd.
    nahoře v `NeoLauncherView`, tlumení 1 = bez překmitu)
13. Nastavení (dashboard) a rychlé menu ve světlém skle: je text dobře
    čitelný na Questu? Případně ztmavit `Glass.GLASS_TOP/BOTTOM`. Zkusit
    „Styl skla: visionOS“ pro hlavní panel.
14. Nové rozložení: je ornament nahoře a lišta vlevo dobře vidět i proti
    prostředí (kus leží mimo panel)? Rozbalí se lišta po najetí? Není
    průhledná část okna kolem panelu vidět jako rozmazaný obdélník
    (systémové rozmazání prostředí)? Velikost okna je 1176×664 dp.

## Meta tlačítko — doplněk „Neo – Meta tlačítko“ (od 2026-09-30, modul `metaaddon/`)

Meta tlačítko do aplikací nechodí a přepsat natvrdo nejde. Řešení **přesně
jako Lightning Launcher**: samostatná malá aplikace se službou přístupnosti
(`com.neolauncher.meta`, třídy `MetaService`, `CommandReceiver`, `Neo`).

- **Proč samostatně:** Android 13+ nedovolí zapnout službu přístupnosti
  aplikaci nainstalované ručně ze souboru („Omezené nastavení“ – uživateli
  se to stalo s vestavěnou službou v Neu, v2.0.4x–56). Aplikaci, kterou
  nainstaluje jiná aplikace přes `PackageInstaller` session (výchozí zdroj),
  Android za ručně nainstalovanou nepovažuje → jde zapnout. LL takhle instaluje
  svůj doplněk (`RemotePackageUpdater.installApk`).
- **Přibalení:** CI (`build.yml`) nejdřív postaví `:metaaddon:assembleDebug`,
  zkopíruje APK do `app/src/main/assets/meta-addon.apk` (v `.gitignore`)
  a pak postaví Neo. Do releasu jde i `NeoMetaTlacitko-*.apk` (až jako
  druhý asset – staré aktualizátory berou první `.apk`; nový bere
  `NeoLauncher*`). Verze doplňku = `versionCode` v `metaaddon/build.gradle`
  (**zvýšit při každé změně kódu doplňku**) – Neo porovná přibalenou verzi
  (`getPackageArchiveInfo`) s nainstalovanou a nabídne „Aktualizovat“.
- **Postup pro uživatele:** Nastavení → Quest → Meta tlačítko →
  Nainstalovat (`LauncherActivity.installMetaAddon` → `ApkInstaller.installFile`,
  `KIND_ADDON`) → Quest potvrdí → Neo otevře Přístupnost v Android nastavení
  (`MetaAddon.openSettings` = `ACTION_ACCESSIBILITY_SETTINGS` +
  `setPackage("com.android.settings")`, stejný intent jako LL) → zapnout
  „Neo – Meta tlačítko“. S `WRITE_SECURE_SETTINGS` (adb pm grant pro Neo) se
  doplněk zapne sám (`MetaAddon.selfEnable`).
- **Jak se pozná Meta (jako LL, varianta *navigator*):** událost
  `TYPE_WINDOW_STATE_CHANGED` z **`com.oculus.panelapp.library`** s titulkem
  „Knihovna“ / „Navigátor“ (lokalizované, `NAV_TITLES`). Vestavěná služba
  v2.0.4x hledala jen `com.oculus.systemux` → na novém Questu nereagovala.
  `systemux` + hledání tlačítka Knihovna v okně zůstaly jako záloha.
- **Komunikace:** doplněk čte stav a volby Nea z `ShortcutStateProvider`
  (`content://com.neolauncher.v1.shortcutStateProvider`, sloupce `isVisible`,
  `allowShortcuts`, `metaGameMenu`, `metaAfterGame`, `openOnBoot`,
  `metaTriple`) a otevírá `LauncherActivity` s extra `neo.running` /
  `neo.stopped` / `neo.resume`. Neo posílá příkazy explicitním broadcastem
  na `CommandReceiver` (`SLEEP`, `POWER`, `FORCE_STOP`, `SUPPRESS`,
  `CLEAR_RUNNING`), chráněno signature oprávněním
  `com.neolauncher.permission.CONTROL` (definují ho obě aplikace, stejný klíč).
- **Pravidla** (stejná jako dřív): doma Meta = Neo; když je Neo vidět, další
  stisk = menu Questu; ve VR hře menu Questu (`metaGameMenu`); po hře Neo
  (`metaAfterGame`); po zapnutí Questu Neo (`openOnBoot`); 3× Meta (dvě
  otevření menu do 2,2 s) ze hry = Neo s lištou Pokračovat / Ukončit, v Neu
  = zpět do hry (`metaTriple`); uspat / nabídka vypnutí / vynutit ukončení
  na příkaz z Nea. `MetaAddon.suppress` když Neo samo otevírá systém Questu.
- **Co je v popředí = UsageStats v Neu, ne události oken** (od doplňku v1.1,
  čtvrtý test 2026-09-30: ve hře Meta otevřelo Neo a chyběla lišta – menu
  Questu ve hře posílá i událost `vrshell`, takže doplněk myslel, že hra
  skončila). `data/ForegroundApps` (Neo, oprávnění Přístup k využití) projde
  `UsageEvents` za 8 h: poslední RESUMED / PAUSED / STOPPED každé aktivity →
  `vrPkg` (VR hra RESUMED nebo PAUSED < 5 s = běží i pod menu), `topPkg`,
  `endedVrPkg` (STOPPED < 15 s po ≥ 4 s hraní). Provider je posílá doplňku
  (`usageKnown`, `topPkg`, `vrPkg`, `endedVrPkg`). Doplněk: Navigator + `vrPkg`
  + `metaGameMenu` → menu Questu; lišta/3× cílí na `vrPkg` (jinak `topPkg`);
  událost domova jen naplánuje `checkAfterGame` za 1,5 s (Neo po hře jen při
  `endedVrPkg`). Bez oprávnění záložně události oken. Dlaždice Meta v Nastavení
  → Quest nabídne „Povolit“, když Neo Přístup k využití nemá.
- **Od doplňku v1.2 (pátý test):** Quest hru při otevření menu nejspíš rovnou
  zastaví (STOPPED) → `ForegroundApps.recentVrPkg` (VR hra PAUSED/STOPPED
  < 6 s) a doplněk bere `game = vrPkg ?: recentVrPkg`. `checkAfterGame` nebere
  jako konec hry nic, kolem čeho (±`NAV_NEAR_HOME_MS` 1,5 s) byl Navigator
  (= menu přes hru); po hře posílá `neo.ended` (Neo pro ni lištu neukáže).
  Popředí z událostí oken ignoruje systémové překryvy (nespustitelné balíčky).
  3× Meta má okno 2,2 s a v Neu vrací i do hry z lišty, kterou si Neo našlo
  samo (sloupec `runningPkg`).
- **Hra na pozadí (Neo samo, od v2.0.65):** `ForegroundApps.bgVrPkg` = VR hra,
  která byla naposledy v popředí (novější VR hra by ji na Questu ukončila), teď
  v popředí není a nemá `FLAG_STOPPED` (vynucené ukončení). Ukončenou a jen
  odloženou hru UsageStats neodliší – Android po STOPPED už `ACTIVITY_DESTROYED`
  nepošle – takže lišta se může ukázat i u hry zavřené přes menu Questu (když ji
  Quest nevynuceně neukončí). ✕ / Ukončit ji schová (`Prefs.dismissBackground`,
  platí, dokud hra znovu neodejde do pozadí). `LauncherActivity.checkBackgroundGame`
  v `onResume` (na pozadí přes `queryAsync`), zruší i lištu VR hry, která už neběží.
- **Upozornění na baterii (doplněk, `Alerts`):** 20 % a 10 % při vybíjení, 100 %
  při nabíjení; když je Neo vidět, nic (ukáže bublinu samo). Oznámení Androidu
  (kanál „Baterie“, klepnutí otevře Neo) + hláška (Toast) – co z toho Quest ve hře
  ukáže, je potřeba ověřit. Android 13+: povolení oznámení → průhledná
  `PermissionActivity` doplňku (otevírá ji Neo, `MetaAddon.requestNotifications`).
  Volba `Prefs.batteryAlerts` (Nastavení → Quest), sloupec `batteryAlerts`.
- **Vypnout / Restartovat:** `ui/PowerSheet` (Uspat / Restartovat / Vypnout ve skle
  Nea) → `MetaAddon.power(ctx, "off"|"restart")` → doplněk otevře
  `GLOBAL_ACTION_POWER_DIALOG` a do 4 s v něm sám klepne na položku podle textu
  (`POWER_OFF_LABELS` / `RESTART_LABELS`, prohledá všechna okna díky
  `flagRetrieveInteractiveWindows`, nejdřív SystemUI). Nenajde-li ji, nabídka
  zůstane otevřená.
- **Heuristika z událostí oken, loguje:** `adb -P 5038 logcat -s NeoMeta`
  (od v1.1 i každé okno: „Okno: balíček třída [text]“).
- Když má uživatel zapnutou i službu Lightning Launcheru, otevřou se po
  stisku oba launchery → v Přístupnosti nechat jen „Neo – Meta tlačítko“.
- `ShortcutStateProvider` slouží i starému addonu RedirectServices (kompatibilita).

## Co zbývá / nápady

- Zvuková odezva při hoveru a spuštění
- Vlastní tapeta / pozadí
- **Neo jako výchozí launcher (HOME)** v Android nastavení místo launcheru Questu –
  uživatel to zmínil „do budoucna“ (pátý test), zatím nedělat.
- Lišta běžící aplikace je hotová (3× Meta, viz výše). Od pátého testu si ji Neo
  hledá i samo (viz „Hra na pozadí“) – text níže je z doby před tím. Ukazovala se jen po
  3× Meta / Meta ze hry – uživatel NEchtěl odhad ze statistik (mohla by ukázat
  už zavřenou hru). Případně rozšířit: ukázat ji i po otevření Nea jinak.
- Rozložení jako v Lightning Launcheru: hry velké karty, aplikace malá
  kolečka dole (uživatel zvažuje místo záložek)
- Karusel: podle zpětné vazby z headsetu buď vylepšit (paralaxa, zvuk,
  tapeta), nebo zahodit — je to testovací režim
- Rychlé menu: když Meta tlačítko (addon přenesený do repa) pozná, že ho
  otevřel uživatel, otevřít rovnou rychlé menu; ovladače (baterie) nejdou
  z běžné aplikace přečíst
- Češtinu přesunout do `strings.xml` (teď natvrdo v kódu — Quest češtinu
  jako systémový jazyk nemá, takže `values-cs` by se stejně nepoužilo)

## Náhled bez headsetu (skutečný snímek kódu)

`tools/screenshot/run.sh` spustí skutečný `NeoLauncherView` v Robolectricu
(emulace Androidu na JVM) s nativní grafikou a HW vykreslováním — RenderNode,
RenderEffect i AGSL shader jedou přes opravdovou Skia/HWUI. Stáhne opravdové
bannery her, vyfotí klidový stav, stav s kartou pod "laserem" po odrolování,
6 fází animace spuštění (6× zpomalené), karusel (klid + pohyb), rychlé menu,
světlé sklo, nastavení, hledání, „Co je nového“ a menu karty, a složí to s ilustračním pozadím. Potřebuje jen JDK, Maven a přístup na Maven
Central + GitHub (funguje i v cloudové session). Trvá ~3 min, poprvé stáhne
~350 MB. **Používat po každé změně kreslení** — takhle se našla chyba v bodě 6.
Pozadí a systémové rozmazání prostředí jsou jen ilustrace; skutečný vzhled
průhlednosti na Questu je potřeba ověřit na headsetu.

## Build

APK staví **GitHub Actions** (`.github/workflows/build.yml`) při každém pushi
na jakoukoliv větev. Hotové APK: záložka Actions → konkrétní běh → Artifacts →
`NeoLauncher-APK`.

Lokálně (s Android SDK):
```bash
./gradlew assembleDebug
# výsledek: app/build/outputs/apk/debug/app-debug.apk
```
Potřeba: JDK 17+, Android SDK `platforms;android-36`, `build-tools;36.0.0`.
AGP 9.2.1 + Gradle 9.6.1 (stejné jako Lightning Launcher).

**V cloudové session Claude Code** je `dl.google.com` (a tedy Google Maven
s AGP) zablokovaný síťovou politikou prostředí → plný build tam nejde. Pro
rychlou kontrolu, že se Java přeloží: `tools/compile-check.sh` (přeloží
zdrojáky proti `android-all.jar` z Maven Central). Neověří resources ani
manifest a nehlídá API level — to dělá až CI.

## Instalace na Quest

První instalace jakýmkoliv způsobem sideloadu (adb, SideQuest, Meta Quest
Developer Hub, nebo stáhnout APK v prohlížeči Questu a otevřít v Souborech —
vše vyžaduje vývojářský režim). Další verze už přes aktualizace v launcheru.

```bash
# na PC uživatele běží starý adb server HTC Sync Manageru na 5037 -> vždy -P 5038
adb -P 5038 install -r NeoLauncher-2.0-xxxxxxx.apk
```
Pokud je na headsetu ještě starý fork `com.neolauncher.v1` (jiný podpis),
nejdřív `adb -P 5038 uninstall com.neolauncher.v1`. (Na konci minulé session
byl odinstalovaný.)

## Poznámky pro práci na projektu

- **Novinky: při každé změně, kterou uživatel uvidí, přidej nahoru sekci do
  `app/src/main/assets/novinky.txt`** (`# Nadpis`, pod tím body `• …`, lidsky
  česky s diakritikou – jak píšeš uživateli, ne technicky). Launcher ji ukáže
  po aktualizaci v okně „Co je nového“ a CI ji dá do poznámek k releasu
  (okno aktualizace). Starší sekce nech pod ní.

- **Žádné cizí značky v textech pro uživatele** (UI, `novinky.txt`, poznámky
  k releasu): nepsat „visionOS“, „iOS“, „iPhone“, „Apple“ apod. Inspirace je
  jasná, ale uživatel to tam psát nechce. (Interní poznámky v kódu a tady
  v CLAUDE.md nevadí.)

- **Uživatel mluví česky** — komentáře v kódu i komunikace česky.
  V kódu komentáře bez diakritiky, texty v UI a `.md` s diakritikou.
- **Testovat jde jen na reálném headsetu.** Uživatel je jediný, kdo vidí
  výsledek. Menší dávky změn → chyba jde jednoznačně přiřadit.
- Předchozí pokusy (Gemini, Antigravity) selhaly, protože přidaly moc funkcí
  naráz. V2.0 je výjimka (přepis od nuly) — proto je výše checklist.
- Ladicí konstanty jsou nahoře v `NeoLauncherView` (rozměry, časy, fyzika).

## Licence

Odvozeno z Lightning Launcher (threethan), **GPL-3.0**. Viz `LICENSE`.
