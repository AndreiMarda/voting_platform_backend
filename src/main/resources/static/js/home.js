/*
 * Session page (home). Shows the running session and its votes - or, with ?session=ID, a past session.
 * Same page for everyone; admins additionally get start/end session and create/close/delete vote controls.
 * Admins never vote: they only manage votes, so they get no vote buttons (the server refuses their votes too).
 */

let me;
let session = null;                 // the session being shown (null = none is running)
let polls = [];                     // polls of that session, from the last refresh
const viewedId = new URLSearchParams(location.search).get('session');   // set when looking at a past session
const resultsCache = new Map();     // closed polls never change, so fetch their results once
let startDialog, live;
const pollDialogs = {};             // keyed by "anonymous" (true/false)

const when = (iso) => new Date(iso).toLocaleString();
const isAdmin = () => me.role === 'ADMIN';

/* ---------- loading and rendering ---------- */

async function load() {
  session = viewedId
    ? await api.get('/api/sessions/' + encodeURIComponent(viewedId))
    : await api.get('/api/sessions/current');                     // null when nothing is running
  renderHead();
  const box = document.getElementById('polls');
  if (!session) { polls = []; box.hidden = true; return; }
  polls = await api.get('/api/polls?sessionId=' + session.id);
  box.hidden = false;
  render();
}

function renderHead() {
  const head = document.getElementById('head');
  if (!session) {
    head.replaceChildren(el('div', { class: 'card' },
      el('h2', {}, 'No session is running'),
      el('p', {}, isAdmin() ? 'Start a session to be able to create votes.'
                            : 'Votes appear here once an admin starts a session.'),
      isAdmin() ? el('button', { onclick: () => openStartDialog() }, 'Start session') : null));
    return;
  }
  const active = session.status === 'ACTIVE';
  head.replaceChildren(el('div', { class: 'card' },
    el('div', { class: 'row between card-head' },
      el('h2', {}, session.name),
      el('div', { class: 'card-actions' },
        el('span', { class: 'badge ' + (active ? 'open' : 'closed') }, active ? 'Active session' : 'Ended session'),
        el('button', { class: 'secondary small', onclick: () => openLive(null) }, 'Who voted (live)'),
        el('a', { class: 'btn secondary small', href: '/report.html?session=' + session.id }, 'Report / PDF'),
        el('a', { class: 'btn secondary small', href: '/api/sessions/' + session.id + '/export.csv' }, 'Export CSV'),
        isAdmin() && active ? el('button', { class: 'small', onclick: () => openPollDialog(false) }, 'Create new vote') : null,
        isAdmin() && active ? el('button', { class: 'small', onclick: () => openPollDialog(true) }, 'Create anonymous vote') : null,
        isAdmin() && active ? el('button', { class: 'secondary small', onclick: endSession }, 'End session') : null)),
    el('div', { class: 'meta' },
      'Started by ' + session.startedBy + ' on ' + when(session.startedAt)
        + (session.endedAt ? ' · ended ' + when(session.endedAt) : '')),
    viewedId ? el('div', { class: 'meta' }, el('a', { href: '/sessions.html' }, '← Past sessions')) : null));
}

function render() {
  fill('open', polls.filter((p) => p.status === 'OPEN'), 'There are no open votes right now.');
  fill('closed', polls.filter((p) => p.status === 'CLOSED'), 'No closed votes yet.');
}

function fill(containerId, list, emptyText) {
  document.getElementById(containerId).replaceChildren(
    ...(list.length ? list.map(pollCard) : [el('p', { class: 'meta' }, emptyText)]));
}

function pollCard(p) {
  const card = el('div', { class: 'card' },
    el('div', { class: 'row between card-head' },
      el('h2', {}, p.title),
      // top-right corner of the card: status and actions
      el('div', { class: 'card-actions' },
        p.anonymous ? el('span', { class: 'badge anon' }, 'Anonymous') : null,
        el('span', { class: 'badge ' + p.status.toLowerCase() }, p.status === 'OPEN' ? 'Open' : 'Closed'),
        el('button', { class: 'secondary small', onclick: () => openLive(p.id) }, p.anonymous ? 'Live count' : 'Who voted'),
        isAdmin() && p.status === 'OPEN'
          ? el('button', { class: 'secondary small', onclick: () => closePoll(p) }, 'Close this vote') : null,
        isAdmin() ? el('button', { class: 'danger small', onclick: () => deletePoll(p) }, 'Delete') : null)),
    p.description ? el('p', {}, p.description) : null,
    el('div', { class: 'meta' }, p.votedCount + ' of ' + p.eligibleCount + ' have voted'),
    myPart(p));

  if (p.status === 'CLOSED') {
    const results = el('div', { class: 'tally' });
    card.append(results);
    loadResults(p.id, results);
  }
  return card;
}

/** The part that depends on the viewer: choice buttons, their choice, or why they cannot vote. */
function myPart(p) {
  if (isAdmin()) return null;                      // admins create and manage votes; they do not vote
  if (p.hasVoted) {
    // Once voted there are no buttons at all: the choice is final.
    return p.myChoice
      ? el('div', { class: 'note' }, 'You voted: ', el('span', { class: 'badge mine' }, CHOICE_LABELS[p.myChoice]))
      : el('div', { class: 'note' }, 'You voted. This vote is anonymous, so your choice is not stored with your name.');
  }
  if (p.status === 'CLOSED') return el('div', { class: 'note' }, 'You did not vote in this poll.');
  if (!p.eligible) return el('div', { class: 'note' }, 'You cannot vote in this vote right now. If you just signed in, wait a moment.');

  return el('div', { class: 'choices' },
    Object.entries(CHOICE_LABELS).map(([value, label]) =>
      el('button', { onclick: (e) => castVote(p, value, label, e.target) }, label)));
}

async function loadResults(pollId, target) {
  try {
    if (!resultsCache.has(pollId)) resultsCache.set(pollId, await api.get('/api/polls/' + pollId + '/results'));
    const r = resultsCache.get(pollId);
    target.replaceChildren(...Object.entries(CHOICE_LABELS).map(([k, label]) =>
      el('span', {}, el('strong', {}, label + ': '), String(r.tally[k] ?? 0))),
      el('span', { class: 'meta' }, 'Total: ' + r.total));
  } catch (e) { target.textContent = e.message; }
}

/* ---------- actions ---------- */

async function castVote(p, value, label, button) {
  if (!confirm('Vote "' + label + '" on "' + p.title + '"?\n\nYou cannot change your vote afterwards.')) return;
  button.disabled = true;
  try {
    await api.post('/api/polls/' + p.id + '/votes', { choice: value });
    toast('Your vote ("' + label + '") was recorded.');
  } catch (e) {
    toast(e.message, 'error');
  }
  await load();   // on success the buttons disappear; on failure we show the real current state
}

async function closePoll(p) {
  if (!confirm('Close "' + p.title + '"? Nobody will be able to vote afterwards.')) return;
  try {
    await api.post('/api/polls/' + p.id + '/close');
    toast('Vote "' + p.title + '" was closed.');
  } catch (e) { toast(e.message, 'error'); }
  await load();
}

/** Admins can delete a poll at any time: untouched, while people are voting, or already closed. */
async function deletePoll(p) {
  const progress = p.status === 'OPEN'
    ? 'This vote is still open (' + p.votedCount + ' of ' + p.eligibleCount + ' have voted).\n\n' : '';
  if (!confirm(progress + 'Permanently delete "' + p.title + '"?\n\n'
      + 'All its votes and results will be erased. This cannot be undone.')) return;
  try {
    await api.del('/api/polls/' + p.id);
    resultsCache.delete(p.id);
    toast('Vote "' + p.title + '" was deleted.');
  } catch (e) { toast(e.message, 'error'); }
  await load();
}

async function endSession() {
  const open = polls.filter((p) => p.status === 'OPEN').length;
  if (!confirm('End the session "' + session.name + '"?\n\n'
      + (open ? open + ' open vote' + (open === 1 ? ' will' : 's will') + ' be closed. ' : '')
      + 'Nobody will be able to vote in this session afterwards. Its votes and results stay saved.')) return;
  try {
    await api.post('/api/sessions/' + session.id + '/end');
    toast('Session "' + session.name + '" was ended.');
  } catch (e) { toast(e.message, 'error'); }
  await load();
}

/* ---------- pop-ups ---------- */

function openStartDialog() {
  if (!startDialog) {
    startDialog = formDialog({
      title: 'Start a session',
      intro: 'Votes are created inside a session. Give it a name, for example "General assembly 2026".',
      submitLabel: 'Start session',
      fields: [el('label', {}, 'Session name',
        el('input', { name: 'name', maxlength: 100, required: true, autocomplete: 'off' }))],
      onSubmit: async (data) => {
        const s = await api.post('/api/sessions', { name: data.name });
        toast('Session "' + s.name + '" was started.');
        load().catch(() => {});
      },
    });
  }
  startDialog.open();
}

/** One pop-up per kind of vote: normal (names are shown) and anonymous (only the number of voters). */
function openPollDialog(anonymous) {
  if (!pollDialogs[anonymous]) {
    pollDialogs[anonymous] = formDialog({
      title: anonymous ? 'Create anonymous vote' : 'Create new vote',
      intro: anonymous
        ? 'Nobody will see who voted or what they chose, only how many have voted (for example 19 of 20). '
          + 'Everyone who is online can vote, including people who sign in while it is open.'
        : 'Everyone who is online can vote, including people who sign in while it is open.',
      submitLabel: anonymous ? 'Create anonymous vote' : 'Create vote',
      fields: [
        el('label', {}, 'Title', el('input', { name: 'title', maxlength: 500, required: true, autocomplete: 'off' })),
        el('label', {}, 'Description (optional)', el('textarea', { name: 'description', rows: 3, maxlength: 5000 })),
      ],
      onSubmit: async (data) => {
        const poll = await api.post('/api/polls',
          { title: data.title, description: data.description || null, anonymous });
        toast((anonymous ? 'Anonymous vote "' : 'Vote "') + poll.title + '" was created.');   // confirmation toast
        load().catch(() => {});
      },
    });
  }
  pollDialogs[anonymous].open();
}

/* ---------- live "who voted" window ---------- */

function openLive(preferredPollId) {
  if (!live) live = buildLive();
  live.show(preferredPollId);
}

function buildLive() {
  const title = el('h2', {});
  const select = el('select', { 'aria-label': 'Choose the vote to follow' });
  const left = el('div', { class: 'live-col' });
  const right = el('div', { class: 'live-col' });
  const note = el('p', { class: 'meta' });
  const status = el('p', { class: 'meta' });
  const grid = el('div', { class: 'live-grid' }, left, right);
  const counter = el('div', { class: 'live-counter', hidden: true });   // anonymous votes: just the number
  const dialog = el('dialog', { class: 'modal wide' },
    el('div', { class: 'row between' }, title,
      el('span', { class: 'live-badge' }, el('span', { class: 'live-dot' }), 'Live')),
    el('label', {}, 'Vote', select),
    grid, counter,
    note, status,
    el('div', { class: 'row' },
      el('button', { type: 'button', class: 'secondary', onclick: () => dialog.close() }, 'Close')));
  document.body.append(dialog);

  let timer = null;
  let selectedId = null;
  let lastSignature = '';

  function syncOptions(list) {
    const signature = list.map((p) => p.id + ':' + p.title + ':' + p.status).join('|');
    if (signature !== lastSignature) {
      lastSignature = signature;
      select.replaceChildren(...list.map((p) =>
        el('option', { value: p.id }, p.title + (p.status === 'CLOSED' ? ' (closed)' : ''))));
    }
    if (!list.some((p) => p.id === selectedId)) {            // nothing chosen yet, or the vote was deleted
      selectedId = (list.find((p) => p.status === 'OPEN') || list[0] || {}).id ?? null;
    }
    if (selectedId !== null) select.value = String(selectedId);
  }

  function column(target, heading, names, emptyText) {
    target.replaceChildren(el('strong', {}, heading + ' (' + names.length + ')'),
      el('ul', {}, names.length ? names.map((n) => el('li', {}, n)) : el('li', { class: 'meta' }, emptyText)));
  }

  async function tick() {
    if (!session) return;
    try {
      const list = await api.get('/api/polls?sessionId=' + session.id);
      syncOptions(list);
      const poll = list.find((p) => p.id === selectedId);
      if (!poll) {
        title.textContent = 'No votes in this session yet';
        left.replaceChildren(); right.replaceChildren(); note.textContent = ''; status.textContent = '';
        return;
      }
      const open = poll.status === 'OPEN';
      title.textContent = poll.title;
      if (poll.anonymous) {
        // Anonymous: the server refuses to list names, so only the live number is shown.
        grid.hidden = true;
        counter.hidden = false;
        counter.replaceChildren(
          el('div', { class: 'live-number' }, poll.votedCount + ' of ' + poll.eligibleCount),
          el('div', {}, 'have voted'),
          el('div', { class: 'meta' }, 'This vote is anonymous: names are not shown.'));
        note.textContent = '';
        status.textContent = 'Updated ' + new Date().toLocaleTimeString() + (open ? '' : ' · this vote is closed');
        return;
      }
      grid.hidden = false;
      counter.hidden = true;
      const part = await api.get('/api/polls/' + poll.id + '/participation');
      column(left, 'Voted', part.voted, 'Nobody has voted yet.');
      column(right, open ? 'Not voted yet (online now)' : 'Did not vote', part.notVoted,
        open ? 'Everyone who is online has voted.' : 'Everyone voted.');
      note.textContent = open && part.notVotedOffline > 0
        ? part.notVotedOffline + (part.notVotedOffline === 1 ? ' eligible user is' : ' eligible users are')
          + ' offline and not shown.' : '';
      status.textContent = 'Updated ' + new Date().toLocaleTimeString() + (open ? '' : ' · this vote is closed');
    } catch (e) { status.textContent = e.message; }
  }

  select.addEventListener('change', () => { selectedId = Number(select.value); tick(); });
  dialog.addEventListener('close', () => { clearInterval(timer); timer = null; });

  return {
    show(preferredPollId) {
      selectedId = preferredPollId;
      lastSignature = '';
      dialog.showModal();
      tick();
      // Votes arrive instantly through the live events (see refresh below). This slower timer only keeps the
      // "online now" list honest, because people going offline do not trigger an event.
      timer = setInterval(tick, 15000);
    },
    /** Called when the server says something changed. */
    refresh() {
      if (dialog.open) tick();
    },
  };
}

/* ---------- start ---------- */

(async function main() {
  me = await initPage();
  try {
    await load();
  } catch (e) {
    document.getElementById('head').replaceChildren(el('div', { class: 'card' }, e.message,
      el('div', {}, el('a', { href: '/sessions.html' }, '← Past sessions'))));
    return;
  }
  // Live: the server pushes "something changed" and we re-fetch at once. The minute timer is only a safety net.
  let pending = null;
  onServerChange(() => {
    clearTimeout(pending);
    pending = setTimeout(() => {          // several changes in quick succession become one refresh
      load().catch(() => {});
      if (live) live.refresh();
    }, 150);
  });
  setInterval(() => load().catch(() => {}), 60000);
})();
