/* S1 Dash UI. Runs inside the app's WebView (window.S1 is the Kotlin bridge) or
   in a plain browser, where a built-in demo bridge stands in.
   Kept to ES2017 for old Android 9 WebViews: no ?. or ??. */
(function () {
  "use strict";

  // ---------- bridge ----------
  var B = window.S1 || demoBridge();

  function demoBridge() {
    var settings = { theme: "s1", ohEnabled: "1", ohHost: "192.168.1.1", ecuAdapter: "bridge", ecuMac: "",
      bridgeName: "BLE_TO_ISOTP20", dtcIndicator: "1", boostTarget: "1.8", demo: "1" };
    try { var saved = JSON.parse(localStorage.getItem("s1demo") || "{}"); for (var k in saved) settings[k] = saved[k]; } catch (e) {}
    var q = location.search.replace("?", "").split("&");
    q.forEach(function (p) { var kv = p.split("="); if (kv[0] && kv[0] in settings) settings[kv[0]] = decodeURIComponent(kv[1] || ""); });
    var cal = { state: (location.search.match(/[?&]flash=(\w+)/) || [])[1] || "", slot: 0, slots: [
      { slot: 0, name: "95 RON", box: "8X0906259 B", sha: "4be81c07", last: "2026-11-05 16:40" }, { slot: 1, name: "99 RON", box: "8X0906259 B", sha: "a17d33f0" },
      { slot: 2, name: "95 RON with start-stop", box: "8X0906259 B", sha: "c20e94aa" }, { slot: 3 }, { slot: 4 }] };
    var t0 = Date.now(), peak = 0, logging = false, demoSlip = /[?&]slip/.test(location.search), demoGears = [1, 2, 3, 4, 5, 6];
    return {
      snapshot: function () {
        var t = (Date.now() - t0) / 1000, pull = Math.max(0, Math.sin(t * 0.5));
        if (/[?&]still/.test(location.search)) pull = 0.78, t = 40;
        var boost = pull * 1.78 - 0.55 * (1 - pull);
        peak = Math.max(peak, boost);
        var v = { rpm: 1500 + pull * 4800, ambient: 101.3, ambientBar: 1.013, map: 101.3 + boost * 100, mapSp: 101.3 + pull * 182,
          boost: boost, coolant: Math.min(90, 70 + t * 0.4), oil: Math.min(104, 62 + t * 0.25), iat: 18 + pull * 9,
          knock1: 0, knock2: 0, knock3: pull > 0.9 ? -1.5 : 0, knock4: 0, knockAvg: pull > 0.9 ? -0.38 : 0, timing: 8 + (1 - pull) * 14,
          wastegate: 20 + pull * 70, put: 101 + pull * 185, afr: 14.7 - pull * 2.9, lambda: 1 - pull * 0.2, stft: 0.8, ltft: 1.6,
          fpDi: 150 + pull * 200, fpMpi: 4.8, pedal: pull * 100, battery: 14.2, ecuSpeed: 60 + pull * 40, gear: 3, misfires: 0, turbo: 40 + pull * 120,
          torque: 60 + pull * 310, clutchSlip: demoSlip ? 8 : 0, clutchLoad: demoSlip ? 94 : 22, clutchWarn: demoSlip ? 1 : 0,
          hxMode: 2, hxLock: 30 + pull * 60, hxLockTarget: 30 + pull * 65, hxClutchTemp: Math.min(70, 30 + t * 0.15 + pull * 6),
          hxModuleTemp: 24, hxFinTemp: 22, hxVolts: 13.9, hxTempProt: 0, dtcEcu: 2, dtcHaldex: 0 };
        return JSON.stringify({ v: v, s: { oh: "demo", ecu: "demo" }, peak: /[?&]still/.test(location.search) ? 1.78 : peak, logging: logging, t: Date.now() });
      },
      settings: function () { return JSON.stringify(settings); },
      setSetting: function (k, v) { settings[k] = v; try { localStorage.setItem("s1demo", JSON.stringify(settings)); } catch (e) {} },
      version: function () { return "browser demo"; },
      setMode: function () {}, resetPeak: function () { peak = 0; },
      clutchInfo: function () { return JSON.stringify({ gears: demoGears }); }, clutchReset: function () { demoGears = []; },
      haldexDtcs: function () { return JSON.stringify({ state: "done", dtcs: [] }); },
      haldexDtcRead: function () {}, haldexDtcClear: function () {},
      ecuDtcs: function () { return JSON.stringify({ state: "done", dtcs: [{ code: "P3019-00", active: false, confirmed: true }, { code: "U0155-00", active: false, confirmed: true }] }); },
      ecuDtcRead: function () {}, ecuDtcClear: function () {},
      logStart: function () { logging = true; }, logStop: function () { logging = false; },
      logInfo: function () { return JSON.stringify({ active: logging, sessions: 12, dir: "/sdcard/Android/data/com.louij2.s1dash/files/logs", file: "" }); },
      pairedDevices: function () { return JSON.stringify([{ name: "OBDII", mac: "00:1D:A5:68:98:8A" }, { name: "BLE_TO_ISOTP20", mac: "24:0A:C4:11:22:33" }]); },
      openSimosTools: function () { return false; }, reconnect: function () {},
      calState: function () {
        var o = { state: cal.state, message: "", slots: cal.slots };
        if (cal.state) { o.slot = cal.slot; o.name = cal.slots[cal.slot].name; }
        if (cal.state === "check") { o.canFlash = !/[?&]flashblock/.test(location.search); o.checks = [
          { label: "ECU connection", state: "pass", detail: "Connected" }, { label: "Engine off", state: o.canFlash ? "pass" : "fail", detail: o.canFlash ? "Stopped" : "Engine is running" },
          { label: "Parked", state: "pass", detail: "Not moving" }, { label: "Battery", state: "warn", detail: "12.4 V. A charger is safer" },
          { label: "Software number", state: "pass", detail: "8X0906259 B" }, { label: "Fault codes saved", state: "pass", detail: "SimosTools clears them while flashing" }]; }
        if (cal.state === "handoff") o.file = "S1Dash-slot1-" + o.name + ".bin";
        if (cal.state === "verify") o.checks = [
          { label: "ECU answers", state: "pass", detail: "Connected" }, { label: "Software number", state: "pass", detail: "8X0906259 B" },
          { label: "Flash recorded", state: "pass", detail: "New record on the ECU" }, { label: "Fault codes", state: "warn", detail: "New: U0155-00" },
          { label: "Idle", state: "wait", detail: "Watching: 6 of 15 s, don't touch the pedal" }];
        return JSON.stringify(o);
      },
      calPick: function (n) { cal.slots[n] = { slot: n, name: "new-file-" + (n + 1), box: "8X0906259 B", sha: "9f3a10c2" }; },
      calClear: function (n) { cal.slots[n] = { slot: n }; },
      calPrepare: function (n) { cal.slot = n; cal.state = "check"; },
      calStart: function () { cal.state = "handoff"; return ""; },
      calReopen: function () {}, calFinished: function () { cal.state = "verify"; }, calCancel: function () { cal.state = ""; },
      calFinalise: function () { cal.slots[cal.slot].last = "2026-11-06 18:20"; cal.state = ""; }
    };
  }

  function $(id) { return document.getElementById(id); }
  function num(x, d) { return x === undefined || x === null ? "--" : x.toFixed(d); }
  function pad2(n) { return n < 10 ? "0" + n : "" + n; }
  function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }
  var settings = JSON.parse(B.settings());
  var snap = { v: {}, s: {}, peak: 0 };
  var MODES = ["Stock", "FWD", "50 : 50", "60 : 40", "75 : 25", "Expert"];

  // ---------- stage scaling: 480 logical px tall in landscape, 800 wide in portrait ----------
  var W = 800, H = 480;
  function layout() {
    var sw = window.innerWidth, sh = window.innerHeight, aspect = sw / sh, scale;
    if (aspect >= 1.25) { H = 480; W = Math.max(800, Math.round(480 * aspect)); scale = sh / 480; }
    else { W = 800; H = Math.max(480, Math.round(800 / aspect)); scale = sw / 800; }
    var st = $("stage");
    st.style.width = W + "px"; st.style.height = H + "px";
    st.style.transform = "scale(" + scale + ")";
    var cl = $("cluster"), stats = $("perf-stats");
    stats.className = "stats";
    if (W >= 1100) {
      cl.style.left = "0px";
      stats.className = "stats on";
      stats.style.cssText = "left:820px;right:28px;top:76px;bottom:54px;overflow-y:auto";
      setTileWidth("perf-tiles", W - 848 > 520 ? "33.33%" : "50%");
    } else if (H >= 700) {
      // Portrait or tall screens: the whole engine data set under the cluster.
      cl.style.left = Math.round((W - 800) / 2) + "px";
      stats.className = "stats on";
      stats.style.cssText = "left:28px;right:28px;top:450px;bottom:54px;overflow-y:auto";
      if ($("perf-tiles").children.length !== ENGINE_KEYS.length) buildTiles("perf-tiles", ENGINE_KEYS);
      setTileWidth("perf-tiles", "33.33%");
    } else {
      cl.style.left = Math.round((W - 800) / 2) + "px";
    }
    setTileWidth("eng-tiles", W >= 1300 ? "20%" : W >= 1000 ? "25%" : H >= 700 ? "33.33%" : "25%");
  }
  function setTileWidth(id, w) {
    var t = $(id).children;
    for (var i = 0; i < t.length; i++) t[i].style.width = w;
    $(id).setAttribute("data-w", w);
  }

  // ---------- dial themes ----------
  var THEMES = {
    s1: { faces: [["#d3d5d7", "#8c8f93"], ["#d3d5d7", "#8c8f93"]], band: "#7e8185", metal: true,
      tick: "#fbfbfb", num: "#eceded", numStroke: "#55585c", sub: "#e4e5e6", needle: "#fafafa", needleEdge: "#55585c",
      red: "#e1182e", badge: "s1" },
    quattro: { faces: [["#cf2a33", "#5c0a0f"], ["#2b2c2e", "#060606"]], band: null,
      tick: "#f5f5f5", num: "#f5f5f5", numStroke: "none", sub: "#e8e8e8", needle: "#f5f5f5", needleEdge: "#888",
      red: "#ff2a3c", badge: "quattro" },
    mmi: { faces: [["#1c1e21", "#050505"], ["#1c1e21", "#050505"]], band: null,
      tick: "#f2f2f2", num: "#b8bcc0", numStroke: "none", sub: "#9a9ea3", needle: "#ff3b52", needleEdge: "#ff3b52",
      red: "#d1102a", badge: null }
  };
  var theme = THEMES.s1;

  var C = 150;
  function pt(deg, r, cx, cy) {
    if (cx === undefined) { cx = C; cy = C; }
    return [cx + r * Math.cos(deg * Math.PI / 180), cy + r * Math.sin(deg * Math.PI / 180)];
  }
  function arc(a0, a1, r, cx, cy) {
    var p0 = pt(a0, r, cx, cy), p1 = pt(a1, r, cx, cy);
    return "M" + p0[0] + " " + p0[1] + " A" + r + " " + r + " 0 " + (a1 - a0 > 180 ? 1 : 0) + " 1 " + p1[0] + " " + p1[1];
  }
  var FONT = 'font-family="Barlow, sans-serif" font-style="italic"';

  // Needle and boss drawn pointing at 0 degrees; the group is rotated live.
  function needleGroup(cls, len, tail, w, cx, cy, capR) {
    var g = '<g class="' + cls + '" transform="rotate(0 ' + cx + ' ' + cy + ')">' +
      '<polygon points="' + (cx - tail) + ',' + (cy - w) + ' ' + (cx + len) + ',' + (cy - 0.9) + ' ' + (cx + len) + ',' + (cy + 0.9) + ' ' + (cx - tail) + ',' + (cy + w) + '" fill="' + theme.needle + '" stroke="' + theme.needleEdge + '" stroke-width=".6"/>' +
      '<circle cx="' + cx + '" cy="' + cy + '" r="' + (capR + 2) + '" fill="#9a9da1"/>' +
      '<circle cx="' + cx + '" cy="' + cy + '" r="' + capR + '" fill="url(#boss)"/>' +
      '<line x1="' + (cx - capR * 0.55) + '" y1="' + cy + '" x2="' + (cx + capR * 0.55) + '" y2="' + cy + '" stroke="#55585c" stroke-width="' + capR * 0.35 + '" stroke-linecap="round"/></g>';
    return g;
  }

  function s1Badge(x, y, k) {
    var S = "M37 3.3 H8.4 Q3.3 3.3 3.3 6.8 Q3.3 10.25 8.4 10.25 H28.6 Q33.7 10.25 33.7 13.7 Q33.7 17.2 28.6 17.2 H0";
    return '<g transform="translate(' + x + ',' + y + ') scale(' + k + ') skewX(-17)">' +
      '<rect x="0" y="0" width="36" height="34" fill="#e1182e" stroke="#fdfdfd" stroke-width="1.4"/>' +
      '<g transform="translate(23,8.5)"><path d="' + S + '" fill="none" stroke="#5f6367" stroke-width="8"/>' +
      '<path d="' + S + '" fill="none" stroke="#fbfbfb" stroke-width="6.6"/></g>' +
      '<polygon transform="translate(66,8.5)" points="0,5.4 8.6,0 14,0 14,20.5 8.4,20.5 8.4,7.4 1.6,11" fill="#fbfbfb" stroke="#5f6367" stroke-width=".8" stroke-linejoin="round"/></g>';
  }

  function metalSheen(id) {
    var w = "";
    for (var i = 0; i < 8; i++) {
      var a0 = i * 45 - 70, a1 = a0 + 45, p0 = pt(a0, 160), p1 = pt(a1, 160), light = i % 2 === 0;
      w += '<path d="M' + C + ' ' + C + ' L' + p0[0] + ' ' + p0[1] + ' A160 160 0 0 1 ' + p1[0] + ' ' + p1[1] + ' Z" fill="' + (light ? "#fff" : "#000") + '" opacity="' + (light ? 0.10 : 0.06) + '"/>';
    }
    return '<defs><filter id="grain' + id + '" x="0" y="0" width="100%" height="100%">' +
      '<feTurbulence type="fractalNoise" baseFrequency="0.9 0.02" numOctaves="2" seed="3"/>' +
      '<feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .09 0"/></filter>' +
      '<filter id="soft' + id + '" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="9"/></filter>' +
      '<clipPath id="faceclip' + id + '"><circle cx="' + C + '" cy="' + C + '" r="139"/></clipPath></defs>' +
      '<g clip-path="url(#faceclip' + id + ')"><g filter="url(#soft' + id + ')">' + w + '</g>' +
      '<rect x="0" y="0" width="300" height="300" filter="url(#grain' + id + ')" opacity=".8"/></g>';
  }

  var START = 140, SWEEP = 260, SST = 200, SSW = 140, SX = C, SY = 222, SR = 44;
  var dials = {};

  // One cluster dial plus its sub-dial. Returns angle mappers for the live update.
  function buildDial(id, side, main, sub, unit, subIcon, marker) {
    var t = theme, face = t.faces[side];
    function ang(v, o, st, sw) {
      o = o || main; st = st === undefined ? START : st; sw = sw === undefined ? SWEEP : sw;
      return st + sw * (Math.min(Math.max(v, o.min), o.max) - o.min) / (o.max - o.min);
    }
    function sang(v) { return ang(v, sub, SST, SSW); }
    var g = '<svg viewBox="0 0 300 300" xmlns="http://www.w3.org/2000/svg"><defs>' +
      '<radialGradient id="face' + id + '" cx="45%" cy="38%" r="70%"><stop offset="0" stop-color="' + face[0] + '"/><stop offset="1" stop-color="' + face[1] + '"/></radialGradient>' +
      '<linearGradient id="bezel' + id + '" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f7f7f7"/><stop offset=".45" stop-color="#8e9195"/><stop offset=".55" stop-color="#5d6064"/><stop offset="1" stop-color="#e6e7e8"/></linearGradient>' +
      '<radialGradient id="boss" cx="40%" cy="35%" r="70%"><stop offset="0" stop-color="#4a4d51"/><stop offset="1" stop-color="#0d0e0f"/></radialGradient></defs>' +
      '<circle cx="' + C + '" cy="' + C + '" r="147" fill="#0b0b0c"/>' +
      '<circle cx="' + C + '" cy="' + C + '" r="143" fill="none" stroke="url(#bezel' + id + ')" stroke-width="6"/>' +
      '<circle cx="' + C + '" cy="' + C + '" r="139" fill="url(#face' + id + ')"/>';
    if (t.metal) g += metalSheen(id);
    if (t.band) g += '<circle cx="' + C + '" cy="' + C + '" r="129" fill="none" stroke="' + t.band + '" stroke-width="20" opacity=".55"/>';
    var steps = Math.round((main.max - main.min) / main.minor);
    for (var i = 0; i <= steps; i++) {
      var v = main.min + i * main.minor, k = (v - main.min) / main.step, major = Math.abs(k - Math.round(k)) < 1e-6;
      var hot = main.hot !== undefined && v >= main.hot - 1e-9, a = ang(v);
      var p0 = pt(a, major ? 120 : 129), p1 = pt(a, 138);
      g += '<line x1="' + p0[0] + '" y1="' + p0[1] + '" x2="' + p1[0] + '" y2="' + p1[1] + '" stroke="' + (hot ? t.red : t.tick) + '" stroke-width="' + (major ? 3.2 : 1.6) + '"/>';
      if (major) {
        var n = pt(a, 101), txt = main.labels ? main.labels(v) : (+v.toFixed(main.dec || 0));
        g += '<text x="' + n[0] + '" y="' + (n[1] + 10) + '" fill="' + (hot ? t.red : t.num) + '" stroke="' + t.numStroke + '" stroke-width=".6" font-size="28" font-weight="600" text-anchor="middle" ' + FONT + '>' + txt + '</text>';
      }
    }
    if (main.hot !== undefined) g += '<path d="' + arc(ang(main.hot), ang(main.max), 117) + '" fill="none" stroke="' + t.red + '" stroke-width="4"/>';
    if (marker !== undefined) {
      // Expected peak: a small red marker just inside the ticks, like the cluster's limit marks.
      var ma = ang(marker), m0 = pt(ma, 104), m1 = pt(ma - 2.4, 115), m2 = pt(ma + 2.4, 115);
      g += '<polygon points="' + m0[0] + ',' + m0[1] + ' ' + m1[0] + ',' + m1[1] + ' ' + m2[0] + ',' + m2[1] + '" fill="' + t.red + '"/>';
    }
    if (t.badge === "s1" && side === 0) g += s1Badge(126, 90, 0.68);
    if (t.badge === "quattro" && side === 0) g += '<text x="' + C + '" y="98" fill="#f5f5f5" font-size="19" font-weight="600" text-anchor="middle" letter-spacing="1" ' + FONT + '>quattro</text>';
    g += '<text x="' + C + '" y="283" fill="' + t.num + '" font-size="12" text-anchor="middle" ' + FONT + '>' + unit + '</text>';
    // sub-dial
    g += '<circle cx="' + SX + '" cy="' + SY + '" r="' + SR + '" fill="none" stroke="' + t.sub + '" stroke-width="1" opacity=".6"/>';
    sub.marks.forEach(function (mv) {
      var shot = sub.hot !== undefined && mv >= sub.hot, sa = sang(mv);
      var q0 = pt(sa, SR - 10, SX, SY), q1 = pt(sa, SR - 2, SX, SY), qn = pt(sa, SR - 22, SX, SY);
      g += '<line x1="' + q0[0] + '" y1="' + q0[1] + '" x2="' + q1[0] + '" y2="' + q1[1] + '" stroke="' + (shot ? t.red : t.tick) + '" stroke-width="2.4"/>' +
        '<text x="' + qn[0] + '" y="' + (qn[1] + 5) + '" fill="' + (shot ? t.red : t.num) + '" font-size="14" font-weight="600" text-anchor="middle" ' + FONT + '>' + mv + '</text>';
    });
    g += '<text x="' + (SX - 30) + '" y="' + (SY + 30) + '" fill="' + t.sub + '" font-size="11" ' + FONT + '>' + subIcon + '</text>';
    g += needleGroup("ns", SR - 4, 8, 2, SX, SY, 9);
    // peak-hold mark (main dial only)
    g += '<g class="pk" transform="rotate(0 ' + C + ' ' + C + ')" opacity="0"><line x1="' + (C + 108) + '" y1="' + C + '" x2="' + (C + 138) + '" y2="' + C + '" stroke="' + t.red + '" stroke-width="2.5"/></g>';
    g += needleGroup("nm", 132, 26, 3.4, C, C, 21);
    g += '</svg>';
    var el = $(id);
    el.innerHTML = g;
    return { main: main, sub: sub, ang: function (v) { return ang(v); }, sang: sang,
      nm: el.querySelector(".nm"), ns: el.querySelector(".ns"), pk: el.querySelector(".pk"), cur: { m: null, s: null } };
  }

  function buildDials() {
    theme = THEMES[settings.theme] || THEMES.s1;
    var target = parseFloat(settings.boostTarget) || 1.8;
    dials.L = buildDial("dialL", 0,
      { min: -1, max: 2, step: 0.5, minor: 0.1, hot: Math.min(1.9, Math.round((target + 0.1) * 10) / 10), dec: 1,
        labels: function (v) { if (Math.abs(v) < 1e-9) return "0"; var s = (+v.toFixed(1)).toString(); return s.replace(/^(-?)0\./, "$1."); } },
      { min: 50, max: 130, marks: [50, 90, 130], hot: 130 }, "boost · bar over atm", "coolant °C", target);
    dials.R = buildDial("dialR", 1,
      { min: 40, max: 160, step: 20, minor: 5, hot: 130 },
      { min: 0, max: 150, marks: [0, 75, 150], hot: 150 }, "oil °C", "Haldex °C");
  }

  // Ease needles toward their targets every frame.
  var targets = { L: { m: -1, s: 50, pk: null }, R: { m: 40, s: 0 } };
  function animate() {
    ["L", "R"].forEach(function (k) {
      var d = dials[k]; if (!d) return;
      var tm = d.ang(targets[k].m), ts = d.sang(targets[k].s);
      d.cur.m = d.cur.m === null ? tm : d.cur.m + (tm - d.cur.m) * 0.3;
      d.cur.s = d.cur.s === null ? ts : d.cur.s + (ts - d.cur.s) * 0.3;
      d.nm.setAttribute("transform", "rotate(" + d.cur.m.toFixed(2) + " " + C + " " + C + ")");
      d.ns.setAttribute("transform", "rotate(" + d.cur.s.toFixed(2) + " " + SX + " " + SY + ")");
      if (targets[k].pk !== undefined && targets[k].pk !== null && targets[k].pk > 0.05) {
        d.pk.setAttribute("opacity", "1");
        d.pk.setAttribute("transform", "rotate(" + d.ang(targets[k].pk).toFixed(2) + " " + C + " " + C + ")");
      } else d.pk.setAttribute("opacity", "0");
    });
    requestAnimationFrame(animate);
  }

  // ---------- value tiles (engine data, and the extra panel on big screens) ----------
  var TILE = {
    boost: ["Boost", "bar", 2], map: ["MAP", "kPa", 0], mapSp: ["MAP target", "kPa", 0], ambient: ["Ambient press.", "kPa", 1],
    rpm: ["Engine speed", "rpm", 0], iat: ["Intake air", "°C", 0], coolant: ["Coolant", "°C", 0], oil: ["Oil temp", "°C", 0],
    knockAvg: ["Knock avg", "°", 2], knock1: ["Knock cyl 1", "°", 2], knock2: ["Knock cyl 2", "°", 2], knock3: ["Knock cyl 3", "°", 2], knock4: ["Knock cyl 4", "°", 2],
    timing: ["Ignition", "°", 1], wastegate: ["Wastegate", "%", 0], put: ["Pre-throttle", "kPa", 0], afr: ["AFR", "", 1], lambda: ["Lambda", "λ", 2],
    stft: ["STFT", "%", 1], ltft: ["LTFT", "%", 1], fpDi: ["Fuel DI", "bar", 0], fpMpi: ["Fuel MPI", "bar", 2], pedal: ["Pedal", "%", 0],
    battery: ["ECU volts", "V", 1], ecuSpeed: ["Speed", "km/h", 0], gear: ["Gear", "", 0], misfires: ["Misfires", "", 0], turbo: ["Turbo speed", "krpm", 0],
    hxLock: ["Haldex lock", "%", 0], hxLockTarget: ["Haldex target", "%", 0], hxClutchTemp: ["Haldex clutch", "°C", 0], hxModuleTemp: ["Haldex module", "°C", 0],
    hxVolts: ["Haldex volts", "V", 1], torque: ["Torque", "Nm", 0], clutchSlip: ["Clutch slip", "%", 1], clutchLoad: ["Clutch load est.", "%", 0]
  };
  var ENGINE_KEYS = ["boost", "map", "mapSp", "ambient", "rpm", "iat", "coolant", "oil", "knockAvg", "knock1", "knock2", "knock3", "knock4",
    "timing", "wastegate", "put", "afr", "lambda", "stft", "ltft", "fpDi", "fpMpi", "pedal", "battery", "ecuSpeed", "gear", "torque", "clutchSlip", "clutchLoad", "misfires", "turbo",
    "hxLock", "hxLockTarget", "hxClutchTemp", "hxModuleTemp", "hxVolts"];
  var PERF_KEYS = ["rpm", "iat", "knockAvg", "timing", "wastegate", "lambda", "clutchSlip", "clutchLoad", "hxLock", "hxClutchTemp", "ltft", "battery"];

  function buildTiles(id, keys) {
    var h = "", w = $(id).getAttribute("data-w") || "";
    keys.forEach(function (k) {
      h += '<div class="tile" data-k="' + k + '"' + (w ? ' style="width:' + w + '"' : "") + '><div class="k">' + TILE[k][0] + '</div><div class="v">--</div></div>';
    });
    $(id).innerHTML = h;
  }
  function clutchState(x) { return x >= 90 ? "alarm" : x >= 70 ? "warn" : ""; }
  function tileState(k, x) {
    if (x === undefined) return "";
    if (k === "knockAvg" || /^knock\d$/.test(k)) return x <= -3 ? "alarm" : x <= -1.5 ? "warn" : "";
    if (k === "oil") return x >= 130 ? "alarm" : x < 80 ? "warn" : "";
    if (k === "coolant") return x >= 110 ? "alarm" : "";
    if (k === "boost") return x > (parseFloat(settings.boostTarget) || 1.8) + 0.1 ? "warn" : "";
    if (k === "battery" || k === "hxVolts") return x < 11.5 ? "warn" : "";
    if (k === "clutchLoad") return clutchState(x);
    if (k === "clutchSlip") return snap.v.clutchWarn === 1 ? "alarm" : "";
    return "";
  }
  function updateTiles(id) {
    var t = $(id).children, v = snap.v;
    for (var i = 0; i < t.length; i++) {
      var k = t[i].getAttribute("data-k"), def = TILE[k], x = v[k];
      t[i].className = "tile " + tileState(k, x);
      t[i].lastChild.innerHTML = num(x, def[2]) + (def[1] ? "<small>" + def[1] + "</small>" : "");
    }
  }

  // ---------- navigation ----------
  var stack = [];
  var current = null;
  function go(id, noPush) {
    if (current && !noPush && current !== id) stack.push(current);
    var s = document.querySelectorAll(".screen");
    for (var i = 0; i < s.length; i++) s[i].className = s[i].className.replace(/\s*on\b/, "") + (s[i].id === id ? " on" : "");
    current = id;
    try { localStorage.setItem("s1screen", id); } catch (e) {}
    if (id === "faults") renderFaults();
    if (id === "settings") renderSettings();
    if (id === "logger") renderLogger();
    if (id === "tune") tuneShown = "";
    render();
  }
  window.goBack = function () {
    if ($("modal").className.indexOf("on") >= 0) { closeModal(); return true; }
    if (!stack.length) { if (current !== "perf") { go("perf", true); return true; } return false; }
    go(stack.pop(), true);
    return true;
  };
  document.addEventListener("click", function (e) {
    var el = e.target;
    while (el && el !== document.body) {
      if (el.hasAttribute && el.hasAttribute("data-go")) { go(el.getAttribute("data-go")); return; }
      if (el.hasAttribute && el.hasAttribute("data-back")) { window.goBack(); return; }
      el = el.parentNode;
    }
  });

  // ---------- modal ----------
  var modalOk = null;
  function openModal(title, text, listHtml, okLabel, onOk) {
    $("m-title").textContent = title; $("m-text").textContent = text || "";
    $("m-list").innerHTML = listHtml || ""; $("m-list").style.display = listHtml ? "block" : "none";
    $("m-ok").textContent = okLabel || "OK"; $("m-ok").style.display = onOk ? "block" : "none";
    modalOk = onOk; $("modal").className = "modal on";
  }
  function closeModal() { $("modal").className = "modal"; modalOk = null; }
  $("m-cancel").onclick = closeModal;
  $("m-ok").onclick = function () { var f = modalOk; closeModal(); if (f) f(); };

  // ---------- screens ----------
  function buildModes() {
    var h = "";
    MODES.forEach(function (m, i) { h += '<div class="row" data-mode="' + i + '"><span class="radio"></span>' + m + (i === 5 ? '<span class="sub">map from OpenHaldex</span>' : "") + "</div>"; });
    $("modes").innerHTML = h;
    var rows = $("modes").children;
    for (var i = 0; i < rows.length; i++) rows[i].onclick = function () { B.setMode(parseInt(this.getAttribute("data-mode"), 10)); };
  }

  function dtcTotal() {
    var a = snap.v.dtcEcu, b = snap.v.dtcHaldex;
    if (a === undefined && b === undefined) return null;
    return (a || 0) + (b || 0);
  }

  var DTC_ICON = '<svg width="22" height="16" viewBox="0 0 22 16"><path d="M3 5h2V3h7v2h3l2 2h2v-2h2v7h-2v-2h-2v4h-4l-2 2H6l-1-2H3v-3H1v-3h2z" fill="none" stroke="#f0a81c" stroke-width="1.5" stroke-linejoin="round"/></svg>';

  function renderCommon() {
    var d = new Date(), clock = pad2(d.getHours()) + ":" + pad2(d.getMinutes());
    var els = document.querySelectorAll(".clock");
    for (var i = 0; i < els.length; i++) els[i].textContent = clock;
    var total = dtcTotal(), show = settings.dtcIndicator === "1" && total;
    var dt = document.querySelectorAll(".dtc");
    for (i = 0; i < dt.length; i++) { dt[i].className = "dtc" + (show ? " show" : ""); dt[i].innerHTML = show ? DTC_ICON + total : ""; }
    var amb = document.querySelectorAll(".amb"), a = snap.v.ambientBar;
    for (i = 0; i < amb.length; i++) amb[i].textContent = a !== undefined ? a.toFixed(3) + " bar" : "";
  }

  function disMessage(v, s) {
    if (v.hxTempProt === 1) return ["alarm", "Haldex temp<br>protection active"];
    if (v.coolant !== undefined && v.coolant >= 110) return ["alarm", "Coolant hot<br>" + v.coolant.toFixed(0) + " °C"];
    if (v.oil !== undefined && v.oil >= 130) return ["alarm", "Oil hot<br>" + v.oil.toFixed(0) + " °C"];
    if (v.clutchWarn === 1) return ["alarm", "Clutch slipping<br>" + num(v.clutchSlip, 0) + " %"];
    if (v.clutchLoad !== undefined && v.clutchLoad >= 90) return ["alarm", "Clutch hot<br>estimate"];
    if (v.knockAvg !== undefined && v.knockAvg <= -3) return ["warn", "Knock<br>" + v.knockAvg.toFixed(1) + "°"];
    if (v.clutchLoad !== undefined && v.clutchLoad >= 70) return ["warn", "Clutch warm<br>estimate"];
    if (v.oil !== undefined && v.oil < 80) return ["", "Oil warming up<br>avoid full load"];
    if (settings.ohEnabled === "1" && s.oh && s.oh !== "connected" && s.oh !== "demo") return ["warn", "OpenHaldex<br>" + s.oh];
    if (settings.ecuAdapter !== "none" && s.ecu && s.ecu.indexOf("connected") !== 0 && s.ecu !== "demo") return ["warn", "ECU<br>" + s.ecu];
    return ["", ""];
  }

  function renderPerf() {
    var v = snap.v, s = snap.s;
    targets.L.m = v.boost !== undefined ? v.boost : -1;
    targets.L.s = v.coolant !== undefined ? v.coolant : 50;
    targets.L.pk = snap.peak;
    targets.R.m = v.oil !== undefined ? v.oil : 40;
    targets.R.s = v.hxClutchTemp !== undefined ? v.hxClutchTemp : 0;
    $("d-mode").textContent = v.hxMode !== undefined ? MODES[v.hxMode] || "--" : "--";
    $("d-lock").textContent = "lock " + num(v.hxLock, 0) + " %";
    var rear = document.querySelectorAll("#perf .rear"), op = v.hxLock !== undefined ? 0.25 + 0.75 * Math.min(100, v.hxLock) / 100 : 0.25;
    for (var i = 0; i < rear.length; i++) rear[i].setAttribute("opacity", op.toFixed(2));
    $("d-peak").textContent = snap.peak > 0.05 ? snap.peak.toFixed(2) + " bar" : "--";
    $("d-atm").textContent = v.ambientBar !== undefined ? v.ambientBar.toFixed(3) + " bar" : "--";
    $("d-hxv").textContent = v.hxVolts !== undefined ? v.hxVolts.toFixed(1) + " V" : "--";
    $("d-clutch").textContent = num(v.clutchLoad, 0) + " %";
    var cb = $("d-clutchbar");
    cb.style.width = (v.clutchLoad !== undefined ? Math.min(100, v.clutchLoad) : 0) + "%";
    cb.parentNode.className = "cbar " + (v.clutchLoad !== undefined ? clutchState(v.clutchLoad) : "");
    var m = disMessage(v, s);
    $("d-msg").className = "msg " + m[0];
    $("d-msg").innerHTML = m[1];
    if ($("perf-stats").className.indexOf("on") >= 0) updateTiles("perf-tiles");
  }

  function renderAwd() {
    var v = snap.v, rows = $("modes").children;
    for (var i = 0; i < rows.length; i++) {
      var on = v.hxMode === i;
      rows[i].className = "row" + (on ? " sel" : "");
      rows[i].firstChild.className = "radio" + (on ? " on" : "");
    }
    $("a-lock").textContent = num(v.hxLock, 0) + " %";
    $("a-lockbar").style.width = (v.hxLock !== undefined ? Math.min(100, v.hxLock) : 0) + "%";
    $("a-target").textContent = num(v.hxLockTarget, 0) + " %";
    $("a-clutch").textContent = num(v.hxClutchTemp, 0) + " °C";
    $("a-module").textContent = num(v.hxModuleTemp, 0) + " °C";
    $("a-volts").textContent = num(v.hxVolts, 1) + " V";
    $("a-prot").textContent = v.hxTempProt === undefined ? "--" : v.hxTempProt ? "ON" : "Off";
    $("a-oh").textContent = snap.s.oh || "--";
  }

  // Fault memory: engine (via the ECU dongle) and Haldex (via OpenHaldex).
  var faultGroup = "ecu";
  function codeRows(json) {
    var o = {};
    try { o = JSON.parse(json); } catch (e) {}
    if (!o.state) return '<div class="row dim">Not read yet</div>';
    if (o.state === "busy") return '<div class="row dim">Reading…</div>';
    if (o.state === "error") return '<div class="row dim">' + (o.message || "Error") + "</div>";
    if (!o.dtcs || !o.dtcs.length) return '<div class="row dim">No fault codes</div>';
    return o.dtcs.map(function (d) {
      return '<div class="row">' + d.code + '<span class="val">' + (d.active ? "active" : d.confirmed ? "stored" : "pending") + "</span></div>";
    }).join("");
  }
  function renderFaults() {
    var v = snap.v;
    var h = '<div class="row head' + (faultGroup === "ecu" ? " sel" : "") + '" data-group="ecu">01 Engine electronics<span class="badge' + (v.dtcEcu ? " warn" : "") + '">' + num(v.dtcEcu, 0) + "</span></div>" +
      codeRows(B.ecuDtcs()) +
      '<div class="row head' + (faultGroup === "hx" ? " sel" : "") + '" data-group="hx">22 All-wheel drive · via OpenHaldex<span class="badge' + (v.dtcHaldex ? " warn" : "") + '">' + num(v.dtcHaldex, 0) + "</span></div>" +
      codeRows(B.haldexDtcs());
    $("fault-list").innerHTML = h;
    var heads = document.querySelectorAll("#fault-list .head");
    for (var i = 0; i < heads.length; i++) heads[i].onclick = function () { faultGroup = this.getAttribute("data-group"); renderFaults(); };
  }
  $("f-read").onclick = function () { if (faultGroup === "ecu") B.ecuDtcRead(); else B.haldexDtcRead(); setTimeout(renderFaults, 300); };
  $("f-clear").onclick = function () {
    var name = faultGroup === "ecu" ? "engine" : "Haldex";
    openModal("Clear " + name + " fault codes?", "Car stationary, engine off or idling. Codes for faults that are still there will come back.", "", "Clear",
      function () { if (faultGroup === "ecu") B.ecuDtcClear(); else B.haldexDtcClear(); setTimeout(renderFaults, 500); });
  };

  // Logger
  var recStart = 0;
  function renderLogger() {
    var info = JSON.parse(B.logInfo());
    $("rec").className = "rec" + (info.active ? " on" : "");
    $("rec-note").textContent = info.active ? "Recording · 10 Hz" : "Tap to record · 10 Hz";
    if (!info.active) $("rec-time").textContent = "00:00";
    $("log-list").innerHTML =
      '<div class="row">Saved sessions<span class="val">' + info.sessions + "</span></div>" +
      '<div class="row" style="font-size:16px;color:#9a9ea3">' + info.dir + "</div>" +
      '<div class="row head">Recorded</div>' +
      '<div class="row" style="font-size:18px;color:#9a9ea3">Boost, MAP, ambient, IAT, coolant, oil, knock 1-4, timing, wastegate, fuel, trims, speed, gear, torque, clutch slip and load estimate, Haldex mode / lock / temps / volts</div>';
  }
  $("rec-btn").onclick = function () {
    var info = JSON.parse(B.logInfo());
    if (info.active) B.logStop(); else { B.logStart(); recStart = Date.now(); }
    setTimeout(renderLogger, 150);
  };

  // ECU files: five CAL slots. SimosTools does the flash; this screen checks before and after.
  var tuneShown = "";
  var CHK = { pass: "✓", warn: "!", fail: "✕", wait: "…" };
  function checkRows(checks) {
    return checks.map(function (c) {
      return '<div class="row"><span class="chk ' + c.state + '">' + (CHK[c.state] || "") + "</span>" + esc(c.label) + '<span class="det">' + esc(c.detail) + "</span></div>";
    }).join("");
  }
  function softKey(id, label, cls, fn) {
    var el = $(id);
    el.textContent = label || "";
    el.className = "sk " + (id === "tune-left" ? "bl" : "br") + (cls ? " " + cls : "");
    el.onclick = fn || null;
    el.style.display = label ? "block" : "none";
  }
  function renderTune() {
    var json = B.calState();
    if (json === tuneShown) return;
    tuneShown = json;
    var o = JSON.parse(json), h = "";
    if (o.message) h += '<div class="row alert">' + esc(o.message) + "</div>";
    if (!o.state) {
      h += '<div class="row head">CAL slots · flashed by SimosTools</div>';
      o.slots.forEach(function (s) {
        var n = s.slot + 1;
        if (!s.name) { h += '<div class="row dim">Slot ' + n + ' · empty<span class="val"></span><span class="btn" data-pick="' + s.slot + '">Add…</span></div>'; return; }
        h += '<div class="row">' + n + " · " + esc(s.name) + (s.last ? '<span class="sub">last flashed ' + esc(s.last) + "</span>" : "") +
          '<span class="val"></span><span class="btn" data-prep="' + s.slot + '">Flash…</span><span class="btn" data-pick="' + s.slot + '">Replace</span><span class="btn" data-clear="' + s.slot + '">✕</span></div>';
      });
      h += '<div class="row note">"Last flashed" is what you confirmed here. The ECU can\'t say which file it holds.</div>';
      softKey("tune-left", "Back", "", function () { window.goBack(); });
      softKey("tune-right", "");
    } else if (o.state === "check") {
      h += '<div class="row head">Before flashing · ' + esc(o.name) + "</div>" + checkRows(o.checks) +
        '<div class="row note">Ignition on, engine off. Take out any other dongle, and keep the phone or head unit powered.</div>';
      softKey("tune-left", "Cancel", "", function () { B.calCancel(); tuneShown = ""; });
      softKey("tune-right", o.canFlash ? "Open SimosTools" : "Waiting for checks", o.canFlash ? "go" : "off", o.canFlash ? function () {
        openModal("Flash in SimosTools?", "Take out any other dongle. S1 Dash lets go of its dongle and leaves it alone until you say the flash is over. In SimosTools, choose " + o.name + " from Downloads and confirm.", "", "Open SimosTools",
          function () { var err = B.calStart(); tuneShown = ""; if (err) openModal("Not started", err, "", null, null); });
      } : null);
    } else if (o.state === "handoff") {
      h += '<div class="row head">Flashing in SimosTools · ' + esc(o.name) + "</div>" +
        '<div class="row">File in Downloads<span class="det">' + esc(o.file || "") + "</span></div>" +
        '<div class="row">Dongle<span class="det">Released until you tap below</span></div>' +
        '<div class="row note">Don\'t switch off the ignition or close SimosTools during the flash. When SimosTools says it\'s done, close it, switch the ignition off and on, and tap Flash finished.</div>' +
        '<div class="row" id="tune-reopen">Back to SimosTools<span class="chev" style="margin-left:auto">↗</span></div>' +
        '<div class="row dim" id="tune-noflash">I didn\'t flash it<span class="chev" style="margin-left:auto">›</span></div>';
      softKey("tune-left", "");
      softKey("tune-right", "Flash finished", "go", function () { B.calFinished(); tuneShown = ""; });
    } else if (o.state === "verify") {
      h += '<div class="row head">Health check · ' + esc(o.name) + "</div>" + checkRows(o.checks) +
        '<div class="row note">Nothing is cleared here. Clear codes in Fault memory when you\'re ready, after any tests that need them.</div>';
      softKey("tune-left", "");
      softKey("tune-right", "Finish", "go", function () {
        var waiting = o.checks.some(function (c) { return c.state === "wait"; });
        if (!waiting) { B.calFinalise(); tuneShown = ""; return; }
        openModal("Finish now?", "Some checks haven't finished. This records the slot as flashed anyway.", "", "Finish", function () { B.calFinalise(); tuneShown = ""; });
      });
    }
    $("tune-list").innerHTML = h;
    function each(sel, fn) { var r = document.querySelectorAll("#tune-list " + sel); for (var i = 0; i < r.length; i++) r[i].onclick = fn; }
    each("[data-pick]", function () { B.calPick(parseInt(this.getAttribute("data-pick"), 10)); tuneShown = ""; });
    each("[data-prep]", function () { B.calPrepare(parseInt(this.getAttribute("data-prep"), 10)); tuneShown = ""; });
    each("[data-clear]", function () {
      var n = parseInt(this.getAttribute("data-clear"), 10);
      openModal("Empty slot " + (n + 1) + "?", "This only removes the file from S1 Dash, not from the ECU.", "", "Empty", function () { B.calClear(n); tuneShown = ""; });
    });
    if ($("tune-reopen")) $("tune-reopen").onclick = function () { B.calReopen(); };
    if ($("tune-noflash")) $("tune-noflash").onclick = function () {
      openModal("Didn't flash it?", "Only say so if SimosTools never started writing. S1 Dash takes the dongle back straight away.", "", "I didn't flash", function () { B.calCancel(); tuneShown = ""; });
    };
  }

  // Settings
  function renderSettings() {
    settings = JSON.parse(B.settings());
    var s = snap.s, h = "";
    function radio(key, val, label, sub) {
      return '<div class="row" data-set="' + key + '" data-val="' + val + '"><span class="radio' + (settings[key] === val ? " on" : "") + '"></span>' + label + (sub ? '<span class="sub">' + sub + "</span>" : "") + "</div>";
    }
    function toggle(key, label, sub) {
      var on = settings[key] === "1";
      return '<div class="row" data-set="' + key + '" data-val="' + (on ? "0" : "1") + '"><span class="box' + (on ? " on" : "") + '"></span>' + label + (sub ? '<span class="sub">' + sub + "</span>" : "") + "</div>";
    }
    h += '<div class="row head">Display theme</div>' + radio("theme", "s1", "S1 cluster", "silver") + radio("theme", "quattro", "A1 quattro", "red / black") + radio("theme", "mmi", "MMI black");
    h += '<div class="row head">Dials</div>' + toggle("dtcIndicator", "Fault code indicator", "amber engine light in the header") +
      '<div class="row">Expected peak boost<span class="val">' + settings.boostTarget + ' bar</span><span class="btn" id="bt-down">−</span><span class="btn" id="bt-up">+</span></div>';
    var gears = [];
    try { gears = JSON.parse(B.clutchInfo()).gears; } catch (e) {}
    h += '<div class="row head">Clutch estimate (manual gearbox, no real sensor)</div>' +
      '<div class="row">Learned gears<span class="val">' + (gears.length ? gears.join(" ") : "none yet, cruise in each gear") + '</span><span class="btn" id="clutch-reset">Reset</span></div>';
    h += '<div class="row head">OpenHaldex (Haldex data and modes)</div>' + toggle("ohEnabled", "Use OpenHaldex", "over its Wi-Fi") +
      '<div class="row">Address<input id="oh-host" value="' + settings.ohHost + '"></div>' +
      '<div class="row dim">Status<span class="val" id="st-oh">' + (s.oh || "--") + "</span></div>";
    h += '<div class="row head">Engine ECU (Simos18 live data, same set as SimosTools)</div>' +
      radio("ecuAdapter", "none", "None") + radio("ecuAdapter", "bridge", "SimosTools dongle", "BLE ISO-TP bridge") +
      radio("ecuAdapter", "elm", "ELM327 dongle", "e.g. the steering wheel's; switch the wheel off first") +
      '<div class="row">Dongle<span class="val">' + (settings.ecuMac || (settings.ecuAdapter === "bridge" ? "find by name " + settings.bridgeName : "not set")) + '</span><span class="btn" id="pick-dev">Choose…</span><span class="btn" id="clear-dev">Clear</span></div>' +
      '<div class="row dim">Status<span class="val" id="st-ecu">' + (s.ecu || "--") + "</span></div>";
    h += '<div class="row head">Other</div>' + toggle("demo", "Demo mode", "made-up readings, no car needed") +
      '<div class="row" id="set-simos">Open SimosTools<span class="val">frees the dongle</span><span class="chev">↗</span></div>';
    $("set-list").innerHTML = h;
    var rows = document.querySelectorAll("#set-list [data-set]");
    for (var i = 0; i < rows.length; i++) rows[i].onclick = function () {
      var k = this.getAttribute("data-set"), val = this.getAttribute("data-val");
      B.setSetting(k, val); settings[k] = val;
      if (k === "theme" || k === "boostTarget") buildDials();
      renderSettings();
    };
    $("oh-host").onchange = function () { B.setSetting("ohHost", this.value.trim()); };
    function stepBoost(d) {
      var x = Math.max(0.5, Math.min(1.9, Math.round(((parseFloat(settings.boostTarget) || 1.8) + d) * 10) / 10));
      B.setSetting("boostTarget", x.toFixed(1)); settings.boostTarget = x.toFixed(1); buildDials(); renderSettings();
    }
    $("bt-down").onclick = function (e) { e.stopPropagation(); stepBoost(-0.1); };
    $("bt-up").onclick = function (e) { e.stopPropagation(); stepBoost(0.1); };
    $("pick-dev").onclick = function (e) { e.stopPropagation(); pickDevice(); };
    $("clear-dev").onclick = function (e) { e.stopPropagation(); B.setSetting("ecuMac", ""); renderSettings(); };
    $("set-simos").onclick = openSimos;
    $("clutch-reset").onclick = function (e) {
      e.stopPropagation();
      openModal("Forget learned gears?", "Do this after changing tyre size or gearbox. They are learned again while cruising.", "", "Forget",
        function () { B.clutchReset(); renderSettings(); });
    };
    var ver = B.version();
    $("ver").textContent = /^\d/.test(ver) ? "v" + ver : ver;
  }

  function pickDevice() {
    var devs = [];
    try { devs = JSON.parse(B.pairedDevices()); } catch (e) {}
    var h = devs.length ? devs.map(function (d) {
      return '<div class="row" data-mac="' + d.mac + '">' + d.name + '<span class="val">' + d.mac + "</span></div>";
    }).join("") : '<div class="row dim">No paired devices. Pair the dongle in Android Bluetooth settings first.</div>';
    openModal("Choose dongle", "Paired Bluetooth devices", h, null, null);
    var rows = document.querySelectorAll("#m-list [data-mac]");
    for (var i = 0; i < rows.length; i++) rows[i].onclick = function () {
      B.setSetting("ecuMac", this.getAttribute("data-mac")); closeModal(); renderSettings();
    };
  }

  function openSimos() {
    if (!B.openSimosTools()) openModal("SimosTools not installed", "Install SimosTools on this head unit to open it from here.", "", null, null);
  }
  $("open-simos").onclick = openSimos;

  // Tap the boost dial to reset the peak-hold.
  $("dialL").onclick = function () { B.resetPeak(); };

  var ticks = 0;
  function render() {
    ticks++;
    renderCommon();
    if (current === "perf") renderPerf();
    else if (current === "awd") renderAwd();
    else if (current === "engine") { updateTiles("eng-tiles"); $("eng-status").textContent = "ECU: " + (snap.s.ecu || "--"); }
    else if (current === "menu") {
      $("m-mode").textContent = snap.v.hxMode !== undefined ? MODES[snap.v.hxMode] : "";
      var t = dtcTotal(); $("m-dtc").textContent = t === null ? "" : t + " codes";
      $("m-log").textContent = snap.logging ? "recording" : "";
      $("m-tune").textContent = snap.s.ecu === "paused for flash" ? "flashing" : "";
    } else if (current === "faults") {
      if (ticks % 10 === 0) renderFaults();
    } else if (current === "tune") {
      if (ticks % 5 === 0 || !tuneShown) renderTune();
    } else if (current === "settings") {
      // Only the status lines: rebuilding the list would drop focus from the address box.
      if ($("st-oh")) $("st-oh").textContent = snap.s.oh || "--";
      if ($("st-ecu")) $("st-ecu").textContent = snap.s.ecu || "--";
    } else if (current === "logger" && snap.logging) {
      var sec = Math.floor((Date.now() - (recStart || Date.now())) / 1000);
      $("rec-time").textContent = pad2(Math.floor(sec / 60)) + ":" + pad2(sec % 60);
    }
  }

  function tick() {
    try { snap = JSON.parse(B.snapshot()); } catch (e) {}
    render();
  }

  // ---------- start ----------
  buildDials();
  buildModes();
  buildTiles("eng-tiles", ENGINE_KEYS);
  buildTiles("perf-tiles", PERF_KEYS);
  layout();
  window.addEventListener("resize", layout);
  var first = "perf";
  try {
    var saved = localStorage.getItem("s1screen");
    first = (location.search.match(/screen=(\w+)/) || [])[1] || (saved !== "settings" && saved) || "perf";
  } catch (e) {}
  if (!$(first)) first = "perf";
  go(first, true);
  tick();
  setInterval(tick, 100);
  requestAnimationFrame(animate);
})();
