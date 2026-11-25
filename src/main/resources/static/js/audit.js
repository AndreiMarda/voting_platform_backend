/* Admin page: the audit trail, newest first, loaded 50 entries at a time, with search filters. */

const PAGE = 50;
const LABELS = {
  LOGIN_SUCCESS: 'Logged in', LOGIN_FAILED: 'Failed login', LOGIN_LOCKED: 'Login blocked', LOGOUT: 'Logged out',
  USER_CREATED: 'User created', USER_DELETED: 'User deleted', PASSWORD_RESET: 'Password reset',
  PASSWORD_CHANGED: 'Password changed', SESSION_STARTED: 'Session started', SESSION_ENDED: 'Session ended',
  SESSION_RENAMED: 'Session renamed',
  PROFILE_UPDATED: 'Profile updated', USER_REGISTERED: 'User registered',
  REGISTRATION_OPENED: 'Registration opened', REGISTRATION_CLOSED: 'Registration closed',
  POLL_CREATED: 'Vote created', POLL_CLOSED: 'Vote closed', POLL_DELETED: 'Vote deleted',
};
const WARN = new Set(['LOGIN_FAILED', 'LOGIN_LOCKED', 'USER_DELETED', 'POLL_DELETED', 'PASSWORD_RESET']);

let lastId = null;

/**
 * The search as query-string parameters. "From" and "to" are whole days in the admin's own time zone:
 * from = start of that day, to = start of the day after (the server treats `to` as exclusive).
 */
function filterParams(values) {
  const q = new URLSearchParams();
  if (values.from) q.set('from', new Date(values.from + 'T00:00:00').toISOString());
  if (values.to) {
    const end = new Date(values.to + 'T00:00:00');
    end.setDate(end.getDate() + 1);
    q.set('to', end.toISOString());
  }
  if (values.user && values.user.trim()) q.set('user', values.user.trim());
  if (values.action) q.set('action', values.action);
  return q;
}

function row(e) {
  return el('tr', {},
    el('td', {}, fmtDateTime(e.occurredAt)),
    el('td', {}, e.actorName || '—'),
    el('td', {}, el('span', { class: 'badge ' + (WARN.has(e.action) ? 'warn' : '') }, LABELS[e.action] || e.action)),
    el('td', {}, e.details || ''),
    el('td', { class: 'meta' }, e.ip || ''));
}

function currentValues() {
  return {
    from: document.getElementById('from').value, to: document.getElementById('to').value,
    user: document.getElementById('user').value, action: document.getElementById('action').value,
  };
}

async function loadMore() {
  const q = filterParams(currentValues());
  q.set('limit', PAGE);
  if (lastId) q.set('beforeId', lastId);
  const entries = await api.get('/api/audit?' + q);
  const rows = document.getElementById('rows');
  if (!lastId) rows.replaceChildren();
  if (!lastId && entries.length === 0) {
    rows.replaceChildren(el('tr', {}, el('td', { colspan: 5, class: 'meta' }, 'No entries match.')));
  }
  rows.append(...entries.map(row));
  if (entries.length) lastId = entries[entries.length - 1].id;
  document.getElementById('more').hidden = entries.length < PAGE;     // fewer than a full page = that was the end
  document.getElementById('status').textContent = '';
}

function search() {
  lastId = null;
  return loadMore().catch((e) => toast(e.message, 'error'));
}

// The printable report page loads this file too (for LABELS) but has no search form.
if (document.getElementById('filters')) {
  (async function main() {
    const me = await initPage();
    if (me.role !== 'ADMIN') { location.href = '/'; return; }

    document.getElementById('action').append(
      ...Object.entries(LABELS).map(([value, label]) => el('option', { value }, label)));

    document.getElementById('filters').addEventListener('submit', (e) => { e.preventDefault(); search(); });
    document.getElementById('clear').addEventListener('click', () => {
      document.getElementById('filters').reset();
      search();
    });
    document.getElementById('pdf').addEventListener('click', () => {
      window.open('/audit-report.html?' + filterParams(currentValues()), '_blank');
    });
    document.getElementById('more').addEventListener('click', () => loadMore().catch((e) => toast(e.message, 'error')));
    await search();
  })();
}
