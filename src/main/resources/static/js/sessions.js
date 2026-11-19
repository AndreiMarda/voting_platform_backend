/* All sessions, newest first. Open one to see its votes and results. */

const when = (iso) => (iso ? new Date(iso).toLocaleString() : '—');

function row(s) {
  const active = s.status === 'ACTIVE';
  return el('tr', {},
    el('td', {}, s.name),
    el('td', {}, el('span', { class: 'badge ' + (active ? 'open' : 'closed') }, active ? 'Active' : 'Ended')),
    el('td', {}, s.startedBy),
    el('td', {}, when(s.startedAt)),
    el('td', {}, when(s.endedAt)),
    el('td', {}, String(s.pollCount)),
    el('td', {},
      el('a', { href: active ? '/' : '/?session=' + s.id }, 'View'), ' · ',
      el('a', { href: '/report.html?session=' + s.id }, 'Report / PDF'), ' · ',
      el('a', { href: '/api/sessions/' + s.id + '/export.csv' }, 'CSV')));
}

(async function main() {
  await initPage();
  const sessions = await api.get('/api/sessions');
  document.getElementById('rows').replaceChildren(...(sessions.length
    ? sessions.map(row)
    : [el('tr', {}, el('td', { colspan: 7, class: 'meta' }, 'No session has been started yet.'))]));
})();
