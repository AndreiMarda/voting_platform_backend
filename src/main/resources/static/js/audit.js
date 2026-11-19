/* Admin page: the audit trail, newest first, loaded 50 entries at a time. */

const PAGE = 50;
const LABELS = {
  LOGIN_SUCCESS: 'Logged in', LOGIN_FAILED: 'Failed login', LOGIN_LOCKED: 'Login blocked', LOGOUT: 'Logged out',
  USER_CREATED: 'User created', USER_DELETED: 'User deleted', PASSWORD_RESET: 'Password reset',
  PASSWORD_CHANGED: 'Password changed', SESSION_STARTED: 'Session started', SESSION_ENDED: 'Session ended',
  POLL_CREATED: 'Vote created', POLL_CLOSED: 'Vote closed', POLL_DELETED: 'Vote deleted',
};
const WARN = new Set(['LOGIN_FAILED', 'LOGIN_LOCKED', 'USER_DELETED', 'POLL_DELETED', 'PASSWORD_RESET']);

let lastId = null;

function row(e) {
  return el('tr', {},
    el('td', {}, new Date(e.occurredAt).toLocaleString()),
    el('td', {}, e.actorName || '—'),
    el('td', {}, el('span', { class: 'badge ' + (WARN.has(e.action) ? 'warn' : '') }, LABELS[e.action] || e.action)),
    el('td', {}, e.details || ''),
    el('td', { class: 'meta' }, e.ip || ''));
}

async function loadMore() {
  const url = '/api/audit?limit=' + PAGE + (lastId ? '&beforeId=' + lastId : '');
  const entries = await api.get(url);
  const rows = document.getElementById('rows');
  if (!lastId && entries.length === 0) {
    rows.replaceChildren(el('tr', {}, el('td', { colspan: 5, class: 'meta' }, 'Nothing has been recorded yet.')));
  }
  rows.append(...entries.map(row));
  if (entries.length) lastId = entries[entries.length - 1].id;
  document.getElementById('more').hidden = entries.length < PAGE;     // fewer than a full page = that was the end
  document.getElementById('status').textContent = '';
}

(async function main() {
  const me = await initPage();
  if (me.role !== 'ADMIN') { location.href = '/'; return; }
  document.getElementById('more').addEventListener('click', () => loadMore().catch((e) => toast(e.message, 'error')));
  await loadMore();
})();
