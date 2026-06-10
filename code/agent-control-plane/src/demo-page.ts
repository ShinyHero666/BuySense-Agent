export const DEMO_PAGE = String.raw`<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="theme-color" content="#10182d">
  <title>墨圆 · 搜广推智能决策台</title>
  <style>
    :root {
      color-scheme: light;
      font-family: Inter, ui-sans-serif, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
      --ink:#172033; --ink-soft:#4e596d; --muted:#7e899d; --canvas:#f2f4f8;
      --paper:#ffffff; --line:#e4e8ef; --line-strong:#d6dce6; --navy:#10182d;
      --violet:#665cf6; --violet-soft:#eeecff; --teal:#13a898; --teal-soft:#e2f7f3;
      --blue:#3d7cf5; --blue-soft:#e8f0ff; --amber:#e99a2f; --amber-soft:#fff3df;
      --red:#df5b64; --red-soft:#ffeaeb; --green:#238a67;
      --shadow:0 18px 55px rgba(37,48,74,.09); --radius:22px;
    }
    * { box-sizing:border-box; }
    html { scroll-behavior:smooth; }
    body { margin:0; min-height:100vh; color:var(--ink); background:
      radial-gradient(circle at 8% 9%,rgba(102,92,246,.07),transparent 24rem),
      radial-gradient(circle at 92% 28%,rgba(19,168,152,.06),transparent 28rem),var(--canvas); }
    button,textarea { font:inherit; }
    button { -webkit-tap-highlight-color:transparent; }
    .app-shell { max-width:1580px; margin:auto; padding:0 30px 64px; }
    .topbar { height:78px; display:flex; align-items:center; justify-content:space-between; gap:24px; }
    .brand { display:flex; align-items:center; gap:12px; min-width:max-content; }
    .brand-mark { width:37px; height:37px; display:grid; place-items:center; border-radius:12px; color:#fff; font-size:13px; font-weight:900; letter-spacing:-.06em; background:linear-gradient(145deg,var(--violet),#4542be); box-shadow:0 8px 20px rgba(102,92,246,.28); }
    .brand-copy strong { display:block; font-size:14px; letter-spacing:.02em; }
    .brand-copy span { display:block; margin-top:2px; color:var(--muted); font-size:10px; letter-spacing:.12em; text-transform:uppercase; }
    .topnav { display:flex; align-items:center; gap:4px; padding:4px; border:1px solid var(--line); border-radius:12px; background:rgba(255,255,255,.64); }
    .nav-item { padding:8px 13px; border-radius:9px; color:var(--muted); font-size:12px; font-weight:700; }
    .nav-item.active { color:var(--ink); background:#fff; box-shadow:0 2px 8px rgba(29,38,60,.07); }
    .runtime-pill { display:flex; align-items:center; gap:10px; padding:9px 12px; border:1px solid var(--line); border-radius:13px; background:rgba(255,255,255,.76); backdrop-filter:blur(10px); }
    .runtime-indicator { position:relative; width:9px; height:9px; flex:0 0 auto; border-radius:50%; color:var(--amber); background:currentColor; }
    .runtime-indicator:after { content:""; position:absolute; inset:-4px; border:1px solid currentColor; border-radius:50%; opacity:.28; }
    .runtime-indicator.up { color:var(--teal); } .runtime-indicator.down { color:var(--red); }
    .runtime-copy b { display:block; font-size:11px; }
    .runtime-copy span { display:block; margin-top:2px; color:var(--muted); font-size:9px; }
    .runtime-latency { padding-left:10px; border-left:1px solid var(--line); color:var(--ink-soft); font-size:10px; }

    .command-center { position:relative; overflow:hidden; padding:34px 36px 86px; border-radius:30px; color:#fff; background:
      radial-gradient(circle at 78% 0%,rgba(108,98,255,.5),transparent 31rem),
      radial-gradient(circle at 6% 100%,rgba(14,173,156,.24),transparent 27rem),var(--navy); box-shadow:0 25px 70px rgba(20,29,53,.2); }
    .command-center:after { content:""; position:absolute; width:380px; height:380px; right:-170px; bottom:-240px; border:1px solid rgba(255,255,255,.09); border-radius:50%; box-shadow:0 0 0 50px rgba(255,255,255,.025),0 0 0 100px rgba(255,255,255,.018); }
    .command-layout { position:relative; z-index:1; display:grid; grid-template-columns:minmax(0,1.3fr) minmax(330px,.7fr); gap:46px; align-items:end; }
    .eyebrow { display:flex; align-items:center; gap:8px; color:#a9a4ff; font-size:10px; font-weight:850; letter-spacing:.17em; text-transform:uppercase; }
    .eyebrow:before { content:""; width:19px; height:2px; border-radius:2px; background:#8880ff; }
    h1 { max-width:760px; margin:13px 0 13px; font-size:clamp(31px,4.4vw,57px); line-height:1.08; letter-spacing:-.045em; }
    .hero-copy { max-width:670px; margin:0; color:#aab3c8; font-size:14px; line-height:1.75; }
    .trust-row { display:flex; flex-wrap:wrap; gap:18px; margin-top:24px; }
    .trust-item { display:flex; align-items:center; gap:7px; color:#cbd3e3; font-size:10px; }
    .trust-icon { width:17px; height:17px; display:grid; place-items:center; border-radius:50%; color:#6fe0cb; background:rgba(65,205,179,.12); font-size:9px; }
    .composer { position:relative; padding:15px; border:1px solid rgba(255,255,255,.12); border-radius:20px; background:rgba(5,11,25,.38); box-shadow:inset 0 1px rgba(255,255,255,.04); backdrop-filter:blur(12px); }
    .composer-label { display:flex; justify-content:space-between; margin-bottom:10px; color:#9da8c0; font-size:10px; }
    .composer-label span:last-child { color:#737f98; }
    textarea { width:100%; min-height:102px; resize:vertical; padding:0; border:0; outline:0; color:#fff; caret-color:#8d86ff; background:transparent; font-size:14px; line-height:1.7; }
    textarea::placeholder { color:#6e7991; }
    .prompt-presets { display:flex; gap:7px; flex-wrap:wrap; margin:10px 0 13px; }
    .preset { padding:6px 9px; border:1px solid rgba(255,255,255,.1); border-radius:999px; color:#adb6ca; background:rgba(255,255,255,.035); font-size:9px; cursor:pointer; }
    .preset:hover { color:#fff; border-color:rgba(152,145,255,.55); }
    .composer-actions { display:flex; align-items:center; justify-content:space-between; gap:10px; padding-top:12px; border-top:1px solid rgba(255,255,255,.08); }
    .session-tag { color:#6f7b94; font-size:9px; }
    .button-row { display:flex; gap:8px; }
    .primary-btn,.confirm-btn { border:0; border-radius:11px; padding:10px 14px; cursor:pointer; font-size:11px; font-weight:850; transition:transform .18s,box-shadow .18s,opacity .18s; }
    .primary-btn { color:#fff; background:linear-gradient(135deg,#746bff,#5a50dc); box-shadow:0 8px 20px rgba(102,92,246,.28); }
    .confirm-btn { color:#073a31; background:#6fe0cb; }
    .primary-btn:hover:not(:disabled),.confirm-btn:hover:not(:disabled) { transform:translateY(-1px); }
    button:focus-visible,textarea:focus-visible { outline:3px solid rgba(112,224,203,.35); outline-offset:2px; }
    button:disabled { opacity:.38; cursor:not-allowed; box-shadow:none; }
    .run-status { position:absolute; z-index:2; left:36px; right:36px; bottom:30px; display:flex; align-items:center; justify-content:space-between; gap:20px; color:#9da8bd; font-size:11px; }
    .status-copy { display:flex; align-items:center; gap:9px; }
    .spinner { width:13px; height:13px; border:2px solid #36415a; border-top-color:#8c85ff; border-radius:50%; animation:spin .75s linear infinite; }
    @keyframes spin { to { transform:rotate(360deg); } }
    .run-progress { width:min(34vw,420px); height:3px; overflow:hidden; border-radius:10px; background:rgba(255,255,255,.08); }
    .run-progress i { display:block; width:0; height:100%; border-radius:inherit; background:linear-gradient(90deg,var(--violet),#68dbc7); transition:width .45s ease; }

    .kpi-ribbon { position:relative; z-index:4; display:grid; grid-template-columns:repeat(5,1fr); margin:-35px 24px 26px; border:1px solid var(--line); border-radius:19px; background:rgba(255,255,255,.96); box-shadow:var(--shadow); backdrop-filter:blur(16px); }
    .kpi { min-width:0; padding:19px 21px; }
    .kpi + .kpi { border-left:1px solid var(--line); }
    .kpi-label { display:flex; align-items:center; gap:7px; color:var(--muted); font-size:10px; font-weight:800; letter-spacing:.08em; text-transform:uppercase; }
    .kpi-label i { width:6px; height:6px; border-radius:50%; background:var(--violet); }
    .kpi-value { display:block; margin-top:8px; font-size:23px; line-height:1; letter-spacing:-.04em; }
    .kpi-delta { display:block; margin-top:6px; overflow:hidden; color:var(--muted); font-size:10px; text-overflow:ellipsis; white-space:nowrap; }
    .kpi.good .kpi-value { color:var(--green); }

    .workspace { display:grid; grid-template-columns:minmax(0,1.55fr) minmax(340px,.62fr); gap:22px; align-items:start; }
    .decision-canvas { overflow:hidden; border:1px solid var(--line); border-radius:var(--radius); background:var(--paper); box-shadow:var(--shadow); }
    .canvas-head { display:flex; justify-content:space-between; gap:16px; align-items:flex-start; padding:26px 28px 20px; }
    .section-kicker { color:var(--violet); font-size:10px; font-weight:850; letter-spacing:.14em; text-transform:uppercase; }
    h2 { margin:6px 0 0; font-size:20px; letter-spacing:-.025em; }
    .verdict-badge { display:flex; align-items:center; gap:7px; padding:7px 10px; border-radius:999px; color:var(--muted); background:#f5f6f8; font-size:10px; font-weight:800; }
    .verdict-badge.approved { color:var(--green); background:var(--teal-soft); }
    .verdict-badge.vetoed { color:var(--red); background:var(--red-soft); }
    .verdict-badge i { width:6px; height:6px; border-radius:50%; background:currentColor; }
    .decision-hero { display:grid; grid-template-columns:minmax(0,1fr) 185px; gap:26px; margin:0 28px; padding:22px 0 25px; border-top:1px solid var(--line); border-bottom:1px solid var(--line); }
    .summary-label { color:var(--muted); font-size:10px; font-weight:800; letter-spacing:.1em; text-transform:uppercase; }
    .summary { margin-top:10px; color:#28334a; font-size:14px; line-height:1.8; white-space:pre-wrap; }
    .plan-chips { display:flex; flex-wrap:wrap; gap:6px; margin-top:15px; }
    .plan-chip { padding:6px 9px; border-radius:8px; color:#5d6680; background:#f3f5f8; font-size:10px; }
    .plan-chip.model { color:#554bde; background:var(--violet-soft); }
    .decision-score { display:flex; flex-direction:column; justify-content:center; padding-left:25px; border-left:1px solid var(--line); }
    .decision-score strong { font-size:42px; line-height:1; letter-spacing:-.06em; }
    .decision-score span { margin-top:7px; color:var(--muted); font-size:9px; line-height:1.45; }
    .score-line { width:100%; height:5px; margin-top:12px; overflow:hidden; border-radius:5px; background:#edf0f4; }
    .score-line i { display:block; width:0; height:100%; border-radius:inherit; background:linear-gradient(90deg,var(--violet),var(--teal)); transition:width .5s ease; }

    .bundle-section { padding:25px 28px 28px; }
    .subhead { display:flex; justify-content:space-between; gap:14px; align-items:center; margin-bottom:16px; }
    .subhead h3 { margin:0; font-size:13px; }
    .subhead span { color:var(--muted); font-size:9px; }
    .bundle-flow { display:flex; align-items:stretch; min-height:190px; }
    .empty-state { flex:1; display:grid; place-items:center; min-height:190px; border:1px dashed var(--line-strong); border-radius:16px; color:var(--muted); background:#fafbfc; text-align:center; }
    .empty-state b { display:block; margin-bottom:7px; color:var(--ink-soft); font-size:12px; }
    .empty-state span { font-size:10px; }
    .flow-plus { flex:0 0 34px; display:grid; place-items:center; color:#a3acba; font-size:19px; font-weight:300; }
    .product { position:relative; min-width:0; flex:1; padding:14px; border:1px solid var(--line); border-radius:17px; background:linear-gradient(180deg,#fff,#fafbfe); transition:transform .2s,box-shadow .2s; }
    .product:hover { transform:translateY(-2px); box-shadow:0 12px 25px rgba(41,52,76,.09); }
    .product-visual { height:72px; display:flex; justify-content:space-between; align-items:flex-start; padding:12px; overflow:hidden; border-radius:12px; background:linear-gradient(135deg,#edefff,#e8f7f5); }
    .product-visual.phone { background:linear-gradient(135deg,#ece9ff,#e4eaff); }
    .product-visual.headphones { background:linear-gradient(135deg,#e2f7f3,#eafaf3); }
    .product-visual.charger,.product-visual.cable { background:linear-gradient(135deg,#fff1dc,#fff8ea); }
    .visual-code { align-self:flex-end; color:rgba(26,37,63,.22); font-size:28px; font-weight:900; letter-spacing:-.08em; }
    .product-index { color:rgba(25,34,53,.45); font-size:9px; font-weight:900; }
    .sponsored { padding:4px 6px; border-radius:999px; color:#8c5b10; background:rgba(255,255,255,.62); font-size:8px; font-weight:800; }
    .product-brand { margin-top:11px; color:var(--muted); font-size:9px; font-weight:800; letter-spacing:.09em; text-transform:uppercase; }
    .product-title { min-height:39px; margin-top:4px; font-size:12px; font-weight:800; line-height:1.55; }
    .product-price { margin-top:8px; font-size:19px; font-weight:900; letter-spacing:-.04em; }
    .product-price small { margin-left:5px; color:var(--muted); font-size:8px; font-weight:600; }
    .evidence-row { display:flex; flex-wrap:wrap; gap:5px; margin-top:10px; }
    .evidence-chip { max-width:100%; overflow:hidden; padding:4px 6px; border-radius:6px; color:#677187; background:#f0f2f6; font-size:9px; text-overflow:ellipsis; white-space:nowrap; }
    .cart-banner { display:flex; justify-content:space-between; gap:16px; align-items:center; margin:0 28px 28px; padding:15px 17px; border:1px solid #bfe5d7; border-radius:14px; background:linear-gradient(90deg,#eaf8f3,#f4fbf8); }
    .cart-banner strong { display:block; color:#1c7459; font-size:11px; }
    .cart-banner span { display:block; margin-top:4px; color:#5f7c71; font-size:9px; }
    .cart-total { color:#1b7057; font-size:18px; font-weight:900; }
    .hidden { display:none !important; }

    .agent-console { position:relative; overflow:hidden; padding:25px; border-radius:var(--radius); color:#eaf0fb; background:linear-gradient(160deg,#17213b,#10172a 72%); box-shadow:0 20px 55px rgba(22,30,54,.18); }
    .agent-console:before { content:""; position:absolute; width:260px; height:260px; right:-120px; top:-120px; border-radius:50%; background:rgba(102,92,246,.14); filter:blur(3px); }
    .console-head { position:relative; display:flex; justify-content:space-between; gap:15px; align-items:flex-start; }
    .console-head .section-kicker { color:#8b84ff; }
    .console-head h2 { color:#fff; }
    .model-stat { color:#7f8ba4; font-size:9px; text-align:right; line-height:1.55; }
    .stage-map { position:relative; margin-top:24px; }
    .stage-map:before { content:""; position:absolute; left:11px; top:17px; bottom:17px; width:1px; background:#303b55; }
    .agent-stage { position:relative; display:grid; grid-template-columns:23px 67px 1fr; gap:10px; align-items:start; padding:0 0 22px; }
    .agent-stage:last-child { padding-bottom:0; }
    .stage-number { position:relative; z-index:1; width:23px; height:23px; display:grid; place-items:center; border:1px solid #3a4660; border-radius:50%; color:#8290aa; background:#161f35; font-size:8px; font-weight:850; }
    .stage-name { padding-top:5px; color:#7e8aa3; font-size:10px; font-weight:800; }
    .stage-roles { display:flex; flex-wrap:wrap; gap:7px; }
    .role { min-width:82px; flex:1 1 82px; padding:8px 9px; border:1px solid #2d3851; border-radius:9px; background:rgba(255,255,255,.025); }
    .role-name { display:flex; align-items:center; gap:6px; color:#acb6c9; font-size:9px; font-weight:750; }
    .role-dot { width:5px; height:5px; border-radius:50%; background:#48546c; }
    .role-state { display:block; margin-top:5px; color:#5f6b83; font-size:8px; white-space:nowrap; }
    .role.active { border-color:#6c63da; background:rgba(102,92,246,.08); }
    .role.active .role-dot { background:#918aff; box-shadow:0 0 9px #7d74ff; animation:pulse 1s infinite; }
    .role.accepted .role-dot { background:#55d0b6; }
    .role.corrected .role-dot { background:#f0b557; }
    .role.fallback .role-dot { background:#ed737b; }
    .role.accepted .role-state { color:#55bda7; } .role.corrected .role-state { color:#dba64f; } .role.fallback .role-state { color:#dc6c75; }
    @keyframes pulse { 50% { opacity:.35; } }
    .console-footer { display:grid; grid-template-columns:repeat(3,1fr); margin-top:24px; border-top:1px solid #2b354d; }
    .console-metric { padding:16px 10px 0; }
    .console-metric + .console-metric { border-left:1px solid #2b354d; padding-left:15px; }
    .console-metric span { display:block; color:#69758e; font-size:8px; text-transform:uppercase; }
    .console-metric strong { display:block; margin-top:5px; color:#eef2fb; font-size:14px; }

    .story-grid { display:grid; grid-template-columns:1fr 1fr; gap:22px; margin-top:22px; }
    .viz-card { border:1px solid var(--line); border-radius:var(--radius); background:var(--paper); box-shadow:var(--shadow); }
    .viz-head { display:flex; align-items:center; justify-content:space-between; gap:15px; padding:23px 25px 17px; }
    .viz-head h3 { margin:0; font-size:14px; }
    .viz-head span { color:var(--muted); font-size:10px; }
    .budget-body { display:grid; grid-template-columns:145px 1fr; gap:22px; align-items:center; padding:4px 25px 25px; }
    .budget-ring { --score:0; position:relative; width:132px; aspect-ratio:1; display:grid; place-items:center; border-radius:50%; background:conic-gradient(var(--violet) calc(var(--score) * 1%),#edf0f5 0); }
    .budget-ring:before { content:""; position:absolute; inset:13px; border-radius:50%; background:#fff; }
    .ring-copy { position:relative; text-align:center; }
    .ring-copy strong { display:block; font-size:24px; letter-spacing:-.05em; }
    .ring-copy span { display:block; margin-top:4px; color:var(--muted); font-size:8px; }
    .allocation-list { display:grid; gap:13px; }
    .allocation-row { display:grid; grid-template-columns:54px 1fr 52px; gap:9px; align-items:center; font-size:10px; }
    .allocation-row span:first-child { color:var(--ink-soft); }
    .allocation-row strong { text-align:right; font-size:9px; }
    .bar-track { height:7px; overflow:hidden; border-radius:9px; background:#edf0f5; }
    .bar-track i { display:block; width:0; height:100%; border-radius:inherit; background:var(--violet); transition:width .6s ease; }
    .allocation-row:nth-child(2) .bar-track i { background:var(--teal); }
    .allocation-row:nth-child(3) .bar-track i { background:var(--amber); }
    .allocation-row:nth-child(4) .bar-track i { background:var(--blue); }
    .funnel { display:grid; gap:9px; padding:4px 25px 25px; }
    .funnel-row { display:grid; grid-template-columns:78px 1fr 42px; align-items:center; gap:10px; }
    .funnel-label { color:var(--ink-soft); font-size:9px; }
    .funnel-track { height:24px; display:flex; align-items:center; }
    .funnel-bar { height:100%; min-width:8%; display:flex; align-items:center; padding-left:10px; border-radius:6px; color:#fff; background:linear-gradient(90deg,#6e65ef,#8179f5); font-size:8px; transition:width .55s ease; }
    .funnel-row:nth-child(2) .funnel-bar { background:linear-gradient(90deg,#6373e8,#5c91ee); }
    .funnel-row:nth-child(3) .funnel-bar { background:linear-gradient(90deg,#3e91cf,#35a7c2); }
    .funnel-row:nth-child(4) .funnel-bar { background:linear-gradient(90deg,#27a5a0,#29b58e); }
    .funnel-row:nth-child(5) .funnel-bar { background:linear-gradient(90deg,#2e9a78,#42b178); }
    .funnel-value { color:var(--muted); font-size:9px; text-align:right; }

    .analytics { margin-top:22px; overflow:hidden; border:1px solid var(--line); border-radius:var(--radius); background:var(--paper); box-shadow:var(--shadow); }
    .analytics-head { display:flex; align-items:flex-end; justify-content:space-between; gap:18px; padding:25px 28px 20px; border-bottom:1px solid var(--line); }
    .analytics-head p { margin:6px 0 0; color:var(--muted); font-size:9px; }
    .metric-time { color:var(--muted); font-size:9px; }
    .analytics-body { display:grid; grid-template-columns:minmax(0,1.45fr) minmax(270px,.55fr); }
    .metric-chart { padding:24px 28px 27px; }
    .chart-legend { display:flex; gap:16px; margin-bottom:20px; color:var(--muted); font-size:8px; }
    .chart-legend span { display:flex; align-items:center; gap:5px; }
    .chart-legend i { width:7px; height:7px; border-radius:2px; background:var(--violet); }
    .chart-legend span:nth-child(2) i { background:var(--teal); }
    .metric-bars { display:grid; gap:13px; }
    .metric-row { display:grid; grid-template-columns:105px 1fr 38px; gap:13px; align-items:center; }
    .metric-row label { color:var(--ink-soft); font-size:10px; }
    .metric-track { position:relative; height:8px; border-radius:10px; background:repeating-linear-gradient(90deg,#edf0f5 0,#edf0f5 calc(25% - 1px),#fff calc(25% - 1px),#fff 25%); }
    .metric-track i { display:block; width:0; height:100%; border-radius:inherit; background:linear-gradient(90deg,var(--violet),#827af6); transition:width .6s ease; }
    .metric-row:nth-child(even) .metric-track i { background:linear-gradient(90deg,var(--teal),#45c4b3); }
    .metric-row strong { color:var(--ink-soft); font-size:9px; text-align:right; }
    .reliability { padding:24px 27px; border-left:1px solid var(--line); background:#fafbfc; }
    .reliability h3 { margin:0; font-size:12px; }
    .gauge-row { display:flex; justify-content:space-around; gap:18px; margin-top:22px; }
    .gauge-unit { text-align:center; }
    .gauge { --score:0; position:relative; width:94px; aspect-ratio:1; display:grid; place-items:center; border-radius:50%; background:conic-gradient(var(--teal) calc(var(--score) * 1%),#e7ebf1 0); }
    .gauge.violet { background:conic-gradient(var(--violet) calc(var(--score) * 1%),#e7ebf1 0); }
    .gauge:before { content:""; position:absolute; inset:10px; border-radius:50%; background:#fafbfc; }
    .gauge strong { position:relative; font-size:18px; }
    .gauge-unit span { display:block; margin-top:8px; color:var(--muted); font-size:8px; }
    .reliability-note { margin-top:22px; padding-top:18px; border-top:1px solid var(--line); color:var(--muted); font-size:9px; line-height:1.7; }

    .audit { margin-top:22px; border:1px solid var(--line); border-radius:var(--radius); background:var(--paper); box-shadow:var(--shadow); }
    .audit-head { display:flex; align-items:center; justify-content:space-between; gap:18px; padding:22px 26px; border-bottom:1px solid var(--line); }
    .audit-head h3 { margin:0; font-size:14px; }
    .audit-tools { display:flex; gap:4px; padding:3px; border-radius:9px; background:#f1f3f6; }
    .filter-btn { padding:6px 9px; border:0; border-radius:7px; color:var(--muted); background:transparent; cursor:pointer; font-size:8px; font-weight:800; }
    .filter-btn.active { color:var(--ink); background:#fff; box-shadow:0 2px 7px rgba(30,40,65,.08); }
    .timeline { max-height:340px; overflow:auto; padding:6px 26px 15px; }
    .audit-empty { padding:34px 0; color:var(--muted); font-size:10px; text-align:center; }
    .event { display:grid; grid-template-columns:30px 105px 1fr auto; gap:12px; align-items:center; min-height:47px; border-bottom:1px solid #edf0f4; }
    .event-seq { width:21px; height:21px; display:grid; place-items:center; border-radius:7px; color:#68748a; background:#eff2f6; font-size:7px; font-weight:850; }
    .event-role { color:var(--ink-soft); font-size:9px; font-weight:800; }
    .event-main { min-width:0; }
    .event-title { font-size:10px; font-weight:750; }
    .event-detail { margin-top:3px; overflow:hidden; color:var(--muted); font-size:9px; text-overflow:ellipsis; white-space:nowrap; }
    .event-kind { padding:4px 6px; border-radius:6px; color:#5b51da; background:var(--violet-soft); font-size:7px; }
    .event[data-kind="guard"] .event-kind { color:#287c67; background:var(--teal-soft); }

    .system-note { display:grid; grid-template-columns:1fr auto; gap:28px; align-items:center; margin-top:22px; padding:20px 25px; border:1px solid var(--line); border-radius:17px; background:rgba(255,255,255,.62); }
    .boundary-copy strong { display:block; font-size:10px; }
    .boundary-copy span { display:block; margin-top:5px; color:var(--muted); font-size:9px; line-height:1.65; }
    .system-flow { display:flex; align-items:center; gap:7px; color:#a0a9b8; font-size:9px; white-space:nowrap; }
    .system-flow b { padding:7px 9px; border:1px solid var(--line); border-radius:8px; color:#5c6679; background:#fff; font-size:8px; }

    @media (max-width:1150px) {
      .command-layout { grid-template-columns:1fr 380px; gap:28px; }
      .workspace { grid-template-columns:1fr; }
      .stage-map { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; }
      .stage-map:before { display:none; }
      .agent-stage { display:block; padding:0; }
      .stage-number { margin-bottom:8px; }
      .stage-name { margin-bottom:8px; }
      .stage-roles { display:grid; }
    }
    @media (max-width:850px) {
      .app-shell { padding:0 16px 40px; }
      .topnav { display:none; }
      .command-center { padding:28px 24px 82px; }
      .command-layout { grid-template-columns:1fr; }
      .kpi-ribbon { grid-template-columns:repeat(2,1fr); margin-left:12px; margin-right:12px; }
      .kpi:last-child { grid-column:span 2; }
      .kpi:nth-child(3),.kpi:nth-child(5) { border-left:0; }
      .kpi:nth-child(n+3) { border-top:1px solid var(--line); }
      .story-grid { grid-template-columns:1fr; }
      .analytics-body { grid-template-columns:1fr; }
      .reliability { border-left:0; border-top:1px solid var(--line); }
      .system-note { grid-template-columns:1fr; }
      .system-flow { overflow:auto; padding-bottom:4px; }
    }
    @media (max-width:620px) {
      .topbar { height:68px; }
      .runtime-copy span,.runtime-latency,.brand-copy span { display:none; }
      .command-center { padding:25px 18px 78px; border-radius:23px; }
      h1 { font-size:34px; }
      .trust-row { gap:10px; }
      .composer-actions { align-items:flex-end; }
      .session-tag { display:none; }
      .button-row { width:100%; }
      .primary-btn,.confirm-btn { flex:1; }
      .run-status { left:19px; right:19px; }
      .run-progress { width:90px; }
      .kpi-ribbon { grid-template-columns:1fr 1fr; margin-top:-28px; }
      .kpi { padding:15px; }
      .kpi-value { font-size:20px; }
      .decision-hero { grid-template-columns:1fr; }
      .decision-score { padding:17px 0 0; border-left:0; border-top:1px solid var(--line); }
      .bundle-flow { flex-direction:column; }
      .flow-plus { min-height:28px; transform:rotate(90deg); }
      .budget-body { grid-template-columns:1fr; justify-items:center; }
      .allocation-list { width:100%; }
      .stage-map { grid-template-columns:1fr 1fr; }
      .event { grid-template-columns:28px 82px 1fr; }
      .event-kind { display:none; }
      .event-detail { max-width:150px; }
      .metric-row { grid-template-columns:82px 1fr 34px; }
      .system-flow b { padding:6px; }
    }
  </style>
</head>
<body>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <div class="brand-mark">MY</div>
        <div class="brand-copy"><strong>墨圆智能商业</strong><span>Commerce intelligence</span></div>
      </div>
      <nav class="topnav" aria-label="主导航">
        <span class="nav-item active">决策工作台</span>
        <span class="nav-item">Agent 观测</span>
        <span class="nav-item">指标中心</span>
      </nav>
      <div class="runtime-pill" aria-live="polite">
        <i id="runtimeDot" class="runtime-indicator"></i>
        <div class="runtime-copy"><b id="runtimeLabel">检查运行环境</b><span id="runtimeDetail">ModelPort · Qwen · Data Plane</span></div>
        <span id="runtimeLatency" class="runtime-latency">—</span>
      </div>
    </header>

    <section class="command-center">
      <div class="command-layout">
        <div>
          <div class="eyebrow">Search · Ads · Recommendation</div>
          <h1>把购买意图，变成<br>可核验的商品决策</h1>
          <p class="hero-copy">本地千问驱动的搜广推 Agent 负责理解与协作，确定性数据服务守住价格、库存、兼容和广告边界。每一步建议都有证据，也都可以追溯。</p>
          <div class="trust-row">
            <span class="trust-item"><i class="trust-icon">✓</i>本地推理不出域</span>
            <span class="trust-item"><i class="trust-icon">✓</i>实时 Quote 校验</span>
            <span class="trust-item"><i class="trust-icon">✓</i>无自动支付权限</span>
          </div>
        </div>
        <div class="composer">
          <label class="composer-label" for="message"><span>描述你的购买目标</span><span>自然语言即可</span></label>
          <textarea id="message" maxlength="2000">总预算7000元，重视拍照和续航，帮我选手机并搭配降噪耳机和充电器</textarea>
          <div class="prompt-presets">
            <button id="bundle" class="preset" type="button">手机全套</button>
            <button id="noAds" class="preset" type="button">不要广告</button>
            <button id="single" class="preset" type="button">只选单品</button>
          </div>
          <div class="composer-actions">
            <span id="session" class="session-tag"></span>
            <div class="button-row">
              <button id="confirm" class="confirm-btn" type="button" disabled>确认购物车</button>
              <button id="run" class="primary-btn" type="button">生成决策方案 →</button>
            </div>
          </div>
        </div>
      </div>
      <div class="run-status">
        <div id="status" class="status-copy" aria-live="polite">工作台已就绪，等待新的购买需求。</div>
        <div class="run-progress" aria-hidden="true"><i id="progressBar"></i></div>
      </div>
    </section>

    <section class="kpi-ribbon" aria-label="本次决策关键指标">
      <div class="kpi"><span class="kpi-label"><i></i>组合金额</span><strong id="kpiTotal" class="kpi-value">—</strong><span id="kpiTotalNote" class="kpi-delta">等待生成方案</span></div>
      <div class="kpi"><span class="kpi-label"><i style="background:var(--teal)"></i>预算使用</span><strong id="kpiBudget" class="kpi-value">—</strong><span id="kpiBudgetNote" class="kpi-delta">预算约束尚未解析</span></div>
      <div class="kpi"><span class="kpi-label"><i style="background:var(--blue)"></i>证据完整度</span><strong id="kpiEvidence" class="kpi-value">—</strong><span class="kpi-delta">事实门禁通过比例</span></div>
      <div class="kpi"><span class="kpi-label"><i style="background:var(--amber)"></i>模型提案采用</span><strong id="kpiAdoption" class="kpi-value">—</strong><span id="kpiAdoptionNote" class="kpi-delta">ModelPort 真实调用</span></div>
      <div class="kpi"><span class="kpi-label"><i style="background:var(--red)"></i>本次耗时</span><strong id="kpiLatency" class="kpi-value">—</strong><span id="kpiLatencyNote" class="kpi-delta">端到端执行时间</span></div>
    </section>

    <div class="workspace">
      <main class="decision-canvas">
        <div class="canvas-head">
          <div><span class="section-kicker">Decision Brief</span><h2>推荐决策简报</h2></div>
          <span id="verdict" class="verdict-badge"><i></i>等待决策</span>
        </div>
        <div class="decision-hero">
          <div>
            <span class="summary-label">AI 决策摘要</span>
            <div id="summary" class="summary">运行后，这里会先给出结论；商品组合、预算依据和完整执行证据将在下方展开。</div>
            <div id="plan" class="plan-chips"></div>
          </div>
          <div class="decision-score">
            <strong id="decisionScore">—</strong>
            <span>决策可信度<br>基于硬规则与证据覆盖</span>
            <div class="score-line"><i id="decisionScoreLine"></i></div>
          </div>
        </div>
        <section class="bundle-section">
          <div class="subhead"><h3>最优商品组合</h3><span id="bundleMeta">尚未生成商品组合</span></div>
          <div id="products" class="bundle-flow">
            <div class="empty-state"><div><b>商品组合会出现在这里</b><span>不是结果列表，而是一套经过预算与兼容校验的组合</span></div></div>
          </div>
        </section>
        <div id="cart" class="cart-banner hidden"></div>
      </main>

      <aside class="agent-console">
        <div class="console-head">
          <div><span class="section-kicker">Live Orchestration</span><h2>Agent 执行泳道</h2></div>
          <span id="modelStats" class="model-stat">0 model calls<br>等待任务</span>
        </div>
        <div id="roles" class="stage-map"></div>
        <div class="console-footer">
          <div class="console-metric"><span>已采纳</span><strong id="acceptedCount">0</strong></div>
          <div class="console-metric"><span>安全修正</span><strong id="correctedCount">0</strong></div>
          <div class="console-metric"><span>降级兜底</span><strong id="fallbackCount">0</strong></div>
        </div>
      </aside>
    </div>

    <div class="story-grid">
      <section class="viz-card">
        <div class="viz-head"><h3>预算分配</h3><span id="budgetCaption">按最终商品类别拆解</span></div>
        <div class="budget-body">
          <div id="budgetRing" class="budget-ring"><div class="ring-copy"><strong id="budgetRingValue">—</strong><span>预算利用率</span></div></div>
          <div id="allocationList" class="allocation-list"><div class="audit-empty">生成方案后显示金额结构</div></div>
        </div>
      </section>
      <section class="viz-card">
        <div class="viz-head"><h3>决策漏斗</h3><span>从意图理解到交易门禁</span></div>
        <div id="funnel" class="funnel">
          <div class="audit-empty">等待搜广推链路开始执行</div>
        </div>
      </section>
    </div>

    <section class="analytics">
      <div class="analytics-head">
        <div><span class="section-kicker">Performance Intelligence</span><h2>全链路质量仪表</h2><p>将六层指标压缩为可比较的质量曲线，避免用卡片逐项罗列。</p></div>
        <span id="metricTime" class="metric-time">等待指标快照</span>
      </div>
      <div class="analytics-body">
        <div class="metric-chart">
          <div class="chart-legend"><span><i></i>模型与决策</span><span><i></i>检索与证据</span></div>
          <div id="metricBars" class="metric-bars"><div class="audit-empty">正在加载质量指标…</div></div>
        </div>
        <div class="reliability">
          <h3>核心健康度</h3>
          <div class="gauge-row">
            <div class="gauge-unit"><div id="northStarGauge" class="gauge violet"><strong id="northStarValue">—</strong></div><span>合格方案率</span></div>
            <div class="gauge-unit"><div id="adoptionGauge" class="gauge"><strong id="adoptionValue">—</strong></div><span>模型提案使用率</span></div>
          </div>
          <div id="reliabilityNote" class="reliability-note">North Star 衡量通过约束、证据和策略门禁的方案；确认转化与草案成功率独立统计。</div>
        </div>
      </div>
    </section>

    <section class="audit">
      <div class="audit-head">
        <div><span class="section-kicker">Audit Trail</span><h3>可追溯执行证据</h3></div>
        <div class="audit-tools" aria-label="Trace 筛选">
          <button class="filter-btn active" data-filter="all" type="button">全部</button>
          <button class="filter-btn" data-filter="model" type="button">模型调用</button>
          <button class="filter-btn" data-filter="guard" type="button">事实门禁</button>
        </div>
      </div>
      <div id="timeline" class="timeline"><div class="audit-empty">发起请求后，委派、Tool Call、模型提案和安全校验会按时间出现。</div></div>
    </section>

    <footer class="system-note">
      <div class="boundary-copy"><strong>系统边界</strong><span>商品、评论与规则为合成教学数据；千问负责理解与排序建议，确定性服务负责价格、库存、兼容和广告合规。确认仅创建购物车草案，不会提交订单或支付。</span></div>
      <div class="system-flow"><b>Buyer</b>→<b>Pi Agents</b>→<b>ModelPort</b>→<b>Local Qwen</b>＋<b>Python Data</b></div>
    </footer>
  </div>

  <script>
    const roleStages = [
      {label:'理解',roles:['lead','intent_router']},
      {label:'发现',roles:['search','recommendation','ads']},
      {label:'验证',roles:['compatibility','pricing','review_evidence','critic']},
      {label:'履约',roles:['cart']}
    ];
    const roleLabels = {lead:'Lead',intent_router:'Intent',search:'Search',recommendation:'Recommend',ads:'Ads',compatibility:'Compatibility',pricing:'Pricing',review_evidence:'Reviews',critic:'Critic',cart:'Cart'};
    const categoryLabels = {phone:'手机',headphones:'耳机',charger:'充电器',cable:'线材',case:'保护壳'};
    const categoryCodes = {phone:'PH',headphones:'AU',charger:'PW',cable:'CB',case:'CS'};
    const sessionId = 'web-' + crypto.randomUUID();
    const userId = 'demo-user';
    const $ = selector => document.querySelector(selector);
    let executionStartedAt = 0;
    let executionTimer = null;
    let activeFilter = 'all';
    let traceCount = 0;
    $('#session').textContent = 'SESSION · ' + sessionId.slice(-8).toUpperCase();

    function percent(value) { return Math.max(0,Math.min(100,Math.round(Number(value||0)*100))); }
    function money(value) { return '¥' + Number(value||0).toLocaleString('zh-CN'); }
    function textNode(tag,className,text) { const node=document.createElement(tag); if(className)node.className=className; node.textContent=text; return node; }
    function setChildren(target,children) { target.replaceChildren(...children); }

    function initRoles() {
      const target=$('#roles'); target.replaceChildren();
      roleStages.forEach((stage,index)=>{
        const group=textNode('div','agent-stage','');
        group.append(textNode('span','stage-number',String(index+1)),textNode('span','stage-name',stage.label));
        const roles=textNode('div','stage-roles','');
        stage.roles.forEach(role=>{
          const node=textNode('div','role',''); node.dataset.role=role;
          const name=textNode('span','role-name',''); name.append(textNode('i','role-dot',''),document.createTextNode(roleLabels[role]));
          node.append(name,textNode('span','role-state','等待任务')); roles.append(node);
        });
        group.append(roles); target.append(group);
      });
    }

    function setRole(role,state,meta) {
      const node=document.querySelector('[data-role="'+role+'"]'); if(!node)return;
      node.className='role '+state;
      const labels={active:'执行中',accepted:'已采纳',corrected:'安全修正',fallback:'已降级',replay:'离线回放'};
      node.querySelector('.role-state').textContent=(labels[state]||state)+(meta?' · '+meta:'');
    }

    function setStatus(message,busy,error) {
      const target=$('#status'); target.replaceChildren();
      if(busy)target.append(textNode('i','spinner',''));
      target.append(document.createTextNode(message));
      target.style.color=error?'#ff8b91':'';
      if(busy)$('#progressBar').style.width=Math.min(92,8+traceCount*3)+'%';
      if(error)$('#progressBar').style.width='100%';
    }

    function startTimer() {
      executionStartedAt=performance.now();
      clearInterval(executionTimer);
      executionTimer=setInterval(()=>{const seconds=(performance.now()-executionStartedAt)/1000;$('#kpiLatency').textContent=seconds.toFixed(1)+'s';},100);
    }

    function stopTimer() {
      if(!executionStartedAt)return;
      clearInterval(executionTimer); executionTimer=null;
      const seconds=(performance.now()-executionStartedAt)/1000;
      $('#kpiLatency').textContent=seconds.toFixed(1)+'s';
      $('#kpiLatencyNote').textContent='端到端流式执行已完成';
    }

    function renderRuntime(data) {
      const model=data.model,plane=data.dataPlane;
      const ok=model.status!=='down'&&plane.status!=='down';
      $('#runtimeDot').className='runtime-indicator '+(ok?'up':'down');
      $('#runtimeLabel').textContent=model.mode==='modelport'?'本地千问在线':'离线 Replay 模式';
      $('#runtimeLatency').textContent=model.latencyMs+' ms';
      $('#runtimeDetail').textContent=model.model+' · '+plane.mode+' '+plane.status;
    }

    function traceKind(record) {
      if(record.event==='model_execution')return 'model';
      if(['critic','compatibility','pricing','review_evidence','cart'].includes(record.role))return 'guard';
      return 'flow';
    }

    function applyTraceFilter() {
      document.querySelectorAll('.event').forEach(node=>{node.classList.toggle('hidden',activeFilter!=='all'&&node.dataset.kind!==activeFilter);});
    }

    function appendEvent(record) {
      const timeline=$('#timeline'); if(timeline.querySelector('.audit-empty'))timeline.replaceChildren();
      traceCount+=1; $('#progressBar').style.width=Math.min(92,8+traceCount*3)+'%';
      const kind=traceKind(record); const event=textNode('div','event',''); event.dataset.kind=kind;
      const detail=record.detail||{};
      let detailText=Object.entries(detail).slice(0,4).map(entry=>entry[0]+'='+String(entry[1])).join(' · ');
      if(record.event==='model_execution') {
        detailText=detail.model+' · '+detail.outcome+' · '+detail.latencyMs+'ms · '+detail.totalTokens+' tokens';
        setRole(record.role,String(detail.outcome),detail.latencyMs+'ms');
      } else if(record.event==='task_received'||record.event==='delegated') {
        setRole(String(detail.to||record.role),'active','运行中');
      }
      event.append(
        textNode('span','event-seq',String(record.sequence)),
        textNode('span','event-role',roleLabels[record.role]||record.role),
        (()=>{const main=textNode('div','event-main','');main.append(textNode('div','event-title',record.event),textNode('div','event-detail',detailText));return main;})(),
        textNode('span','event-kind',kind==='model'?'MODEL':kind==='guard'?'GUARD':'FLOW')
      );
      timeline.append(event); applyTraceFilter(); timeline.scrollTop=timeline.scrollHeight;
    }

    function renderPlan(decision) {
      const target=$('#plan'); target.replaceChildren();
      const items=[
        '意图 · '+decision.plan.intent,
        '预算 · '+(decision.plan.requirements.budgetMax?money(decision.plan.requirements.budgetMax):'待补充'),
        '通道 · '+decision.plan.channels.join(' / '),
        '类目 · '+decision.plan.requirements.requestedCategories.map(item=>categoryLabels[item]||item).join(' / '),
        '模型 · '+decision.runtime.model
      ];
      items.forEach((value,index)=>target.append(textNode('span','plan-chip '+(index===4?'model':''),value)));
    }

    function renderProducts(decision) {
      const reviews=new Map(decision.reviewEvidence.products.map(item=>[item.productId,item]));
      const compatibility=new Map(decision.bundle.compatibility.map(item=>[item.accessoryId,item]));
      const products=$('#products'); products.replaceChildren();
      decision.bundle.items.forEach((item,index)=>{
        if(index>0)products.append(textNode('div','flow-plus','＋'));
        const card=textNode('article','product','');
        const visual=textNode('div','product-visual '+item.product.category,'');
        visual.append(textNode('span','product-index','0'+(index+1)),textNode('span','visual-code',categoryCodes[item.product.category]||'SKU'));
        if(item.sponsored)visual.insertBefore(textNode('span','sponsored','赞助'),visual.lastChild);
        const review=reviews.get(item.product.productId); const compat=compatibility.get(item.product.productId);
        const evidence=textNode('div','evidence-row','');
        if(review&&review.aspects&&review.aspects.length)evidence.append(textNode('span','evidence-chip',review.aspects[0].aspect+' · '+review.aspects[0].mentionCount+'条评价'));
        if(compat)evidence.append(textNode('span','evidence-chip','兼容 '+compat.status));
        evidence.append(textNode('span','evidence-chip','库存 '+item.product.stock));
        card.append(
          visual,
          textNode('div','product-brand',item.product.brand+' · '+(categoryLabels[item.product.category]||item.product.category)),
          textNode('div','product-title',item.product.title),
          (()=>{const price=textNode('div','product-price',money(item.product.price));price.append(textNode('small','',item.product.quoteVersion));return price;})(),
          evidence
        );
        products.append(card);
      });
      $('#bundleMeta').textContent=decision.bundle.items.length+' 件商品 · Quote '+decision.priceQuote.quoteVersion;
    }

    function renderBudget(decision) {
      const total=Number(decision.bundle.totalPrice||0); const limit=Number(decision.bundle.budgetMax||0);
      const utilization=limit?Math.min(100,Math.round(total/limit*100)):0;
      $('#budgetRing').style.setProperty('--score',String(utilization));
      $('#budgetRingValue').textContent=limit?utilization+'%':'—';
      $('#budgetCaption').textContent=limit?'总预算 '+money(limit)+' · 剩余 '+money(Math.max(0,limit-total)):'未设置总预算';
      const grouped=new Map();
      decision.bundle.items.forEach(item=>{const category=item.product.category;grouped.set(category,(grouped.get(category)||0)+Number(item.product.price));});
      const target=$('#allocationList'); target.replaceChildren();
      Array.from(grouped.entries()).forEach(entry=>{
        const row=textNode('div','allocation-row','');
        const track=textNode('div','bar-track',''); const bar=textNode('i','',''); bar.style.width=(total?entry[1]/total*100:0)+'%'; track.append(bar);
        row.append(textNode('span','',categoryLabels[entry[0]]||entry[0]),track,textNode('strong','',money(entry[1]))); target.append(row);
      });
      $('#kpiTotal').textContent=money(total);
      $('#kpiTotalNote').textContent=decision.bundle.items.length+' 件商品 · '+(decision.bundle.withinBudget?'预算内':'超出预算');
      $('#kpiBudget').textContent=limit?utilization+'%':'—';
      $('#kpiBudgetNote').textContent=limit?'剩余 '+money(Math.max(0,limit-total)):'没有硬预算约束';
    }

    function renderFunnel(decision) {
      const rows=[
        ['需求字段',decision.plan.requirements.hardFields.length+decision.plan.requirements.useCases.length,'已结构化'],
        ['召回通道',decision.plan.channels.length,'并行执行'],
        ['候选商品',decision.slate.length,'融合候选'],
        ['组合商品',decision.bundle.items.length,'通过预算'],
        ['最终决策',decision.critique.verdict==='approved'?1:0,decision.critique.verdict]
      ];
      const widths=[100,88,74,61,48]; const target=$('#funnel'); target.replaceChildren();
      rows.forEach((row,index)=>{
        const node=textNode('div','funnel-row',''); const track=textNode('div','funnel-track','');
        const bar=textNode('div','funnel-bar',row[2]); bar.style.width=widths[index]+'%'; track.append(bar);
        node.append(textNode('span','funnel-label',row[0]),track,textNode('span','funnel-value',String(row[1]))); target.append(node);
      });
    }

    function renderDecisionQuality(decision) {
      const checks=Object.values(decision.critique.checks); const score=checks.length?Math.round(checks.filter(Boolean).length/checks.length*100):0;
      $('#decisionScore').textContent=score+'%'; $('#decisionScoreLine').style.width=score+'%';
      $('#kpiEvidence').textContent=score+'%';
      const badge=$('#verdict'); const approved=decision.critique.verdict==='approved';
      badge.className='verdict-badge '+(approved?'approved':'vetoed'); badge.replaceChildren(textNode('i','',''),document.createTextNode(approved?'确定性门禁通过':'需要重新规划'));
    }

    function renderRuntimeStats(runtime) {
      const adoption=runtime.modelCalls?Math.round((runtime.proposalAccepted+runtime.proposalCorrected)/runtime.modelCalls*100):0;
      $('#modelStats').textContent=runtime.modelCalls+' model calls · '+runtime.totalTokens.toLocaleString('zh-CN')+' tokens';
      $('#acceptedCount').textContent=String(runtime.proposalAccepted);
      $('#correctedCount').textContent=String(runtime.proposalCorrected);
      $('#fallbackCount').textContent=String(runtime.fallbackCount);
      $('#kpiAdoption').textContent=adoption+'%';
      $('#kpiAdoptionNote').textContent=runtime.fallbackCount?'含 '+runtime.fallbackCount+' 次透明降级':'本轮没有模型失败兜底';
    }

    function renderResult(body) {
      stopTimer(); $('#progressBar').style.width='100%';
      const proposal=body.phase==='proposal'; const drafted=body.phase==='cart_draft';
      setStatus(drafted?'购物车草案已创建，支付权限仍为关闭。':proposal?'决策通过事实门禁，可在核对后确认购物车。':body.message,false,body.phase==='needs_replan');
      $('#summary').textContent=body.message; $('#confirm').disabled=!proposal;
      const decision=body.decision; if(!decision)return;
      renderPlan(decision); renderProducts(decision); renderBudget(decision); renderFunnel(decision); renderDecisionQuality(decision); renderRuntimeStats(decision.runtime);
      if(body.cartDraft) {
        const cart=$('#cart'); cart.classList.remove('hidden'); cart.replaceChildren();
        const copy=textNode('div','',''); copy.append(textNode('strong','','购物车草案已安全创建'),textNode('span','',body.cartDraft.draftId+' · '+body.cartDraft.items.length+' 件 · paymentAuthorized=false'));
        cart.append(copy,textNode('div','cart-total',money(body.cartDraft.totalPrice)));
      }
    }

    async function callAgent(confirmed) {
      const run=$('#run'),confirm=$('#confirm'); run.disabled=true; confirm.disabled=true;
      if(!confirmed) {
        initRoles(); traceCount=0; setChildren($('#timeline'),[textNode('div','audit-empty','正在建立实时执行链路…')]);
        $('#cart').classList.add('hidden'); startTimer();
      } else startTimer();
      setStatus(confirmed?'Pricing 与 Cart Agent 正在刷新 Quote…':'本地千问与搜广推 Agent 正在协作…',true,false);
      try {
        const response=await fetch('/api/v1/agent/stream',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({sessionId,userId,message:confirmed?'确认生成购物车草案':$('#message').value,confirmed})});
        if(!response.ok){const error=await response.json();throw new Error(error.message||error.error||'请求失败');}
        const reader=response.body.getReader(),decoder=new TextDecoder(); let buffer='';
        while(true) {
          const chunk=await reader.read(); buffer+=decoder.decode(chunk.value||new Uint8Array(),{stream:!chunk.done});
          const lines=buffer.split('\n'); buffer=lines.pop()||'';
          for(const line of lines) { if(!line.trim())continue; const event=JSON.parse(line); if(event.type==='runtime')renderRuntime(event.data); if(event.type==='trace')appendEvent(event.data); if(event.type==='result')renderResult(event.data); }
          if(chunk.done)break;
        }
        await loadMetrics();
      } catch(error) {
        stopTimer(); setStatus(error instanceof Error?error.message:'请求失败',false,true); throw error;
      } finally { run.disabled=false; }
    }

    function metricRow(label,value) {
      const score=percent(value); const row=textNode('div','metric-row',''); const track=textNode('div','metric-track',''); const bar=textNode('i','',''); bar.style.width=score+'%'; track.append(bar);
      row.append(textNode('label','',label),track,textNode('strong','',score+'%')); return row;
    }

    async function loadMetrics() {
      const data=await fetch('/metrics').then(response=>response.json()); const layers=data.layers;
      const values=[
        ['意图理解',layers.intentUnderstanding.requirementParseRate],
        ['通道执行',layers.retrieval.channelExecutionRate],
        ['自然召回',layers.retrieval.naturalCandidateSuccessRate],
        ['融合合规',layers.rankingFusion.policyComplianceRate],
        ['组合完整',layers.rankingFusion.completeProposalRate],
        ['证据覆盖',layers.evidenceDecision.groundedEvidenceRate],
        ['实时 Quote',layers.evidenceDecision.liveQuoteCoverageRate],
        ['模型提案',layers.agentReliability.modelProposalUseRate]
      ];
      const target=$('#metricBars'); target.replaceChildren(...values.map(item=>metricRow(item[0],item[1])));
      const north=percent(data.northStar.value),adoption=percent(layers.agentReliability.modelProposalUseRate);
      $('#northStarGauge').style.setProperty('--score',String(north)); $('#northStarValue').textContent=north+'%';
      $('#adoptionGauge').style.setProperty('--score',String(adoption)); $('#adoptionValue').textContent=adoption+'%';
      $('#metricTime').textContent='快照 · '+new Date(data.generatedAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',second:'2-digit'});
      $('#reliabilityNote').textContent='累计 '+data.counters.purchase_intent_sessions+' 次购买会话 · '+data.counters.real_model_role_runs+' 次真实模型角色调用 · '+data.counters.model_fallbacks+' 次降级。';
    }

    async function loadRuntime() {
      try { renderRuntime(await fetch('/api/v1/runtime').then(response=>response.json())); }
      catch { $('#runtimeLabel').textContent='运行状态不可用'; $('#runtimeDot').className='runtime-indicator down'; }
    }

    document.querySelectorAll('.filter-btn').forEach(button=>button.addEventListener('click',()=>{
      activeFilter=button.dataset.filter; document.querySelectorAll('.filter-btn').forEach(item=>item.classList.toggle('active',item===button)); applyTraceFilter();
    }));
    $('#run').addEventListener('click',()=>callAgent(false).catch(()=>{}));
    $('#confirm').addEventListener('click',()=>callAgent(true).catch(()=>{}));
    $('#bundle').addEventListener('click',()=>{$('#message').value='总预算7000元，重视拍照和续航，帮我选手机并搭配降噪耳机和充电器';});
    $('#noAds').addEventListener('click',()=>{$('#message').value='预算6500元，不要广告，帮我选拍照手机并搭配耳机和充电器';});
    $('#single').addEventListener('click',()=>{$('#message').value='预算5000元，通勤拍照为主，帮我推荐一台安卓手机，不要广告';});
    initRoles(); loadRuntime(); loadMetrics();
  </script>
</body>
</html>`;
