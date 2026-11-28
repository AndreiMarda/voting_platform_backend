/* The user's own email and ID ONCR. Also where a user lands when they still have to add their email. */

(async function main() {
  let me = await initPage();
  const form = document.getElementById('f');
  const error = document.getElementById('error');
  const intro = document.getElementById('intro');

  function fill() {
    document.getElementById('username').value = me.username;
    document.getElementById('email').value = me.email || '';
    document.getElementById('idOncr').value = me.idOncr || '';
    intro.textContent = me.mustAddEmail
      ? 'Please add your email address before you continue.'
      : 'Your details. You can change them at any time.';
  }
  fill();

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    error.hidden = true;
    try {
      me = await api.request('PUT', '/api/auth/profile', {
        email: document.getElementById('email').value,
        idOncr: document.getElementById('idOncr').value,
      });
      toast('Your profile was saved.');
      fill();
      if (!me.mustAddEmail && new URLSearchParams(location.search).has('next')) location.href = '/';
    } catch (err) {
      error.textContent = err.message;
      error.hidden = false;
    }
  });
})();
