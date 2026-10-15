# Neo Launcher

Vlastní launcher pro **Meta Quest 3S** — skleněný panel, karty her, které při
namíření laserem vyskočí do prostoru, radiální hloubka ostrosti a gumové
rolování. Vzhled vychází z prototypu [`docs/preview_neo.html`](docs/preview_neo.html).

Verze 2.0 je napsaná od nuly (složka `app/`). Načítání bannerů a spouštění
her vychází z [Lightning Launcheru](https://github.com/threethan/LightningLauncher)
(threethan, GPL-3.0).

## Stažení a instalace

1. **Actions** → poslední běh *Build Neo Launcher 2.0* → **Artifacts** →
   `NeoLauncher-APK` (zip s APK).
2. `adb install -r NeoLauncher-2.0-xxxxxxx.apk`
3. Na Questu: Knihovna → Neznámé zdroje → **Neo Launcher**.

Meta tlačítko / dock zkratky: addon z workflow *Legacy* (spouští se ručně),
pak v Neo → Nastavení → "Otevírat Meta tlačítkem".

## Ovládání

- **Namířit** na kartu → vyskočí, nakloní se za kurzorem, okolí se rozmaže
- **Kliknout** → spustit
- **Podržet a táhnout** → přesunout kartu (pořadí se uloží)
- **Podržet a pustit** → menu karty (přejmenovat, vlastní obrázek, skrýt…)
- **Thumbstick / tažení** → rolování
- **Logo Neo** → nastavení; **5× rychle** → testovací karusel (karty ve
  vějíři, do stran rozmazané, joystick ←/→, herní čas a počet spuštění)
- **Hodiny / baterie** (nebo tlačítko menu) → rychlé menu: jas, hlasitost,
  Wi-Fi, Bluetooth, Nastavení a Menu Questu, soubory, prohlížeč, fotoaparát

## Vývoj

Viz [`CLAUDE.md`](CLAUDE.md) — architektura, omezení, checklist testů.

Licence: GPL-3.0 (viz `LICENSE`).
