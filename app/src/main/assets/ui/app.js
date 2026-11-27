/* S1 Dash UI. Runs inside the app's WebView (window.S1 is the Kotlin bridge) or
   in a plain browser, where a built-in demo bridge stands in.
   Kept to ES2017 for old Android 9 WebViews: no ?. or ??. */
(function () {
  "use strict";

  // ---------- bridge ----------
  var B = window.S1 || demoBridge();

  function demoBridge() {
    var settings = { theme: "s1", ohEnabled: "1", ohHost: "auto", ecuAdapter: "bridge", ecuMac: "",
      bridgeName: "BLE_TO_ISOTP20", bridgePassword: "", autoLog: "off", carplayPkg: "", carplayName: "", returnBubble: "1", stayConnected: "1", permPrompted: "", mediaKeysFlip: "0", needleFeel: "smooth", dialL: "boost", dialLs: "coolant", dialR: "oil", dialRs: "hxClutchTemp", cen1: "peak", cen2: "atm", cen3: "hxVolts", cen4: "clutchLoad", unitPressure: "bar", unitTemp: "C", unitSpeed: "kmh", unitTorque: "Nm", unitPower: "PS", dtcIndicator: "1", boostTarget: "1.8", demo: "1", uiScale: "100" };
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
          fpDi: 150 + pull * 200, fpMpi: 4.8, putSp: 101 + pull * 190, lambdaSp: 1 - pull * 0.19, torqueReq: 60 + pull * 320,
          fpDiSp: 150 + pull * 205, fpMpiSp: 5, lpfpDuty: 30 + pull * 45, boostDev: -pull * 0.04, lambdaDev: -pull * 0.01, fpDiDev: -pull * 5, fpMpiDev: -0.2, pedal: pull * 100, battery: 14.2, ecuSpeed: 60 + pull * 40, gear: 3, misfires: 0, turbo: 40 + pull * 120,
          torque: 60 + pull * 310, clutchSlip: demoSlip ? 8 : 0, clutchLoad: demoSlip ? 94 : 22, clutchWarn: demoSlip ? 1 : 0,
          hxMode: 2, hxLock: 30 + pull * 60, hxLockTarget: 30 + pull * 65, hxClutchTemp: Math.min(70, 30 + t * 0.15 + pull * 6),
          hxModuleTemp: 24, hxFinTemp: 22, hxVolts: 13.9, hxTempProt: 0, dtcEcu: 2, dtcHaldex: 0,
          gLat: Math.sin(t * 0.31) * 0.95, gLon: -pull * 0.45 + Math.max(0, -Math.sin(t * 0.5 + 0.6)) * 0.8 };
        return JSON.stringify({ v: v, s: { oh: "demo", simos: /[?&]simos/.test(location.search) ? "connected" : undefined, ecu: /[?&]simos/.test(location.search) ? "in use by SimosTools" : /[?&]elm/.test(location.search) ? "connected (ELM327)" : "demo", ecuData: /[?&]elm/.test(location.search) ? "core" : undefined, ecuRate: /[?&]elm/.test(location.search) ? "3.2" : undefined },  peak: /[?&]still/.test(location.search) ? 1.78 : peak, logging: logging, t: Date.now() });
      },
      settings: function () { return JSON.stringify(settings); },
      setSetting: function (k, v) { settings[k] = v; try { localStorage.setItem("s1demo", JSON.stringify(settings)); } catch (e) {} },
      version: function () { return "browser demo"; },
      setMode: function () {}, resetPeak: function () { peak = 0; }, pollFocus: function () {},
      clutchInfo: function () { return JSON.stringify({ gears: demoGears }); }, clutchReset: function () { demoGears = []; },
      haldexDtcs: function () { return JSON.stringify({ state: "done", dtcs: [] }); },
      haldexDtcRead: function () {}, haldexDtcClear: function () {},
      ecuDtcs: function () { return JSON.stringify({ state: "done", dtcs: [{ code: "P3019-00", active: false, confirmed: true }, { code: "U0155-00", active: false, confirmed: true }] }); },
      ecuDtcRead: function () {}, ecuDtcClear: function () {},
      logStart: function () { logging = true; }, logStop: function () { logging = false; },
      logInfo: function () { return JSON.stringify({ active: logging, sessions: 12, dir: "/sdcard/Android/data/com.louij2.s1dash/files/logs", file: "" }); },
      pairedDevices: function () { return JSON.stringify([{ name: "OBDII", mac: "00:1D:A5:68:98:8A" }, { name: "BLE_TO_ISOTP20", mac: "24:0A:C4:11:22:33" }]); },
      openSimosTools: function () { return false; }, reconnect: function () {},
      launchableApps: function (all) {
        var l = [{ pkg: "com.zjinnova.zlink", name: "ZLink", carplay: true }, { pkg: "com.app.simostools", name: "Simos Tools", carplay: false }, { pkg: "com.spotify.music", name: "Spotify", carplay: false }];
        if (all) l.unshift({ pkg: "com.carlinkit.carplay", name: "CarPlay", carplay: true, system: true });
        return JSON.stringify(l);
      },
      openApp: function () { return true; },
      // The demo is a fully set-up device; ?perms pretends two permissions are missing, to try the prompt.
      permState: function () { var miss = /[?&]perms/.test(location.search); return JSON.stringify({ overlay: !miss, notif: true, battery: !miss }); },
      askPerm: function () {}, overlayAllowed: function () { return /[?&]overlay/.test(location.search); }, askOverlay: function () {},
      connLog: function () { return "21 Nov 10:00:01  sources started\n21 Nov 10:00:02  OpenHaldex: connected\n21 Nov 10:00:03  ECU: connected (SimosTools dongle)\n21 Nov 10:04:12  ECU: BLE link dropped (status 8: link timeout, the radio lost the dongle), asking again"; },
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

  // ---------- stage scaling: the largest size at which 800 x 480 logical px fits, then fill what's left ----------
  var W = 800, H = 480;
  function layout() {
    var sw = window.innerWidth, sh = window.innerHeight;
    if (!sw || !sh) return; // a WebView can report nothing until it's laid out; resize comes after
    var scale = Math.min(sw / 800, sh / 480) * (parseInt(settings.uiScale, 10) || 100) / 100;
    W = Math.floor(sw / scale); H = Math.floor(sh / scale);
    var st = $("stage");
    st.style.width = W + "px"; st.style.height = H + "px";
    st.style.transform = "scale(" + scale + ")";
    // RS mode: side by side, or on tall screens (a phone upright) a bigger circle above the numbers.
    var sp = $("sp"), tall = H >= 1000;
    sp.className = "sp" + (tall ? " tall" : "");
    sp.style.left = Math.max(0, Math.round((W - 800) / 2)) + "px";
    sp.style.top = (tall ? 70 : 64 + Math.max(0, Math.round((H - 480) / 2))) + "px";
    $("gsvg").setAttribute("width", tall ? 600 : 360);
    $("gsvg").setAttribute("height", tall ? 600 : 360);
    var cl = $("cluster"), stats = $("perf-stats");
    stats.className = "stats";
    cl.style.top = (58 + Math.max(0, Math.round((H - 480) / 2))) + "px";
    if (W >= 1100) {
      cl.style.left = "0px";
      stats.className = "stats on";
      stats.style.cssText = "left:820px;right:28px;top:76px;bottom:54px;overflow-y:auto";
      if ($("perf-tiles").children.length !== PERF_KEYS.length) buildTiles("perf-tiles", PERF_KEYS);
      fitTiles("perf-tiles", W - 848, H - 130);
    } else if (H >= 700) {
      // Portrait or tall screens: the whole engine data set under the cluster.
      cl.style.left = Math.round((W - 800) / 2) + "px";
      cl.style.top = "58px";
      stats.className = "stats on";
      stats.style.cssText = "left:28px;right:28px;top:450px;bottom:54px;overflow-y:auto";
      if ($("perf-tiles").children.length !== ENGINE_KEYS.length) buildTiles("perf-tiles", ENGINE_KEYS);
      fitTiles("perf-tiles", W - 56, H - 504);
    } else {
      cl.style.left = Math.round((W - 800) / 2) + "px";
    }
    if (current) pushFocus();
    setTileWidth("eng-tiles", W >= 1300 ? "20%" : W >= 1000 ? "25%" : H >= 700 ? "33.33%" : "25%");
  }
  // Enough columns that all the tiles fit, unless they'd get too narrow to read.
  function fitTiles(id, w, h) {
    var n = $(id).children.length, cols = 2;
    while (Math.ceil(n / cols) * TILE_H > h && w / (cols + 1) >= TILE_MIN_W) cols++;
    setTileWidth(id, (100 / cols).toFixed(2) + "%");
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

  // ---------- units: values arrive metric (bar, kPa, °C, km/h, Nm, kW); shown in the units chosen in Settings ----------
  function unitOf(kind) {
    var d = { pressure: "bar", temp: "C", speed: "kmh", torque: "Nm", power: "PS" }[kind];
    return settings["unit" + kind.charAt(0).toUpperCase() + kind.slice(1)] || d;
  }
  /** [value, label, decimals] for x given in the metric unit src ("bar", "kPa", "°C", "km/h", "Nm"); dec = metric decimals. */
  function conv(src, x, dec) {
    var p = unitOf("pressure");
    if (src === "bar" || src === "kPa") {
      var bar = src === "kPa" ? (x === undefined ? x : x / 100) : x;
      var isAbs = src === "kPa"; // kPa values are absolute pressures (MAP and friends); keep their sense
      if (p === "psi") return [bar === undefined ? bar : bar * 14.5038, "psi", src === "kPa" ? 1 : Math.max(dec - 1, 0)];
      if (p === "kPa") return [bar === undefined ? bar : bar * 100, "kPa", src === "kPa" ? dec : Math.max(dec - 2, 0)];
      // Default "bar" keeps the kPa readings (MAP, ambient, pre-throttle) in kPa, as before units existed.
      return src === "kPa" ? [x, "kPa", dec] : [bar, "bar", dec];
    }
    if (src === "°C") return unitOf("temp") === "F" ? [x === undefined ? x : x * 9 / 5 + 32, "°F", dec] : [x, "°C", dec];
    if (src === "km/h") return unitOf("speed") === "mph" ? [x === undefined ? x : x * 0.621371, "mph", dec] : [x, "km/h", dec];
    if (src === "Nm") return unitOf("torque") === "lbft" ? [x === undefined ? x : x * 0.737562, "lb-ft", dec] : [x, "Nm", dec];
    if (src === "kW") { var pw = unitOf("power"); return pw === "kW" ? [x, "kW", 0] : pw === "hp" ? [x === undefined ? x : x * 1.34102, "hp", 0] : [x === undefined ? x : x * 1.35962, "PS", 0]; }
    return [x, src, dec];
  }
  /** "1.23 bar" style text in the chosen unit. */
  function fmt(src, x, dec) { var c = conv(src, x, dec); return num(c[0], c[2]) + " " + c[1]; }
  function P(barValue) { return conv("bar", barValue, 2)[0]; }
  function T(celsius) { return conv("°C", celsius, 0)[0]; }
  function powerText(kw) {
    if (kw === undefined) return "--";
    var u = unitOf("power"), x = Math.max(0, kw);
    return u === "kW" ? x.toFixed(0) + " kW" : u === "hp" ? (x * 1.34102).toFixed(0) + " hp" : (x * 1.35962).toFixed(0) + " PS";
  }

  // ---------- what the dials and the centre display show (Settings > Dials) ----------
  // The four defaults (boost, coolant, oil, Haldex clutch) keep their hand-picked scales, also in
  // psi / kPa / °F; any other choice gets a round-numbered scale in whatever unit is set.
  function hotBar() { var t = parseFloat(settings.boostTarget) || 1.8; return Math.min(1.9, Math.round((t + 0.1) * 10) / 10); }
  function fahr() { return unitOf("temp") === "F"; }
  var DIAL_METRICS = {
    boost: { name: "Boost", src: "bar", val: function (v) { return v.boost; },
      cap: function (u) { return "boost · " + u + " over atm"; },
      big: function () {
        var pu = unitOf("pressure"), hb = hotBar();
        return pu === "psi" ? { min: -15, max: 30, step: 5, minor: 1, hot: Math.round(P(hb)), dec: 0 }
          : pu === "kPa" ? { min: -100, max: 200, step: 50, minor: 10, hot: Math.round(P(hb)), dec: 0 }
          : { min: -1, max: 2, step: 0.5, minor: 0.1, hot: hb, dec: 1,
              labels: function (v) { if (Math.abs(v) < 1e-9) return "0"; var s = (+v.toFixed(1)).toString(); return s.replace(/^(-?)0\./, "$1."); } };
      },
      small: { min: -1, max: 2, hot: 1.9 } },
    coolant: { name: "Coolant", src: "°C", val: function (v) { return v.coolant; }, cap: "coolant",
      big: { min: 40, max: 140, step: 20, minor: 5, hot: 110 },
      small: function () { return fahr() ? { min: 120, max: 270, marks: [120, 195, 270], hot: 266 } : { min: 50, max: 130, marks: [50, 90, 130], hot: 130 }; } },
    oil: { name: "Oil", src: "°C", val: function (v) { return v.oil; }, cap: "oil",
      big: function () { return fahr() ? { min: 100, max: 340, step: 40, minor: 10, hot: 266 } : { min: 40, max: 160, step: 20, minor: 5, hot: 130 }; },
      small: { min: 40, max: 160, hot: 130 } },
    hxClutchTemp: { name: "Haldex clutch temp", src: "°C", val: function (v) { return v.hxClutchTemp; }, cap: "Haldex",
      big: { min: 0, max: 150, step: 25, minor: 5, hot: 120 },
      small: function () { return fahr() ? { min: 30, max: 300, marks: [30, 165, 300], hot: 300 } : { min: 0, max: 150, marks: [0, 75, 150], hot: 150 }; } },
    iat: { name: "Intake air", src: "°C", val: function (v) { return v.iat; }, cap: "intake", big: { min: -20, max: 80, step: 20, minor: 5, hot: 60 }, small: { min: 0, max: 80, hot: 60 } },
    rpm: { name: "Engine speed", src: "rpm ×1000", val: function (v) { return v.rpm === undefined ? v.rpm : v.rpm / 1000; }, cap: "",
      big: { min: 0, max: 8, step: 1, minor: 0.25, hot: 7 }, small: { min: 0, max: 8, hot: 7 } },
    map: { name: "MAP (absolute)", src: "kPa", val: function (v) { return v.map; }, cap: "MAP", big: { min: 0, max: 300, step: 50, minor: 10, hot: 290 }, small: { min: 0, max: 300, hot: 290 } },
    hxLock: { name: "Haldex lock", src: "%", val: function (v) { return v.hxLock; }, cap: "Haldex lock", big: { min: 0, max: 100, step: 20, minor: 5 }, small: { min: 0, max: 100 } },
    torque: { name: "Torque", src: "Nm", val: function (v) { return v.torque; }, cap: "torque", big: { min: 0, max: 500, step: 100, minor: 20, hot: 450 }, small: { min: 0, max: 500, hot: 450 } },
    power: { name: "Power (ECU est.)", src: "kW", val: function (v) { return v.torque !== undefined && v.rpm !== undefined ? Math.max(0, v.torque * v.rpm / 9549) : undefined; }, cap: "power",
      big: { min: 0, max: 300, step: 50, minor: 10, hot: 260 }, small: { min: 0, max: 300, hot: 260 } },
    ecuSpeed: { name: "Speed", src: "km/h", val: function (v) { return v.ecuSpeed; }, cap: "speed", big: { min: 0, max: 260, step: 40, minor: 10 }, small: { min: 0, max: 260 } },
    lambda: { name: "Lambda", src: "λ", val: function (v) { return v.lambda; }, cap: "lambda", big: { min: 0.6, max: 1.4, step: 0.2, minor: 0.05, dec: 1 }, small: { min: 0.6, max: 1.4 } },
    fpDi: { name: "Fuel DI pressure", src: "bar", val: function (v) { return v.fpDi; }, cap: "fuel DI", big: { min: 0, max: 400, step: 50, minor: 10, hot: 350 }, small: { min: 0, max: 400, hot: 350 } },
    battery: { name: "Battery", src: "V", val: function (v) { return v.battery; }, cap: "battery", big: { min: 10, max: 16, step: 1, minor: 0.25, hot: 15.5 }, small: { min: 10, max: 16, hot: 15.5 } },
    turbo: { name: "Turbo speed", src: "krpm", val: function (v) { return v.turbo; }, cap: "turbo", big: { min: 0, max: 220, step: 40, minor: 10, hot: 195 }, small: { min: 0, max: 220, hot: 195 } },
    timing: { name: "Ignition timing", src: "°", val: function (v) { return v.timing; }, cap: "ignition", big: { min: -10, max: 40, step: 10, minor: 2 }, small: { min: -10, max: 40 } }
  };
  var DIAL_SLOTS = [["dialL", "Left dial", "boost"], ["dialLs", "Left small dial", "coolant"], ["dialR", "Right dial", "oil"], ["dialRs", "Right small dial", "hxClutchTemp"]];
  function dialKey(slot) { var d = DIAL_SLOTS.filter(function (x) { return x[0] === slot; })[0]; var k = settings[slot] || d[2]; return DIAL_METRICS[k] ? k : d[2]; }
  /** A scale with round numbers covering lo..hi, about six labelled steps. */
  function niceScale(lo, hi, hot) {
    var raw = (hi - lo) / 6, mag = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10)), step = mag;
    [1, 2, 2.5, 5, 10].some(function (f) { step = f * mag; return step >= raw; });
    var min = Math.floor(lo / step) * step, max = Math.ceil(hi / step) * step;
    return { min: min, max: max, step: step, minor: step / 5, hot: hot, dec: step < 1 ? (step < 0.1 ? 2 : 1) : 0 };
  }
  function cv(m, x) { return x === undefined || x === null ? x : conv(m.src, x, 2)[0]; }
  function bigScale(m) {
    if (typeof m.big === "function") return m.big();
    if (conv(m.src, 0, 0)[1] === m.src) return m.big;
    return niceScale(cv(m, m.big.min), cv(m, m.big.max), cv(m, m.big.hot));
  }
  function smallScale(m) {
    var sc = typeof m.small === "function" ? m.small() : m.small;
    if (sc.marks) return sc;
    var conv0 = conv(m.src, 0, 0)[1] !== m.src, lo = conv0 ? cv(m, sc.min) : sc.min, hi = conv0 ? cv(m, sc.max) : sc.max;
    var n = niceScale(lo, hi);
    return { min: n.min, max: n.max, marks: [n.min, +((n.min + n.max) / 2).toFixed(n.dec), n.max], hot: sc.hot === undefined ? undefined : (conv0 ? cv(m, sc.hot) : sc.hot) };
  }
  function caption(m) { var u = conv(m.src, 0, 0)[1]; return typeof m.cap === "function" ? m.cap(u) : (m.cap ? m.cap + " " : "") + u; }

  function buildDials() {
    theme = THEMES[settings.theme] || THEMES.s1;
    var target = parseFloat(settings.boostTarget) || 1.8;
    var L = DIAL_METRICS[dialKey("dialL")], Ls = DIAL_METRICS[dialKey("dialLs")], Rm = DIAL_METRICS[dialKey("dialR")], Rs = DIAL_METRICS[dialKey("dialRs")];
    // The expected-peak marker belongs to boost, wherever boost is shown.
    dials.L = buildDial("dialL", 0, bigScale(L), smallScale(Ls), caption(L), caption(Ls), dialKey("dialL") === "boost" ? P(target) : undefined);
    dials.R = buildDial("dialR", 1, bigScale(Rm), smallScale(Rs), caption(Rm), caption(Rs), dialKey("dialR") === "boost" ? P(target) : undefined);
  }

  // Centre display lines (Settings > Dials): label + formatted value; line 4 has a bar for percentages.
  var CENTRE = {
    peak: ["Peak", "Peak boost", function (v) { return snap.peak > 0.05 ? fmt("bar", snap.peak, 2) : "--"; }],
    atm: ["Atm", "Atmospheric pressure", function (v) { return v.ambientBar !== undefined ? fmt("bar", v.ambientBar, 3) : "--"; }],
    hxVolts: ["Haldex", "Haldex volts", function (v) { return v.hxVolts !== undefined ? v.hxVolts.toFixed(1) + " V" : "--"; }],
    clutchLoad: ["Clutch est.", "Clutch load estimate", function (v) { return num(v.clutchLoad, 0) + " %"; }, "clutchLoad"],
    boost: ["Boost", "Boost", function (v) { return fmt("bar", v.boost, 2); }],
    oil: ["Oil", "Oil temp", function (v) { return fmt("°C", v.oil, 0); }],
    coolant: ["Coolant", "Coolant temp", function (v) { return fmt("°C", v.coolant, 0); }],
    iat: ["Intake", "Intake air temp", function (v) { return fmt("°C", v.iat, 0); }],
    hxClutchTemp: ["Haldex °", "Haldex clutch temp", function (v) { return fmt("°C", v.hxClutchTemp, 0); }],
    hxLock: ["Lock", "Haldex lock", function (v) { return num(v.hxLock, 0) + " %"; }, "hxLock"],
    power: ["Power", "Power (ECU est.)", function (v) { return powerText(v.torque !== undefined && v.rpm !== undefined ? v.torque * v.rpm / 9549 : undefined); }],
    torque: ["Torque", "Torque", function (v) { return fmt("Nm", v.torque, 0); }],
    rpm: ["rpm", "Engine speed", function (v) { return num(v.rpm, 0); }],
    gear: ["Gear", "Gear", function (v) { return num(v.gear, 0); }],
    ecuSpeed: ["Speed", "Speed", function (v) { return fmt("km/h", v.ecuSpeed, 0); }],
    lambda: ["Lambda", "Lambda", function (v) { return num(v.lambda, 2); }],
    knockAvg: ["Knock", "Knock average", function (v) { return num(v.knockAvg, 1) + "°"; }],
    battery: ["Volts", "Battery", function (v) { return num(v.battery, 1) + " V"; }],
    pedal: ["Pedal", "Pedal", function (v) { return num(v.pedal, 0) + " %"; }, "pedal"]
  };
  var CENTRE_SLOTS = [["cen1", "Centre line 1", "peak"], ["cen2", "Centre line 2", "atm"], ["cen3", "Centre line 3", "hxVolts"], ["cen4", "Centre line 4", "clutchLoad"]];
  function centreKey(i) { var d = CENTRE_SLOTS[i]; var k = settings[d[0]] || d[2]; return CENTRE[k] ? k : d[2]; }

  // Ease needles toward their targets every frame.
  var targets = { L: { m: -1, s: 50, pk: null }, R: { m: 40, s: 0, pk: null } };
  // Weighted needles: the target angle is lightly filtered (readings arrive at ~10 Hz with noise),
  // then a critically damped spring moves the needle, so it accelerates and settles without
  // overshoot like a real gauge. w = natural frequency (rad/s), lp = target filter time (s).
  var NEEDLE = { smooth: { w: 7, lp: 0.12 }, sport: { w: 14, lp: 0.05 } };
  var lastFrame = 0;
  function stepNeedle(cur, k, target, dt, feel) {
    if (cur[k] === null) { cur[k] = target; cur[k + "v"] = 0; cur[k + "f"] = target; return; }
    var f = cur[k + "f"] + (target - cur[k + "f"]) * (1 - Math.exp(-dt / feel.lp));
    var acc = feel.w * feel.w * (f - cur[k]) - 2 * feel.w * cur[k + "v"];
    cur[k + "v"] += acc * dt;
    cur[k] += cur[k + "v"] * dt;
    cur[k + "f"] = f;
  }
  function animate(now) {
    var dt = lastFrame && now ? Math.min(0.05, Math.max(0.001, (now - lastFrame) / 1000)) : 1 / 60;
    lastFrame = now || 0;
    var feel = NEEDLE[settings.needleFeel] || NEEDLE.smooth;
    ["L", "R"].forEach(function (k) {
      var d = dials[k]; if (!d) return;
      stepNeedle(d.cur, "m", d.ang(targets[k].m), dt, feel);
      stepNeedle(d.cur, "s", d.sang(targets[k].s), dt, feel);
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
    hxVolts: ["Haldex volts", "V", 1], torque: ["Torque", "Nm", 0],
    putSp: ["Pre-throttle target", "kPa", 0], lambdaSp: ["Lambda target", "λ", 2], torqueReq: ["Torque request", "Nm", 0],
    fpDiSp: ["Fuel DI target", "bar", 0], fpMpiSp: ["Fuel MPI target", "bar", 2], lpfpDuty: ["LPFP duty", "%", 0],
    boostDev: ["Boost vs target", "bar", 2], lambdaDev: ["Lambda vs target", "λ", 2], fpDiDev: ["Fuel DI vs target", "bar", 0], fpMpiDev: ["Fuel MPI vs target", "bar", 2], clutchSlip: ["Clutch slip", "%", 1], clutchLoad: ["Clutch load est.", "%", 0]
  };
  var ENGINE_KEYS = ["boost", "map", "mapSp", "ambient", "rpm", "iat", "coolant", "oil", "knockAvg", "knock1", "knock2", "knock3", "knock4",
    "timing", "wastegate", "put", "afr", "lambda", "stft", "ltft", "fpDi", "fpMpi", "pedal", "battery", "ecuSpeed", "gear", "torque", "clutchSlip", "clutchLoad", "misfires", "turbo",
    "boostDev", "putSp", "lambdaSp", "lambdaDev", "torqueReq", "fpDiSp", "fpDiDev", "fpMpiSp", "fpMpiDev", "lpfpDuty",
    "hxLock", "hxLockTarget", "hxClutchTemp", "hxModuleTemp", "hxVolts"];
  var TILE_H = 72, TILE_MIN_W = 140;
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
      var c = conv(def[1], x, def[2]);
      t[i].lastChild.innerHTML = num(c[0], c[2]) + (c[1] ? "<small>" + c[1] + "</small>" : "");
    }
  }

  // ---------- navigation ----------
  var stack = [];
  var current = null;
  function go(id, noPush) {
    if (typeof clearFocus === "function") clearFocus();
    if (current && !noPush && current !== id) stack.push(current);
    var s = document.querySelectorAll(".screen");
    for (var i = 0; i < s.length; i++) s[i].className = s[i].className.replace(/\s*on\b/, "") + (s[i].id === id ? " on" : "");
    current = id;
    try { localStorage.setItem("s1screen", id); } catch (e) {}
    if (id === "faults") renderFaults();
    if (id === "settings") renderSettings();
    if (id === "logger") renderLogger();
    if (id === "tune") tuneShown = "";
    pushFocus();
    render();
  }
  // Slow adapters only fetch the core dials, unless this screen shows the rest.
  function pushFocus() {
    B.pollFocus(current === "engine" || (current === "perf" && $("perf-stats").className.indexOf("on") >= 0));
  }
  function dataNote() {
    var s = snap.s;
    if (!s.ecuRate) return "";
    return s.ecuData === "core" ? "slow adapter, core dials first · " + s.ecuRate + " updates/s" : "all values · " + s.ecuRate + " updates/s";
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
  function closeModal() { $("modal").className = "modal"; modalOk = null; if (keyTester && B.keyProbe) B.keyProbe(false); keyTester = false; }
  $("m-cancel").onclick = closeModal;
  $("m-ok").onclick = function () { var f = modalOk; closeModal(); if (f) f(); };

  // ---------- screens ----------
  function buildModes() {
    var h = "";
    MODES.forEach(function (m, i) { h += '<div class="row" data-mode="' + i + '"><span class="radio"></span>' + m + (i === 5 ? '<span class="sub">map from OpenHaldex</span>' : "") + "</div>"; });
    h += '<div class="row alert" id="a-modenote" style="display:none"></div>';
    h += '<div class="row alert" id="a-diag" style="display:none"></div>';
    $("modes").innerHTML = h;
    var rows = $("modes").children;
    for (var i = 0; i < rows.length; i++) rows[i].onclick = function () {
      var m = parseInt(this.getAttribute("data-mode"), 10);
      modePending = { mode: m, t: Date.now() };
      B.setMode(m);
    };
  }

  function dtcTotal() {
    var a = snap.v.dtcEcu, b = snap.v.dtcHaldex;
    if (a === undefined && b === undefined) return null;
    return (a || 0) + (b || 0);
  }

  var DTC_ICON = '<svg width="22" height="16" viewBox="0 0 22 16"><path d="M3 5h2V3h7v2h3l2 2h2v-2h2v7h-2v-2h-2v4h-4l-2 2H6l-1-2H3v-3H1v-3h2z" fill="none" stroke="#f0a81c" stroke-width="1.5" stroke-linejoin="round"/></svg>';

  // OpenHaldex overrides (#1049). The flags come from /api/dashboard; which triggers force a mode,
  // to what, and in which order come from /api/settings. Same rule as the firmware's
  // get_forced_mode_value(): the first enabled + active trigger in priority order wins, then drive mode.
  var FORCE_ORDER = [[1, 0, 2], [0, 1, 2], [1, 2, 0], [0, 2, 1], [2, 0, 1], [2, 1, 0]]; // 0 TC, 1 hazards, 2 button
  var FORCE_BY = ["TC off", "hazards", "button"];
  function overrides() {
    var v = snap.v, cfg = {}, chips = [];
    try { cfg = JSON.parse(snap.s.ohForce || "{}"); } catch (e) {}
    if (v.hxTcOff === 1) chips.push("TC OFF"); else if (v.hxAsrOff === 1) chips.push("ASR OFF");
    if (v.hxHazard === 1) chips.push("HAZARD");
    if (v.hxExtBtn === 1) chips.push("BUTTON");
    var flags = [v.hxTcOff === 1, v.hxHazard === 1, v.hxExtBtn === 1], trig = [cfg.tc, cfg.hz, cfg.ext];
    var order = FORCE_ORDER[cfg.prio] || FORCE_ORDER[0], forced = -1, by = "";
    for (var i = 0; i < 3 && forced < 0; i++) {
      var k = order[i];
      if (trig[k] && trig[k][0] && flags[k]) { forced = trig[k][1]; by = FORCE_BY[k]; }
    }
    if (forced < 0 && v.hxDriveForce !== undefined && v.hxDriveForce >= 0) { forced = v.hxDriveForce; by = "drive mode"; }
    if (!MODES[forced]) forced = -1;
    if (forced >= 0) chips.push("FORCED " + MODES[forced]);
    return { chips: chips, forced: forced, by: by };
  }

  function renderCommon() {
    var d = new Date(), clock = pad2(d.getHours()) + ":" + pad2(d.getMinutes());
    var els = document.querySelectorAll(".clock");
    for (var i = 0; i < els.length; i++) els[i].textContent = clock;
    var total = dtcTotal(), show = settings.dtcIndicator === "1" && total;
    var dt = document.querySelectorAll(".dtc");
    for (i = 0; i < dt.length; i++) { dt[i].className = "dtc" + (show ? " show" : ""); dt[i].innerHTML = show ? DTC_ICON + total : ""; }
    var st = snap.s.simos ? "SimosTools connected" : snap.s.ecu === "paused for SimosTools" ? "SimosTools open" : "";
    var si = document.querySelectorAll(".simos");
    for (i = 0; i < si.length; i++) { si[i].textContent = st; si[i].style.display = st ? "block" : "none"; }
    var cpn = canSwitch() && settings.carplayPkg ? (settings.carplayName || "CarPlay") + " ↗" : "";
    var cpe = document.querySelectorAll(".cp");
    for (i = 0; i < cpe.length; i++) { cpe[i].textContent = cpn; cpe[i].style.display = cpn ? "block" : "none"; }
    var ov = overrides().chips.join(" · ");
    var oe = document.querySelectorAll(".ovr");
    for (i = 0; i < oe.length; i++) { oe[i].textContent = ov; oe[i].style.display = ov ? "block" : "none"; }
    var amb = document.querySelectorAll(".amb"), a = snap.v.ambientBar;
    for (i = 0; i < amb.length; i++) amb[i].textContent = a !== undefined ? fmt("bar", a, 3) : "";
  }

  function disMessage(v, s) {
    if (v.hxTempProt === 1) return ["alarm", "Haldex temp<br>protection active"];
    if (v.coolant !== undefined && v.coolant >= 110) return ["alarm", "Coolant hot<br>" + fmt("°C", v.coolant, 0)];
    if (v.oil !== undefined && v.oil >= 130) return ["alarm", "Oil hot<br>" + fmt("°C", v.oil, 0)];
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
    function tgt(slot) { var m = DIAL_METRICS[dialKey(slot)], x = cv(m, m.val(v)); return x === undefined || x === null ? -1e9 : x; } // -1e9 = rests at the stop
    targets.L.m = tgt("dialL"); targets.L.s = tgt("dialLs");
    targets.R.m = tgt("dialR"); targets.R.s = tgt("dialRs");
    var pk = snap.peak > 0.05 ? P(snap.peak) : null;
    targets.L.pk = dialKey("dialL") === "boost" ? pk : null;
    targets.R.pk = dialKey("dialR") === "boost" ? pk : null;
    var ovp = overrides();
    $("d-mode").textContent = ovp.forced >= 0 ? MODES[ovp.forced] : v.hxMode !== undefined ? MODES[v.hxMode] || "--" : "--";
    $("d-lock").textContent = ovp.forced >= 0 ? "forced · " + ovp.by : "lock " + num(v.hxLock, 0) + " %";
    var rear = document.querySelectorAll("#perf .rear"), op = v.hxLock !== undefined ? 0.25 + 0.75 * Math.min(100, v.hxLock) / 100 : 0.25;
    for (var i = 0; i < rear.length; i++) rear[i].setAttribute("opacity", op.toFixed(2));
    for (var ci = 0; ci < 4; ci++) {
      var c = CENTRE[centreKey(ci)];
      $("d-k" + (ci + 1)).textContent = c[0];
      $("d-v" + (ci + 1)).textContent = c[2](v);
    }
    // The bar under line 4 shows a percentage (clutch load by default), coloured like the clutch warning.
    var c4 = CENTRE[centreKey(3)], pct = c4[3] ? v[c4[3]] : undefined, cb = $("d-clutchbar");
    cb.parentNode.style.display = c4[3] ? "" : "none";
    cb.style.width = (pct !== undefined ? Math.min(100, Math.max(0, pct)) : 0) + "%";
    cb.parentNode.className = "cbar " + (c4[3] === "clutchLoad" && pct !== undefined ? clutchState(pct) : "");
    var m = disMessage(v, s);
    $("d-msg").className = "msg " + m[0];
    $("d-msg").innerHTML = m[1];
    if ($("perf-stats").className.indexOf("on") >= 0) updateTiles("perf-tiles");
  }

  // OpenHaldex never answers POST /api/mode, so the only sign a tap worked is the mode it reports next.
  var modePending = null;
  function modeNote() {
    if (snap.s.oh === "demo") { modePending = null; return ""; }
    if (snap.s.ohDisabled === "1") return "OpenHaldex has Disable controller on in its settings, so it ignores modes from here. Its own button still works.";
    if (!modePending) return "";
    var age = Date.now() - modePending.t;
    if (snap.v.hxMode === modePending.mode || age > 10000) { modePending = null; return ""; }
    if (age < 2500) return "";
    if (modePending.mode === 0 && snap.s.ohStandalone === "1") return "OpenHaldex is in standalone mode, where Stock isn't a mode: it keeps the last one.";
    if (snap.s.oh !== "connected") return "Not connected to OpenHaldex (" + (snap.s.oh || "--") + "). Is this device on its Wi-Fi?";
    return "OpenHaldex didn't take " + MODES[modePending.mode] + ".";
  }

  function renderAwd() {
    var v = snap.v, rows = $("modes").children;
    var note = modeNote(), el = $("a-modenote");
    el.textContent = note;
    el.style.display = note ? "" : "none";
    for (var i = 0; i < MODES.length; i++) {
      var on = v.hxMode === i;
      rows[i].className = "row" + (on ? " sel" : "");
      rows[i].firstChild.className = "radio" + (on ? " on" : "");
    }
    $("a-lock").textContent = num(v.hxLock, 0) + " %";
    $("a-lockbar").style.width = (v.hxLock !== undefined ? Math.min(100, v.hxLock) : 0) + "%";
    $("a-target").textContent = num(v.hxLockTarget, 0) + " %";
    $("a-clutch").textContent = fmt("°C", v.hxClutchTemp, 0);
    $("a-module").textContent = fmt("°C", v.hxModuleTemp, 0);
    $("a-volts").textContent = num(v.hxVolts, 1) + " V";
    $("a-prot").textContent = v.hxTempProt === undefined ? "--" : v.hxTempProt ? "ON" : "Off";
    $("a-oh").textContent = snap.s.oh || "--";
    // Haldex voltage / temps come from OpenHaldex's live diagnostics, which the firmware ships switched off.
    var dn = $("a-diag"), dmsg = "";
    if (snap.s.oh === "connected" && v.hxVolts === undefined && v.hxClutchTemp === undefined) {
      if (snap.s.ohDiagTool === "1") dmsg = "Haldex data paused: VCDS or SimosTools is using it";
      else if (snap.s.ohLiveDiag === "0") dmsg = 'Live Diagnostics is off in OpenHaldex<span class="btn" id="a-diag-on" style="white-space:nowrap;margin-left:auto">Turn on</span>';
      else if (snap.s.ohLiveDiag === "1") dmsg = "Live Diagnostics on, no Haldex reply yet";
    }
    if (dn.getAttribute("data-msg") !== dmsg) {
      dn.setAttribute("data-msg", dmsg); dn.innerHTML = dmsg; dn.style.display = dmsg ? "" : "none";
      if ($("a-diag-on")) $("a-diag-on").onclick = function () {
        openModal("Turn on Live Diagnostics?", "OpenHaldex will poll the Haldex over its diagnostic channel for voltage, temperatures and clutch current. Turn it off in OpenHaldex before using VCDS on the Haldex.", "", "Turn on",
          function () { B.ohLiveDiag(true); });
      };
    }
    var ova = overrides();
    $("a-ovr").textContent = ova.forced >= 0 ? ova.by : "none";
  }

  // RS mode: g-force circle. gLat + = pushed right (a left turn), gLon + = pushed forward (braking).
  // The dot shows the force the driver feels: up when braking, left in a right turn.
  var G_PX = 160 / 1.5, gTrail = [], gPeak = { l: 0, r: 0, b: 0, a: 0 };
  function buildSport() {
    var svg = $("gsvg"), h = "";
    [0.5, 1, 1.5].forEach(function (g) {
      h += '<circle class="ring' + (g === 1 ? " one" : "") + '" r="' + g * G_PX + '"/>';
      h += '<text x="' + (g * G_PX - 30) + '" y="16">' + g + " g</text>";
    });
    h += '<line class="axis" x1="-170" y1="0" x2="170" y2="0"/><line class="axis" x1="0" y1="-170" x2="0" y2="170"/>';
    h += '<text x="-30" y="-166" style="font-size:12px">BRAKE</text><text x="-24" y="178" style="font-size:12px">ACCEL</text>';
    h += '<polyline class="trail" id="g-trail" points=""/>';
    ["l", "r", "b", "a"].forEach(function (k) { h += '<circle class="peak" id="g-pk-' + k + '" r="5" cx="0" cy="0" style="display:none"/>'; });
    h += '<circle class="dot" id="g-dot" r="11" cx="0" cy="0"/>';
    svg.innerHTML = h;
  }
  function resetGPeaks() { gPeak = { l: 0, r: 0, b: 0, a: 0 }; gTrail = []; }
  $("g-reset").onclick = resetGPeaks;
  function gx(v) { return Math.max(-1.6, Math.min(1.6, v)) * G_PX; }
  function renderSport() {
    var v = snap.v, s = snap.s, have = v.gLat !== undefined && v.gLon !== undefined, now = Date.now();
    var note = "";
    if (!have) note = s.g === "no motion sensor" ? "This screen has no motion sensor, so no g-force here. Try S1 Dash on a phone."
      : s.g === "learning" ? "Learning which way is down: keep the car still a moment." : "No g-force reading yet.";
    $("g-note").textContent = note;
    $("g-dot").style.display = have ? "" : "none";
    if (have) {
      gPeak.l = Math.max(gPeak.l, -v.gLat); gPeak.r = Math.max(gPeak.r, v.gLat);
      gPeak.b = Math.max(gPeak.b, v.gLon); gPeak.a = Math.max(gPeak.a, -v.gLon);
      gTrail.push([now, gx(v.gLat), gx(-v.gLon)]);
      $("g-dot").setAttribute("cx", gx(v.gLat)); $("g-dot").setAttribute("cy", gx(-v.gLon));
    }
    while (gTrail.length && gTrail[0][0] < now - 3000) gTrail.shift();
    $("g-trail").setAttribute("points", gTrail.map(function (p) { return p[1].toFixed(1) + "," + p[2].toFixed(1); }).join(" "));
    var pos = { l: [-gPeak.l, 0], r: [gPeak.r, 0], b: [0, -gPeak.b], a: [0, gPeak.a] };
    for (var k in pos) {
      var el = $("g-pk-" + k), on = gPeak[k] >= 0.05;
      el.style.display = on ? "" : "none";
      if (on) { el.setAttribute("cx", gx(pos[k][0])); el.setAttribute("cy", gx(pos[k][1])); }
    }
    $("g-lat").textContent = have ? Math.abs(v.gLat).toFixed(2) + " g" : "--";
    $("g-lonl").textContent = have && v.gLon < 0 ? "Accel." : "Braking";
    $("g-lon").textContent = have ? Math.abs(v.gLon).toFixed(2) + " g" : "--";
    $("pk-l").textContent = gPeak.l.toFixed(2) + " g"; $("pk-r").textContent = gPeak.r.toFixed(2) + " g";
    $("pk-b").textContent = gPeak.b.toFixed(2) + " g"; $("pk-a").textContent = gPeak.a.toFixed(2) + " g";
    // Power from the ECU's own torque figure: P = T x rpm / 9549 kW, shown in PS like the cluster.
    var kw = v.torque !== undefined && v.rpm !== undefined ? v.torque * v.rpm / 9549 : undefined;
    $("sp-pow").textContent = powerText(kw);
    $("sp-boost").textContent = fmt("bar", v.boost, 2);
    $("sp-oil").textContent = fmt("°C", v.oil, 0);
    $("sp-lock").textContent = num(v.hxLock, 0) + " %";
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
      '<div class="row">Auto-record<span class="val">' + ({ either: "cruise held or pedal > 70 %", pedal: "pedal > 70 %", cruise: "cruise held" }[settings.autoLog] || "off (Settings)") + "</span></div>" +
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
  // Pickers for Settings > Dials.
  function pickFrom(title, opts, cur, onPick) {
    var h = opts.map(function (o) { return '<div class="row' + (o[0] === cur ? " sel" : "") + '" data-pick="' + o[0] + '">' + esc(o[1]) + "</div>"; }).join("");
    openModal(title, "", h, null, null);
    var rows = document.querySelectorAll("#m-list [data-pick]");
    for (var i = 0; i < rows.length; i++) rows[i].onclick = function () { var k = this.getAttribute("data-pick"); closeModal(); onPick(k); };
  }
  function pickDial(slot) {
    var label = DIAL_SLOTS.filter(function (d) { return d[0] === slot; })[0][1];
    pickFrom(label, Object.keys(DIAL_METRICS).map(function (k) { return [k, DIAL_METRICS[k].name]; }), dialKey(slot), function (k) {
      B.setSetting(slot, k); settings[slot] = k; buildDials(); renderSettings();
    });
  }
  function pickCentre(i) {
    pickFrom(CENTRE_SLOTS[i][1], Object.keys(CENTRE).map(function (k) { return [k, CENTRE[k][1]]; }), centreKey(i), function (k) {
      B.setSetting(CENTRE_SLOTS[i][0], k); settings[CENTRE_SLOTS[i][0]] = k; renderSettings();
    });
  }

  // ---------- Android permissions the optional features need (#1060, #1061) ----------
  var PERMS = {
    overlay: ["Floating S1 button", "display over other apps, to come back from CarPlay"],
    notif: ["Notifications", "shows S1 Dash staying connected in the background, with Disconnect"],
    battery: ["Battery: not restricted", "otherwise Android may stop S1 Dash behind CarPlay"],
    bluetooth: ["Bluetooth (nearby devices)", "to reach the ECU dongle"]
  };
  function permState() { try { return JSON.parse(B.permState()); } catch (e) { return {}; } }
  function missingPerms() { var st = permState(); return Object.keys(PERMS).filter(function (k) { return st[k] === false; }); }
  function permRows() {
    var st = permState(), keys = Object.keys(PERMS).filter(function (k) { return st[k] !== undefined; });
    if (!keys.length) return '<div class="row dim">Nothing needed for the features that are on</div>';
    return keys.map(function (k) {
      return '<div class="row' + (st[k] ? "" : " alert") + '">' + PERMS[k][0] + '<span class="sub">' + PERMS[k][1] + "</span>" +
        (st[k] ? '<span class="val">allowed</span>' : '<span class="val"></span><span class="btn" data-perm="' + k + '">Grant…</span>') + "</div>";
    }).join("");
  }
  // Back from Android's settings, or after a permission prompt: show the new state.
  window.permsChanged = function () { if (current === "settings") renderSettings(); };
  // Once per set of missing permissions: offer to set them up, so nothing silently doesn't work.
  function promptPerms() {
    if (typeof B.permState !== "function") return;
    var miss = missingPerms(), sig = miss.slice().sort().join(",");
    if (!miss.length || settings.permPrompted === sig || $("modal").className.indexOf("on") >= 0) return;
    B.setSetting("permPrompted", sig); settings.permPrompted = sig;
    var list = miss.map(function (k) { return '<div class="row">' + PERMS[k][0] + '<span class="sub">' + PERMS[k][1] + "</span></div>"; }).join("");
    openModal("Android permissions needed", "Some features you have on need a permission. Grant them in Settings › Permissions.", list, "Set up", function () {
      go("settings");
      setTimeout(function () { var h = $("perm-head"); if (h) h.scrollIntoView(); }, 50);
    });
  }

  // Expected-peak-boost buttons in the chosen pressure unit: [step shown, the same step in bar]. Stored in bar.
  function boostSteps() {
    var pu = unitOf("pressure"), s = pu === "psi" ? [1, 0.1] : pu === "kPa" ? [10, 1] : [0.1, 0.01], k = pu === "psi" ? 1 / 14.5038 : pu === "kPa" ? 0.01 : 1;
    return [-s[0], -s[1], s[1], s[0]].map(function (d) { return [d, +(d * k).toFixed(5)]; });
  }

  function renderSettings() {
    settings = JSON.parse(B.settings());
    var s = snap.s, h = "";
    function radio(key, val, label, sub) {
      return '<div class="row" data-set="' + key + '" data-val="' + val + '"><span class="radio' + (settings[key] === val ? " on" : "") + '"></span>' + label + (sub ? '<span class="sub">' + sub + "</span>" : "") + "</div>";
    }
    // One row per quantity, its choices as buttons; the chosen one is highlighted.
    function unitRow(key, label, opts) {
      var cur = settings[key] || opts[0][0];
      return '<div class="row">' + label + '<span class="val"></span>' + opts.map(function (o) {
        return '<span class="btn' + (o[0] === cur ? " on" : "") + '" data-unit="' + key + '" data-uval="' + o[0] + '">' + o[1] + "</span>";
      }).join("") + "</div>";
    }
    function toggle(key, label, sub) {
      var on = settings[key] === "1";
      return '<div class="row" data-set="' + key + '" data-val="' + (on ? "0" : "1") + '"><span class="box' + (on ? " on" : "") + '"></span>' + label + (sub ? '<span class="sub">' + sub + "</span>" : "") + "</div>";
    }
    h += '<div class="row head">Display theme</div>' + radio("theme", "s1", "S1 cluster", "silver") + radio("theme", "quattro", "A1 quattro", "red / black") + radio("theme", "mmi", "MMI black");
    h += '<div class="row head">Screen size</div>' + radio("uiScale", "100", "Auto fit", "largest that fits this screen") +
      radio("uiScale", "90", "90 %") + radio("uiScale", "80", "80 %", "more data on bigger screens") + radio("uiScale", "70", "70 %");
    h += '<div class="row head">Dials</div>' + toggle("dtcIndicator", "Fault code indicator", "amber engine light in the header") +
      '<div class="row">Expected peak boost<span class="val">' + fmt("bar", parseFloat(settings.boostTarget) || 1.8, 2) + '</span>' +
      boostSteps().map(function (d) { return '<span class="btn" data-step="' + d[1] + '">' + (d[0] < 0 ? "−" : "+") + Math.abs(d[0]) + "</span>"; }).join("") + "</div>";
    var gears = [];
    try { gears = JSON.parse(B.clutchInfo()).gears; } catch (e) {}
    h += unitRow("needleFeel", "Needle feel", [["smooth", "Smooth"], ["sport", "Sport"]]);
    h += DIAL_SLOTS.map(function (d) { return '<div class="row">' + d[1] + '<span class="val">' + DIAL_METRICS[dialKey(d[0])].name + '</span><span class="btn" data-dslot="' + d[0] + '">Choose…</span></div>'; }).join("") +
      CENTRE_SLOTS.map(function (d, i) { return '<div class="row">' + d[1] + '<span class="val">' + CENTRE[centreKey(i)][1] + '</span><span class="btn" data-cslot="' + i + '">Choose…</span></div>'; }).join("") +
      '<div class="row" id="dials-reset">Dials and centre lines back to default<span class="val"></span><span class="btn">Reset</span></div>';
    h += '<div class="row head">Units</div>' +
      unitRow("unitPressure", "Pressure", [["bar", "bar"], ["psi", "psi"], ["kPa", "kPa"]]) +
      unitRow("unitTemp", "Temperature", [["C", "°C"], ["F", "°F"]]) +
      unitRow("unitSpeed", "Speed", [["kmh", "km/h"], ["mph", "mph"]]) +
      unitRow("unitTorque", "Torque", [["Nm", "Nm"], ["lbft", "lb-ft"]]) +
      unitRow("unitPower", "Power", [["PS", "PS"], ["hp", "hp"], ["kW", "kW"]]);
    h += '<div class="row head">Clutch estimate (manual gearbox, no real sensor)</div>' +
      '<div class="row">Learned gears<span class="val">' + (gears.length ? gears.join(" ") : "none yet, cruise in each gear") + '</span><span class="btn" id="clutch-reset">Reset</span></div>';
    h += '<div class="row head">OpenHaldex (Haldex data and modes)</div>' + toggle("ohEnabled", "Use OpenHaldex", "over its Wi-Fi") +
      '<div class="row">Address<span class="sub">auto finds it, also on this unit’s hotspot</span><input id="oh-host" value="' + esc(settings.ohHost) + '" placeholder="auto"></div>' +
      '<div class="row dim">Using<span class="val" id="st-ohat">' + esc(s.ohAt || "--") + "</span></div>" +
      '<div class="row dim">Status<span class="val" id="st-oh">' + (s.oh || "--") + "</span></div>";
    h += '<div class="row head">Engine ECU (Simos18 live data, same set as SimosTools)</div>' +
      radio("ecuAdapter", "none", "None") + radio("ecuAdapter", "bridge", "SimosTools dongle", "BLE ISO-TP bridge") +
      radio("ecuAdapter", "elm", "ELM327 dongle", "Bluetooth Classic only, slower: core dials first. No flashing") +
      '<div class="row">Dongle<span class="val">' + (settings.ecuMac || (settings.ecuAdapter === "bridge" ? "find by name " + settings.bridgeName : "not set")) + '</span><span class="btn" id="pick-dev">Choose…</span><span class="btn" id="clear-dev">Clear</span></div>' +
      (settings.ecuAdapter === "bridge" ? '<div class="row">Dongle password<span class="sub">only if you set one in SimosTools</span><input id="br-pass" value="' + esc(settings.bridgePassword || "") + '" placeholder="default"></div>' : "") +
      '<div class="row dim">Status<span class="val" id="st-ecu">' + (s.ecu || "--") + "</span></div>" +
      '<div class="row dim">Data<span class="val" id="st-data">' + (dataNote() || "--") + "</span></div>";
    h += '<div class="row head">Data logger</div>' +
      radio("autoLog", "off", "Record by hand only") +
      radio("autoLog", "either", "Auto-record: cruise held or pedal over 70 %", "like SimosTools, 2 s before and after") +
      radio("autoLog", "pedal", "Auto-record: pedal over 70 %") +
      radio("autoLog", "cruise", "Auto-record: cruise held");
    if (canSwitch()) {
      h += '<div class="row head">CarPlay switch</div>' +
        '<div class="row">CarPlay app<span class="val">' + esc(settings.carplayName || "not set") + '</span><span class="btn" id="pick-cp">Choose…</span></div>' +
        toggle("returnBubble", "Floating S1 button", "over CarPlay and other apps: tap to come back, drag to move");
    }
    if (typeof B.connLog === "function") h += '<div class="row head">Connections</div><div class="row" id="conn-log">Connection log<span class="val">OpenHaldex and ECU, with times</span><span class="chev">›</span></div>';
    h += '<div class="row head">Controls</div>' +
      '<div class="row" id="key-tester">Key tester<span class="val">press each MMI, wheel and remote button</span><span class="chev">›</span></div>' +
      (settings.mediaKeysFlip !== undefined ? toggle("mediaKeysFlip", "Wheel next / previous flips S1 Dash screens", "only while S1 Dash is in front; off = they skip tracks") : "");
    if (typeof B.permState === "function") h += '<div class="row head" id="perm-head">Permissions</div>' + permRows();
    h += '<div class="row head">Other</div>' +
      (settings.stayConnected !== undefined ? toggle("stayConnected", "Stay connected in the background", "OpenHaldex and the dongle keep going behind CarPlay; a notification shows it. To use SimosTools, open it from S1 Dash") : "") +
      toggle("demo", "Demo mode", "made-up readings, no car needed") +
      '<div class="row" id="set-simos">Open SimosTools<span class="val">frees the dongle</span><span class="chev">↗</span></div>';
    $("set-list").innerHTML = h;
    var rows = document.querySelectorAll("#set-list [data-set]");
    for (var i = 0; i < rows.length; i++) rows[i].onclick = function () {
      var k = this.getAttribute("data-set"), val = this.getAttribute("data-val");
      B.setSetting(k, val); settings[k] = val;
      if (k === "theme" || k === "boostTarget") buildDials();
      if (k === "uiScale") layout();
      renderSettings();
    };
    if ($("br-pass")) $("br-pass").onchange = function () { B.setSetting("bridgePassword", this.value.trim()); };
    if ($("pick-cp")) $("pick-cp").onclick = pickApp;
    if ($("conn-log")) $("conn-log").onclick = showConnLog;
    $("key-tester").onclick = openKeyTester;
    var dsl = document.querySelectorAll("#set-list [data-dslot]");
    for (var di = 0; di < dsl.length; di++) dsl[di].onclick = function (e) { e.stopPropagation(); pickDial(this.getAttribute("data-dslot")); };
    var csl = document.querySelectorAll("#set-list [data-cslot]");
    for (var cj = 0; cj < csl.length; cj++) csl[cj].onclick = function (e) { e.stopPropagation(); pickCentre(+this.getAttribute("data-cslot")); };
    $("dials-reset").onclick = function (e) {
      e.stopPropagation();
      DIAL_SLOTS.forEach(function (d) { B.setSetting(d[0], d[2]); settings[d[0]] = d[2]; });
      CENTRE_SLOTS.forEach(function (d) { B.setSetting(d[0], d[2]); settings[d[0]] = d[2]; });
      buildDials(); renderSettings();
    };
    var pb = document.querySelectorAll("#set-list [data-perm]");
    for (var pi = 0; pi < pb.length; pi++) pb[pi].onclick = function (e) { e.stopPropagation(); B.askPerm(this.getAttribute("data-perm")); };
    $("oh-host").onchange = function () { B.setSetting("ohHost", this.value.trim() || "auto"); };
    function stepBoost(d) {
      var x = Math.max(0.5, Math.min(1.9, Math.round(((parseFloat(settings.boostTarget) || 1.8) + d) * 1000) / 1000));
      B.setSetting("boostTarget", x.toFixed(3)); settings.boostTarget = x.toFixed(3); buildDials(); renderSettings();
    }
    var ub = document.querySelectorAll("#set-list [data-unit]");
    for (var ui = 0; ui < ub.length; ui++) ub[ui].onclick = function (e) {
      e.stopPropagation();
      var k = this.getAttribute("data-unit"), val = this.getAttribute("data-uval");
      B.setSetting(k, val); settings[k] = val; buildDials(); renderSettings();
    };
    var steps = document.querySelectorAll("#set-list [data-step]");
    for (i = 0; i < steps.length; i++) steps[i].onclick = function (e) { e.stopPropagation(); stepBoost(parseFloat(this.getAttribute("data-step"))); };
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

  // S1 Dash <-> CarPlay switch (#1060): Android head unit only; the iPhone build has no app switching.
  function canSwitch() { return typeof B.openApp === "function" && typeof B.launchableApps === "function"; }
  function openCarPlay() {
    if (!settings.carplayPkg) { pickApp(); return; }
    if (!B.openApp(settings.carplayPkg)) openModal("App not found", (settings.carplayName || settings.carplayPkg) + " isn't installed any more. Choose the CarPlay app again in Settings.", "", null, null);
  }
  // A built-in (system) CarPlay app may have no launcher icon and not show in the app switcher,
  // so the picker can list every app that has a screen to open.
  var pickAll = false;
  function pickApp() {
    var apps = [];
    try { apps = JSON.parse(B.launchableApps(pickAll)); } catch (e) {}
    var h = '<div class="row" id="pick-all"><span class="tick' + (pickAll ? " on" : "") + '"></span>Show system apps<span class="sub">built-in, no icon</span></div>';
    h += apps.length ? apps.map(function (a) {
      var tags = (a.carplay ? "looks like CarPlay" : "") + (a.system ? (a.carplay ? " · " : "") + "system" : "");
      return '<div class="row" data-pkg="' + esc(a.pkg) + '" data-name="' + esc(a.name) + '">' + esc(a.name) + (tags ? '<span class="sub">' + tags + "</span>" : "") + '<span class="val">' + esc(a.pkg) + "</span></div>";
    }).join("") : '<div class="row dim">No apps found.</div>';
    openModal("Choose the CarPlay app", "The app that shows CarPlay on this head unit", h, null, null);
    $("pick-all").onclick = function () { pickAll = !pickAll; pickApp(); };
    var rows = document.querySelectorAll("#m-list [data-pkg]");
    for (var i = 0; i < rows.length; i++) rows[i].onclick = function () {
      B.setSetting("carplayPkg", this.getAttribute("data-pkg")); B.setSetting("carplayName", this.getAttribute("data-name"));
      settings = JSON.parse(B.settings()); closeModal(); if (current === "settings") renderSettings();
    };
  }

  // Every OpenHaldex / ECU status change with its time, newest first (#1061). Also in logs/connections.log.
  function showConnLog() {
    var lines = (B.connLog() || "").split("\n").filter(function (l) { return l; }).reverse();
    var h = lines.length ? lines.map(function (l) { return '<div class="row" style="font-size:17px">' + esc(l) + "</div>"; }).join("")
      : '<div class="row dim">Nothing yet</div>';
    openModal("Connection log", "Newest first. The full log is connections.log in the logs folder.", h, null, null);
  }

  function openSimos() {
    if (!B.openSimosTools()) openModal("SimosTools not installed", "Install SimosTools on this head unit to open it from here.", "", null, null);
  }
  $("open-simos").onclick = openSimos;
  $("m-carplay").onclick = openCarPlay;
  // Back from Android's "draw over other apps" screen: show the new permission state.
  document.addEventListener("visibilitychange", function () { if (!document.hidden && current === "settings") renderSettings(); });

  // Tap the boost dial to reset the peak-hold.
  $("dialL").onclick = function () { B.resetPeak(); };

  var ticks = 0;
  function render() {
    renderScreens();
    // Screens redraw their rows' classes; keep the key highlight on (or drop it if its element was replaced).
    if (kfocus) { if (!document.body.contains(kfocus)) kfocus = null; else if (!kfocus.classList.contains("kfocus")) kfocus.classList.add("kfocus"); }
  }

  function renderScreens() {
    ticks++;
    renderCommon();
    if (current === "perf") renderPerf();
    else if (current === "awd") renderAwd();
    else if (current === "sport") renderSport();
    else if (current === "engine") { updateTiles("eng-tiles"); $("eng-status").textContent = snap.s.ecuRate ? "ECU " + snap.s.ecuRate + " updates/s" : "ECU: " + (snap.s.ecu || "--"); }
    else if (current === "menu") {
      $("m-mode").textContent = snap.v.hxMode !== undefined ? MODES[snap.v.hxMode] : "";
      var t = dtcTotal(); $("m-dtc").textContent = t === null ? "" : t + " codes";
      $("m-log").textContent = snap.logging ? "recording" : "";
      $("m-tune").textContent = snap.s.ecu === "paused for flash" ? "flashing" : "";
      $("m-carplay").style.display = canSwitch() ? "" : "none";
      $("m-cpname").textContent = settings.carplayName || "choose the app";
    } else if (current === "faults") {
      if (ticks % 10 === 0) renderFaults();
    } else if (current === "tune") {
      if (ticks % 5 === 0 || !tuneShown) renderTune();
    } else if (current === "settings") {
      // Only the status lines: rebuilding the list would drop focus from the address box.
      if ($("st-oh")) $("st-oh").textContent = snap.s.oh || "--";
      if ($("st-ohat")) $("st-ohat").textContent = snap.s.ohAt || "--";
      if ($("st-ecu")) $("st-ecu").textContent = snap.s.ecu || "--";
      if ($("st-data")) $("st-data").textContent = dataNote() || "--";
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
  (function () {
    var h = document.querySelectorAll(".hdr .status");
    for (var i = 0; i < h.length; i++) {
      var p = document.createElement("span"); p.className = "simos"; h[i].insertBefore(p, h[i].firstChild);
      var o = document.createElement("span"); o.className = "ovr"; h[i].insertBefore(o, h[i].firstChild);
      var c = document.createElement("span"); c.className = "cp"; c.onclick = openCarPlay; h[i].insertBefore(c, h[i].firstChild);
    }
  })();
  buildDials();
  buildSport();
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
  // ---------- keys: remote, MMI knob, steering wheel (#1078) ----------
  // The car's controls reach the Tbox through CarPlay as ordinary key events, like a remote's.
  // Arrows / Tab / the knob move a highlight, OK / Enter presses, Back / Escape goes back.
  // Touch keeps working; the highlight shows only after a key press.
  var kfocus = null, keyLog = [], keyTester = false, nativeKeys = false;
  var MAIN_SCREENS = ["perf", "awd", "engine", "sport"];
  var FOCUSABLE = '.row[data-go], .row[data-set], .row[data-mode], .row[data-pkg], .row[data-mac], .row[id], .btn, .sk, .cp, [data-go], #m-ok, #m-cancel, #dialL, .tick';
  function modalOpen() { return $("modal").className.indexOf("on") >= 0; }
  function focusables() {
    var root = modalOpen() ? $("modal") : $(current);
    if (!root) return [];
    var els = Array.prototype.slice.call(root.querySelectorAll(FOCUSABLE)).filter(function (e) {
      var r = e.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && e.style.display !== "none" && !e.classList.contains("dim");
    });
    // The screen's own items first, the top bar's chips (faults, CarPlay) last: the first key lands on content.
    function inHdr(e) { return !!(e.closest && e.closest(".hdr")); }
    return els.filter(function (e) { return !inHdr(e); }).concat(els.filter(inHdr));
  }
  function setFocus(e) {
    if (kfocus) kfocus.classList.remove("kfocus");
    kfocus = e;
    if (e) { e.classList.add("kfocus"); try { e.scrollIntoView({ block: "nearest" }); } catch (x) {} }
  }
  function clearFocus() { setFocus(null); }
  /** Nearest focusable in a direction (dx, dy = -1/0/1), by distance along it plus twice the sideways offset. */
  function moveFocus(dx, dy) {
    var list = focusables();
    if (!list.length) return false;
    if (!kfocus || list.indexOf(kfocus) < 0) { setFocus(list[0]); return true; }
    var a = kfocus.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2, best = null, bs = 1e9;
    list.forEach(function (e) {
      if (e === kfocus) return;
      var r = e.getBoundingClientRect(), ddx = r.left + r.width / 2 - ax, ddy = r.top + r.height / 2 - ay;
      var along = dx ? ddx * dx : ddy * dy;
      if (along <= 2) return;
      var score = along + 2 * (dx ? Math.abs(ddy) : Math.abs(ddx));
      if (score < bs) { bs = score; best = e; }
    });
    if (best) setFocus(best);
    return !!best;
  }
  /** Tab / knob turns: the next or previous one in screen order. */
  function stepFocus(d) {
    var list = focusables();
    if (!list.length) return;
    var i = kfocus ? list.indexOf(kfocus) : -1;
    setFocus(list[(i + d + list.length) % list.length]);
  }
  /** Left / right on a main screen with nothing further that way, or wheel next / previous: flip screens. */
  window.flipScreen = function (d) {
    var i = MAIN_SCREENS.indexOf(current);
    clearFocus();
    go(i < 0 ? "perf" : MAIN_SCREENS[(i + d + MAIN_SCREENS.length) % MAIN_SCREENS.length], true);
  };
  function keyName(e) { return e.key + (e.code && e.code !== e.key ? " (" + e.code + ")" : ""); }
  function logKey(name, from) {
    keyLog.unshift(new Date().toTimeString().slice(0, 8) + "  " + name + (from ? "  ·  " + from : ""));
    if (keyLog.length > 12) keyLog.pop();
    if (keyTester) renderKeyTester();
  }
  /** Every key Android gets, with the device it came from, for the key tester. */
  window.onNativeKey = function (code, name, device) { nativeKeys = true; logKey(name + " " + code, device); };
  // Buttons that don't arrive as key events: the media session, head-unit broadcasts, the system log.
  window.onProbe = function (what, from) { logKey(what, from); };
  var probeOn = "";
  window.keyProbeResume = function () { if (keyTester && B.keyProbe) { probeOn = B.keyProbe(true); renderKeyTester(); } };
  function renderKeyTester() {
    $("m-list").innerHTML = (probeOn ? '<div class="row dim" style="font-size:15px">Listening for: ' + esc(probeOn) + "</div>" : "") +
      (keyLog.length ? keyLog.map(function (l) { return '<div class="row" style="font-size:17px">' + esc(l) + "</div>"; }).join("")
      : '<div class="row dim">Press a button…</div>');
    $("m-list").style.display = "block";
  }
  function openKeyTester() {
    keyLog = []; keyTester = true;
    probeOn = typeof B.keyProbe === "function" ? B.keyProbe(true) : "";
    openModal("Key tester", "Press each MMI, steering-wheel and remote button once. Newest first; also saved to logs/keys.log. While this is open, S1 Dash takes the media buttons (music keeps playing). Back or Cancel closes." +
      (probeOn.indexOf("system log: off") >= 0 ? " To see the head unit’s own key messages too, run once from a computer: adb shell pm grant com.louij2.s1dash android.permission.READ_LOGS" : ""), "", null, null);
    renderKeyTester();
  }
  document.addEventListener("keydown", function (e) {
    if (!nativeKeys) logKey(keyName(e), "");
    if (keyTester) { if (e.key === "Escape" || e.key === "Backspace" || e.key === "GoBack") { closeModal(); e.preventDefault(); } return; }
    var t = e.target && e.target.tagName;
    if (t === "INPUT" || t === "TEXTAREA") { if (e.key === "Escape") e.target.blur(); return; }
    var k = e.key, handled = true, first = !kfocus;
    if (k === "ArrowDown") { if (!first) moveFocus(0, 1); else moveFocus(0, 0); }
    else if (k === "ArrowUp") { if (!first) moveFocus(0, -1); else moveFocus(0, 0); }
    else if (k === "ArrowRight" || k === "ArrowLeft") {
      var d = k === "ArrowRight" ? 1 : -1;
      if (first) moveFocus(0, 0);
      else if (!moveFocus(d, 0) && !modalOpen() && MAIN_SCREENS.indexOf(current) >= 0) window.flipScreen(d);
    }
    else if (k === "Tab") stepFocus(e.shiftKey ? -1 : 1);
    else if (k === "Enter" || k === " " || k === "Select") { if (kfocus) kfocus.click(); else moveFocus(0, 0); }
    else if (k === "Escape" || k === "Backspace" || k === "GoBack" || k === "BrowserBack") { clearFocus(); window.goBack(); }
    else handled = false;
    if (handled) e.preventDefault();
  }, true);
  // A touch or click puts the highlight away; it comes back with the next key.
  document.addEventListener("pointerdown", function (e) { if (e.pointerType !== "mouse") clearFocus(); }, true);

  go(first, true);
  tick();
  setTimeout(promptPerms, 2500);
  setInterval(tick, 100);
  requestAnimationFrame(animate);
})();
