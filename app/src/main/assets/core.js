/* Общие данные и функции: панель клавиатуры (panel.html) и приложение (app.html). */
const uid = p => p + Date.now().toString(36) + Math.random().toString(36).slice(2, 5);
/* меняет элемент местами с ближайшим соседом (в том же уровне/правиле, если задан same). dir: -1 вверх, +1 вниз */
function moveIn(arr, item, dir, same) {
  const i = arr.indexOf(item); if (i < 0) return false;
  for (let j = i + dir; j >= 0 && j < arr.length; j += dir) if (!same || same(arr[j])) { arr[i] = arr[j]; arr[j] = item; return true }
  return false
}
const CMDT = { mute: { u: 'мин', lv: 2 }, jail: { u: 'мин', lv: 2 }, rmute: { u: 'мин', lv: 2 }, ban: { u: 'дн', lv: 3 }, sban: { u: 'дн', lv: 4 }, warn: { u: '', lv: 3 } };
const OFF = { mute: 'offmute', jail: 'offjail', warn: 'offwarn', ban: 'offban' };
const CATS = ['Чат', 'RP', 'Читы/ПО', 'Экономика', 'Админы', 'Прочее'];
const unitOf = c => { const k = String(c || '').replace(/^off/, ''); return CMDT[k] ? CMDT[k].u : '' };
const TIPS = { мин: [5, 15, 30, 60, 120], дн: [1, 3, 7, 14, 31] };

/* [id, название (оно же причина), категория, команда, срок от, срок до, примечание, ключевые слова, доп. команда] */
const DEF_REASONS_RAW = [
  ['оск', 'Оскорбление игрока', 'Чат', 'mute', 15, 30, '', ['оскорб', 'тупой', 'дебил', 'идиот', 'урод', 'лох', 'чмо', 'мразь']],
  ['род', 'Упоминание родных', 'Чат', 'mute', 90, 180, '', ['мать', 'мамк', 'мамы', 'родных', 'родител', 'отца']],
  ['оскр', 'Оскорбление родных', 'Чат', 'ban', 0, 0, 'срок не указан в правилах — уточни', ['оскорбил мать', 'оскорбил родных']],
  ['рек', 'Реклама', 'Чат', 'mute', 60, 120, 'грубое: бан от 31 дн до навсегда', ['реклам', 'дискорд', 'телеграм', 'ютуб', 't.me', 'youtube', 'вступай', 'заходи на', 'ссылк']],
  ['расизм', 'Расизм', 'Чат', 'mute', 60, 180, 'или бан 1–31 дн', ['расизм', 'чурка', 'хач', 'ксенофоб']],
  ['нацизм', 'Нацизм / фашизм', 'Чат', 'mute', 60, 180, 'или бан 1–31 дн', ['нацист', 'фашист']],
  ['полит', 'Политика', 'Чат', 'mute', 60, 180, 'или бан 1–31 дн', ['политик']],
  ['мик', 'Звуки в микрофон', 'Чат', 'mute', 0, 20, 'грубое: мут 30–60 мин', ['музык', 'шум', 'крики', 'орет', 'войс', 'микрофон']],
  ['проп', 'Пропаганда запрещённых тем', 'Чат', 'ban', 3, 14, 'грубое: бан от 31 дн до навсегда', ['пропаганд', 'наркот', 'порно', 'суицид', 'терроризм']],

  ['нрп', 'Non-RP', 'RP', 'jail', 0, 30, '', ['нонрп', 'нрп', 'nonrp', 'non rp']],
  ['пг', 'PowerGaming', 'RP', 'jail', 0, 30, '', ['пг', 'повергейм', 'powergaming']],
  ['мг', 'MetaGaming', 'RP', 'mute', 0, 15, '', ['мг', 'метагейм', 'metagaming']],
  ['дм', 'DeathMatch', 'RP', 'jail', 30, 60, '', ['дм', 'deathmatch', 'убил без причины', 'убивает без причины']],
  ['массдм', 'Mass DM', 'RP', 'warn', 0, 0, '', ['массдм', 'масс дм', 'mass dm']],
  ['гз', 'Mass DM в зелёной зоне', 'RP', 'jail', 0, 90, '+ варн; рецидив: бан до 3 дн', ['гз', 'зеленой зоне'], 'warn'],
  ['дб', 'DriveBy', 'RP', 'jail', 0, 0, 'срок не указан в правилах — уточни', ['дб', 'драйвбай', 'таран', 'таранил', 'сбил']],
  ['ск', 'Spawn Kill', 'RP', 'jail', 60, 120, '', ['ск', 'спавнкил', 'на спавне']],
  ['массск', 'Mass Spawn Kill', 'RP', 'warn', 0, 0, 'рецидив: бан до 3 дн', ['массск', 'масс ск']],
  ['рк', 'Revenge Kill', 'RP', 'jail', 0, 30, '', ['рк', 'месть', 'revenge']],
  ['тк', 'Team Kill', 'RP', 'jail', 0, 30, 'в организации — варн', ['тк', 'teamkill', 'убил своего']],
  ['уход', 'Уход от RP', 'RP', 'jail', 0, 60, '', ['ливнул', 'лив', 'афк', 'вышел из игры', 'в воду', 'ушел от рп']],
  ['дом', 'Провокация у дома', 'RP', 'jail', 0, 120, '', ['у дома', 'территори']],
  ['вдом', 'Уход в дом от RP', 'RP', 'jail', 0, 120, '', ['забежал в дом', 'в квартиру']],
  ['сила', 'Огонь после требования уйти', 'RP', 'jail', 0, 120, '', ['после требования']],
  ['дейс', 'Злоупотребление «Действие/Попытка»', 'RP', 'mute', 0, 20, '', ['попытк', 'действи', 'событи']],
  ['свою', 'Отыгрыш только в свою пользу', 'RP', 'jail', 0, 20, '', ['в свою пользу', 'не дает ответить']],

  ['клик', 'Автокликер / скрипты', 'Читы/ПО', 'ban', 3, 7, '', ['скрипт', 'автокликер', 'макрос']],
  ['чит', 'Читы (WH, Aim и др.)', 'Читы/ПО', 'ban', 3, 7, '', ['чит', 'читы', 'читер', 'читак', 'аимбот', 'аим', 'wh', 'вх', 'летает']],
  ['баг', 'Багоюз', 'Читы/ПО', 'warn', 0, 0, 'или бан до 3 дн', ['баг', 'багоюз', 'баганул', 'текстур']],
  ['по', 'Распространение стороннего ПО', 'Читы/ПО', 'ban', 7, 31, '', ['распростран', 'раздает чит']],
  ['кноп', 'Мод кнопок', 'Читы/ПО', 'ban', 0, 7, '', ['увеличил кнопки', 'мод кнопок']],
  ['зона', 'Увеличение зоны нажатия', 'Читы/ПО', 'ban', 0, 7, '', ['зона нажатия']],

  ['мош', 'Мошенничество', 'Экономика', 'ban', 3, 7, 'грубое: бан от 31 дн до навсегда', ['обман', 'кинул', 'скам', 'мошенник', 'развел']],
  ['махин', 'Махинации', 'Экономика', 'ban', 3, 7, 'грубое: бан от 31 дн до навсегда', ['махинац', 'продаю аккаунт']],
  ['вым', 'Вымогательство', 'Экономика', 'ban', 7, 31, '', ['вымогат', 'отдай или', 'ультиматум']],
  ['биз', 'Второй бизнес', 'Экономика', 'ban', 0, 31, 'обычное: предупреждение', ['второй бизнес', 'два бизнеса']],
  ['реал', 'Сделки за реальные деньги', 'Экономика', 'ban', 0, 31, 'с обнулением; грубое: навсегда', ['за реал', 'за рубли', 'за донат', 'за деньги']],

  ['репн', 'Репорт не по назначению', 'Админы', 'rmute', 0, 30, 'или устное предупреждение', ['дай деньги', 'дайте деньги', 'дайте уровень', 'телепорт', 'флуд', 'оффтоп']],
  ['хам', 'Хамство админу', 'Админы', 'mute', 30, 60, 'грубое: бан 3–31 дн', ['админ лох', 'админы лохи', 'админ дебил', 'админ мразь']],
  ['клев', 'Клевета на админа', 'Админы', 'mute', 30, 60, 'грубое: бан 3–31 дн', ['клевет']],
  ['меш', 'Мешает работе админов', 'Админы', 'jail', 0, 60, 'обычное: устное; грубое: деморган до 60', ['мешает админ', 'провокац']],
  ['спор', 'Оспаривание действий админов', 'Админы', 'mute', 60, 60, 'грубое: бан 1–5 дн', ['оспарив', 'тролл', 'несправедлив']],
  ['выдача', 'Выдаёт себя за админа', 'Админы', 'ban', 3, 7, 'грубое: бан от 31 дн до навсегда', ['представляется админом', 'фейк админ', 'выдает себя']],

  ['ооc', 'Перенос конфликта IC↔OOC', 'Прочее', 'ban', 7, 0, 'до навсегда', ['доксинг', 'угрожает', 'личные данные', 'в реале']],
  ['стрим', 'Срыв стрима медиа', 'Прочее', 'jail', 0, 60, 'грубое: бан до 3 дн', ['стримснайп', 'стрим', 'медиа']],
  ['ном', 'Оскорбительный номер', 'Прочее', 'ban', 0, 31, 'снимается при смене номера', ['номер', 'номерной']],
  ['сем', 'Оскорбительное название семьи', 'Прочее', 'ban', 0, 31, 'снимается при смене названия', ['название семьи', 'семья называется']]
];
/* Правила → пункты. Правило (rule) — раздел правил со своей категорией, пункт (reason) — конкретное нарушение с командой и сроком. */
const DEF_RULES_RAW = [['chat', 'Чат и общение', 'Чат'], ['rp', 'RolePlay', 'RP'], ['cheat', 'Читы и ПО', 'Читы/ПО'], ['eco', 'Экономика', 'Экономика'], ['adm', 'Администрация', 'Админы'], ['oth', 'Прочее', 'Прочее']];
const DEF_RULES = () => DEF_RULES_RAW.map(r => ({ id: r[0], n: r[1], cat: r[2] }));
const DEF_REASONS = () => {
  const cnt = {};
  return DEF_REASONS_RAW.map(r => {
    const ri = DEF_RULES_RAW.findIndex(x => x[2] === r[2]); cnt[r[2]] = (cnt[r[2]] || 0) + 1;
    return { id: r[0], rid: DEF_RULES_RAW[ri][0], pt: (ri + 1) + '.' + cnt[r[2]], n: r[1], cat: r[2], cmd: r[3], a: r[4], b: r[5], note: r[6], kw: r[7].slice(), x: r[8] || '', txt: '', tm: [] }
  })
};
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
    r.a = +r.a || 0; r.b = +r.b || 0; r.note = r.note || ''; r.txt = r.txt || ''; r.x = r.x || ''
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
    const p = { id: uid('p'), rid: rule.id, pt, n: parts[0], cat: rule.cat, cmd: 'mute', a: 0, b: 0, note: '', kw: [], x: '', txt: '', tm: [] };
    const notes = [];
    parts.slice(1).forEach(x => {
      const lc = x.toLowerCase().replace(/^\//, '');
      const t = x.match(/^(\d+)(?:\s*[-–]\s*(\d+))?\s*(?:мин|дн|д|м)?\.?$/i);
      if (CMDT[lc]) p.cmd = lc; else if (t) { p.a = +t[1]; p.b = t[2] ? +t[2] : +t[1] } else notes.push(x)
    });
    p.note = notes.join('; ');
    out.push(p)
  });
  return out
}

/* команды по уровням. pun — открывает конструктор наказания; rp — ключ автоответа на репорт */
const DEF_CMDS_RAW = [
  [1, 'Наблюдение', 'Слежка', '/sp {id}', 'sp'], [1, 'Наблюдение', 'Выйти из слежки', '/spoff'], [1, 'Наблюдение', 'Инфо о наказаниях', '/getinfo {t}'], [1, 'Наблюдение', 'ID ↔ ник', '/id {t}'],
  [1, 'Игрок', 'Здоровье 100', '/hp {id} 100'], [1, 'Игрок', 'Лицензии', '/alic {id}'],
  [1, 'Транспорт', 'Починить авто', '/fixcar {id}'], [1, 'Транспорт', 'Перевернуть авто', '/flip {id}'],
  [1, 'Общее', 'В админ-зону', '/az'], [1, 'Общее', 'Режим бога', '/gm'], [1, 'Общее', 'Админы онлайн', '/admins'], [1, 'Общее', 'Медиа онлайн', '/medias'], [1, 'Общее', 'Надеть маску', '/clist'],
  [2, 'Наказания', 'Мут', '/mute', '', 'mute'], [2, 'Наказания', 'Деморган', '/jail', '', 'jail'], [2, 'Наказания', 'Мут репорта', '/rmute', '', 'rmute'],
  [2, 'Снятие', 'Снять мут', '/unmute {id}'], [2, 'Снятие', 'Снять деморган', '/unjail {id}'], [2, 'Снятие', 'Снять мут репорта', '/runmute {id}'],
  [2, 'Телепорт', 'К игроку', '/goto {id}', 'tp'], [2, 'Телепорт', 'К метке', '/gotomark'],
  [2, 'Игрок', 'Заспавнить', '/spawn {id}', 'spawn'], [2, 'Игрок', 'Заморозить', '/freeze {id}'], [2, 'Игрок', 'Разморозить', '/unfreeze {id}'], [2, 'Игрок', 'Подбросить', '/slap {id}'], [2, 'Игрок', 'Кикнуть (причина вручную)', '/kick {id} '],
  [2, 'Наблюдение', 'Статистика', '/stats {id}'], [2, 'Наблюдение', 'Оружие', '/weap {id}'], [2, 'Наблюдение', 'Навыки', '/skills {id}'], [2, 'Наблюдение', 'Входы/выходы', '/connect'],
  [2, 'Общее', 'Поставить метку', '/mark'], [2, 'Общее', 'Лидеры', '/leaders'],
  [3, 'Наказания', 'Бан', '/ban', '', 'ban'], [3, 'Наказания', 'Варн', '/warn', '', 'warn'],
  [3, 'Снятие', 'Снять варн (причина вручную)', '/unwarn {id} '], [3, 'Снятие', 'Снять наручники', '/uncuff {id}'], [3, 'Снятие', 'Снять мешок', '/untie {id}'],
  [3, 'Телепорт', 'К себе', '/gethere {id}'],
  [3, 'Общее', 'ХП вокруг', '/hpall'], [3, 'Общее', 'Оружие у всех (100 м)', '/weapall'], [3, 'Общее', 'Одобрить гос. волну', '/acceptgnews'], [3, 'Общее', 'Список фракции', '/find'], [3, 'Общее', 'Транспорт по параметрам', '/veh_find'],
  [4, 'Наказания', 'Бан без объявления', '/sban', '', 'sban'],
  [4, 'Снятие', 'Разбан', '/unban {id}'], [4, 'Игрок', 'Бронежилет 100', '/arm {id} 100']
];
/* команды, которые по умолчанию показываются во всплывающем окне слежки */
const DEF_SP = ['/spoff', '/stats {id}', '/weap {id}', '/getinfo {t}', '/fixcar {id}', '/flip {id}', '/hp {id} 100', '/freeze {id}', '/unfreeze {id}', '/spawn {id}'];
const DEF_CMDS = () => DEF_CMDS_RAW.map((c, i) => ({ id: 'c' + i, lv: c[0], cat: c[1], l: c[2], t: c[3], rp: c[4] || '', pun: c[5] || '', sp: DEF_SP.includes(c[3]) ? 1 : 0 }));
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
  lvl: 3, auto: false, autoReply: true, kbh: 40, kbt: 36, gap: 500, aTpl: '/a {who} нужна помощь: {text}', reqTpl: '/a {cmd}', admNick: '', lang: 'rus+eng', scan: 350,
  cscale: 100, fb: 5, strictOn: false, forms: true, oneOn: false, pauseS: 0, pm: '15,20,30', pd: '5,7,10', others: [],
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
const DEF_BTNS = () => ({
  rep: [
    { id: 'rb1', l: 'Слежка', s: ['@ans:sp', '/sp {id}'], c: 'ac' },
    { id: 'rb2', l: '→ ТП', s: ['@ans:tp', '/goto {id}'], c: '' },
    { id: 'rb3', l: 'Спавн', s: ['@ans:spawn', '/spawn {id}'], c: '' },
    { id: 'rb4', l: 'На помощь', s: ['@help', '@ans Ваш вопрос передан администрации, ожидайте.'], c: 'wr' }
  ],
  spec: [
    { id: 'sb1', l: 'Выйти', s: ['/spoff'], c: 'er' },
    { id: 'sb2', l: 'Стата', s: ['/stats {id}'], c: '' },
    { id: 'sb3', l: 'Оружие', s: ['/weap {id}'], c: '' },
    { id: 'sb4', l: 'Починить+перев.', s: ['/fixcar {id}', '/flip {id}'], c: '' },
    { id: 'sb5', l: 'ХП 100', s: ['/hp {id} 100'], c: '' },
    { id: 'sb6', l: 'Заморозить', s: ['/freeze {id}'], c: '' },
    { id: 'sb7', l: 'На помощь', s: ['@help'], c: 'wr' }
  ]
});
function normBtns(o) {
  const d = DEF_BTNS();
  if (!o || typeof o !== 'object') return d;
  const fix = (a, def) => Array.isArray(a) ? a.filter(b => b && b.l).map(b => ({ id: b.id || uid('b'), l: String(b.l), s: Array.isArray(b.s) ? b.s.map(String).filter(Boolean) : [], c: b.c || '' })) : def;
  return { rep: fix(o.rep, d.rep), spec: fix(o.spec, d.spec) }
}

/* быстрые ответы на репорт: [ключ, подпись, текст, категория] */
const SN = [
  ['ож', 'Ожидайте', 'Приветствую, ожидайте.', 'Общее'], ['пр', 'Проверю', 'Приветствую, проверю вашу ситуацию.', 'Общее'], ['уч', 'Уточните', 'Приветствую! Уточните, пожалуйста, вопрос подробнее.', 'Общее'], ['дк', 'Нужны доказательства', 'Приветствую! Пришлите, пожалуйста, доказательства (видео или скриншоты).', 'Общее'], ['ид', 'Нужен ID', 'Приветствую! Укажите, пожалуйста, ID игрока и опишите ситуацию.', 'Общее'], ['вр', 'Займёт время', 'Приветствую. Проверка займёт некоторое время, благодарю за ожидание.', 'Общее'], ['сп', 'Спасибо', 'Благодарю за обращение! Приятной игры.', 'Общее'], ['пи', 'Приятной игры', 'Приятной игры!', 'Общее'],
  ['вст', 'Набор во фракцию', 'Приветствую! Набор во фракции объявляется в игровом чате и официальных источниках проекта.', 'Вопросы'], ['тп', 'Просит ТП/валюту', 'Приветствую. Телепорт, валюта, уровни и имущество через репорт не выдаются (п. 3.1).', 'Вопросы'], ['пот', 'Потерял имущество', 'Приветствую. Администрация не восстанавливает потерянное, украденное или переданное имущество (п. 1.4).', 'Вопросы'], ['уд', 'Удалил аккаунт', 'Приветствую. Удаление аккаунта необратимо, восстановить его невозможно (п. 1.7).', 'Вопросы'], ['пер', 'Между серверами', 'Приветствую. Перемещение имущества и валюты между серверами запрещено (п. 4.7).', 'Вопросы'], ['дон', 'Сделки за реал', 'Приветствую. Сделки с игровым имуществом и валютой за реальные деньги запрещены (п. 4.4).', 'Вопросы'], ['лим', 'Лимит передачи', 'Приветствую. Безвозмездно можно передавать не более 30.000.000 рублей в сутки (п. 4.5).', 'Вопросы'], ['бизн', 'Один бизнес', 'Приветствую. На одном сервере можно владеть только одним бизнесом (п. 4.3).', 'Вопросы'], ['ник', 'Про никнейм', 'Приветствую. Никнейм не должен быть схож с существующими, администрации и медиа-партнёров и не должен содержать оскорблений (п. 1.6).', 'Вопросы'], ['багр', 'О баге', 'Приветствую. О баге сообщите через официальные ресурсы проекта с описанием и доказательствами. Использовать баги в своих целях запрещено (п. 5.2).', 'Вопросы'], ['прав', 'Про правила', 'Приветствую. С правилами проекта можно ознакомиться на официальных ресурсах. Незнание правил не освобождает от ответственности (п. 1.1).', 'Вопросы'],
  ['нач', 'Начал проверку', 'Приветствую, приступил к проверке нарушителя.', 'Нарушения'], ['офф', 'Игрок не в сети', 'Приветствую. Игрок не в сети, рассмотрим вашу жалобу по предоставленным доказательствам.', 'Нарушения'], ['нд', 'Нет доказательств', 'Приветствую. Без доказательств наказать игрока не могу. Пришлите видео или скриншоты.', 'Нарушения'], ['пов', 'Передал старшим', 'Приветствую. Ваша ситуация передана старшей администрации, ожидайте.', 'Нарушения'], ['рп', 'Это RP-ситуация', 'Приветствую. Данная ситуация является частью RolePlay процесса, нарушений нет.', 'Нарушения'],
  ['вн', 'Наказан', 'Нарушитель наказан. Приятной игры!', 'Итог'], ['нн', 'Нарушений нет', 'Приветствую, нарушений не обнаружено. Приятной игры!', 'Итог'], ['реш', 'Решено', 'Приветствую, ваш вопрос решён. Приятной игры!', 'Итог'], ['ждк', 'Ответ в чате', 'Приветствую, посмотрите игровой чат — я ответил.', 'Итог'], ['жал', 'Жалоба на наказание', 'Приветствую. Если не согласны с наказанием, подайте жалобу в соответствующем разделе в течение 2 дней с момента выдачи (п. 3.4).', 'Итог'],
  ['флуд', 'Не флудить', 'Приветствую. Репорт предназначен только для вопросов и жалоб, прошу не флудить (п. 3.1).', 'Отказ'], ['груб', 'Без грубости', 'Приветствую. Прошу общаться вежливо, хамство в сторону администрации наказуемо (п. 3.2).', 'Отказ'], ['оспор', 'Не оспаривать', 'Приветствую. Публичное оспаривание действий администрации запрещено, подайте жалобу в соответствующем разделе (п. 3.4).', 'Отказ'], ['обм', 'Обман не вернём', 'Приветствую. Администрация не возвращает имущество, переданное добровольно, даже при мошенничестве (п. 1.4).', 'Отказ'], ['нельзя', 'Не могу помочь', 'Приветствую. К сожалению, помочь с этим не могу.', 'Отказ']
];
const DEF_ANS = () => SN.map(x => ({ k: x[0], l: x[1], t: x[2], c: x[3] }));
const ansCats = list => [...new Set(list.map(a => a.c))];
function normAns(list) { return Array.isArray(list) ? list.map(a => ({ k: a.k || uid('a'), l: a.l || '', t: a.t || '', c: a.c || 'Общее' })) : [] }

/* какие команды из админ-чата показывать как запрос (минимальный уровень) */
const LV = { id: 1, dl: 1, a: 1, admins: 1, medias: 1, arep: 1, ans: 1, pos: 1, setpass: 1, az: 1, gm: 1, sp: 1, spoff: 1, hp: 1, alic: 1, fixcar: 1, flip: 1, clist: 1, getinfo: 1, spawn: 2, freeze: 2, tp: 2, slap: 2, kick: 2, mute: 2, unmute: 2, offmute: 2, offunmute: 2, jail: 2, unjail: 2, offjail: 2, offunjail: 2, rmute: 2, runmute: 2, weap: 2, stats: 2, connect: 2, mark: 2, gotomark: 2, goto: 2, gotocar: 2, getcar: 2, skills: 2, pay: 2, unfreeze: 2, leaders: 2, ban: 3, offwarn: 3, offunwarn: 3, skick: 3, veh_find: 3, warn: 3, unwarn: 3, gethere: 3, hpall: 3, weapall: 3, uncuff: 3, untie: 3, acceptgnews: 3, find: 3, f: 3, r: 3, d: 3, spcar: 3, spcars: 3, sban: 4, unban: 4, offban: 4, house: 4, biz: 4, okay: 4, no: 4, setskin: 4, setfuel: 4, getname: 4, history: 4, rgun: 4, lip: 4, arm: 4 };
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
function compose(r, o) {
  let c = o.cmd || r.cmd; if (o.off && OFF[c]) c = OFF[c];
  const id = o.id || '{ID}', reason = (o.reason || r.n || '').trim(), t = o.time;
  const mk = cm => cm.endsWith('warn') ? `/${cm} ${id} ${reason}` : `/${cm} ${id} ${t || '{срок}'} ${reason}`;
  const lines = [mk(c)], warns = [];
  if (r.x === 'warn') lines.push(`/${o.off ? 'offwarn' : 'warn'} ${id} ${reason}`);
  const need = Math.max(...lines.map(l => cmdLevel(l)));
  if ((o.lvl || 1) < need) warns.push(`Нужен ${need} ур. — уйдёт запросом в админ-чат`);
  const n = +t;
  if (n && (o.cmd || r.cmd) === r.cmd && unitOf(c)) {
    if (r.b && n > r.b) warns.push(`Больше максимума (${r.b}) — только при отягчающих обстоятельствах`);
    if (r.a && n < r.a) warns.push(`Меньше минимума (${r.a})`);
  }
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
if (typeof module !== 'undefined') module.exports = { THEMES, DEF_BTNS, normBtns, tplUses, fillTpl, TPL_RE, parseNums, presets, timeChips, timeSets, mainTimes, toggleMain, smartTime, outRange, PUNRE, compose, matchReasons, parseChat, idsIn, adminCmd, DEF_REASONS, DEF_RULES, DEF_CMDS, DEF_CFG, DEF_ANS, normModel, loadModel, loadCmds, normCmds, normAns, parsePoints, parseTarget, helpMsg, reqWrap, admSig, signReq, moveIn, catsOf, ansCats, cmdLevel, rng, unitOf, CATS, SN, LV, uid };
