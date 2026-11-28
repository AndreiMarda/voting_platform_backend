/* Admin page: who is logged in right now (active within the last couple of minutes). */

function ago(iso) {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 10) return 'just now';
  if (seconds < 60) return seconds + ' seconds ago';
  const minutes = Math.round(seconds / 60);
  return minutes + (minutes === 1 ? ' minute ago' : ' minutes ago');
}

async function load() {
  const online = await api.get('/api/users/online');
  document.getElementById('summary').textContent =
    online.length + (online.length === 1 ? ' user is' : ' users are') + ' online right now. This list refreshes by itself.';
  const rows = online.length
    ? online.map((u) => el('tr', {}, el('td', {}, u.displayName), el('td', {}, u.role), el('td', {}, ago(u.lastSeenAt))))
    : [el('tr', {}, el('td', { colspan: 3, class: 'meta' }, 'Nobody is online.'))];
  document.getElementById('rows').replaceChildren(...rows);
}

(async function main() {
  const me = await initPage();
  if (me.role !== 'ADMIN') { location.href = '/'; return; }
  await load();
  setInterval(() => load().catch(() => {}), 10000);
})();
