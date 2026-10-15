# Neo Launcher – předávací dokument (jak navázat)

> Tenhle soubor shrnuje, kam jsme se s Neem dostali (stav k **29. 9. 2026, verze v2.0.65**),
> a jak na to navázat v novém chatu – i s málo kredity. Podrobný technický
> kontext je v [`CLAUDE.md`](../CLAUDE.md) v kořeni repozitáře.

---

## 1. Jak pokračovat (návod pro Jana)

### A) Nejlepší a nejlevnější: Claude Code s tímhle repem
Claude Code je součástí placených plánů (Pro / Max) – nemusí to být stejné kredity,
na kterých vznikala tahle session. Stačí být přihlášený svým účtem s předplatným.
1. Otevři **claude.ai/code** (nebo aplikaci Claude → Code) a založ novou session
   s repozitářem **`lichicks/neo-launcher`** (GitHub propojený se stejným účtem).
2. Claude si **sám načte `CLAUDE.md`** – nemusíš nic vysvětlovat ani nic vkládat.
3. Napiš jen, co chceš změnit (+ screenshot z Questu, jestli jde o vzhled).
4. Na konci ho požádej: „commitni a pushni“. GitHub pak sám postaví novou verzi
   a launcher ti ji v Questu nabídne k aktualizaci.

Tipy, jak šetřit kredity:
- **Jedna změna na zprávu**, krátce a konkrétně („v rychlém menu zmenši hodiny o 10 %“).
- Obrázek s kroužkem řekne víc než odstavec textu.
- Nechtěj po Claudovi „projdi celý projekt“ – `CLAUDE.md` už to shrnuje.
- Náhled bez headsetu (`tools/screenshot/run.sh`) trvá ~3 minuty – vyplatí se
  jen u změn vzhledu. U drobností stačí rychlá kontrola `tools/compile-check.sh`.

### B) Obyčejný chat (claude.ai) bez přístupu k repu
1. Vlož do chatu **tenhle soubor** a napiš, co chceš změnit.
2. Claude řekne, které soubory potřebuje – najdeš je na GitHubu
   (`github.com/lichicks/neo-launcher`, větev `claude/funny-ramanujan-xp8ba6`)
   a jejich obsah vložíš / nahraješ do chatu.
3. Upravený soubor vložíš zpátky na GitHub: otevři soubor → tužka (Edit) →
   vlož nový obsah → **Commit changes** do stejné větve.
4. GitHub Actions postaví APK (~5 min) a vydá novou verzi; launcher ji nabídne.
   Když build selže (červený křížek v záložce Actions), pošli Claudovi text chyby.

### Úvodní zpráva ke zkopírování do nového chatu
```
Pokračujeme na mém launcheru Neo pro Meta Quest 3S (Android, Java, jeden vlastní View).
Repo: github.com/lichicks/neo-launcher, větev claude/funny-ramanujan-xp8ba6.
Přikládám předávací dokument (docs/POKRACOVANI.md) – drž se pravidel v něm
(česky, malé dávky změn, mezery/barvy z tokenů, novinky do novinky.txt, žádné cizí značky v UI).
Chci změnit: …
```

---

## 2. Co je Neo

Vlastní launcher pro **Meta Quest 3S**, napsaný od nuly (v2.0) v Javě, složka `app/`.
Package `com.neolauncher.v1`. Je to 2D panel v prostoru Questu:

- **Skleněný panel s mřížkou her** (karty 1.6:1, 3–6 sloupců), při najetí laserem karta
  „vyskočí“ (zvětšení, náklon, záře v barvě hry), okolí se rozmaže (hloubka ostrosti).
- **Horní bublina (ornament)** napůl zanořená do panelu: záložky Hry / Aplikace / Vše,
  čas (bez data), baterie. Klepnutí na čas/baterii = rychlé menu.
- **Levá lišta**: logo Neo, Knihovna, Hledat, Karusel, Rychlé menu, Nastavení
  (po najetí se rozbalí s popisky).
- **Rychlé menu** (boční panel vpravo): hodiny + datum, Wi-Fi, baterie, „Naposledy hráno“
  s tlačítkem Hrát, posuvníky jasu a hlasitosti, dlaždice (Wi-Fi, Bluetooth, Nastavení
  Questu, Menu Questu, Soubory, Prohlížeč, Fotoaparát), tlačítko „Nastavení Nea“.
- **Nastavení** jako dashboard s dlaždicemi (Vzhled, Aplikace, Quest, Aktualizace, O Neo).
- **Karusel** (testovací režim, 5× klepnout na logo): karty ve vějíři se statistikami.
- **Animace spuštění „kukátko“**, po spuštění hry se launcher zavře.
- Hledání, oblíbené (hvězdička), štítek NOVÉ, řazení (i podle herního času),
  paměť pozice rolování, „Co je nového“ po aktualizaci, varování slabé baterie.
- **Aktualizace přímo v launcheru** z GitHub Releases (repo je veřejné).

---

## 3. Stav

- Poslední verze: **v2.0.65** (testovací buildy z větve `claude/funny-ramanujan-xp8ba6`
  PR #1 do `main` je otevřený). APK: GitHub → Releases → nejnovější `NeoLauncher-2.0.xx.apk`.
- **29. 9. první test na Questu: „funguje až podivuhodně super“.** Opraveno podle testu:
  Meta tlačítko (vestavěná služba, viz níže), karusel (pryč „Spuštěno 0×“ a datum
  instalace, nápověda volitelná), silnější světlo hry na skle, nástup karet při otevření.
- **Meta tlačítko** (dříve služba přímo v Neu, od 30. 9. doplněk `metaaddon/`):
  zapíná se v Nastavení → Quest → „Meta tlačítko otevře Neo“. Doma Meta = Neo, když je
  Neo otevřené, další stisk = menu Questu, ve hře menu Questu, po hře se otevře Neo.
  Je to heuristika – **ještě neověřená na headsetu**; logy `adb -P 5038 logcat -s NeoMeta`.
  Službu Lightning Launcheru v Přístupnosti vypnout (jinak se otevřou oba).
- **Nové (29. 9., zatím neověřené na headsetu):** instalace APK z Nea (Nastavení → Aplikace),
  velikost her v menu karty + řazení „Velikost“, záloha a obnova nastavení (Nastavení → O Neo),
  paralaxa (po druhém testu odstraněna).
- **Pátý test (30. 9.):** otevření po zapnutí a Uspat fungují. Ve hře ale Meta pořád
  otevíralo Neo, 3× Meta nic a lišta hry na pozadí chyběla (Beat Saber vybil baterii).
  Uživatel teď chce, aby Neo **samo vědělo, co běží na pozadí**. Opraveno (doplněk v1.2):
  Neo si hru na pozadí najde samo ze statistik využití a ukáže lištu (✕ ji schová),
  doplněk počítá i s tím, že Quest hru pod menu zastaví, upozornění na baterii i ve hře
  (20 / 10 / 100 %), vlastní okno Vypnout (Uspat / Restartovat / Vypnout – doplněk klepne
  v systémové nabídce sám). Neo jednou nabídne aktualizaci doplňku a Přístup k využití.
  Do budoucna: Neo jako výchozí launcher v Android nastavení.
- **Čtvrtý test (30. 9.):** Meta doma i otevření po zapnutí Questu fungují. Ve hře se ale
  otevíralo Neo a chyběla lišta Pokračovat / Ukončit → doplněk teď bere „co běží“ ze statistik
  využití v Neu (`data/ForegroundApps`), ne z událostí oken; doplněk v1.1.
- **Třetí test (30. 9.):** vše kromě Meta tlačítka OK. Meta tlačítko je teď samostatný
  doplněk „Neo – Meta tlačítko“ (modul `metaaddon/`), který si Neo samo nainstaluje – přesně
  jako Lightning Launcher (takto nainstalovanou aplikaci Android pustí zapnout v Přístupnosti).
  Meta se pozná podle okna Knihovny `com.oculus.panelapp.library` (jako LL), ne systemux.
- **Druhý test (29. 9. v noci):** skoro vše fajn. Opraveno: Quest přesměrovával
  odkazy na nastavení do Nastavení Questu → teď Android nastavení (jako Lightning
  Launcher); zapnutí služby Meta tlačítka blokuje Android „Omezené nastavení“ →
  Informace o aplikaci → ⋮ → Povolit omezená nastavení (nebo z PC `adb -P 5038 shell
  appops set com.neolauncher.v1 ACCESS_RESTRICTED_SETTINGS allow`); paralaxa pryč;
  Streamovat → aplikace Fotoaparát; nová dlaždice Android nastavení; nástup karet
  výraznější a až je okno vidět.
- **Nové (29. 9. noc, neověřené):** 3× Meta ve hře otevře Neo s lištou dole
  (Pokračovat / Ukončit / skrýt; Ukončit klepne za uživatele na Vynutit ukončení
  v informacích o aplikaci), v Neu 3× Meta = zpět do hry; rychlé menu má dlaždice
  Streamovat (sdílení Questu), Uspat a Vypnout (přes službu Meta tlačítka) a odhad
  výdrže baterie.
- **Nové (29. 9. večer, neověřené):** Neo se otevře po zapnutí Questu; živá bublina
  v horní liště (instalace, stahování aktualizace, záloha, nabíjení s odhadem, slabá baterie –
  nahradila vyskakovací hlášky); tekuté sklo horní bubliny (lom u okraje, AGSL); hrana skla
  se rozsvítí u laseru; při přesouvání se karty třesou, uhnou a ukáže se cílová „jamka“.
- První instalace: sideload (adb / SideQuest / stáhnout APK v prohlížeči Questu),
  další verze už přes aktualizace v launcheru. Na PC běží starý adb na portu 5037 →
  vždy `adb -P 5038 install -r NeoLauncher-2.0.xx.apk`.

---

## 4. Pravidla a přání (drž se jich)

**Komunikace a proces**
- Uživatel mluví **česky**. Komentáře v kódu česky **bez diakritiky**, texty v UI a `.md` s diakritikou.
- **Malé dávky změn** – testovat jde jen na headsetu, chyba musí jít přiřadit.
- **Každá změna, kterou uživatel uvidí → nová sekce nahoře v `app/src/main/assets/novinky.txt`**
  (`# Nadpis`, pod tím `• body`, lidsky česky). Ukáže se v „Co je nového“ a v poznámkách k verzi.
- **Žádné cizí značky v UI ani novinkách** (visionOS, iOS, iPhone, Apple…).

**Vzhled (schválený styl)**
- Matné **sklo** podle předloh uživatele (smart-home dashboardy, visionOS okna):
  průhledné, rozmazané pozadí prosvítá, okraj s přechodem (nahoře jasný, po stranách slábne).
  Jeden recept pro všechno: `ui/GlassSurface.java` (+ `GlassDrawable` pro dialogy).
- **Paleta** (`ui/Palette.java`, vybral uživatel z dsgn.house): Void Black `#06070A`,
  Electric Cobalt `#3D5AFE`, Synth Magenta `#FF2FA3`, Toxic Amber `#FF8A1E`,
  Titanium Fog `#AEB6C2`, Holographic Pearl `#F5F7FF`. Žádné jiné barvy „od oka“.
- **Ikony** = sada Lucide (jednoduché, zaoblené čáry) – `tools/icons/gen_icons.py`.
- **Mezery a zaoblení z tokenů** v `ui/Glass.java`: okraj `PAD` 20, sekce `SECTION` 20,
  mezi dlaždicemi `GAP` 12, malé prvky `GAP_S` 8, dlaždice radius 20, panel 40
  (soustředné rohy), hlavní tlačítko 48, velký nadpis nahoře 30.
- **Hover = zesvětlení skla, žádná barevná záře** (modrá záře uživateli vadila).
- Pozadí za dialogem: jen rozmazat + oživit barvy, **ne** šedé „zakalení“.
- Rozhodující předloha chování: `docs/preview_neo.html` („přesně takhle to chci“).

**Uživatel NEchce:** testovací funkce (bezpečný režim, diagnostika, FPS), „vrátit zpět“
po skrytí, vlastní kategorie, weby jako karty, denní přehled herního času.
Zvuky/haptika a počasí možná později.

---

## 5. Co je v plánu

**Další krok:**
1. Ověřit na headsetu Meta tlačítko ve hře, 3× Meta, lištu hry na pozadí, upozornění
   na baterii a Vypnout / Restartovat (checklist bod 0 v `CLAUDE.md`) – a doladit podle
   logu `adb -P 5038 logcat -s NeoMeta` (uživatel ho má poslat).
2. Lišta běžící aplikace je hotová (3× Meta i vlastní kontrola v Neu). Když by ukazovala
   hry zavřené přes menu Questu, hledat, jak Quest „Ukončit“ pozná (statistiky to neodliší).

**Nové nápady (29. 9. večer, uživatel zatím nevybral; odhad výdrže, uspat/vypnout
a dvojí/trojí Meta už jsou hotové):** úsporný režim při slabé baterii (vypne efekty, ztlumí jas),
varování před přehřátím (PowerManager thermal status) v bublině, kontrola baterie před
spuštěním velké hry, „Uvolnit místo“ (velké hry nehrané 30+ dní), kvalita Wi-Fi pro
streamování z PC (pásmo, rychlost linky, ping), ADB přes Wi-Fi jedním klepnutím,
dvojí stisk Meta = předchozí aplikace, Snímek obrazovky v rychlém menu
(globální akce služby přístupnosti), velká karta „Pokračovat“ s poslední hrou nahoře.

**Starší nápady, které uživatel zatím nevybral:** info bublina při delším najetí, štítek
AKTUALIZOVÁNO, režim pro návštěvu, „Nevím, co hrát“, motivy (víc palet), sklo podle denní
doby, oživené obrázky; ovládání joystickem jako na konzoli;
tlačítko „…“ na kartě místo držení; vlastní pozadí; varování při plném úložišti.
Ovládání hudby jen pokud jde ukázat jen když něco hraje (spíš ne). Připomínka pauzy: ne.
Starší nápady: rozložení jako Lightning Launcher (hry velké, aplikace kolečka),
paralaxa mřížky, rychlé menu přímo z Meta tlačítka.

---

## 6. Jak se staví, vydává a ověřuje

- **Build dělá GitHub Actions** při každém pushi (`.github/workflows/build.yml`).
  Změny jen v `.md` / `docs/` build nespouští. Každý build = GitHub Release
  `v2.0.<číslo běhu>` (mimo `main` jako testovací/prerelease), `versionCode = 2000 + číslo běhu`.
- Launcher kontroluje nové verze při otevření (max 1× za 6 h) nebo tlačítkem v nastavení;
  testovací buildy nabízí (výchozí zapnuto).
- Podpis pevným klíčem `keystore/neo-debug.keystore` → aktualizace bez ztráty nastavení.
- **Bez headsetu:** `tools/compile-check.sh` (rychlá kontrola, že se Java přeloží) a
  `tools/screenshot/run.sh` (skutečný kód v emulaci, vyfotí mřížku, hover, kukátko,
  karusel, rychlé menu, nastavení, hledání, menu karty – ~3 min, výstup do `out/`).
- V cloudové session Claude Code je `dl.google.com` zablokovaný → plný Gradle build
  lokálně nejde, jen přes CI.

---

## 7. Technické pasti (naučeno tvrdě)

1. **Žádný blur na jednotlivých kartách** – headset to neutáhne (jednou spadl).
   Jeden efekt na celou vrstvu, stíny předpočítané.
2. **Stav nikdy z konce animace** (`withEndAction`) – všechno se dopočítává z času
   (`Spring`, `Eased`), zavření dialogu mění stav hned.
3. **Fokus/hover = jeden zdroj pravdy**, počítaný z polohy laseru (Quest neposílá
   spolehlivě HOVER_ENTER/EXIT mezi kartami). HOVER_EXIT chodí i těsně před stiskem → 90 ms odklad.
4. **3D náklon jen přes `RenderNode.setRotationX/Y`** (vlastní perspektivní matice kartu
   po odrolování nevykreslila).
5. **AGSL shadery** (hloubka ostrosti, čočka kukátka) jsou vidět až na headsetu – emulace je neumí.

---

## 8. Mapa hlavních souborů (`app/src/main/java/com/neolauncher/`)

| Soubor | Co dělá |
|---|---|
| `LauncherActivity.java` | Jediná aktivita, skládá všechno, otevírá dialogy |
| `ui/NeoLauncherView.java` | Panel, mřížka, hover, ornament, levá lišta (ladicí konstanty nahoře) |
| `ui/QuickMenuView.java` | Rychlé menu |
| `ui/SettingsSheet.java`, `SearchSheet.java`, `AppMenu.java`, `WhatsNewSheet.java`, `UpdateSheet.java` | Dialogy |
| `ui/CarouselView.java` | Karusel |
| `ui/Glass.java`, `GlassSurface.java`, `GlassDrawable.java`, `Palette.java` | Tokeny mezer, sklo, barvy |
| `ui/Icons.java`, `IconPaths.java`, `SvgPath.java` | Ikony Lucide |
| `ui/PeepholeAnimation.java`, `LaunchLens.java` | Animace spuštění |
| `ui/Spring.java`, `ModalDepth.java`, `OverlayHost.java` | Fyzika, ustoupení launcheru, dialogy „vyrůstající“ z místa |
| `data/AppRepository.java`, `Prefs.java`, `UsageInfo.java` | Seznam aplikací, nastavení, herní čas |
| `art/ArtworkLoader.java` | Obrázky her (bannery) |
| `launch/AppLauncher.java` | Spouštění aplikací na Questu |
| `update/Updater.java` | Aktualizace z GitHubu |
| `app/src/main/assets/novinky.txt` | Texty „Co je nového“ |

Licence: GPL-3.0 (odvozeno z Lightning Launcheru, threethan); ikony Lucide (ISC) –
viz `THIRD_PARTY_NOTICES.md`.
