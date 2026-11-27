package com.nuvio.tv.core.posters

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.server.jsEscaped
import com.nuvio.tv.core.server.withAppLocale

/**
 * The phone page of [RatingPosterServer] (sandbox `s114-rating-posters` stages 3 and 7, the Spec 52 phone
 * page's type and controls): one box for the address, the rules hint, Send to TV. The answer waits for the
 * TV's test poster: Saved (with the amber http warning for a home-network http address), or Not accepted
 * with its cause and the box kept for a fix. The page never receives the saved address back (P0-6): what
 * the box holds is only what this phone typed. Inline only (the server's CSP allows nothing else); every
 * text is an app string set through textContent from one escaped JS object, so no text and never a server
 * answer is parsed as HTML.
 */
object RatingPosterWebPage {
    fun html(appContext: Context): String {
        val context = appContext.withAppLocale()
        fun text(id: Int): String = context.getString(id).jsEscaped()
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title></title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
  [hidden] { display: none !important; }
  html, body { background: #000; color: #fff; min-height: 100%; }
  body { font-family: Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
  .pg { max-width: 480px; min-height: 100vh; min-height: 100dvh; margin: 0 auto; padding: 20px 18px 24px; display: flex; flex-direction: column; gap: 14px; }
  .brand { font-size: 15px; line-height: 20px; font-weight: 700; color: #fff; }
  section { flex: 1; display: flex; flex-direction: column; gap: 14px; }
  h1 { font-size: 26px; line-height: 32px; font-weight: 700; letter-spacing: -0.3px; }
  .sub { font-size: 14px; line-height: 20px; color: rgba(255,255,255,.5); }
  .c { text-align: center; }
  #intro, #rejected { display: flex; flex-direction: column; gap: 10px; }
  .fld { width: 100%; min-height: 132px; border: 0; border-radius: 16px; background: rgba(255,255,255,.08); box-shadow: inset 0 0 0 1.5px rgba(255,255,255,.35); color: #fff; font: 500 13px/18px ui-monospace, 'SFMono-Regular', Menlo, 'DejaVu Sans Mono', monospace; padding: 12px 14px; outline: none; resize: vertical; word-break: break-all; }
  .fld:focus { box-shadow: inset 0 0 0 1.5px #fff; }
  .fld.bad { box-shadow: inset 0 0 0 1.5px #FF8A80; }
  .hint { font-size: 13px; line-height: 18px; color: rgba(255,255,255,.5); }
  .hint code { font: 600 13px/18px ui-monospace, 'SFMono-Regular', Menlo, 'DejaVu Sans Mono', monospace; color: #fff; }
  .conn { display: flex; align-items: center; gap: 8px; font-size: 13px; color: rgba(255,255,255,.5); }
  .conn i { width: 8px; height: 8px; border-radius: 50%; background: #4CAF50; }
  .actions { margin-top: auto; display: flex; flex-direction: column; gap: 10px; }
  .btn { height: 52px; border: 0; border-radius: 26px; background: #fff; color: #000; font: inherit; font-size: 16px; font-weight: 700; }
  .btn:disabled { opacity: .6; }
  .res { width: 88px; height: 88px; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 32px auto 6px; }
  .res svg { width: 46px; height: 46px; }
  .res.ok { background: rgba(76,175,80,.16); }
  .res.ok svg { fill: #7BD27F; }
  .res.bad { background: rgba(255,138,128,.14); margin-top: 8px; }
  .res.bad svg { fill: #FF8A80; }
  #saved .res { margin-top: 48px; }
  .warn { display: flex; gap: 10px; align-items: flex-start; font-size: 14px; line-height: 20px; font-weight: 600; color: #FFCC80; padding: 12px 14px; border-radius: 14px; background: rgba(255,183,77,.1); }
  .warn svg { flex: none; width: 20px; height: 20px; fill: #FFB74D; }
  .err { min-height: 20px; font-size: 14px; line-height: 20px; color: #FF8A80; text-align: center; }
</style>
</head>
<body>
<main class="pg">
  <div class="brand" data-t="app"></div>
  <section id="enter">
    <div id="intro">
      <h1 data-t="title"></h1>
      <p class="sub" data-t="intro"></p>
    </div>
    <div id="rejected" hidden>
      <div class="res bad"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"/></svg></div>
      <h1 id="rejectedTitle" class="c"></h1>
      <p id="rejectedBody" class="sub c"></p>
    </div>
    <textarea id="address" class="fld" rows="5" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" maxlength="$MAX_INPUT_LENGTH"></textarea>
    <p id="hint" class="hint"></p>
    <div class="actions">
      <div id="conn" class="conn"><i></i><span data-t="connected"></span></div>
      <button id="send" type="button" class="btn" data-t="send"></button>
    </div>
  </section>
  <section id="saved" hidden>
    <div class="res ok"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg></div>
    <h1 class="c" data-t="savedTitle"></h1>
    <p id="savedBody" class="sub c"></p>
    <div id="warn" class="warn" hidden><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg><span data-t="httpWarning"></span></div>
  </section>
  <p id="err" class="err" role="alert"></p>
</main>
<script>
'use strict';
const T = {
  app: '${text(R.string.app_name)}',
  title: '${text(R.string.rating_posters_title)}',
  intro: '${text(R.string.web_posters_intro)}',
  hint: '${text(R.string.web_posters_hint)}',
  connected: '${text(R.string.web_photo_connected)}',
  send: '${text(R.string.web_photo_send)}',
  sendAgain: '${text(R.string.web_tmdb_send_again)}',
  checking: '${text(R.string.tmdb_api_key_checking)}',
  savedTitle: '${text(R.string.web_tmdb_saved_title)}',
  savedBody: '${text(R.string.web_posters_saved_body)}',
  savedBodyHttp: '${text(R.string.web_posters_saved_body_http)}',
  httpWarning: '${text(R.string.web_posters_http_warning)}',
  failedTitle: '${text(R.string.web_posters_failed_title)}',
  keyBody: '${text(R.string.web_posters_key_body)}',
  addressBody: '${text(R.string.web_posters_address_body)}',
  noPosterBody: '${text(R.string.web_posters_no_poster_body)}',
  httpsTitle: '${text(R.string.web_posters_https_title)}',
  httpsBody: '${text(R.string.web_posters_https_body)}',
  formatTitle: '${text(R.string.web_posters_format_title)}',
  formatBody: '${text(R.string.web_posters_format_body)}',
  noIdBody: '${text(R.string.web_posters_no_id_body)}',
  untestableBody: '${text(R.string.web_posters_untestable_body)}',
  expired: '${text(R.string.web_photo_error_expired)}',
  failed: '${text(R.string.web_posters_error_send)}'
};
const SEND_TIMEOUT_MS = 30000;
const base = location.pathname.endsWith('/') ? location.pathname : location.pathname + '/';
const byId = (id) => document.getElementById(id);
const field = byId('address'), sendButton = byId('send');
let rejected = false;

document.title = T.title;
document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = T[el.dataset.t]; });

// The hint's {placeholders} in code type, as text nodes only.
T.hint.split(/(\{[a-z_|?]+\})/).forEach((part) => {
  if (!part) return;
  const node = /^\{.*\}$/.test(part) ? document.createElement('code') : document.createElement('span');
  node.textContent = part;
  byId('hint').appendChild(node);
});

// %1${'$'}s / %2${'$'}d in an app string, filled by plain replacement (the values come from the TV or this phone).
function fill(template, host, code) {
  return template.split('%1${'$'}s').join(host || '').split('%2${'$'}d').join(code == null ? '' : String(code));
}
function hostOf(value) {
  try { return new URL(value).host; } catch (e) { return ''; }
}
function setError(text) { byId('err').textContent = text || ''; }
function setRejected(title, body) {
  rejected = title != null;
  ['intro', 'conn'].forEach((id) => { byId(id).hidden = rejected; });
  byId('rejected').hidden = !rejected;
  byId('rejectedTitle').textContent = title || '';
  byId('rejectedBody').textContent = body || '';
  field.classList.toggle('bad', rejected);
}
function showRefusal(answer, sent) {
  const host = answer.host || '';
  switch (answer.problem) {
    case 'key': return setRejected(T.failedTitle, fill(T.keyBody, host, answer.code));
    case 'address': return setRejected(T.failedTitle, fill(T.addressBody, host));
    case 'no_poster': return setRejected(T.failedTitle, fill(T.noPosterBody, host));
    case 'https': return setRejected(T.httpsTitle, fill(T.httpsBody, hostOf(sent)));
    case 'no_id': return setRejected(T.formatTitle, T.noIdBody);
    case 'untestable': return setRejected(T.formatTitle, T.untestableBody);
    default: return setRejected(T.formatTitle, T.formatBody);
  }
}

field.addEventListener('input', () => { field.classList.remove('bad'); });

sendButton.addEventListener('click', async () => {
  const address = field.value.trim();
  if (!address) { field.focus(); return; }
  setError('');
  sendButton.disabled = true;
  sendButton.textContent = T.checking;
  try {
    const response = await fetch(base + 'address', {
      method: 'POST', headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: address, cache: 'no-store',
      // A TV that went away mid-check (asleep, off the Wi-Fi) must not leave the page on "Checking…".
      signal: window.AbortSignal && AbortSignal.timeout ? AbortSignal.timeout(SEND_TIMEOUT_MS) : undefined
    });
    if (response.status === 200) {
      const answer = await response.json();
      byId('savedBody').textContent = fill(answer.http ? T.savedBodyHttp : T.savedBody, answer.host);
      byId('warn').hidden = !answer.http;
      byId('enter').hidden = true;
      byId('saved').hidden = false;
      // The box held the key: nothing of it stays on the page once the TV has it.
      field.value = '';
    } else if (response.status === 422) {
      showRefusal(await response.json(), address);
    } else {
      setRejected(null);
      setError(response.status === 404 || response.status === 410 ? T.expired : T.failed);
    }
  } catch (e) {
    setRejected(null);
    setError(T.failed);
  } finally {
    sendButton.disabled = false;
    sendButton.textContent = rejected ? T.sendAgain : T.send;
  }
});
</script>
</body>
</html>
""".trimIndent()
    }

    /** [RatingPosterTemplate.MAX_LENGTH] plus room for a paste's surrounding whitespace. */
    private const val MAX_INPUT_LENGTH = RatingPosterTemplate.MAX_LENGTH + 64
}
