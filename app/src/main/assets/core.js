/* Общие данные и функции: панель клавиатуры (panel.html) и приложение (app.html). */
const uid = p => p + Date.now().toString(36) + Math.random().toString(36).slice(2, 5);
/* меняет элемент местами с ближайшим соседом (в том же уровне/правиле, если задан same). dir: -1 вверх, +1 вниз */
function moveIn(arr, item, dir, same) {
  const i = arr.indexOf(item); if (i < 0) return false;
  for (let j = i + dir; j >= 0 && j < arr.length; j += dir) if (!same || same(arr[j])) { arr[i] = arr[j]; arr[j] = item; return true }
  return false
}
const CMDT = { mute: { u: 'мин', lv: 1 }, jail: { u: 'мин', lv: 1 }, rmute: { u: 'мин', lv: 1 }, ban: { u: 'дн', lv: 1 }, sban: { u: 'дн', lv: 1 }, warn: { u: '', lv: 1 } };
const OFF = { mute: 'offmute', jail: 'offjail', warn: 'offwarn', ban: 'offban' };
const CATS = ['Чат', 'RP', 'Читы/ПО', 'Экономика', 'Админы', 'Прочее'];
const unitOf = c => { const k = String(c || '').replace(/^off/, ''); return CMDT[k] ? CMDT[k].u : '' };
const TIPS = { мин: [5, 15, 30, 60, 120], дн: [1, 3, 7, 14, 31] };

/* [id, название (оно же причина), категория, команда, срок от, срок до, примечание, ключевые слова, доп. команда] */
/* Правила → пункты. Правило (rule) — раздел правил со своей категорией, пункт (reason) — конкретное нарушение с командой и сроком. */
/* приводит сохранённые данные к актуальному виду (старые версии без правил тоже понимает) */
function normModel(rules, reasons) {
  const okR = Array.isArray(rules) && rules.length, okP = Array.isArray(reasons) && reasons.length;
  rules = okR ? rules.map(r => ({ id: r.id || uid('r'), n: r.n || 'Правило', cat: r.cat || r.n || 'Прочее' })) : DEF_RULES();
  reasons = okP ? reasons : DEF_REASONS();
  if (!okP && okR) { rules = DEF_RULES() }
  const byId = new Map(rules.map(r => [r.id, r]));
  reasons.forEach(r => {
    if (!r.rid || !byId.has(r.rid)) {
      let ru = rules.find(x => x.cat === r.cat) || rules.find(x => x.n === r.cat);
      if (!ru) { ru = { id: uid('r'), n: r.cat || 'Прочее', cat: r.cat || 'Прочее' }; rules.push(ru); byId.set(ru.id, ru) }
      r.rid = ru.id
    }
    r.cat = byId.get(r.rid).cat;
    if (!Array.isArray(r.kw)) r.kw = [];
    if (!Array.isArray(r.tm)) r.tm = [];
    r.pt = r.pt == null ? '' : String(r.pt);
    r.a = +r.a || 0; r.b = +r.b || 0; r.note = r.note || ''; r.txt = r.txt || '';
    /* «термин» = пункт без номера; у любого пункта может быть несколько наказаний сразу (more). Старый флаг x:'warn' превращается в доп. варн */
    r.more = (Array.isArray(r.more) ? r.more : []).filter(m => m && CMDT[String(m.cmd || '').replace(/^off/, '')]).map(m => ({ cmd: m.cmd, a: +m.a || 0, b: +m.b || 0, tm: Array.isArray(m.tm) ? m.tm.slice(0, 3) : [] }));
    if (!r.more.length && r.x === 'warn') r.more = [{ cmd: 'warn', a: 0, b: 0, tm: [] }];
    r.x = ''
  });
  return { rules, reasons }
}
/* общий загрузчик для приложения и клавиатуры: при первой миграции сразу сохраняет, чтобы у обоих были одинаковые id правил */
function loadModel(gd, sd) {
  const r0 = gd('rules', null), p0 = gd('reasons', null);
  if (!Array.isArray(r0)) return { rules: [], reasons: [] };   /* новая установка: полностью пусто */
  const m = normModel(r0, p0);
  if (!Array.isArray(r0) || !r0.length || (Array.isArray(p0) && p0.some(x => !x.rid))) { sd('rules', m.rules); sd('reasons', m.reasons) }
  return m
}
function loadCmds(gd, sd) { const n = normCmds(gd('cmds', null)); if (n.changed) sd('cmds', n.list); return n.list }
const catsOf = rules => [...new Set(rules.map(r => r.cat))];
/* быстрое добавление пунктов: по строке на пункт. «2.1 Название | mute | 15-30 | примечание» — всё кроме названия необязательно */
function parsePoints(text, rule) {
  const out = [];
  String(text || '').split('\n').forEach(raw => {
    let ln = raw.trim(); if (!ln) return;
    let pt = ''; const m = ln.match(/^(\d+(?:\.\d+)*)[.)]?\s+(.*)$/); if (m) { pt = m[1]; ln = m[2] }
    const parts = ln.split(/\s*[|;\t]\s*/).map(x => x.trim()).filter(Boolean); if (!parts.length) return;
    const p = { id: uid('p'), rid: rule.id, pt, n: parts[0], cat: rule.cat, cmd: 'mute', a: 0, b: 0, note: '', kw: [], x: '', more: [], txt: '', tm: [] };
    const notes = [];
    parts.slice(1).forEach(x => {
      const lc = x.toLowerCase().replace(/^\//, '');
      /* «+ warn», «+ jail 30-60» — второе наказание; «jail 30-60» — команда со сроком в одной ячейке */
      const mo = x.match(/^\+\s*\/?([a-z]+)\s*(?:(\d+)(?:\s*[-–]\s*(\d+))?)?\s*$/i);
      if (mo && CMDT[mo[1].toLowerCase()]) { p.more.push({ cmd: mo[1].toLowerCase(), a: +mo[2] || 0, b: +(mo[3] || mo[2]) || 0, tm: [] }); return }
      const mc = x.match(/^\/?([a-z]+)\s+(\d+)(?:\s*[-–]\s*(\d+))?\s*(?:мин|дн|д|м)?\.?$/i);
      if (mc && CMDT[mc[1].toLowerCase()]) { p.cmd = mc[1].toLowerCase(); p.a = +mc[2]; p.b = mc[3] ? +mc[3] : +mc[2]; return }
      const t = x.match(/^(\d+)(?:\s*[-–]\s*(\d+))?\s*(?:мин|дн|д|м)?\.?$/i);
      if (CMDT[lc]) p.cmd = lc; else if (t) { p.a = +t[1]; p.b = t[2] ? +t[2] : +t[1] } else notes.push(x)
    });
    p.note = notes.join('; ');
    out.push(p)
  });
  return out
}

/* команды по уровням. pun — открывает конструктор наказания; rp — ключ автоответа на репорт */
/* команды, которые по умолчанию показываются во всплывающем окне слежки */
const DEF_SP = ['/spoff', '/stats {id}', '/weap {id}', '/getinfo {t}', '/fixcar {id}', '/flip {id}', '/hp {id} 100', '/freeze {id}', '/unfreeze {id}', '/spawn {id}'];
/* старые сохранённые команды: добавляет флаг слежки и переводит /id и /getinfo на {t} (ник или ID). changed — нужно пересохранить */
function normCmds(list) {
  if (!Array.isArray(list)) return { list: [], changed: false };   /* новая установка: пусто */
  if (!list.length) return { list: [], changed: false };
  let changed = false;
  if (!list.some(c => 'sp' in c)) {
    changed = true;
    list.forEach(c => { if (c.t === '/id {id}') c.t = '/id {t}'; if (c.t === '/getinfo {id}') c.t = '/getinfo {t}'; c.sp = DEF_SP.includes(c.t) ? 1 : 0 })
  }
  list.forEach(c => { if (!c.id) { c.id = uid('c'); changed = true } c.lv = +c.lv || 1; c.sp = c.sp ? 1 : 0; c.rp = c.rp || ''; c.pun = c.pun || ''; if (!Array.isArray(c.ov)) c.ov = [] });
  return { list, changed }
}

const DEF_CFG = {
  lvl: 3, auto: false, autoReply: true, kbh: 40, kbt: 36, gap: 500, aTpl: '/a {who} нужна помощь: {text}', reqTpl: '/a {cmd}', admNick: '', lang: 'rus+eng',
  cscale2: 75, fb: 10, aCmd: '/a', loose: true, ocrThreads: 2, wrapMs: 1100, maxId: 1500, ttlMin: 90, vib: 250, autoRecAny: false, autoRecMin: 20, winDp: 320, gridPref: 0, ansCols: 2, uiPct: 100, winAlpha: 100, hideMin: 3, nextAuto: true, helpAns: 'Ваш вопрос передан администрации, ожидайте.', pz: 100, admAuto: false, strict2: true, autoRec: true, forms: true, oneOn: false, pauseS: 0, pm: '15,20,30', pd: '5,7,10', others: [],
  crop: '0.19,0.0,0.67,0.25',
  theme: 'teal', bkH: 0, bkLast: 0,
  replies: { sp: '', tp: '', spawn: '', pun: '' }
};

/* ---------- темы: акцентные цвета для приложения, клавиатуры и окон ---------- */
const THEMES = {
  teal:   ['Бирюзовая', '#5eead4', '#0f766e'],
  red:    ['Красная',   '#ff8a8a', '#b4232f'],
  purple: ['Сиреневая', '#c4a8ff', '#6d3fc4'],
  blue:   ['Синяя',     '#8ab8ff', '#1f5fd1'],
  green:  ['Зелёная',   '#7ee2a0', '#1c7a46'],
  orange: ['Оранжевая', '#ffbd6e', '#b85d0c'],
  pink:   ['Розовая',   '#ff9fcf', '#b8346f'],
  gray:   ['Графитовая','#cfd6e4', '#46526a']
};
function applyTheme(c) {
  const t = THEMES[(c && c.theme) || 'teal'] || THEMES.teal, r = document.documentElement.style;
  r.setProperty('--ac', t[1]); r.setProperty('--acb', t[2])
}

/* ---------- настраиваемые кнопки всплывающих окон ----------
   Кнопка: { id, l: название, s: [шаги], c: цвет }. Шаг — команда с подстановками:
   {id} нарушитель/цель, {rep} автор репорта, {nickname} ник; либо @ans:ключ (автоответ автору: sp/tp/spawn/pun),
   @ans текст (свой ответ автору), @help (позвать на помощь), @done (отметить репорт отвеченным), @spoff (выйти из слежки). */
/* новая установка — кнопок нет: всё добавляется в приложении (Команды → Кнопки окон) */
const DEF_BTNS = () => ({ rep: [], spec: [] });
function normBtns(o) {
  const d = DEF_BTNS();
  if (!o || typeof o !== 'object') return d;
  const fix = (a, def) => Array.isArray(a) ? a.filter(b => b && b.l).map(b => ({ id: b.id || uid('b'), l: String(b.l), s: Array.isArray(b.s) ? b.s.map(String).filter(Boolean) : [], c: b.c || '' })) : def;
  return { rep: fix(o.rep, d.rep), spec: fix(o.spec, d.spec) }
}

/* быстрые ответы на репорт: [ключ, подпись, текст, категория] */
const ansCats = list => [...new Set(list.map(a => a.c))];
function normAns(list) { return Array.isArray(list) ? list.map(a => ({ k: a.k || uid('a'), l: a.l || '', t: a.t || '', c: a.c || 'Общее' })) : [] }

/* какие команды из админ-чата показывать как запрос (минимальный уровень) */
/* Встроенных правил, наказаний, команд и ответов нет: приложение пустое, всё добавляется самим.
   Уровни команд берутся из раздела «Команды» (setLV вызывается при загрузке и сохранении). */
const DEF_RULES_RAW = [], DEF_REASONS_RAW = [], DEF_CMDS_RAW = [], SN = [];
const DEF_RULES = () => [], DEF_REASONS = () => [], DEF_CMDS = () => [], DEF_ANS = () => [];
const LV = {};
function setLV(list) {
  Object.keys(LV).forEach(k => delete LV[k]);
  (list || []).forEach(c => {
    const m = String(c.t || '').trim().match(/^\/?([A-Za-z_]{2,12})/); if (!m) return;
    const n = m[1].toLowerCase(), lv = +c.lv || 1;
    if (!LV[n] || lv < LV[n]) LV[n] = lv;
  });
}
/* нет прав на команду — вместо неё в админ-чат уходит запрос-форма (по умолчанию «/a {cmd}») */
const reqWrap = (cfg, cmd) => String((cfg && cfg.reqTpl && cfg.reqTpl.includes('{cmd}')) ? cfg.reqTpl : '/a {cmd}').replace('{cmd}', cmd);
/* подпись админа: Artem_Danielle -> A.Danielle; к принятым формам наказаний добавляется « | A.Danielle» */
const admSig = n => { const p = String(n || '').trim().split('_').filter(Boolean); return p.length >= 2 ? p[0][0].toUpperCase() + '.' + p.slice(1).join('_') : p.join('') };
const signReq = (sender, t) => { const s = admSig(sender); return s && PUNRE.test(t) && !/ \| /.test(t) ? t.replace(/\s+$/, '') + ' | ' + s : t };
const cmdLevel = l => { const n = String(l).replace(/^\//, '').split(/\s/)[0].toLowerCase(); return LV[n] || 1 };

const norm = s => String(s).toLowerCase().replace(/ё/g, 'е');
const words = s => norm(s).split(/[^a-zа-я0-9_.]+/).filter(Boolean);
function kwHit(t, tk, k) { k = norm(k); if (k.includes(' ') || k.includes('.')) return t.includes(k); return k.length <= 3 ? tk.includes(k) : tk.some(w => w.startsWith(k)) }
function matchReasons(list, txt) { const t = norm(txt), tk = words(txt); return list.map(r => ({ r, s: r.kw.filter(k => kwHit(t, tk, k)).length })).filter(x => x.s).sort((a, b) => b.s - a.s) }
function rng(r) { const u = unitOf(r.cmd), a = r.a, b = r.b; if (!u) return 'без срока'; if (!a && !b) return 'срок уточни'; if (a && b) return a === b ? a + ' ' + u : a + '–' + b + ' ' + u; return a ? 'от ' + a + ' ' + u : 'до ' + b + ' ' + u }

/* переменные в шаблонах команд: {id} {nickname} {time} {other} и {t} — «что есть»: ID, а если его нет — ник (для команд, которые принимают и то и другое) */
const TPL_RE = /\{(id|nickname|time|other|t)\}/g;
const tplUses = t => { const s = new Set(); String(t).replace(TPL_RE, (m, k) => { s.add(k); return m }); return s };
const fillTpl = (t, v) => String(t).replace(TPL_RE, (m, k) => v[k] == null || v[k] === '' ? m : v[k]);
/* быстрые сроки: только цифры, единицу (мин/дн) подставляет команда */
const parseNums = (s, d) => { const a = String(s == null ? '' : s).split(/[^0-9]+/).filter(Boolean).map(Number).filter(n => n > 0 && n < 10000); return a.length ? [...new Set(a)] : d.slice() };
const presets = (cfg, u) => u === 'мин' ? parseNums(cfg.pm, [15, 20, 30]) : u === 'дн' ? parseNums(cfg.pd, [5, 7, 10]) : [];
/* Главные сроки (до 3): свои у пункта (r.tm), а если не заданы — быстрые значения, попавшие в диапазон правила */
function mainTimes(cfg, r, cmd) {
  const u = unitOf(cmd); if (!u) return [];
  const same = r && r.cmd === cmd;
  if (same && r.tm && r.tm.length) return r.tm.slice(0, 3);
  const a = same ? r.a : 0, b = same ? r.b : 0, pr = presets(cfg, u);
  const inr = pr.filter(x => (!a || x >= a) && (!b || x <= b));
  return (inr.length ? inr : pr).slice(0, 3)
}
/* {main, extra}: главные кнопки и остальные (быстрые значения + границы правила) */
function timeSets(cfg, r, cmd) {
  const u = unitOf(cmd); if (!u) return { main: [], extra: [] };
  const main = mainTimes(cfg, r, cmd), ex = new Set(presets(cfg, u));
  if (r && r.cmd === cmd) [r.a, r.b].forEach(x => { if (x > 0) ex.add(x) });
  return { main, extra: [...ex].filter(x => !main.includes(x)).sort((a, b) => a - b) }
}
function timeChips(cfg, r, cmd) { const t = timeSets(cfg, r, cmd); return [...t.main, ...t.extra].sort((a, b) => a - b) }
function outRange(r, cmd, v) { return !!(r && r.cmd === cmd && ((r.a && v < r.a) || (r.b && v > r.b))) }
/* срок по умолчанию: первый главный срок внутри диапазона правила */
function smartTime(cfg, r, cmd) {
  const u = unitOf(cmd); if (!u) return '';
  const m = mainTimes(cfg, r, cmd);
  if (r && r.cmd === cmd && (r.a || r.b)) { const a = r.a || 0, b = r.b || 1e9, ok = m.filter(x => x >= a && x <= b); return String(ok.length ? ok[0] : (r.a || r.b)) }
  return m.length ? String(m[0]) : ''
}
/* отметить/снять «главный» срок у пункта (максимум 3, лишний вытесняет самый старый) */
function toggleMain(r, v) {
  v = +v; if (!v) return r.tm || [];
  const t = (r.tm || []).slice();
  const i = t.indexOf(v);
  if (i >= 0) t.splice(i, 1); else { t.push(v); while (t.length > 3) t.shift() }
  r.tm = t.sort((a, b) => a - b); return r.tm
}
/* что считать наказанием в журнале */
const PUNRE = /^\/(mute|jail|rmute|ban|sban|warn|offmute|offjail|offwarn|offban|kick|skick)\s+\S+\s+\S/i;
const defTime = r => r.a > 0 ? r.a : (r.b > 0 ? r.b : '');

/* собирает команду(ы) наказания. o: {id, time, reason, off, lvl, cmd} */
/* Выдача наказания. Основное: o.cmd + o.time. Дополнительные (термины и пункты с двумя наказаниями сразу): o.extra = [{cmd, time}],
   если не передан — берутся из r.more со сроками по умолчанию. */
function extraOf(r) { return ((r && r.more) || []).map(m => ({ cmd: m.cmd, time: '' })) }
function compose(r, o) {
  const off = c => (o.off && OFF[c]) ? OFF[c] : c;
  const id = o.id || '{ID}', reason = (o.reason || r.n || '').trim();
  const mk = (cm, t) => cm.endsWith('warn') ? `/${cm} ${id} ${reason}` : `/${cm} ${id} ${t || '{срок}'} ${reason}`;
  const c = off(o.cmd || r.cmd), t = o.time;
  const lines = [mk(c, t)], warns = [];
  const ex = Array.isArray(o.extra) ? o.extra : extraOf(r);
  ex.forEach(e => { if (e && e.cmd) lines.push(mk(off(e.cmd), e.time)) });
  const need = Math.max(...lines.map(l => cmdLevel(l)));
  if ((o.lvl || 1) < need) warns.push(`Нужен ${need} ур. — уйдёт запросом в админ-чат`);
  const n = +t;
  if (n && (o.cmd || r.cmd) === r.cmd && unitOf(c)) {
    if (r.b && n > r.b) warns.push(`Больше максимума (${r.b}) — только при отягчающих обстоятельствах`);
    if (r.a && n < r.a) warns.push(`Меньше минимума (${r.a})`);
  }
  ex.forEach((e, k) => {
    const m = ((r && r.more) || []).find(z => z.cmd === e.cmd), nn = +e.time;
    if (m && nn && unitOf(e.cmd)) { if (m.b && nn > m.b) warns.push(`${e.cmd}: больше максимума (${m.b})`); if (m.a && nn < m.a) warns.push(`${e.cmd}: меньше минимума (${m.a})`) }
  });
  if (r.note && /не указан/.test(r.note)) warns.push(r.note);
  return { lines, warns, need };
}

/* что ввёл админ в поле «цель»: цифры — ID, иначе ник */
const parseTarget = v => { v = String(v || '').trim(); const pm = v.match(/([A-Za-z][A-Za-z0-9_]*)\s*\[\s*(\d{1,5})\s*\]/); if (pm) return { id: pm[2], nick: '' }; return /^\d{1,5}$/.test(v) ? { id: v, nick: '' } : { id: '', nick: v.replace(/[^A-Za-z0-9_]/g, '').slice(0, 32) } };
/* «позвать на помощь»: шаблон /a с ID (по умолчанию «/a {id} нужна помощь») */
/* {who} = ник[ID] (или просто ID), {nick}, {id}, {text} = суть репорта */
function helpMsg(cfg, id, nick, text) {
  nick = nick || ''; const t = String(text || '').replace(/\s+/g, ' ').trim().slice(0, 90);
  let tpl = cfg.aTpl; if (!tpl || tpl === '/a {id} нужна помощь') tpl = DEF_CFG.aTpl;
  let r = tpl.split('{who}').join(nick ? nick + '[' + id + ']' : id).split('{nick}').join(nick).split('{text}').join(t)
    .split('{id}').join(id).split('{t}').join(id).split('{nickname}').join(nick);
  if (!t) r = r.replace(/\s*[:\-–]\s*$/, '');
  return r.replace(/\s{2,}/g, ' ').trim()
}
function idsIn(s) { const r = [], re = /[A-Za-z][A-Za-z0-9_]*\[(\d{1,4})\]|(?<![\d.,\[])(\d{1,3})(?![\d\]]|[.,]\d)/g; let m; while ((m = re.exec(s))) { const v = m[1] || m[2]; if (!r.includes(v)) r.push(v) } return r }
function adminCmd(body) { const m = body.match(/\/([A-Za-z_]{2,12})(?:\s+([^\n]{0,100}))?/); return m ? { cmd: m[1].toLowerCase(), text: ('/' + m[1] + (m[2] ? ' ' + m[2] : '')).trim() } : null }
function parseChat(raw) {
  let s = raw.replace(/\b\d{1,2}:\d{2}(:\d{2})?\b/g, ' ').trim();
  const o = { rep: '', tgt: '', name: '', msg: '', count: '', admin: false, ids: [] };
  const cm = s.match(/К-во репорта:\s*(\d+)/i); if (cm) { o.count = cm[1]; s = s.replace(/\[?К-во репорта:[^\]]*\]?/i, '').trim() }
  else { const c2 = s.match(/[\[({][^\])}]{3,24}[:;.]\s*(\d{1,3})\s*[\])}]\s*$/); if (c2) { o.count = c2[1]; s = s.replace(/[\[({][^\])}]{3,24}[:;.]\s*\d{1,3}\s*[\])}]\s*$/, '').trim() } }
  o.admin = /<ADM>/i.test(s); s = s.replace(/<ADM>\s*(\(\d+\))?/i, '').trim();
  const m = s.match(/([A-Za-z][A-Za-z0-9_]*)\s*\[(\d+)\]\s*:\s*([\s\S]*)$/); let body = s;
  if (m) { o.name = m[1]; body = m[3]; if (!o.admin) o.rep = m[2] }
  else if (/^\d{1,4}$/.test(s)) { o.tgt = s; o.ids = [s]; return o }
  else if (/^[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+$/.test(s)) { o.tgt = s; return o }
  o.msg = body.trim(); o.ids = idsIn(body).filter(x => x !== o.rep); if (o.ids.length) o.tgt = o.ids[0];
  return o
}
if (typeof module !== 'undefined') module.exports = { setLV, extraOf, THEMES, DEF_BTNS, normBtns, tplUses, fillTpl, TPL_RE, parseNums, presets, timeChips, timeSets, mainTimes, toggleMain, smartTime, outRange, PUNRE, compose, matchReasons, parseChat, idsIn, adminCmd, DEF_REASONS, DEF_RULES, DEF_CMDS, DEF_CFG, DEF_ANS, normModel, loadModel, loadCmds, normCmds, normAns, parsePoints, parseTarget, helpMsg, reqWrap, admSig, signReq, moveIn, catsOf, ansCats, cmdLevel, rng, unitOf, CATS, SN, LV, uid };
