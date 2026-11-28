/*
 * Shared frontend code: API client, toast, tiny DOM helper, form pop-ups, page header, heartbeat.
 * Every request to the backend goes through `api` so a future frontend can port this one file.
 * User-provided text is only ever inserted with textContent / append(string), never innerHTML (XSS).
 */

/* Light / dark theme: remembered in this browser; until chosen, the device's own setting is used. */
function currentTheme() {
  try {
    const saved = localStorage.getItem('theme');
    if (saved === 'light' || saved === 'dark') return saved;
  } catch { /* storage blocked: use the system setting */ }
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
function applyTheme(theme) { document.documentElement.dataset.theme = theme; }
applyTheme(currentTheme());

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const api = {
  async request(method, url, body) {
    const res = await fetch(url, {
      method,
      credentials: 'same-origin',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    if (res.status === 401) {                       // session gone, or account removed/disabled
      location.href = '/login.html';
      throw new ApiError(401, 'Please sign in again.');
    }
    if (!res.ok) {
      const problem = await res.json().catch(() => ({}));
      if (res.status === 403 && problem.error === 'PASSWORD_CHANGE_REQUIRED') {
        location.href = '/change-password.html';
        throw new ApiError(403, 'You must change your password first.');
      }
      if (res.status === 403 && problem.error === 'EMAIL_REQUIRED') {
        location.href = '/profile.html';
        throw new ApiError(403, 'You must add your email address first.');
      }
      if (res.status === 503 && problem.error === 'PLATFORM_FULL') {
        location.href = '/login.html?full=1';
        throw new ApiError(503, 'The platform is full right now.');
      }
      throw new ApiError(res.status, problem.detail || problem.error || 'Something went wrong (' + res.status + ').');
    }
    return res.status === 204 ? null : res.json();
  },
  get: (url) => api.request('GET', url),
  post: (url, body) => api.request('POST', url, body === undefined ? {} : body),
  patch: (url, body) => api.request('PATCH', url, body),
  del: (url) => api.request('DELETE', url),
};

/** el('div', {class: 'x', onclick: fn}, 'text', childNode) -> HTMLElement */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === false || value == null) continue;
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child !== null && child !== undefined && child !== false) node.append(child);
  }
  return node;
}

function toast(message, type = 'success') {
  let box = document.getElementById('toasts');
  if (!box) {
    box = el('div', { id: 'toasts', 'aria-live': 'polite' });
    document.body.append(box);
  }
  const t = el('div', { class: 'toast ' + type, role: type === 'error' ? 'alert' : 'status' }, message);
  box.append(t);
  setTimeout(() => t.remove(), type === 'error' ? 6000 : 4000);
}

const CHOICE_LABELS = { FOR: 'For', AGAINST: 'Against', ABSTAIN: 'Abstain' };

/* Dates are shown as DD.MM.YYYY and times as HH:mm (24 hours), in the viewer's own time zone. */
const pad2 = (n) => String(n).padStart(2, '0');
const fmtDate = (iso) => { if (!iso) return ''; const d = new Date(iso); return pad2(d.getDate()) + '.' + pad2(d.getMonth() + 1) + '.' + d.getFullYear(); };
const fmtTime = (iso) => { if (!iso) return ''; const d = new Date(iso); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); };
const fmtDateTime = (iso) => (iso ? fmtDate(iso) + ' ' + fmtTime(iso) : '');

/**
 * Live updates (Server-Sent Events). The server sends a tiny "changed" event, with only a topic ("polls",
 * "session") and never any data; the page then re-fetches what it shows. The browser reconnects by itself
 * if the connection drops.
 */
function onServerChange(handler) {
  if (!window.EventSource) return null;           // very old browser: the page's slow timer still refreshes it
  const source = new EventSource('/api/events');
  source.addEventListener('changed', (e) => handler(e.data));
  return source;
}

/* ---------- Form pop-ups ---------- */

/**
 * A modal form. `fields` are <label> elements; `onSubmit(data)` receives the form values and may throw
 * (the message is shown inside the pop-up - a modal sits above the toast area). Closes on success.
 * Returns { open() }.
 */
function formDialog({ title, intro, fields, submitLabel, onSubmit }) {
  const error = el('div', { class: 'form-error', role: 'alert', hidden: true });
  const submit = el('button', { type: 'submit' }, submitLabel);
  const form = el('form', {},
    el('h2', {}, title),
    intro ? el('p', { class: 'meta' }, intro) : null,
    error,
    fields,
    el('div', { class: 'row' }, submit,
      el('button', { type: 'button', class: 'secondary', onclick: () => dialog.close() }, 'Cancel')));
  const dialog = el('dialog', { class: 'modal' }, form);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    error.hidden = true;
    submit.disabled = true;
    try {
      await onSubmit(Object.fromEntries(new FormData(form)));
      dialog.close();
    } catch (err) {
      error.textContent = err.message;
      error.hidden = false;
    } finally {
      submit.disabled = false;
    }
  });
  document.body.append(dialog);

  return {
    open() {
      form.reset();
      error.hidden = true;
      dialog.showModal();
    },
  };
}

let renameSessionDialog, renameSessionInput, renameSessionTarget, renameSessionDone;

/** Admin pop-up to rename a session (running or ended). `onDone(updatedSession)` runs after it was saved. */
function openRenameSessionDialog(session, onDone) {
  renameSessionTarget = session;
  renameSessionDone = onDone;
  if (!renameSessionDialog) {
    renameSessionInput = el('input', { name: 'name', maxlength: 100, required: true, autocomplete: 'off' });
    renameSessionDialog = formDialog({
      title: 'Rename session',
      submitLabel: 'Save name',
      fields: [el('label', {}, 'Session name', renameSessionInput)],
      onSubmit: async (data) => {
        const updated = await api.patch('/api/sessions/' + renameSessionTarget.id, { name: data.name });
        toast('Session renamed to "' + updated.name + '".');
        if (renameSessionDone) renameSessionDone(updated);
      },
    });
  }
  renameSessionDialog.open();                       // open() resets the form, so fill the current name afterwards
  renameSessionInput.value = session.name;
  renameSessionInput.select();
}

let createUserDialog;

/** Opens the create-user pop-up. Fires a `users-changed` event on document after a user is created. */
function openCreateUserDialog() {
  if (!createUserDialog) {
    createUserDialog = formDialog({
      title: 'Create a user',
      intro: 'The first and last name are how the person is shown in the platform. The username is only used to '
        + 'sign in. The user must change this temporary password at first login.',
      submitLabel: 'Create user',
      fields: [
        el('label', {}, 'First name',
          el('input', { name: 'firstName', maxlength: 60, required: true, autocomplete: 'off',
            placeholder: 'e.g. Ion Mihai' })),
        el('label', {}, 'Last name',
          el('input', { name: 'lastName', maxlength: 60, required: true, autocomplete: 'off',
            placeholder: 'e.g. Popescu' })),
        el('label', {}, 'Username (used to sign in)',
          el('input', { name: 'username', maxlength: 100, required: true, autocomplete: 'off',
            placeholder: 'e.g. Ion Popescu',
            pattern: '\\s*\\p{L}+(\\s+\\p{L}+){1,2}\\s*', title: 'Two or three words, letters only' })),
        el('label', {}, 'Temporary password (8–72 characters)',
          el('input', { name: 'password', type: 'text', minlength: 8, maxlength: 72, required: true, autocomplete: 'off' })),
        el('label', {}, 'Role',
          el('select', { name: 'role' }, el('option', { value: 'USER' }, 'User'), el('option', { value: 'ADMIN' }, 'Admin'))),
        el('label', {}, 'Email (optional: if left empty, the user must add it at first login)',
          el('input', { name: 'email', type: 'email', maxlength: 254, autocomplete: 'off' })),
      ],
      onSubmit: async (data) => {
        const created = await api.post('/api/users', data);
        toast('User "' + created.displayName + '" was created.');
        document.dispatchEvent(new CustomEvent('users-changed'));
      },
    });
  }
  createUserDialog.open();
}

/* ---------- Top bar ---------- */

/** Admin dropdown for the top bar. */
function adminMenu() {
  const menu = el('details', { class: 'menu' },
    el('summary', {}, 'Admin ▾'),
    el('div', { class: 'menu-items' },
      el('button', { type: 'button', onclick: () => { menu.open = false; openCreateUserDialog(); } }, 'Create user…'),
      el('a', { href: '/online.html' }, 'Online users'),
      el('a', { href: '/users.html' }, 'Manage users'),
      el('a', { href: '/audit.html' }, 'Audit log')));
  // close when clicking anywhere else
  document.addEventListener('click', (e) => { if (!menu.contains(e.target)) menu.open = false; });
  return menu;
}

/** Renders the top bar and starts the heartbeat. Returns the signed-in user. */
async function initPage() {
  const me = await api.get('/api/auth/me');
  const nav = el('nav', {},
    el('a', { href: '/' }, 'Polls'),
    el('a', { href: '/sessions.html' }, 'Past sessions'),
    me.role === 'ADMIN' ? adminMenu() : null,
    el('a', { href: '/profile.html' }, 'My profile'));
  // phones: one "Menu" button opens a dropdown with every page (the CSS hides it on wide screens)
  const menuToggle = el('button', { type: 'button', class: 'menu-toggle secondary', 'aria-expanded': 'false' }, '☰ Menu');
  menuToggle.addEventListener('click', () => {
    const open = nav.classList.toggle('open');
    menuToggle.setAttribute('aria-expanded', String(open));
  });
  const themeButton = el('button', { type: 'button', class: 'secondary' });
  const showTheme = () => { themeButton.textContent = currentTheme() === 'dark' ? '☀ Light' : '🌙 Dark'; };
  themeButton.addEventListener('click', () => {
    const next = currentTheme() === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem('theme', next); } catch { /* still applied for this page */ }
    applyTheme(next);
    showTheme();
  });
  showTheme();
  const logout = el('form', { method: 'post', action: '/logout' },
    el('button', { type: 'submit', class: 'secondary' }, 'Sign out'));
  document.body.prepend(el('header', { class: 'topbar' },
    el('strong', {}, 'Voting platform'), menuToggle, nav,
    el('span', { class: 'spacer' }),
    el('span', { class: 'who' }, me.displayName, ' ', el('span', { class: 'badge' }, me.role)),
    themeButton,
    logout));

  // Tells the server this user is online (poll eligibility is a snapshot of online users).
  const beat = () => fetch('/api/heartbeat', { method: 'POST', credentials: 'same-origin' }).catch(() => {});
  await beat();   // the first heartbeat finishes before the page loads, so someone who just came online is already
                  // added to the open votes (late joiners) when the page asks for them
  setInterval(beat, 30000);
  return me;
}
