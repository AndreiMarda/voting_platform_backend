/* Home page: every poll, same list for everyone. Admins additionally get create / close controls. */

let me;
const openDetails = new Set();     // poll ids whose "who voted" panel is expanded (kept across refreshes)
const resultsCache = new Map();    // closed polls never change, so fetch their results once

async function load() {
  render(await api.get('/api/polls'));
}

function render(polls) {
  const open = polls.filter((p) => p.status === 'OPEN');
  const closed = polls.filter((p) => p.status === 'CLOSED');
  fill('open', open, 'There are no open votes right now.');
  fill('closed', closed, 'No closed votes yet.');
}

function fill(containerId, polls, emptyText) {
  const box = document.getElementById(containerId);
  box.replaceChildren(...(polls.length ? polls.map(pollCard) : [el('p', { class: 'meta' }, emptyText)]));
}

function pollCard(p) {
  const card = el('div', { class: 'card' },
    el('div', { class: 'row between' },
      el('h2', {}, p.title),
      el('span', { class: 'badge ' + p.status.toLowerCase() }, p.status === 'OPEN' ? 'Open' : 'Closed')),
    p.description ? el('p', {}, p.description) : null,
    el('div', { class: 'meta' }, p.votedCount + ' of ' + p.eligibleCount + ' have voted'),
    myPart(p));

  if (p.status === 'CLOSED') {
    const results = el('div', { class: 'tally' });
    card.append(results);
    loadResults(p.id, results);
  }
  card.append(whoVoted(p));
  if (me.role === 'ADMIN' && p.status === 'OPEN') {
    card.append(el('div', { class: 'row', style: 'margin-top:.75rem' },
      el('button', { class: 'danger', onclick: () => closePoll(p) }, 'Close this vote')));
  }
  return card;
}

/** The part that depends on the viewer: choice buttons, their choice, or why they cannot vote. */
function myPart(p) {
  if (p.hasVoted) {
    // Once voted there are no buttons at all: the choice is final.
    return el('div', { class: 'note' }, 'You voted: ',
      el('span', { class: 'badge mine' }, p.myChoice ? CHOICE_LABELS[p.myChoice] : 'choice recorded anonymously'));
  }
  if (p.status === 'CLOSED') return el('div', { class: 'note' }, 'You did not vote in this poll.');
  if (!p.eligible) return el('div', { class: 'note' }, 'You were not online when this vote was launched, so you cannot vote in it.');

  return el('div', { class: 'choices' },
    Object.entries(CHOICE_LABELS).map(([value, label]) =>
      el('button', { onclick: (e) => castVote(p, value, label, e.target) }, label)));
}

function whoVoted(p) {
  const body = el('div', { class: 'names' }, 'Loading…');
  const details = el('details', { open: openDetails.has(p.id) },
    el('summary', {}, 'Who voted'), body);
  const fillNames = async () => {
    try {
      const part = await api.get('/api/polls/' + p.id + '/participation');
      body.replaceChildren(nameList('Voted (' + part.voted.length + ')', part.voted),
        nameList('Not yet (' + part.notVoted.length + ')', part.notVoted));
    } catch (e) { body.textContent = e.message; }
  };
  details.addEventListener('toggle', () => {
    if (details.open) { openDetails.add(p.id); fillNames(); } else openDetails.delete(p.id);
  });
  return details;
}

function nameList(title, names) {
  return el('div', {}, el('strong', {}, title),
    el('ul', {}, names.length ? names.map((n) => el('li', {}, n)) : el('li', { class: 'meta' }, 'nobody')));
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

function createForm() {
  const title = el('input', { name: 'title', maxlength: 500, required: true });
  const description = el('textarea', { name: 'description', rows: 3, maxlength: 5000 });
  const submit = el('button', { type: 'submit' }, 'Launch vote');
  const form = el('form', { class: 'card' },
    el('h2', {}, 'Launch a new vote'),
    el('p', {}, 'Everyone who is online right now will be able to vote in it.'),
    el('label', {}, 'Title', title),
    el('label', {}, 'Description (optional)', description),
    submit);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    submit.disabled = true;
    try {
      const poll = await api.post('/api/polls', { title: title.value, description: description.value || null });
      toast('Vote "' + poll.title + '" was created.');   // the toast confirming creation
      form.reset();
      await load();
    } catch (err) {
      toast(err.message, 'error');
    } finally { submit.disabled = false; }
  });
  return form;
}

(async function main() {
  me = await initPage();
  if (me.role === 'ADMIN') {
    const slot = document.getElementById('create');
    slot.hidden = false;
    slot.append(createForm());
  }
  await load();
  setInterval(() => load().catch(() => {}), 10000);   // keeps counts / voted state fresh
})();
