'use strict';
/* Branch. — front end. Plain JavaScript, no frameworks, no CDN. */

const state = { me: null, products: [], penaltyBp: 10 };
let navId = 0;

// ---------- helpers ----------
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const inr = p => { const n = p / 100; return '₹' + n.toLocaleString('en-IN', { minimumFractionDigits: Number.isInteger(n) ? 0 : 2, maximumFractionDigits: 2 }); };
const compact = p => { const n = p / 100; return n >= 1e7 ? '₹' + (n / 1e7).toFixed(1) + ' Cr' : n >= 1e5 ? '₹' + (n / 1e5).toFixed(1) + ' L' : n >= 1e3 ? '₹' + (n / 1e3).toFixed(1) + 'k' : '₹' + Math.round(n); };
const fdate = iso => { if (!iso) return '—'; const [y, m, d] = iso.split('-').map(Number); return new Date(y, m - 1, d).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }); };
const todayISO = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
const initials = n => n.split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase();
const pct = bp => (bp / 100).toFixed(bp % 100 ? 2 : 0).replace(/(\.\d)0$/, '$1') + '%';
const el = html => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };

const PILL = { ACTIVE: ['green', 'On track'], OVERDUE: ['red', 'Overdue'], CLOSED: ['gray', 'Closed'], PAID: ['green', 'Paid'], DUE: ['orange', 'Due today'], UPCOMING: ['gray', 'Upcoming'] };
const pill = s => { const [c, t] = PILL[s] || ['gray', s]; return `<span class="pill ${c}">${t}</span>`; };

async function api(path, { method = 'GET', body } = {}) {
  const r = await fetch('/api' + path, {
    method, credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'branch' },
    body: body ? JSON.stringify(body) : undefined
  });
  const data = await r.json().catch(() => ({}));
  if (r.status === 401 && state.me) { state.me = null; toast('Your session ended. Please sign in again.'); boot(); throw new Error('Signed out'); }
  if (!r.ok) throw new Error(data.error || 'Something went wrong.');
  return data;
}

function toast(msg) {
  const t = el(`<div class="toast">${esc(msg)}</div>`);
  $('#toasts').append(t); setTimeout(() => t.remove(), 3200);
}

function modal(html, mount) {
  const d = el(`<dialog><div class="dlg">${html}</div></dialog>`);
  document.body.append(d);
  d.addEventListener('click', e => { if (e.target === d) d.close(); });
  d.addEventListener('close', () => d.remove());
  d.showModal();
  d.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => d.close()));
  if (mount) mount(d);
  const first = $('input:not([type=hidden]):not([type=checkbox]),select', d); if (first) first.focus();
  return d;
}

/** Wires a form: shows errors inline, disables the button while saving. */
function submitForm(d, handler) {
  const form = $('form', d), err = $('.err', d), btn = $('button[type=submit]', d);
  form.addEventListener('submit', async e => {
    e.preventDefault(); err.textContent = ''; btn.disabled = true;
    try { await handler(Object.fromEntries(new FormData(form))); }
    catch (ex) { err.textContent = ex.message; btn.disabled = false; }
  });
}

// ---------- boot & public site ----------
async function boot() {
  const st = await api('/status');
  state.products = st.products; state.penaltyBp = st.penalty_bp_per_day;
  if (!st.user) { state.me = null; return publicRoute(); }
  enter(st.user);
}

/** Called once someone is signed in. */
function enter(user) {
  state.me = user;
  if (!/^#\/(borrowers|loans|collections|team)/.test(location.hash)) location.hash = '#/';
  renderShell(); route();
}

function publicRoute() {
  const h = location.hash.replace(/^#\/?/, '');
  if (h === 'login' || h === 'signup') authScreen(h); else landing();
  window.scrollTo(0, 0);
}

const pubNav = () => `
  <header class="pub-nav"><div class="pub-in">
    <a class="brand" href="#/">Branch<span>.</span></a>
    <div class="actions"><a class="link" href="#/login">Log in</a><a class="btn sm" href="#/signup">Get started</a></div>
  </div></header>`;

function landing() {
  const feat = [
    ['Know every borrower', 'Keep each borrower’s details, loans and repayment history in one place.'],
    ['Three loan products', 'Individual, Group and Emergency loans, each with its own interest method.'],
    ['Exact schedules', 'See the EMI, interest and balance for every month before you lend.'],
    ['Collections, simplified', 'A daily list of who to call and who to visit. Record payments in seconds.'],
    ['Overdue, handled', 'Late loans are flagged automatically, with the penalty worked out for you.'],
    ['Private by design', 'Every account is its own workspace. Your data is yours alone, on your own server.']
  ];
  $('#app').innerHTML = `${pubNav()}
  <section class="hero"><div class="pub-in">
    <p class="eyebrow">Loan management for micro-finance</p>
    <h1 class="mega">Lending,<br>made simple.</h1>
    <p class="lede">Branch. helps micro-finance companies issue loans, track every EMI and collect on time. One calm, powerful workspace.</p>
    <div class="actions center"><a class="btn big" href="#/signup">Create your account</a><a class="btn ghost big" href="#/login">Log in</a></div>
    <div class="mock card" aria-hidden="true">
      <div class="grid g4" style="gap:12px">
        <div class="stat"><small>Outstanding</small><div class="v">₹12.4 L</div></div>
        <div class="stat"><small>Collected</small><div class="v">₹8.1 L</div></div>
        <div class="stat bad"><small>Overdue</small><div class="v">₹46k</div></div>
        <div class="stat"><small>At risk</small><div class="v">3.2%</div></div>
      </div>
      <div class="bar" style="margin-top:22px"><i style="width:72%;background:var(--good)"></i><i style="width:8%;background:var(--bad)"></i><i style="width:20%;background:var(--mute)"></i></div>
    </div>
  </div></section>
  <section class="pub-in sect"><h2 class="sect-h">Everything a lender needs. Nothing they don’t.</h2>
    <div class="feat">${feat.map(f => `<div class="card"><h3>${f[0]}</h3><p>${f[1]}</p></div>`).join('')}</div></section>
  <section class="pub-in sect"><h2 class="sect-h">Up and running in minutes</h2>
    <div class="steps"><div><b>1</b><h3>Create your account</h3><p>Sign up with your organisation’s name. Your private workspace is ready instantly.</p></div>
      <div><b>2</b><h3>Add borrowers and loans</h3><p>Choose a loan type, enter the amount and tenure, and see the EMI as you type.</p></div>
      <div><b>3</b><h3>Collect and track</h3><p>Record repayments, print receipts and watch your portfolio health at a glance.</p></div></div>
    <div class="actions center" style="margin-top:44px"><a class="btn big" href="#/signup">Get started. It’s free.</a></div></section>
  <footer class="pub-foot"><div class="pub-in"><span class="brand">Branch<span>.</span></span><small>Micro-finance loan &amp; EMI tracker</small></div></footer>`;
}

function strength(pw) {
  let n = 0;
  if (pw.length >= 8) n++; if (pw.length >= 12) n++;
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) n++;
  if (/\d/.test(pw) && /[^A-Za-z0-9]/.test(pw)) n++;
  return /\d/.test(pw) && /[A-Za-z]/.test(pw) && pw.length >= 8 ? Math.max(n, 1) : 0;
}

function authScreen(kind) {
  const up = kind === 'signup';
  $('#app').innerHTML = `${pubNav()}
  <div class="auth"><div class="box">
    <h1 class="auth-h">${up ? 'Create your account' : 'Welcome back'}</h1>
    <p class="tag">${up ? 'Set up your lending workspace in a minute.' : 'Log in to continue to Branch.'}</p>
    <div class="card"><form novalidate>
      <div class="err"></div>
      ${up ? `<div class="f"><label>Your name</label><input name="name" autocomplete="name" required></div>
      <div class="f"><label>Organisation name</label><input name="org" autocomplete="organization" placeholder="e.g. Sunrise Micro Finance" required></div>` : ''}
      <div class="f"><label>Email</label><input name="email" type="email" autocomplete="${up ? 'email' : 'username'}" autocapitalize="none" required></div>
      <div class="f"><label>Password</label><input name="password" type="password" autocomplete="${up ? 'new-password' : 'current-password'}" required>
        ${up ? '<div class="meter"><i></i><i></i><i></i><i></i></div><div class="hint" id="pwhint">At least 8 characters, with a letter and a number.</div>' : ''}</div>
      ${up ? '<div class="f"><label>Confirm password</label><input name="again" type="password" autocomplete="new-password" required></div>' : ''}
      <button class="btn" type="submit" style="width:100%;margin-top:6px">${up ? 'Create account' : 'Log in'}</button>
    </form></div>
    <p class="switch">${up ? 'Already have an account? <a href="#/login">Log in</a>' : 'New to Branch.? <a href="#/signup">Create an account</a>'}</p>
    ${up ? '' : '<p class="switch mute" style="margin-top:4px"><small>Forgot your password? Ask your administrator to reset it.</small></p>'}
  </div></div>`;
  const form = $('form'), err = $('.err'), btn = $('button', form);
  if (up) form.elements.password.addEventListener('input', () => {
    const n = strength(form.elements.password.value);
    $$('.meter i', form).forEach((b, i) => b.className = i < n ? 'on s' + n : '');
  });
  form.addEventListener('submit', async e => {
    e.preventDefault(); err.textContent = '';
    const f = Object.fromEntries(new FormData(form));
    if (up && f.password !== f.again) { err.textContent = 'The two passwords don’t match.'; return; }
    btn.disabled = true;
    try {
      const r = await api(up ? '/signup' : '/login', { method: 'POST', body: f });
      location.hash = '#/'; enter(r.user);
      if (up) toast('Welcome to Branch.');
    } catch (ex) { err.textContent = ex.message; btn.disabled = false; }
  });
  $('input', form).focus();
}

const ICONS = {
  home: '<path d="M3 11l9-8 9 8"/><path d="M5 10v10h14V10"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.5-3.5 3-5.5 6.5-5.5s6 2 6.5 5.5"/><path d="M16 4.6a3.5 3.5 0 010 6.8M18 14.8c2 .6 3.2 2.3 3.5 5.2"/>',
  loans: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3 10h18M7 15h4"/>',
  collect: '<path d="M12 3v12m0 0l-4-4m4 4l4-4"/><path d="M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2"/>',
  team: '<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6l8-3z"/>'
};
const nav = (href, icon, label) => `<a href="#/${href}" data-nav="${href}"><svg viewBox="0 0 24 24">${ICONS[icon]}</svg>${label}</a>`;

function renderShell() {
  const m = state.me;
  $('#app').innerHTML = `
  <div class="shell">
    <aside class="side">
      <div><a class="brand" href="#/">Branch<span>.</span></a><div class="org">${esc(m.org)}</div></div>
      <nav>
        ${nav('', 'home', 'Overview')}${nav('borrowers', 'users', 'Borrowers')}${nav('loans', 'loans', 'Loans')}${nav('collections', 'collect', 'Collections')}
        ${m.role === 'ADMIN' ? nav('team', 'team', 'Team') : ''}
      </nav>
      <details class="me"><summary><div class="avatar">${esc(initials(m.name))}</div><div><b>${esc(m.name)}</b><small>${m.role === 'ADMIN' ? 'Administrator' : 'Loan officer'}</small></div></summary>
        <div class="menu"><button data-act="password">Change password</button><button data-act="logout">Sign out</button></div></details>
    </aside>
    <main id="view"></main>
  </div>`;
}

// ---------- routing ----------
window.addEventListener('hashchange', () => (state.me ? route() : publicRoute()));

function route() {
  const [a, b] = location.hash.replace(/^#\/?/, '').split('/');
  const key = a || '';
  $$('[data-nav]').forEach(n => n.classList.toggle('on', n.dataset.nav === key));
  const view = {
    '': overview, borrowers: b ? () => borrowerDetail(b) : borrowers, loans: b ? () => loanDetail(b) : loans,
    collections, team
  }[key] || overview;
  show(view);
}

async function show(fn) {
  const id = ++navId, v = $('#view');
  v.innerHTML = '<div class="loading"></div>'; window.scrollTo(0, 0);
  try {
    const out = await fn();
    if (id !== navId) return;
    v.innerHTML = out.html; if (out.mount) out.mount(v);
  } catch (e) {
    if (id === navId) v.innerHTML = `<div class="card empty"><b>We couldn’t load this.</b>${esc(e.message)}<br><br><a class="btn ghost" href="#/">Back to overview</a></div>`;
  }
}
const refresh = () => route();

// ---------- global clicks ----------
document.addEventListener('click', e => {
  const row = e.target.closest('[data-href]');
  if (row && !e.target.closest('a,button')) location.hash = row.dataset.href;
  const act = e.target.closest('[data-act]')?.dataset.act;
  if (act === 'logout') api('/logout', { method: 'POST' }).finally(() => { state.me = null; location.hash = '#/'; publicRoute(); });
  if (act === 'password') passwordModal();
  if (!e.target.closest('.me')) $('.me')?.removeAttribute('open');
});

// ---------- overview ----------
async function overview() {
  const d = await api('/dashboard');
  const hr = new Date().getHours(), greet = hr < 12 ? 'Good morning' : hr < 18 ? 'Good afternoon' : 'Good evening';
  const max = Math.max(...d.months.map(m => m.amount), 1);
  const bars = d.months.map((m, i) => {
    const h = Math.max(3, m.amount / max * 120), x = 18 + i * 82;
    return `<rect x="${x}" y="${150 - h}" width="48" height="${h}" rx="10" fill="var(--accent)" opacity="${i === 5 ? 1 : .35}"/>
      <text x="${x + 24}" y="${150 - h - 8}" text-anchor="middle" font-size="12" font-weight="600" fill="currentColor">${compact(m.amount)}</text>
      <text x="${x + 24}" y="172" text-anchor="middle" font-size="12" fill="var(--mute)">${esc(m.label)}</text>`;
  }).join('');
  const tot = Math.max(d.loans, 1), c = d.counts;
  const seg = (n, col) => n ? `<i style="width:${n / tot * 100}%;background:${col}"></i>` : '';
  const attention = d.attention.length ? d.attention.map(l => `
    <div class="item" data-href="#/loans/${l.id}"><div><b>${esc(l.borrower_name)}</b><br><small>${esc(l.code)} · ${l.days_overdue} days late</small></div>
    <b style="color:var(--bad)" class="num">${inr(l.overdue)}</b></div>`).join('')
    : '<div class="empty"><b>All clear.</b>No overdue loans.</div>';
  const recent = d.recent.length ? d.recent.map(p => `
    <div class="item" data-href="#/loans/${p.loan_id}"><div><b>${esc(p.borrower_name)}</b><br><small>${fdate(p.paid_on)} · ${esc(p.method)}</small></div>
    <b class="num">${inr(p.amount)}</b></div>`).join('') : '<div class="empty">No collections yet.</div>';
  return {
    html: `
    <div class="head"><div><h1>${greet}, ${esc(state.me.name.split(' ')[0])}.</h1><p>Here’s where your portfolio stands today.</p></div>
      <div class="actions"><button class="btn" data-act2="new-loan">New loan</button></div></div>
    <div class="grid g4">
      <div class="card stat"><small>Outstanding</small><div class="v num">${compact(d.outstanding)}</div><div class="s">across ${c.ACTIVE + c.OVERDUE} open loans</div></div>
      <div class="card stat"><small>Collected</small><div class="v num">${compact(d.collected)}</div><div class="s">${inr(d.due_this_week)} due this week</div></div>
      <div class="card stat ${d.overdue ? 'bad' : ''}"><small>Overdue</small><div class="v num">${compact(d.overdue)}</div><div class="s">+ ${inr(d.penalty)} penalty accrued</div></div>
      <div class="card stat"><small>Portfolio at risk</small><div class="v num">${d.par30}%</div><div class="s">loans over 30 days late</div></div>
    </div>
    <div class="grid g2 gap">
      <div class="card"><h2>Collections</h2><svg viewBox="0 0 520 184" width="100%" role="img" aria-label="Collections over the last six months">${bars}</svg></div>
      <div class="card"><h2>Portfolio health</h2>
        <div class="bar">${seg(c.ACTIVE, 'var(--good)')}${seg(c.OVERDUE, 'var(--bad)')}${seg(c.CLOSED, 'var(--mute)')}</div>
        <div class="legend"><span><i style="background:var(--good)"></i>${c.ACTIVE} on track</span><span><i style="background:var(--bad)"></i>${c.OVERDUE} overdue</span><span><i style="background:var(--mute)"></i>${c.CLOSED} closed</span></div>
        <div style="margin-top:22px;display:flex;gap:28px"><div><small>Borrowers</small><div class="v" style="font-size:24px;font-weight:700">${d.borrowers}</div></div><div><small>Disbursed</small><div style="font-size:24px;font-weight:700">${compact(d.disbursed)}</div></div></div>
      </div>
    </div>
    <div class="grid g2 gap" style="grid-template-columns:1fr 1fr">
      <div class="card list"><h2>Needs attention</h2>${attention}</div>
      <div class="card list"><h2>Recent collections</h2>${recent}</div>
    </div>`,
    mount: v => $('[data-act2="new-loan"]', v).addEventListener('click', () => loanModal())
  };
}

// ---------- borrowers ----------
async function borrowers() {
  return {
    html: `<div class="head"><div><h1>Borrowers</h1><p>The people you lend to.</p></div>
      <div class="actions"><input class="search" id="q" placeholder="Search name, phone or ID" type="search"><button class="btn" id="add">New borrower</button></div></div>
      <div class="card"><div id="list"></div></div>`,
    mount: v => {
      let timer;
      const load = async () => {
        const rows = await api('/borrowers?q=' + encodeURIComponent($('#q').value));
        $('#list').innerHTML = rows.length ? `<div class="scroll"><table class="tbl"><thead><tr><th>Borrower</th><th>Phone</th><th>Occupation</th><th class="r">Loans</th></tr></thead><tbody>
          ${rows.map(b => `<tr data-href="#/borrowers/${b.id}"><td><b>${esc(b.name)}</b><br><small>${esc(b.code)}</small></td><td class="num">${esc(b.phone)}</td><td>${esc(b.occupation || '—')}</td><td class="r">${b.loans}</td></tr>`).join('')}
          </tbody></table></div>` : `<div class="empty"><b>No borrowers found.</b>Add your first borrower to get started.</div>`;
      };
      $('#q').addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 220); });
      $('#add').addEventListener('click', () => borrowerModal());
      load().catch(e => toast(e.message));
    }
  };
}

function borrowerModal(b) {
  const edit = !!b; b = b || {};
  const d = modal(`<h2>${edit ? 'Edit borrower' : 'New borrower'}</h2><form><div class="err"></div>
    <div class="f"><label>Full name</label><input name="name" required value="${esc(b.name)}"></div>
    <div class="row"><div class="f"><label>Mobile number</label><input name="phone" inputmode="numeric" maxlength="10" pattern="[6-9][0-9]{9}" title="10-digit mobile number" required value="${esc(b.phone)}"></div>
    <div class="f"><label>Occupation</label><input name="occupation" value="${esc(b.occupation)}"></div></div>
    <div class="f"><label>Address</label><input name="address" required value="${esc(b.address)}"></div>
    <div class="f"><label>ID proof (last digits)</label><input name="id_proof" value="${esc(b.id_proof)}"></div>
    <div class="foot"><button type="button" class="btn ghost" data-close>Cancel</button><button class="btn" type="submit">${edit ? 'Save' : 'Add borrower'}</button></div></form>`);
  submitForm(d, async f => {
    const r = await api(edit ? '/borrowers/' + b.id : '/borrowers', { method: edit ? 'PUT' : 'POST', body: f });
    d.close(); toast(edit ? 'Saved.' : 'Borrower added.');
    if (edit) refresh(); else location.hash = '#/borrowers/' + r.id;
  });
}

async function borrowerDetail(id) {
  const b = await api('/borrowers/' + id);
  return {
    html: `<a class="back" href="#/borrowers">‹ Borrowers</a>
      <div class="head"><div><h1>${esc(b.name)}</h1><p>${esc(b.code)} · ${esc(b.occupation || 'No occupation listed')}</p></div>
        <div class="actions"><button class="btn ghost" id="edit">Edit</button><button class="btn" id="loan">New loan</button></div></div>
      <div class="card"><div class="kv"><div><small>Phone</small><b><a href="tel:${esc(b.phone)}">${esc(b.phone)}</a></b></div>
        <div><small>Address</small><b>${esc(b.address)}</b></div><div><small>ID proof</small><b>${esc(b.id_proof || '—')}</b></div><div><small>Customer since</small><b>${fdate(b.created_at.slice(0, 10))}</b></div></div></div>
      <div class="card gap"><h2>Loans</h2>${loanTable(b.loans, false)}</div>`,
    mount: v => { $('#edit', v).addEventListener('click', () => borrowerModal(b)); $('#loan', v).addEventListener('click', () => loanModal(b)); }
  };
}

// ---------- loans ----------
function loanTable(rows, showBorrower = true) {
  if (!rows.length) return `<div class="empty"><b>No loans yet.</b>Loans you issue will appear here.</div>`;
  return `<div class="scroll"><table class="tbl"><thead><tr><th>Loan</th>${showBorrower ? '<th>Borrower</th>' : ''}<th class="r">Principal</th><th class="r">EMI</th><th class="r">Outstanding</th><th>Next due</th><th>Status</th></tr></thead><tbody>
    ${rows.map(l => `<tr data-href="#/loans/${l.id}"><td><b>${esc(l.code)}</b><br><small>${esc(l.product_label)} · ${pct(l.rate_bp)}</small></td>
      ${showBorrower ? `<td>${esc(l.borrower_name)}</td>` : ''}<td class="r num">${inr(l.principal)}</td><td class="r num">${inr(l.emi)}</td>
      <td class="r num"><b>${inr(l.outstanding)}</b></td><td>${l.status === 'CLOSED' ? '—' : fdate(l.next_due)}</td><td>${pill(l.status)}</td></tr>`).join('')}
    </tbody></table></div>`;
}

async function loans() {
  let status = '';
  return {
    html: `<div class="head"><div><h1>Loans</h1><p>Every loan, at a glance.</p></div>
      <div class="actions"><input class="search" id="q" placeholder="Search loan, name or phone" type="search"><a class="btn ghost" href="/api/export/loans.csv">Export</a><button class="btn" id="new">New loan</button></div></div>
      <div style="margin-bottom:16px" class="seg" id="seg"><button data-s="" class="on">All</button><button data-s="ACTIVE">On track</button><button data-s="OVERDUE">Overdue</button><button data-s="CLOSED">Closed</button></div>
      <div class="card"><div id="list"></div></div>`,
    mount: v => {
      let timer;
      const load = async () => { $('#list').innerHTML = loanTable(await api(`/loans?status=${status}&q=${encodeURIComponent($('#q').value)}`)); };
      $('#q').addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 220); });
      $('#seg').addEventListener('click', e => { const b = e.target.closest('button'); if (!b) return; status = b.dataset.s; $$('#seg button').forEach(x => x.classList.toggle('on', x === b)); load(); });
      $('#new').addEventListener('click', () => loanModal());
      load().catch(e => toast(e.message));
    }
  };
}

async function loanDetail(id) {
  const L = await api('/loans/' + id), done = L.total_payable ? Math.min(100, (L.total_payable - L.outstanding) / L.total_payable * 100) : 0;
  const sched = L.schedule.map(i => `<tr><td>${i.no}</td><td>${fdate(i.due_date)}</td><td class="r num">${inr(i.emi)}</td><td class="r num">${inr(i.principal)}</td><td class="r num">${inr(i.interest)}</td>
    <td class="r num">${inr(i.paid)}</td><td>${pill(i.status)}${i.penalty ? `<br><small style="color:var(--bad)">+${inr(i.penalty)} penalty</small>` : ''}</td></tr>`).join('');
  const pays = L.payments.length ? `<div class="scroll"><table class="tbl"><thead><tr><th>Receipt</th><th>Date</th><th>Method</th><th class="r">Amount</th><th>Received by</th><th></th></tr></thead><tbody>
    ${L.payments.map((p, i) => `<tr><td>${esc(p.receipt_no)}</td><td>${fdate(p.paid_on)}</td><td>${esc(p.method)}</td><td class="r num"><b>${inr(p.amount)}</b></td><td>${esc(p.received_by || '—')}</td><td class="r"><button class="btn ghost sm" data-rc="${i}">Receipt</button></td></tr>`).join('')}</tbody></table></div>`
    : '<div class="empty">No payments received yet.</div>';
  return {
    html: `<a class="back" href="#/loans">‹ Loans</a>
      <div class="head"><div><h1>${esc(L.code)} ${pill(L.status)}</h1><p><a href="#/borrowers/${L.borrower_id}">${esc(L.borrower_name)}</a> · ${esc(L.product_label)} loan · issued ${fdate(L.start_date)} by ${esc(L.issued_by || '—')}</p></div>
        <div class="actions">${L.status === 'CLOSED' ? '' : '<button class="btn" id="pay">Collect payment</button>'}</div></div>
      <div class="card"><div class="kv">
        <div><small>Principal</small><b class="num">${inr(L.principal)}</b></div><div><small>Rate</small><b>${pct(L.rate_bp)} a year</b></div>
        <div><small>EMI</small><b class="num">${inr(L.emi)} × ${L.months}</b></div><div><small>Total payable</small><b class="num">${inr(L.total_payable)}</b></div>
        <div><small>Outstanding</small><b class="num">${inr(L.outstanding)}</b></div>
        <div><small>${L.status === 'OVERDUE' ? 'Overdue' : 'Next due'}</small><b class="num" style="${L.status === 'OVERDUE' ? 'color:var(--bad)' : ''}">${L.status === 'CLOSED' ? '—' : L.status === 'OVERDUE' ? inr(L.overdue) : fdate(L.next_due)}</b></div></div>
        <div class="bar" style="margin-top:22px"><i style="width:${done}%;background:var(--accent)"></i></div>
        <div class="hint">${done.toFixed(0)}% repaid${L.penalty ? ` · <span style="color:var(--bad)">${inr(L.penalty)} penalty accrued (${(state.penaltyBp / 100).toFixed(2)}% of the unpaid EMI per day)</span>` : ''}</div></div>
      <div class="card gap"><h2>Repayment schedule</h2><div class="scroll"><table class="tbl"><thead><tr><th>#</th><th>Due</th><th class="r">EMI</th><th class="r">Principal</th><th class="r">Interest</th><th class="r">Paid</th><th>Status</th></tr></thead><tbody>${sched}</tbody></table></div></div>
      <div class="card gap"><h2>Payments</h2>${pays}</div>`,
    mount: v => {
      $('#pay', v)?.addEventListener('click', () => paymentModal(L));
      $$('[data-rc]', v).forEach(b => b.addEventListener('click', () => { const p = L.payments[b.dataset.rc]; receiptModal({ ...p, loan_code: L.code, borrower_name: L.borrower_name }, false); }));
    }
  };
}

async function loanModal(borrower) {
  const list = await api('/borrowers');
  if (!list.length) { toast('Add a borrower first.'); return borrowerModal(); }
  const label = b => `${b.code} · ${b.name}`;
  const prods = state.products;
  const d = modal(`<h2>New loan</h2><form><div class="err"></div>
    <div class="f"><label>Borrower</label><input name="borrower" list="bl" placeholder="Start typing a name…" required autocomplete="off" value="${borrower ? esc(label(borrower)) : ''}">
      <datalist id="bl">${list.map(b => `<option value="${esc(label(b))}">`).join('')}</datalist></div>
    <div class="f"><label>Loan type</label><div class="choice">${prods.map((p, i) => `<label><input type="radio" name="product" value="${p.key}" ${i === 0 ? 'checked' : ''}><span><b>${esc(p.label)}</b><small>${pct(p.rate_bp)}</small></span></label>`).join('')}</div>
      <div class="hint" id="blurb"></div></div>
    <div class="row"><div class="f"><label>Amount (₹)</label><input name="amount" type="number" min="1000" max="500000" step="100" required placeholder="50000"></div>
    <div class="f"><label>Tenure (months)</label><input name="months" type="number" min="1" max="60" value="12" required></div></div>
    <div class="row"><div class="f"><label>Disbursed on</label><input name="start_date" type="date" max="${todayISO()}" value="${todayISO()}" required></div>
    <div class="f"><label>Purpose</label><input name="purpose" placeholder="Optional"></div></div>
    <div class="preview" id="pv"><div style="grid-column:1/-1" class="mute">Enter an amount to see the EMI.</div></div>
    <div class="foot"><button type="button" class="btn ghost" data-close>Cancel</button><button class="btn" type="submit" disabled>Issue loan</button></div></form>`);
  const form = $('form', d), btn = $('button[type=submit]', d);
  const prod = () => prods.find(p => p.key === form.elements.product.value);
  let timer, seq = 0;
  const update = () => {
    const p = prod(); $('#blurb', d).textContent = p.blurb; form.elements.months.max = p.max_months;
    clearTimeout(timer); btn.disabled = true;
    timer = setTimeout(async () => {
      const my = ++seq, amount = +form.elements.amount.value, months = +form.elements.months.value;
      if (!amount || !months) return;
      try {
        const r = await api('/loans/preview', { method: 'POST', body: { product: p.key, amount, months, start_date: form.elements.start_date.value || todayISO() } });
        if (my !== seq) return;
        $('#pv', d).innerHTML = `<div><small>Monthly EMI</small><b class="num">${inr(r.emi)}</b></div><div><small>Total interest</small><b class="num">${inr(r.total_interest)}</b></div><div><small>Total payable</small><b class="num">${inr(r.total_payable)}</b></div>`;
        $('.err', d).textContent = ''; btn.disabled = false;
      } catch (e) { if (my === seq) { $('.err', d).textContent = e.message; $('#pv', d).innerHTML = '<div style="grid-column:1/-1" class="mute">—</div>'; } }
    }, 250);
  };
  form.addEventListener('input', update); update();
  submitForm(d, async f => {
    const b = list.find(x => label(x) === f.borrower);
    if (!b) throw new Error('Choose a borrower from the list.');
    const r = await api('/loans', { method: 'POST', body: { borrower_id: b.id, product: f.product, amount: +f.amount, months: +f.months, start_date: f.start_date, purpose: f.purpose } });
    d.close(); toast('Loan issued.'); location.hash = '#/loans/' + r.id;
  });
}

// ---------- payments ----------
function paymentModal(L, onDone) {
  const quick = [];
  if (L.overdue > 0) quick.push(['Overdue ' + inr(L.overdue), L.overdue]);
  if (L.next_amount) quick.push(['Next EMI ' + inr(L.next_amount), L.next_amount]);
  quick.push(['Settle in full ' + inr(L.outstanding), L.outstanding]);
  const first = (L.overdue > 0 ? L.overdue : L.next_amount || L.outstanding) / 100;
  const d = modal(`<h2>Collect payment</h2><p class="mute" style="margin:-12px 0 18px">${esc(L.borrower_name)} · ${esc(L.code)} · outstanding ${inr(L.outstanding)}</p>
    <form><div class="err"></div>
    <div class="f"><label>Amount (₹)</label><input name="amount" type="number" step="0.01" min="0.01" max="${L.outstanding / 100}" required value="${first}">
      <div class="chips">${quick.map(([t, v]) => `<button type="button" class="chip" data-v="${v / 100}">${t}</button>`).join('')}</div></div>
    <div class="f"><label>Received by</label><div class="choice"><label><input type="radio" name="method" value="CASH" checked><span><b>Cash</b></span></label><label><input type="radio" name="method" value="UPI"><span><b>UPI</b></span></label><label><input type="radio" name="method" value="BANK"><span><b>Bank</b></span></label></div></div>
    <div class="row"><div class="f"><label>Date</label><input name="paid_on" type="date" min="${L.start_date}" max="${todayISO()}" value="${todayISO()}" required></div><div class="f"><label>Note</label><input name="note" placeholder="Optional"></div></div>
    <div class="foot"><button type="button" class="btn ghost" data-close>Cancel</button><button class="btn" type="submit">Record payment</button></div></form>`);
  $$('.chip', d).forEach(c => c.addEventListener('click', () => { $('[name=amount]', d).value = c.dataset.v; }));
  submitForm(d, async f => {
    const r = await api(`/loans/${L.id}/payments`, { method: 'POST', body: { amount: +f.amount, method: f.method, paid_on: f.paid_on, note: f.note } });
    d.close(); receiptModal(r, true);
  });
}

function receiptModal(r, fresh) {
  const d = modal(`<h2>${fresh ? 'Payment recorded' : 'Receipt'}</h2>
    <div class="receipt"><small>Branch. · ${esc(r.receipt_no)}</small><div class="big num">${inr(r.amount)}</div>
      <dl><dt>Borrower</dt><dd>${esc(r.borrower_name)}</dd><dt>Loan</dt><dd>${esc(r.loan_code)}</dd><dt>Date</dt><dd>${fdate(r.paid_on)}</dd><dt>Method</dt><dd>${esc(r.method)}</dd>
      ${r.outstanding_after != null ? `<dt>Balance after</dt><dd>${inr(r.outstanding_after)}</dd>` : ''}${r.received_by ? `<dt>Received by</dt><dd>${esc(r.received_by)}</dd>` : ''}</dl></div>
    <div class="foot no-print"><button class="btn ghost" id="print">Print</button><button class="btn" data-close>Done</button></div>`);
  $('#print', d).addEventListener('click', () => window.print());
  d.addEventListener('close', () => { if (fresh) refresh(); });
}

// ---------- collections ----------
async function collections() {
  const c = await api('/collections');
  const over = c.overdue.length ? `<div class="scroll"><table class="tbl"><thead><tr><th>Borrower</th><th>Phone</th><th class="r">Days late</th><th class="r">Overdue</th><th class="r">Penalty</th><th></th></tr></thead><tbody>
    ${c.overdue.map(l => `<tr data-href="#/loans/${l.id}"><td><b>${esc(l.borrower_name)}</b><br><small>${esc(l.code)}</small></td><td><a href="tel:${esc(l.phone)}">${esc(l.phone)}</a></td>
      <td class="r num"><span class="pill red">${l.days_overdue}d</span></td><td class="r num"><b>${inr(l.overdue)}</b></td><td class="r num">${inr(l.penalty)}</td><td class="r"><button class="btn sm" data-pay="${l.id}">Collect</button></td></tr>`).join('')}</tbody></table></div>`
    : '<div class="empty"><b>Nothing overdue.</b>Every borrower is up to date.</div>';
  const up = c.upcoming.length ? `<div class="scroll"><table class="tbl"><thead><tr><th>Borrower</th><th>Phone</th><th>Due</th><th class="r">Amount</th><th></th></tr></thead><tbody>
    ${c.upcoming.map(u => `<tr data-href="#/loans/${u.loan_id}"><td><b>${esc(u.borrower_name)}</b><br><small>${esc(u.code)}</small></td><td><a href="tel:${esc(u.phone)}">${esc(u.phone)}</a></td>
      <td>${u.due_date === todayISO() ? '<span class="pill orange">Today</span>' : fdate(u.due_date)}</td><td class="r num"><b>${inr(u.amount)}</b></td><td class="r"><button class="btn ghost sm" data-pay="${u.loan_id}">Collect</button></td></tr>`).join('')}</tbody></table></div>`
    : '<div class="empty"><b>A quiet week.</b>No installments fall due in the next 7 days.</div>';
  return {
    html: `<div class="head"><div><h1>Collections</h1><p>Who to call, and who to visit.</p></div></div>
      <div class="card"><h2>Overdue</h2>${over}</div><div class="card gap"><h2>Due in the next 7 days</h2>${up}</div>`,
    mount: v => $$('[data-pay]', v).forEach(b => b.addEventListener('click', async () => paymentModal(await api('/loans/' + b.dataset.pay))))
  };
}

// ---------- team ----------
async function team() {
  const users = await api('/users');
  return {
    html: `<div class="head"><div><h1>Team</h1><p>Everyone who works in ${esc(state.me.org)}.</p></div>
      <div class="actions"><button class="btn" id="add">Add team member</button></div></div>
      <div class="card"><div class="scroll"><table class="tbl"><thead><tr><th>Name</th><th>Role</th><th class="r">Loans issued</th><th class="r">Collected</th><th>Status</th><th></th></tr></thead><tbody>
      ${users.map(u => `<tr><td><b>${esc(u.name)}</b>${u.id === state.me.id ? ' <small>(you)</small>' : ''}<br><small>${esc(u.email)}</small></td><td>${u.role === 'ADMIN' ? 'Administrator' : 'Loan officer'}</td>
        <td class="r num">${u.loans_issued}</td><td class="r num">${inr(u.collected)}</td>
        <td>${u.active ? '<span class="pill green">Active</span>' : '<span class="pill gray">Disabled</span>'}</td>
        <td class="r">${u.id === state.me.id ? '' : `<button class="btn ghost sm" data-reset="${u.id}" data-name="${esc(u.name)}">Reset password</button> <button class="btn ${u.active ? 'danger' : 'ghost'} sm" data-toggle="${u.id}" data-on="${u.active ? 0 : 1}">${u.active ? 'Disable' : 'Enable'}</button>`}</td></tr>`).join('')}
      </tbody></table></div></div>
      <p class="hint" style="margin-top:14px">Your data is private to ${esc(state.me.org)}. Other accounts on Branch. can never see it.</p>`,
    mount: v => {
      $('#add', v).addEventListener('click', () => {
        const d = modal(`<h2>Add team member</h2><form><div class="err"></div>
          <div class="f"><label>Full name</label><input name="name" required></div>
          <div class="row"><div class="f"><label>Email</label><input name="email" type="email" autocapitalize="none" required></div>
          <div class="f"><label>Role</label><select name="role"><option value="OFFICER">Loan officer</option><option value="ADMIN">Administrator</option></select></div></div>
          <div class="f"><label>Temporary password</label><input name="password" type="text" required><div class="hint">At least 8 characters, with a letter and a number. They can change it after signing in.</div></div>
          <div class="foot"><button type="button" class="btn ghost" data-close>Cancel</button><button class="btn" type="submit">Add</button></div></form>`);
        submitForm(d, async f => { await api('/users', { method: 'POST', body: f }); d.close(); toast('Team member added.'); refresh(); });
      });
      $$('[data-toggle]', v).forEach(b => b.addEventListener('click', async () => {
        try { await api(`/users/${b.dataset.toggle}/active`, { method: 'POST', body: { active: b.dataset.on === '1' } }); refresh(); } catch (e) { toast(e.message); }
      }));
      $$('[data-reset]', v).forEach(b => b.addEventListener('click', () => {
        const d = modal(`<h2>Reset password</h2><p class="mute" style="margin:-12px 0 18px">Set a new temporary password for ${esc(b.dataset.name)}.</p><form><div class="err"></div>
          <div class="f"><label>New password</label><input name="password" type="text" required><div class="hint">At least 8 characters, with a letter and a number.</div></div>
          <div class="foot"><button type="button" class="btn ghost" data-close>Cancel</button><button class="btn" type="submit">Reset</button></div></form>`);
        submitForm(d, async f => { await api(`/users/${b.dataset.reset}/password`, { method: 'POST', body: f }); d.close(); toast('Password reset.'); });
      }));
    }
  };
}

function passwordModal() {
  const d = modal(`<h2>Change password</h2><form><div class="err"></div>
    <div class="f"><label>Current password</label><input name="current" type="password" autocomplete="current-password" required></div>
    <div class="f"><label>New password</label><input name="password" type="password" autocomplete="new-password" required><div class="hint">At least 8 characters, with a letter and a number.</div></div>
    <div class="foot"><button type="button" class="btn ghost" data-close>Cancel</button><button class="btn" type="submit">Update</button></div></form>`);
  submitForm(d, async f => { await api('/password', { method: 'POST', body: f }); d.close(); toast('Password updated.'); });
}

boot().catch(e => { $('#app').innerHTML = `<div class="auth"><div class="card empty"><b>Can’t reach Branch.</b>${esc(e.message)}</div></div>`; });
