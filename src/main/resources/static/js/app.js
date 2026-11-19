/*
 * Shared frontend code: API client, toast, tiny DOM helper, page header, heartbeat.
 * Every request to the backend goes through `api` so a future frontend can port this one file.
 * User-provided text is only ever inserted with textContent / append(string), never innerHTML (XSS).
 */

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
      throw new ApiError(res.status, problem.detail || problem.error || 'Something went wrong (' + res.status + ').');
    }
    return res.status === 204 ? null : res.json();
  },
  get: (url) => api.request('GET', url),
  post: (url, body) => api.request('POST', url, body === undefined ? {} : body),
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

/** Renders the top bar and starts the heartbeat. Returns the signed-in user. */
async function initPage() {
  const me = await api.get('/api/auth/me');
  const nav = el('nav', {},
    el('a', { href: '/' }, 'Votes'),
    me.role === 'ADMIN' ? el('a', { href: '/users.html' }, 'Users') : null);
  const logout = el('form', { method: 'post', action: '/logout' },
    el('button', { type: 'submit', class: 'secondary' }, 'Sign out'));
  document.body.prepend(el('header', { class: 'topbar' },
    el('strong', {}, 'Voting platform'), nav,
    el('span', { class: 'spacer' }),
    el('span', { class: 'who' }, me.username, ' ', el('span', { class: 'badge' }, me.role)),
    logout));

  // Tells the server this user is online (poll eligibility is a snapshot of online users).
  const beat = () => fetch('/api/heartbeat', { method: 'POST', credentials: 'same-origin' }).catch(() => {});
  beat();
  setInterval(beat, 30000);
  return me;
}
