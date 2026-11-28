/* Admin page: create, list, delete users and reset their password. */

let me;

async function load() {
  const users = await api.get('/api/users');
  document.getElementById('rows').replaceChildren(...users.map(row));
  const active = users.filter((u) => u.enabled).length;
  document.getElementById('counts').textContent = 'Active users: ' + active + ' of ' + users.length
    + ' (' + (users.length - active) + ' deactivated)';
}

function row(u) {
  const self = u.id === me.id;
  return el('tr', {},
    el('td', {}, u.displayName, self ? el('span', { class: 'meta' }, ' (you)') : null),
    el('td', {}, u.username),
    el('td', {}, u.idOncr || '—'),
    el('td', {}, u.email || '—'),
    el('td', {}, u.role),
    el('td', {},
      el('span', { class: 'badge ' + (u.enabled ? 'open' : 'closed') }, u.enabled ? 'Active' : 'Deactivated'),
      u.enabled && u.mustChangePassword ? el('div', { class: 'meta' }, 'Must change password') : null,
      u.enabled && !u.mustChangePassword && u.mustAddEmail ? el('div', { class: 'meta' }, 'Must add email') : null),
    el('td', {}, u.lastLoginAt ? fmtDateTime(u.lastLoginAt) : 'Never'),
    el('td', {}, fmtDate(u.createdAt)),
    el('td', {}, el('div', { class: 'row' },
      self ? null : el('button', { class: 'secondary', onclick: () => setStatus(u, !u.enabled) },
        u.enabled ? 'Deactivate' : 'Activate'),
      el('button', { class: 'secondary', onclick: () => reset(u) }, 'Reset password'),
      self ? null : el('button', { class: 'danger', onclick: () => remove(u) }, 'Delete'))));
}

async function setStatus(u, enabled) {
  if (!enabled && !confirm('Deactivate "' + u.displayName + '"?\nThey will be signed out and cannot sign in until you activate them again.')) return;
  try {
    await api.patch('/api/users/' + u.id + '/status', { enabled });
    toast('"' + u.displayName + '" was ' + (enabled ? 'activated.' : 'deactivated.'));
    await load();
  } catch (e) { toast(e.message, 'error'); }
}

async function reset(u) {
  if (!confirm('Reset the password of "' + u.displayName + '" to the default one?\nThey will have to choose a new one at next login.')) return;
  try {
    await api.post('/api/users/' + u.id + '/reset-password');
    toast('Password of "' + u.displayName + '" was reset to the default one.');
    await load();
  } catch (e) { toast(e.message, 'error'); }
}

async function remove(u) {
  if (!confirm('Delete "' + u.displayName + '"? This cannot be undone.')) return;
  try {
    await api.del('/api/users/' + u.id);
    toast('User "' + u.displayName + '" was deleted.');
    await load();
  } catch (e) { toast(e.message, 'error'); }   // e.g. 409 when the user is part of existing polls
}

/* ---------- self-registration switch ---------- */

function showRegistration(open) {
  document.getElementById('registration-state').textContent = open ? 'OPEN' : 'CLOSED';
  const button = document.getElementById('registration-toggle');
  button.hidden = false;
  button.textContent = open ? 'Close registration' : 'Open registration';
  button.onclick = async () => {
    if (!open && !confirm('Open registration?\nAnyone who can reach the login page will be able to create an account until you close it again.')) return;
    try {
      const s = await api.request('PUT', '/api/registration', { open: !open });
      showRegistration(s.open);
      toast(s.open ? 'Registration is now open.' : 'Registration is now closed.');
    } catch (e) { toast(e.message, 'error'); }
  };
}

(async function main() {
  me = await initPage();
  if (me.role !== 'ADMIN') { location.href = '/'; return; }

  document.getElementById('new-user').addEventListener('click', openCreateUserDialog);
  document.addEventListener('users-changed', () => load().catch(() => {}));   // after the pop-up creates a user
  api.get('/api/registration').then((s) => showRegistration(s.open)).catch(() => {});
  await load();
})();
