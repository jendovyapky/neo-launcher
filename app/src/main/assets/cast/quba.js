/* =====================================================================
   QUBA – maskot Quest Castu. Jméno se nikde neukazuje.
   Stejná technika jako Mraq na jendovyapky.eu: všechno se kreslí na canvas
   v plné snímkové frekvenci, pohyby se dotahují exponenciálně (nezávisle na fps),
   scéna má paralaxu podle myši / prstu / náklonu telefonu s hloubkou ostrosti.

   Použití:
     const q = Quba.mount(document.getElementById('quba'), {
       scene: document.body,        // kam dát canvas s pozadím (vyplní ho celé)
       poke: ['Hej!'],              // hlášky po šťouchnutí
     });
     q.set('hleda', 'Hledám Quest…');          // nálada + věta
     q.set('radost', 'Jedem!', { act: 'hop' }); // + jednorázová akce
     q.say('Jen věta');  q.pause(true);         // pause = nekreslit (když běží video)

   Prvky s atributem data-quba-look Quba sleduje pohledem, když na ně najede myš.

   Nálady: hleda, radost, pohoda, ospaly, spi, chyba, nadseni
   Akce:   hop, shake, yawn, wink, wake, roll, scan

   Tenhle soubor je ve dvou kopiích: jendovyapky/quest (public/quba.js)
   a v Quest appce (android/app/src/main/assets/quba.js). Měnit oba.
   ===================================================================== */
(function () {
  const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const rgba = (c, a = 1) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a})`;
  const ease = (rate, dt) => 1 - Math.pow(rate, dt / 1000); // podíl, o který se hodnota za dt přiblíží cíli

  /* ------------------------------------------------------------ nálady */
  const BASE = { open: 1, lidT: 0, happy: 0, eyeW: 15, eyeH: 17, round: .45, spark: 0, asym: 0, wide: 0 };
  const MOODS = {
    hleda:   { color: '#8fe4ff', p: { open: .95, eyeW: 14 }, idle: 'scan', every: [2400, 3200] },
    radost:  { color: '#ffd76a', p: { happy: .8, eyeH: 16 }, idle: 'hop' },
    pohoda:  { color: '#8fe4ff', p: {} },
    ospaly:  { color: '#a3a9e8', p: { open: .42, lidT: -.35 }, idle: 'yawn', look: .4 },
    spi:     { color: '#8b84e8', p: { open: 0 }, sleep: true },
    chyba:   { color: '#ff8a7a', p: { open: .8, lidT: .3, asym: .6 }, idle: 'shake' },
    nadseni: { color: '#8fe4ff', p: { eyeW: 16, eyeH: 19, spark: 1, round: .6, wide: 1 }, idle: 'hop' },
  };

  /* ------------------------------------------------------------ tečkové oči
     Oko = zaoblený obdélník poskládaný z teček (jako písmo Doto). Mřížka je
     vycentrovaná na oko, takže se s pohledem posouvá plynule celé oko. */
  const PITCH = 5.4, DOT = 4.5;
  function eyeDots(x0, y0, p, side, openMul, out) {
    const open = clamp(p.open * openMul * (side > 0 ? 1 - p.asym * .5 : 1), 0, 1.2);
    const w = p.eyeW * (1 + p.wide * .1), H = p.eyeH * (1 + p.wide * .12), hh = Math.max(.1, H * open);
    const n = Math.ceil((w + PITCH) / PITCH), m = Math.ceil((H + PITCH) / PITCH);
    if (open < .16) { // zavřené = řádka teček
      const yy = y0 + H * .25;
      for (let k = -n; k <= n; k++) { const x = x0 + k * PITCH; if (Math.abs(x - x0) <= w * .9) out.dots.push(x, yy); }
      return;
    }
    const r = Math.min(w, hh) * p.round, hx = x0 + w * .45, hy = y0 - hh * .5;
    for (let j = -m; j <= m; j++) {
      const y = y0 + j * PITCH;
      for (let k = -n; k <= n; k++) {
        const x = x0 + (k + .5) * PITCH, dx = Math.abs(x - x0), dy = Math.abs(y - y0);
        if (dx > w || dy > hh) continue;
        if (dx > w - r && dy > hh - r && (dx - (w - r)) ** 2 + (dy - (hh - r)) ** 2 > r * r) continue;
        const u = ((x - x0) / w) * -side; // +1 u nosu, −1 venku
        const drop = p.lidT > 0 ? p.lidT * hh * 1.25 * (u + 1) / 2 : -p.lidT * hh * 1.25 * (1 - u) / 2;
        if (y < y0 - hh + drop) continue;
        if (p.happy >= .5) { // ∩
          const ay = y0 + hh * .4, ry = hh * 1.1, t = PITCH * 1.8;
          if (y > ay + .1) continue;
          const o = (dx / w) ** 2 + ((y - ay) / ry) ** 2, i = (dx / Math.max(1, w - t)) ** 2 + ((y - ay) / Math.max(1, ry - t)) ** 2;
          if (o > 1.08 || i < 1) continue;
        } else if (p.happy > .02) { const ey = y0 + hh * 1.2, rx = w * 1.2, ry = hh * 1.2 * p.happy; if ((dx / rx) ** 2 + ((y - ey) / ry) ** 2 < 1) continue; }
        if (p.spark > .3 && Math.abs(x - hx) < PITCH * .6 && Math.abs(y - hy) < PITCH * .6) { out.spark.push(x, y); continue; }
        out.dots.push(x, y);
      }
    }
  }

  /* ------------------------------------------------------------ tvar brýlí (jednotky 240 × 170) */
  const rr = (x, y, w, h, r) => { const p = new Path2D(); p.roundRect(x, y, w, h, r); return p; };
  const STRAP = new Path2D('M44 86 C 40 22, 200 22, 196 86');
  const SHELL = rr(20, 52, 200, 100, 42), GLASS = rr(36, 64, 168, 76, 32);
  const EYE_L = [98, 102], EYE_R = [142, 102], EYE_SCALE = 1.32;

  /* ------------------------------------------------------------ pozadí: noc, hvězdy, plovoucí „VR okna“ */
  const SKY = ['#0c1030', '#1d2456', '#33307a'].map(hex), GLOW = hex('#5a54b8');
  let seed = 11; const R = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
  const STARS = Array.from({ length: 110 }, () => [(R() - .5) * 5, (R() - .5) * 4, 4 + R() * 2, .3 + R() * .7, R() * 6.28]);
  // [x, y, z, šířka, poměr, rychlost, fáze] – daleko víc menších, blízko jen pár velkých na krajích
  const PANES = [];
  for (let i = 0; i < 5; i++) PANES.push([(i - 2) * 1.05 + (R() - .5) * .4, -.9 + R() * 1.9, 3 + R() * 1.2, .7 + R() * .5, .56 + R() * .2, .008 + R() * .008, R() * 6]);
  for (let i = 0; i < 2; i++) PANES.push([(i ? 1 : -1) * (.95 + R() * .2), (i ? -.35 : .25) + R() * .2, 1.6 + R() * .3, .4 + R() * .15, .62, .014, R() * 6]);
  PANES.push([-.62, .55, .45, .55, .6, .02, R() * 6], [.66, -.62, .5, .5, .6, .02, R() * 6]);

  function paneSprite() { // jedno „okno“ ve třech ostrostech (hloubka ostrosti)
    const W = 320, H = 200, PAD = 40, c = document.createElement('canvas'); c.width = W + PAD * 2; c.height = H + PAD * 2;
    const x = c.getContext('2d'); x.translate(PAD, PAD);
    const g = x.createLinearGradient(0, 0, W, H);
    g.addColorStop(0, 'rgba(170,180,255,.22)'); g.addColorStop(1, 'rgba(120,110,230,.08)');
    x.fillStyle = g; x.beginPath(); x.roundRect(10, 10, W - 20, H - 20, 26); x.fill();
    x.strokeStyle = 'rgba(220,225,255,.28)'; x.lineWidth = 3; x.stroke();
    x.fillStyle = 'rgba(220,225,255,.18)';
    x.beginPath(); x.roundRect(34, 36, 110, 12, 6); x.fill();
    x.beginPath(); x.roundRect(34, 62, 200, 9, 4.5); x.fill();
    x.beginPath(); x.roundRect(34, 82, 160, 9, 4.5); x.fill();
    x.fillStyle = 'rgba(143,228,255,.16)'; x.beginPath(); x.roundRect(34, 112, 120, 54, 12); x.fill();
    x.beginPath(); x.roundRect(166, 112, 120, 54, 12); x.fill();
    const canFilter = 'filter' in x && (() => { const t = document.createElement('canvas').getContext('2d'); t.filter = 'blur(2px)'; return t.filter === 'blur(2px)'; })();
    const blur = (px, f) => {
      const b = document.createElement('canvas'); b.width = Math.ceil(c.width / f); b.height = Math.ceil(c.height / f);
      const bx = b.getContext('2d'); bx.imageSmoothingQuality = 'high';
      if (canFilter) bx.filter = `blur(${px / f}px)`;
      bx.drawImage(c, 0, 0, b.width, b.height); return b;
    };
    return [c, blur(5, 2), blur(16, 3)]; // ostré / mírně / hodně rozmazané (hladce, ne kostičky)
  }
  const PANE_PAD = 40 / 320; // okraj spritu kvůli rozmazání (podíl šířky)

  /* ------------------------------------------------------------ CSS (bublina + místo pro postavičku) */
  const CSS = `
  .quba { display: flex; flex-direction: column; align-items: center; gap: 18px; position: relative; z-index: 2; }
  .quba-spot { width: min(340px, 72vw); aspect-ratio: 240 / 205; cursor: pointer; -webkit-tap-highlight-color: transparent; }
  .quba-bubble { position: relative; max-width: min(520px, 90vw); min-height: 54px; padding: 14px 22px; border-radius: 22px;
    background: rgba(18, 20, 34, .72); border: 1px solid rgba(255,255,255,.09); backdrop-filter: blur(16px) saturate(140%);
    -webkit-backdrop-filter: blur(16px) saturate(140%); color: #eef1f6; font: 500 17px/1.45 "Geist Mono", ui-monospace, monospace;
    text-align: center; box-shadow: 0 10px 40px rgba(0,0,0,.25); will-change: transform;
    transition: opacity .35s ease, scale .45s cubic-bezier(.2,1.4,.4,1); }
  .quba-bubble.pop { opacity: 0; scale: .92; transition: none; }
  .quba-bubble::before { content: ""; position: absolute; top: -8px; left: 50%; width: 15px; height: 15px; margin-left: -7.5px;
    background: rgb(24, 26, 42); border-left: 1px solid rgba(255,255,255,.09); border-top: 1px solid rgba(255,255,255,.09);
    transform: rotate(45deg); border-top-left-radius: 3px; }
  .quba-caret { display: inline-block; width: .55em; height: 1.05em; margin-left: 2px; vertical-align: -2px;
    background: var(--quba-eye, #8fe4ff); animation: quba-caret 1s steps(1) infinite; }
  @keyframes quba-caret { 50% { opacity: 0; } }
  .quba-canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; pointer-events: none; z-index: 0; }
  `;

  /* ================================================================== mount */
  function mount(root, opts = {}) {
    if (!document.getElementById('quba-css')) {
      const st = document.createElement('style'); st.id = 'quba-css'; st.textContent = CSS; document.head.append(st);
    }
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
    root.classList.add('quba');
    root.innerHTML = '<div class="quba-spot" title="Šťouchni do mě"></div><div class="quba-bubble"><span class="quba-say"></span></div>';
    const spot = root.querySelector('.quba-spot'), bubble = root.querySelector('.quba-bubble'), say = root.querySelector('.quba-say');

    const host = opts.scene || document.body;
    const cv = document.createElement('canvas'); cv.className = 'quba-canvas';
    if (host === document.body) { cv.style.position = 'fixed'; }
    else if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
    host.prepend(cv);
    const g = cv.getContext('2d');
    let W = 0, H = 0, DPR = 1;
    const size = () => {
      DPR = Math.min(2, devicePixelRatio || 1);
      const r = host === document.body ? { width: innerWidth, height: innerHeight } : host.getBoundingClientRect();
      W = r.width; H = r.height; cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR);
    };
    size(); addEventListener('resize', size);
    const sprites = paneSprite();
    const eyeLayer = document.createElement('canvas'), eg = eyeLayer.getContext('2d');

    /* ---- stav ---- */
    let mood = 'hleda', moodC = hex(MOODS.hleda.color), target = { ...BASE, ...MOODS.hleda.p };
    const E = { ...target, lx: 0, ly: 0 };          // aktuální výraz + pohled
    const T = { lx: 0, ly: 0 };                     // cíl pohledu
    const body = { y: 0, vy: 0, x: 0, vx: 0, tilt: 0, sx: 1, sy: 1 };
    const pointer = { x: null, y: null, nx: 0, ny: 0, tnx: 0, tny: 0, hover: null, last: -1e9 };
    const cam = { x: 0, y: 0 };
    let action = null, blinkT = -1e9, nextBlink = performance.now() + 1500, nextIdle = performance.now() + 2200;
    let talking = 0, typeTimer = 0, last = 0, paused = false, pokeIdx = 0, zzz = [];

    const ACTIONS = {
      scan: () => { const xs = [-1, -1, .9, .9, 0]; return { dur: 2300, fn: (k) => ({ lx: xs[Math.min(4, Math.floor(k * 5))], ly: -.2 }) }; },
      hop: () => { body.vy = -420; return { dur: 700, fn: (k) => ({ p: { happy: Math.max(target.happy, Math.sin(k * Math.PI) * .9) } }) }; },
      yawn: () => ({ dur: 1700, fn: (k) => { const s = Math.sin(k * Math.PI); return { p: { open: target.open * (1 - s * .9), lidT: -.6 * s }, ly: .5 }; } }),
      shake: () => { body.vx = 520; return { dur: 650, fn: () => ({}) }; },
      wink: () => ({ dur: 650, fn: (k) => ({ openR: 1 - Math.sin(k * Math.PI), p: { happy: .5 * Math.sin(k * Math.PI) } }) }),
      wake: () => { body.vy = -300; return { dur: 1300, fn: (k) => { const s = Math.sin(k * Math.PI); return { p: { open: s * .75, wide: s }, lx: Math.sin(k * 9) * .6 * s }; } }; },
      roll: () => ({ dur: 1300, fn: (k) => { const a = Math.PI * (1 + k * 1.15), s = Math.sin(k * Math.PI); return { lx: Math.cos(a) * .95 * s, ly: -.95 * s + Math.sin(a) * .2, p: { open: lerp(target.open, .62, s) } }; } }),
    };
    const run = (n) => { const a = ACTIONS[n]?.(); if (a) action = { ...a, t0: performance.now() }; };

    /* ---- kde je postavička (podle místa v rozvržení + paralaxa) ---- */
    function figRect() {
      const s = spot.getBoundingClientRect(), h = host === document.body ? { left: 0, top: 0 } : host.getBoundingClientRect();
      const k = s.width / 240;
      return { x: s.left - h.left + s.width / 2 - cam.x * W * .35, y: s.top - h.top + s.height * .52 - cam.y * H * .2, k };
    }

    /* ---- chování ---- */
    function behave(t, dt) {
      const f = figRect(), M = MOODS[mood];
      let tx = null, ty = null;
      if (pointer.hover) { const b = pointer.hover.getBoundingClientRect(); tx = b.left + b.width / 2; ty = b.top + b.height / 2; }
      else if (t - pointer.last < 3500 && pointer.x != null) { tx = pointer.x; ty = pointer.y; }
      if (tx != null && !M.sleep) {
        const hr = host === document.body ? { left: 0, top: 0 } : host.getBoundingClientRect();
        const dx = (tx - hr.left - f.x) / (f.k * 260), dy = (ty - hr.top - f.y) / (f.k * 260);
        const d = Math.hypot(dx, dy) || 1, m = Math.min(1, d);
        T.lx = dx / d * m; T.ly = dy / d * m;
      } else if (t > nextIdle) { // volné rozhlížení
        const roll = Math.random();
        if (M.sleep) { T.lx = 0; T.ly = 0; }
        else if (roll < .55) { T.lx = (Math.random() - .5) * 1.8; T.ly = (Math.random() - .5) * .9; }
        else if (roll < .8) { T.lx = 0; T.ly = 0; }
        else if (M.idle) run(M.idle);
        else run(Math.random() < .5 ? 'roll' : 'wink');
        const [a, b] = M.every || [1300, 3800];
        nextIdle = t + a + Math.random() * (b - a);
      }
      // akce přebíjí cíl
      let over = {}, openL = 1, openR = 1, lx = T.lx, ly = T.ly + (M.look || 0);
      if (talking > t && !M.sleep) ly += Math.sin(t / 70) * .08; // při mluvení oči lehce poskakují
      if (action) {
        const k = (t - action.t0) / action.dur;
        if (k >= 1) action = null;
        else { const r = action.fn(k); over = r.p || {}; openL = r.openL ?? 1; openR = r.openR ?? 1; if (r.lx != null) lx = r.lx; if (r.ly != null) ly = r.ly; }
      }
      // mrkání (občas dvojité)
      if (!M.sleep && t > nextBlink) { blinkT = t; nextBlink = t + 1800 + Math.random() * 3800; if (Math.random() < .2) nextBlink = t + 260; }
      const bk = (t - blinkT) / 170, blink = bk >= 0 && bk < 1 ? Math.abs(Math.cos(bk * Math.PI)) : 1;
      // dotahování – pohled rychle, výraz pomaleji
      const fl = Math.min(1, ease(.0015, dt) * 1.6), fe = ease(.02, dt);
      E.lx = lerp(E.lx, lx, fl); E.ly = lerp(E.ly, ly, fl);
      for (const key in BASE) E[key] = lerp(E[key], over[key] ?? target[key], over[key] != null ? Math.min(1, fe * 3) : fe);
      const c = hex(M.color); moodC = moodC.map((v, i) => lerp(v, c[i], fe));
      // tělo: pružina (skok, třes) + mírné natočení za pohledem
      const ks = 150, dmp = 13, dts = dt / 1000;
      body.vy += (-ks * body.y - dmp * body.vy) * dts; body.y += body.vy * dts;
      body.vx += (-260 * body.x - 9 * body.vx) * dts; body.x += body.vx * dts;
      body.tilt = lerp(body.tilt, E.lx * .05 + body.x * .004, ease(.01, dt));
      const st = clamp(Math.abs(body.vy) / 2200, 0, .09);
      body.sy = body.vy < 0 ? 1 + st : 1 - st * .7; body.sx = 2 - body.sy;
      if (M.sleep && t % 2600 < dt && zzz.length < 4) zzz.push({ t0: t, x: 0 });
      return { openL: openL * blink, openR: openR * blink };
    }

    /* ---- kreslení ---- */
    function drawScene(t) {
      const F = Math.min(W, H * .62) * (W > 700 ? 1.2 : 1), CY = H * .45;
      const proj = (x, y, z) => ({ x: W / 2 + (x - cam.x) * F / z, y: CY + (y - cam.y) * F / z, s: F / z });
      const gr = g.createLinearGradient(0, 0, 0, H);
      gr.addColorStop(0, rgba(SKY[0])); gr.addColorStop(.55, rgba(SKY[1])); gr.addColorStop(1, rgba(SKY[2]));
      g.fillStyle = gr; g.fillRect(0, 0, W, H);
      const sp = proj(.9, -1.2, 6), sg = g.createRadialGradient(sp.x, sp.y, 0, sp.x, sp.y, Math.max(W, H) * .8);
      sg.addColorStop(0, rgba(GLOW, .28)); sg.addColorStop(1, rgba(GLOW, 0)); g.fillStyle = sg; g.fillRect(0, 0, W, H);
      // záře od očí do okolí
      const f = figRect(), eg2 = g.createRadialGradient(f.x, f.y, 0, f.x, f.y, f.k * 260);
      eg2.addColorStop(0, rgba(moodC, .14)); eg2.addColorStop(1, rgba(moodC, 0)); g.fillStyle = eg2; g.fillRect(0, 0, W, H);
      for (const [x, y, z, a, ph] of STARS) {
        const p = proj(x, y, z); if (p.x < -4 || p.x > W + 4 || p.y < -4 || p.y > H + 4) continue;
        g.fillStyle = `rgba(255,255,255,${a * (.5 + .5 * Math.sin(t / 900 + ph))})`;
        g.beginPath(); g.arc(p.x, p.y, .6 + a * .7, 0, 7); g.fill();
      }
      return proj;
    }

    function drawPanes(proj, t, dt, near) {
      for (const p0 of PANES) {
        if ((p0[2] < 1) !== near) continue;
        p0[0] += p0[5] * dt / 1000 / p0[2] * 2;
        const span = 2.4 * p0[2] + p0[3]; if (p0[0] > span / 2) p0[0] -= span;
        const [x, y, z, s, , , ph] = p0, p = proj(x, y + Math.sin(t / 2400 + ph) * .03, z);
        const w = s * p.s * (1 + PANE_PAD * 2), h = w * sprites[0].height / sprites[0].width; // sprite má vlastní poměr stran
        const far = clamp((z - 1.2) / 2.4), a = (1 - far * .6) * (near ? .55 : .75);
        const lv = clamp(Math.abs(Math.log(z)) / .85, 0, 2), i0 = Math.floor(Math.min(lv, 1.999)), fr = lv - i0;
        g.save(); g.translate(p.x, p.y); g.rotate(Math.sin(t / 3000 + ph) * .04 + (x - cam.x) * -.05);
        g.globalAlpha = a * (1 - fr); g.drawImage(sprites[i0], -w / 2, -h / 2, w, h);
        g.globalAlpha = a * fr; g.drawImage(sprites[i0 + 1], -w / 2, -h / 2, w, h);
        g.restore();
      }
      g.globalAlpha = 1;
    }

    function drawQuba(t, lids) {
      const f = figRect(), k = f.k;
      const bob = reduce ? 0 : Math.sin(t / 900) * 4;
      // stín na zemi
      const lift = clamp(-body.y / 40, 0, 1);
      g.save(); g.translate(f.x + body.x * k, f.y + 96 * k); g.scale(1 - lift * .25, 1);
      const sh = g.createRadialGradient(0, 0, 0, 0, 0, 80 * k);
      sh.addColorStop(0, `rgba(0,0,0,${.45 - lift * .2})`); sh.addColorStop(1, 'rgba(0,0,0,0)');
      g.scale(1, .16); g.fillStyle = sh; g.beginPath(); g.arc(0, 0, 80 * k, 0, 7); g.fill(); g.restore();

      g.save();
      g.translate(f.x + (body.x + E.lx * 3) * k, f.y + (body.y + bob + E.ly * 2) * k);
      g.rotate(body.tilt);
      g.scale(k * body.sx, k * body.sy);
      g.translate(-120, -102);
      // popruh (je za hlavou → posouvá se proti pohledu)
      g.save(); g.translate(-E.lx * 3, -E.ly * 2);
      g.lineCap = 'round'; g.strokeStyle = '#272b37'; g.lineWidth = 13; g.stroke(STRAP);
      g.strokeStyle = 'rgba(80,86,104,.55)'; g.lineWidth = 4; g.stroke(STRAP);
      g.restore();
      // skořepina
      g.shadowColor = 'rgba(0,0,0,.35)'; g.shadowBlur = 22 * k; g.shadowOffsetY = 12 * k;
      const sg = g.createLinearGradient(0, 52, 0, 152);
      sg.addColorStop(0, '#fdfbf8'); sg.addColorStop(.55, '#ece7e1'); sg.addColorStop(1, '#c9c1b8');
      g.fillStyle = sg; g.fill(SHELL);
      g.shadowColor = 'transparent'; g.shadowBlur = 0; g.shadowOffsetY = 0;
      // světlo zleva nahoře podle natočení
      const lg = g.createRadialGradient(70 - E.lx * 20, 58, 4, 70 - E.lx * 20, 58, 150);
      lg.addColorStop(0, 'rgba(255,255,255,.55)'); lg.addColorStop(1, 'rgba(255,255,255,0)');
      g.save(); g.clip(SHELL); g.fillStyle = lg; g.fillRect(0, 0, 240, 170);
      // spodní hrana ve stínu
      const bg = g.createLinearGradient(0, 120, 0, 152); bg.addColorStop(0, 'rgba(60,50,70,0)'); bg.addColorStop(1, 'rgba(60,50,70,.18)');
      g.fillStyle = bg; g.fillRect(0, 0, 240, 170); g.restore();
      // senzory
      g.fillStyle = 'rgba(43,47,59,.55)';
      g.beginPath(); g.roundRect(26, 88, 6, 26, 3); g.roundRect(208, 88, 6, 26, 3); g.fill();
      // sklo
      const gg = g.createLinearGradient(0, 64, 0, 140); gg.addColorStop(0, '#1c2030'); gg.addColorStop(1, '#06070b');
      g.fillStyle = gg; g.fill(GLASS);
      g.save(); g.clip(GLASS);
      // záře očí na skle
      const ex = 120 + E.lx * 13, ey = 102 + E.ly * 7;
      const glow = g.createRadialGradient(ex, ey, 0, ex, ey, 80);
      glow.addColorStop(0, rgba(moodC, .22)); glow.addColorStop(1, rgba(moodC, 0));
      g.fillStyle = glow; g.fillRect(36, 64, 168, 76);
      drawEyes(lids, k);
      // odlesk skla – posouvá se proti pohledu (pocit zakřiveného skla)
      const shx = -E.lx * 10, shy = -E.ly * 3;
      const sh2 = g.createLinearGradient(0, 66 + shy, 0, 96 + shy);
      sh2.addColorStop(0, 'rgba(255,255,255,.17)'); sh2.addColorStop(1, 'rgba(255,255,255,0)');
      g.fillStyle = sh2; g.beginPath(); g.roundRect(46 + shx, 67 + shy, 148, 28, 14); g.fill();
      g.fillStyle = 'rgba(255,255,255,.06)';
      g.beginPath(); g.moveTo(150 + shx * 2, 64); g.lineTo(170 + shx * 2, 64); g.lineTo(140 + shx * 2, 140); g.lineTo(120 + shx * 2, 140); g.fill();
      g.restore();
      // okraj skla
      g.strokeStyle = 'rgba(0,0,0,.25)'; g.lineWidth = 1.2; g.stroke(GLASS);
      g.restore();
      // zzz
      for (const z of zzz) {
        const k2 = (t - z.t0) / 3600; if (k2 >= 1) continue;
        g.globalAlpha = Math.sin(k2 * Math.PI) * .85;
        g.fillStyle = '#b9b2ff'; g.font = `900 ${Math.round((16 + k2 * 14) * k)}px Doto, "Geist Mono", monospace`;
        g.fillText('z', f.x + (70 + k2 * 50) * k, f.y + (-60 - k2 * 80) * k);
      }
      g.globalAlpha = 1; zzz = zzz.filter((z) => t - z.t0 < 3600);
    }

    function drawEyes(lids, k) {
      // oči kreslíme do vlastní vrstvy, ať jde jejich záře udělat jedním rozmazáním
      const p = { ...E, lookX: E.lx, lookY: E.ly };
      const out = { dots: [], spark: [] };
      const ox = E.lx * 9, oy = E.ly * 5;
      eyeDots(EYE_L[0] + ox, EYE_L[1] + oy, p, -1, lids.openL, out);
      eyeDots(EYE_R[0] + ox, EYE_R[1] + oy, p, 1, lids.openR, out);
      const sc = Math.max(1, k * DPR); // pixely vrstvy na jednotku skla
      const lw = Math.ceil(168 * sc), lh = Math.ceil(76 * sc);
      if (eyeLayer.width !== lw || eyeLayer.height !== lh) { eyeLayer.width = lw; eyeLayer.height = lh; }
      eg.setTransform(1, 0, 0, 1, 0, 0); eg.clearRect(0, 0, lw, lh);
      eg.setTransform(sc, 0, 0, sc, 0, 0);
      // souřadnice očí: kolem středu skla (84, 38), zvětšené EYE_SCALE
      eg.translate(84, 38); eg.scale(EYE_SCALE, EYE_SCALE); eg.translate(-120, -102);
      eg.fillStyle = rgba(moodC);
      eg.beginPath();
      const d = out.dots, R2 = DOT / 2;
      for (let i = 0; i < d.length; i += 2) { eg.moveTo(d[i] + R2, d[i + 1]); eg.arc(d[i], d[i + 1], R2, 0, 7); }
      eg.fill();
      if (out.spark.length) {
        eg.fillStyle = '#fff'; eg.beginPath();
        for (let i = 0; i < out.spark.length; i += 2) { eg.moveTo(out.spark[i] + R2, out.spark[i + 1]); eg.arc(out.spark[i], out.spark[i + 1], R2, 0, 7); }
        eg.fill();
      }
      // vrstva → sklo: nejdřív záře (rozmazaný stín), pak ostré tečky
      g.save();
      g.translate(36, 64);
      g.shadowOffsetX = 0; g.shadowOffsetY = 0;
      g.shadowColor = rgba(moodC, .9); g.shadowBlur = 9 * k * DPR;
      g.drawImage(eyeLayer, 0, 0, lw, lh, 0, 0, 168, 76);
      g.shadowColor = 'transparent';
      g.drawImage(eyeLayer, 0, 0, lw, lh, 0, 0, 168, 76);
      g.restore();
    }

    function frame(now) {
      requestAnimationFrame(frame);
      const dt = Math.min(50, now - (last || now)); last = now;
      if (paused || document.hidden || !W) return;
      pointer.nx = lerp(pointer.nx, pointer.tnx, ease(.02, dt));
      pointer.ny = lerp(pointer.ny, pointer.tny, ease(.02, dt));
      const drift = reduce ? 0 : now / 1000, amp = reduce ? .3 : 1;
      cam.x = (pointer.nx * .07 + Math.sin(drift / 7) * .012) * amp;
      cam.y = (pointer.ny * .05 + Math.cos(drift / 9) * .008) * amp;
      const lids = behave(now, dt);

      g.setTransform(DPR, 0, 0, DPR, 0, 0);
      const proj = drawScene(now);
      drawPanes(proj, now, dt, false);
      drawQuba(now, lids);
      drawPanes(proj, now, dt, true);

      // bublina jede s postavičkou (paralaxa + skok)
      const f = figRect(), s = spot.getBoundingClientRect(), hr = host === document.body ? { left: 0, top: 0 } : host.getBoundingClientRect();
      const bx = f.x - (s.left - hr.left + s.width / 2), by = body.y * f.k * .35 - cam.y * H * .2;
      bubble.style.transform = `translate(${bx.toFixed(1)}px, ${by.toFixed(1)}px)`;
    }

    /* ---- psaní do bubliny ---- */
    function type(text) {
      clearInterval(typeTimer);
      say.textContent = '';
      if (!text) return;
      bubble.classList.add('pop'); void bubble.offsetWidth; bubble.classList.remove('pop');
      const span = document.createElement('span'), caret = document.createElement('i');
      caret.className = 'quba-caret'; say.append(span, caret);
      const chars = [...text]; let i = 0;
      talking = performance.now() + chars.length * 30 + 200;
      typeTimer = setInterval(() => {
        span.textContent += chars[i++] ?? '';
        if (i >= chars.length) { clearInterval(typeTimer); setTimeout(() => caret.remove(), 1600); }
      }, 30);
    }

    /* ---- vstupy ---- */
    const setPointer = (x, y) => { pointer.x = x; pointer.y = y; pointer.tnx = (x / innerWidth - .5) * 2; pointer.tny = (y / innerHeight - .5) * 2; pointer.last = performance.now(); };
    addEventListener('pointermove', (e) => setPointer(e.clientX, e.clientY), { passive: true });
    addEventListener('pointerdown', (e) => setPointer(e.clientX, e.clientY), { passive: true });
    document.addEventListener('pointerleave', () => { pointer.tnx = 0; pointer.tny = 0; });
    addEventListener('deviceorientation', (e) => {
      if (e.gamma == null) return;
      pointer.tnx = clamp(e.gamma / 25, -1, 1); pointer.tny = clamp((e.beta - 45) / 25, -1, 1);
    });
    document.addEventListener('pointerover', (e) => { const el = e.target.closest?.('[data-quba-look]'); if (el) pointer.hover = el; });
    document.addEventListener('pointerout', (e) => { const el = e.target.closest?.('[data-quba-look]'); if (el && pointer.hover === el) pointer.hover = null; });

    const api = {
      get mood() { return mood; },
      set(key, text, { act } = {}) {
        const changed = key !== mood;
        mood = MOODS[key] ? key : 'pohoda';
        target = { ...BASE, ...MOODS[mood].p };
        root.style.setProperty('--quba-eye', MOODS[mood].color);
        if (text != null) type(text);
        if (act) run(act); else if (changed && MOODS[mood].idle && mood !== 'hleda') run(MOODS[mood].idle);
        nextIdle = performance.now() + 2500;
      },
      say: type,
      act: run,
      pause(v) { paused = !!v; },
      poke() {
        if (MOODS[mood].sleep) { run('wake'); type(opts.sleepPoke || 'Hm? Co je? Ještě spím…'); return; }
        run(Math.random() < .5 ? 'wink' : 'roll'); body.vy = -380;
        const lines = opts.poke || ['Hej, co zkoušíš?', 'Nešťouchej, ladím.', 'Au, to jsou moje čočky!'];
        type(lines[pokeIdx++ % lines.length]);
        navigator.vibrate?.(8);
      },
    };
    spot.addEventListener('click', () => api.poke());
    requestAnimationFrame(frame);
    return api;
  }

  window.Quba = { mount };
})();
