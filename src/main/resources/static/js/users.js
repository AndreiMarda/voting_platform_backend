/* Admin page: create, list, delete users and reset their password. */

let me;

async function load() {
  const users = await api.get('/api/users');
  document.getElementById('rows').replaceChildren(...users.map(row));
}

function row(u) {
  const self = u.id === me.id;
  return el('tr', {},
    el('td', {}, u.username, self ? el('span', { class: 'meta' }, ' (you)') : null),
    el('td', {}, u.role),
    el('td', {}, u.enabled ? (u.mustChangePassword ? 'Must change password' : 'Active') : 'Disabled'),
    el('td', {}, new Date(u.createdAt).toLocaleDateString()),
    el('td', {}, el('div', { class: 'row' },
      el('button', { class: 'secondary', onclick: () => reset(u) }, 'Reset password'),
      self ? null : el('button', { class: 'danger', onclick: () => remove(u) }, 'Delete'))));
}

async function reset(u) {
  if (!confirm('Reset the password of "' + u.username + '" to the default one?\nThey will have to choose a new one at next login.')) return;
  try {
    await api.post('/api/users/' + u.id + '/reset-password');
    toast('Password of "' + u.username + '" was reset to the default one.');
    await load();
  } catch (e) { toast(e.message, 'error'); }
}

async function remove(u) {
  if (!confirm('Delete "' + u.username + '"? This cannot be undone.')) return;
  try {
    await api.del('/api/users/' + u.id);
    toast('User "' + u.username + '" was deleted.');
    await load();
  } catch (e) { toast(e.message, 'error'); }   // e.g. 409 when the user is part of existing polls
}

(async function main() {
  me = await initPage();
  if (me.role !== 'ADMIN') { location.href = '/'; return; }

  document.getElementById('new-user').addEventListener('click', openCreateUserDialog);
  document.addEventListener('users-changed', () => load().catch(() => {}));   // after the pop-up creates a user
  await load();
})();
