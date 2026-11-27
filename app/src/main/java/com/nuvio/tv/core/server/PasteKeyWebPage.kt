package com.nuvio.tv.core.server

import android.content.Context
import androidx.annotation.StringRes
import com.nuvio.tv.R

/**
 * One pasted value's phone page wording, from its owner (Spec 106 K3: Simkl's app id; Spec 105: MDBList's key).
 * [hint] may hold one `%1$s`, filled with [hintBold] and drawn bold. [maxLength] caps the field.
 */
data class PasteKeyPageStrings(
    @StringRes val title: Int,
    @StringRes val intro: Int,
    @StringRes val placeholder: Int,
    @StringRes val hint: Int,
    @StringRes val hintBold: Int?,
    @StringRes val savedBody: Int,
    @StringRes val rejectedTitle: Int,
    @StringRes val rejectedBody: Int,
    @StringRes val unreachable: Int,
    val maxLength: Int
)

/**
 * The phone page of [PasteKeyServer] (sandbox `spec-106-simkl` v2 stage K3; TmdbKeyWebPage's type and controls): one
 * field (whitespace dropped), Send to TV, and the TV's answer: Saved on the TV, or not accepted with the field kept for
 * a fix; an unreachable service, a closed panel or a failed send show a line under the button. Inline only (the
 * server's CSP allows nothing else); every text is an app string in the app's language, set through textContent from
 * one escaped JS object, so no text and never a server answer is parsed as HTML.
 */
object PasteKeyWebPage {
    fun html(appContext: Context, strings: PasteKeyPageStrings): String {
        val context = appContext.withAppLocale()
        fun text(id: Int): String = context.getString(id).jsEscaped()
        val bold = strings.hintBold?.let(context::getString).orEmpty()
        val hint = if (strings.hintBold != null) context.getString(strings.hint, bold) else context.getString(strings.hint)
        val at = if (bold.isEmpty()) -1 else hint.indexOf(bold)
        val (hintBefore, hintBoldPart, hintAfter) = if (at >= 0) {
            Triple(hint.substring(0, at), bold, hint.substring(at + bold.length))
        } else {
            Triple(hint, "", "")
        }
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
  .hint { font-size: 12px; line-height: 17px; color: rgba(255,255,255,.5); }
  .hint b { color: rgba(255,255,255,.85); font-weight: 600; }
  .c { text-align: center; }
  #intro, #rejected { display: flex; flex-direction: column; gap: 10px; }
  .fld { width: 100%; height: 54px; border: 0; border-radius: 16px; background: rgba(255,255,255,.08); box-shadow: inset 0 0 0 1.5px rgba(255,255,255,.35); color: #fff; font: inherit; font-size: 15px; font-weight: 500; font-variant-numeric: tabular-nums; padding: 0 14px; outline: none; }
  .fld:focus { box-shadow: inset 0 0 0 1.5px #fff; }
  .fld.bad { box-shadow: inset 0 0 0 1.5px #FF8A80; }
  .fld::placeholder { color: rgba(255,255,255,.4); }
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
      <h1 class="c" data-t="rejectedTitle"></h1>
      <p class="sub c" data-t="rejectedBody"></p>
    </div>
    <input id="value" class="fld" type="text" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" enterkeyhint="send">
    <p id="hint" class="hint"><span id="hintBefore"></span><b id="hintBold"></b><span id="hintAfter"></span></p>
    <div class="actions">
      <div id="conn" class="conn"><i></i><span data-t="connected"></span></div>
      <button id="send" type="button" class="btn" data-t="send"></button>
    </div>
  </section>
  <section id="saved" hidden>
    <div class="res ok"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg></div>
    <h1 class="c" data-t="savedTitle"></h1>
    <p class="sub c" data-t="savedBody"></p>
  </section>
  <p id="err" class="err" role="alert"></p>
</main>
<script>
'use strict';
const T = {
  app: '${text(R.string.app_name)}',
  title: '${text(strings.title)}',
  intro: '${text(strings.intro)}',
  hintBefore: '${hintBefore.jsEscaped()}',
  hintBold: '${hintBoldPart.jsEscaped()}',
  hintAfter: '${hintAfter.jsEscaped()}',
  placeholder: '${text(strings.placeholder)}',
  connected: '${text(R.string.web_photo_connected)}',
  send: '${text(R.string.web_photo_send)}',
  sendAgain: '${text(R.string.web_tmdb_send_again)}',
  checking: '${text(R.string.tmdb_api_key_checking)}',
  savedTitle: '${text(R.string.web_tmdb_saved_title)}',
  savedBody: '${text(strings.savedBody)}',
  rejectedTitle: '${text(strings.rejectedTitle)}',
  rejectedBody: '${text(strings.rejectedBody)}',
  unreachable: '${text(strings.unreachable)}',
  expired: '${text(R.string.web_photo_error_expired)}',
  failed: '${text(R.string.web_paste_error_send)}'
};
const MAX_LENGTH = ${strings.maxLength}, SEND_TIMEOUT_MS = 45000;
const base = location.pathname.endsWith('/') ? location.pathname : location.pathname + '/';
const byId = (id) => document.getElementById(id);
const field = byId('value'), sendButton = byId('send');
let rejected = false;

document.title = T.title;
document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = T[el.dataset.t]; });
['hintBefore', 'hintBold', 'hintAfter'].forEach((id) => { byId(id).textContent = T[id]; });
field.placeholder = T.placeholder;

// A paste often carries whitespace; no value this page takes has any.
function clean(value) { return value.replace(/\s+/g, '').slice(0, MAX_LENGTH); }
function setError(text) { byId('err').textContent = text || ''; }
function setRejected(on) {
  rejected = on;
  ['intro', 'hint', 'conn'].forEach((id) => { byId(id).hidden = on; });
  byId('rejected').hidden = !on;
  field.classList.toggle('bad', on);
}

field.addEventListener('input', () => {
  const value = clean(field.value);
  if (value !== field.value) field.value = value;
  field.classList.remove('bad');
});
field.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') { event.preventDefault(); sendButton.click(); }
});

sendButton.addEventListener('click', async () => {
  const value = clean(field.value);
  if (!value) { field.focus(); return; }
  setError('');
  sendButton.disabled = true;
  sendButton.textContent = T.checking;
  try {
    const response = await fetch(base + 'value', {
      method: 'POST', headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: value, cache: 'no-store',
      // A TV that went away mid-check (asleep, off the Wi-Fi) must not leave the page on "Checking…".
      signal: window.AbortSignal && AbortSignal.timeout ? AbortSignal.timeout(SEND_TIMEOUT_MS) : undefined
    });
    if (response.status === 200) {
      byId('enter').hidden = true;
      byId('saved').hidden = false;
    } else if (response.status === 422) {
      setRejected(true);
    } else {
      setRejected(false);
      setError(response.status === 503 ? T.unreachable : response.status === 404 || response.status === 410 ? T.expired : T.failed);
    }
  } catch (e) {
    setRejected(false);
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
}
