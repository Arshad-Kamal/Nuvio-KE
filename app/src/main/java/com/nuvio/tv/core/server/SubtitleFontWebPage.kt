package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.data.local.UserSubtitleFontStore

/**
 * The phone page of [SubtitleFontServer] (sandbox spec-132-subtitle-fonts stages 3 and 8): choose a
 * file, see its name, size and a sample line drawn in it (the browser's own render, a quick look;
 * the TV's is what counts), send it raw. The page refuses a name that is not .ttf/.otf or a file
 * over the cap before sending; the TV checks again. Inline only (the server's CSP allows a blob:
 * font and nothing else). Every text is an app string in the app's language, set through
 * textContent from one escaped JS object, so no text (and never a file name, family name or server
 * answer) is parsed as HTML.
 */
object SubtitleFontWebPage {
    fun html(appContext: Context): String {
        val context = appContext.withAppLocale()
        fun text(id: Int): String = context.getString(id).jsEscaped()
        val maxMb = UserSubtitleFontStore.MAX_BYTES / (1024 * 1024)
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
  h1 { font-size: 26px; line-height: 32px; font-weight: 700; letter-spacing: -0.3px; overflow-wrap: anywhere; }
  .sub { font-size: 14px; line-height: 19px; color: rgba(255,255,255,.5); overflow-wrap: anywhere; }
  .drop { flex: 1; min-height: 220px; border-radius: 20px; box-shadow: inset 0 0 0 1.5px rgba(255,255,255,.22); background: rgba(255,255,255,.04); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; text-align: center; padding: 0 20px; cursor: pointer; }
  .drop svg { width: 44px; height: 44px; fill: #fff; }
  .drop b { font-size: 18px; line-height: 24px; }
  .drop span { font-size: 14px; line-height: 19px; color: rgba(255,255,255,.5); }
  .drop input { position: absolute; width: 1px; height: 1px; opacity: 0; }
  .file { display: flex; align-items: center; gap: 12px; border-radius: 16px; background: rgba(255,255,255,.08); box-shadow: inset 0 0 0 1.5px #fff; padding: 12px 14px; }
  .file i { flex: none; width: 36px; height: 44px; border-radius: 7px; background: #9EC3D8; color: #000; font: 800 11px/44px Inter, sans-serif; font-style: normal; text-align: center; text-transform: uppercase; }
  .file b { display: block; font-size: 15px; line-height: 20px; overflow-wrap: anywhere; }
  .file span { display: block; font-size: 13px; line-height: 18px; color: rgba(255,255,255,.5); }
  .sample { border-radius: 16px; background: linear-gradient(160deg, #2a3440, #101418); min-height: 110px; position: relative; display: flex; align-items: flex-end; justify-content: center; padding: 28px 14px 16px; }
  .sample small { position: absolute; left: 12px; top: 9px; font-size: 10px; line-height: 13px; font-weight: 600; letter-spacing: 1.4px; color: rgba(255,255,255,.75); text-transform: uppercase; }
  .sample p { font-family: UserFont, sans-serif; font-size: 22px; line-height: 28px; text-align: center; text-shadow: 0 0 2px #000, 0 0 3px #000; overflow-wrap: anywhere; }
  .conn { display: flex; align-items: center; gap: 8px; font-size: 13px; color: rgba(255,255,255,.5); }
  .conn i { width: 8px; height: 8px; border-radius: 50%; background: #4CAF50; }
  .actions { margin-top: auto; display: flex; flex-direction: column; gap: 10px; }
  .btn { height: 52px; border: 0; border-radius: 26px; background: #fff; color: #000; font: inherit; font-size: 16px; font-weight: 700; }
  .btn.gh { background: rgba(255,255,255,.1); color: #fff; }
  .btn:disabled { opacity: .6; }
  .res { width: 88px; height: 88px; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 48px auto 6px; }
  .res svg { width: 46px; height: 46px; }
  .res.ok { background: rgba(76,175,80,.16); } .res.ok svg { fill: #7BD27F; }
  .res.bad { background: rgba(255,138,128,.14); } .res.bad svg { fill: #FF8A80; }
  #done h1, #done .sub { text-align: center; }
  .err { min-height: 20px; font-size: 14px; line-height: 20px; color: #F28B82; text-align: center; }
</style>
</head>
<body>
<main class="pg">
  <div class="brand" data-t="app"></div>
  <section id="pick">
    <h1 data-t="title"></h1>
    <p class="sub" id="limits"></p>
    <label class="drop">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 4v3h5v12h3V7h5V4H9zm-6 8h3v7h3v-7h3V9H3v3z"/></svg>
      <b data-t="choose"></b>
      <span data-t="chooseSub"></span>
      <input id="file" type="file">
    </label>
    <div class="conn"><i></i><span data-t="connected"></span></div>
  </section>
  <section id="chosen" hidden>
    <h1 data-t="title"></h1>
    <div class="file"><i id="ext"></i><div><b id="fileName"></b><span id="fileSize"></span></div></div>
    <div class="sample"><small data-t="preview"></small><p id="sample"></p></div>
    <button id="another" type="button" class="btn gh" data-t="chooseAnother"></button>
    <div class="actions">
      <div class="conn"><i></i><span data-t="connected"></span></div>
      <button id="send" type="button" class="btn" data-t="send"></button>
    </div>
  </section>
  <section id="done" hidden>
    <div id="mark" class="res"><svg viewBox="0 0 24 24" aria-hidden="true"><path id="markPath" d=""/></svg></div>
    <h1 id="doneTitle"></h1>
    <p class="sub" id="doneBody"></p>
    <div class="actions"><button id="doneButton" type="button" class="btn"></button></div>
  </section>
  <p id="err" class="err" role="alert"></p>
</main>
<script>
'use strict';
const T = {
  app: '${text(R.string.app_name)}',
  title: '${text(R.string.web_font_title)}',
  limits: '${text(R.string.web_font_sub)}',
  choose: '${text(R.string.web_font_choose)}',
  chooseSub: '${text(R.string.web_font_choose_sub)}',
  chooseAnother: '${text(R.string.web_font_choose_another)}',
  preview: '${text(R.string.web_font_preview)}',
  sample: '${text(R.string.sub_preview_line)}',
  connected: '${text(R.string.web_photo_connected)}',
  send: '${text(R.string.web_photo_send)}',
  sending: '${text(R.string.web_photo_sending)}',
  savedTitle: '${text(R.string.web_font_saved_title)}',
  savedBody: '${text(R.string.web_font_saved_body)}',
  sendAnother: '${text(R.string.web_font_send_another)}',
  notFontTitle: '${text(R.string.web_font_not_font_title)}',
  notFontBody: '${text(R.string.web_font_not_font_body)}',
  tooBigTitle: '${text(R.string.web_font_too_big_title)}',
  tooBigBody: '${text(R.string.web_font_too_big_body)}',
  fullTitle: '${text(R.string.web_font_full_title)}',
  fullBody: '${text(R.string.web_font_full_body)}',
  sendAgain: '${text(R.string.web_tmdb_send_again)}',
  alreadyTitle: '${text(R.string.web_font_already_title)}',
  alreadyBody: '${text(R.string.web_font_already_body)}',
  expired: '${text(R.string.web_photo_error_expired)}',
  busy: '${text(R.string.web_font_error_busy)}',
  failed: '${text(R.string.web_font_error_send)}'
};
const MAX_BYTES = ${UserSubtitleFontStore.MAX_BYTES};
const MAX_MB = $maxMb, CAP = ${UserSubtitleFontStore.CAP}, SEND_TIMEOUT_MS = 60000;
const OK_PATH = 'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z';
const BAD_PATH = 'M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z';
const base = location.pathname.endsWith('/') ? location.pathname : location.pathname + '/';
const byId = (id) => document.getElementById(id);
const sendButton = byId('send'), anotherButton = byId('another');

// Fills %1${'$'}s / %2${'$'}d placeholders; the result only ever reaches textContent.
function fmt(template, ...args) {
  return template.replace(/%(\d)\${'$'}[sd]/g, (m, i) => String(args[Number(i) - 1]));
}
function sizeText(bytes) {
  return bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB';
}

document.title = T.title;
document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = T[el.dataset.t]; });
byId('limits').textContent = fmt(T.limits, MAX_MB, CAP);
byId('sample').textContent = T.sample;

// [pick] counts file choices, so a slow preview load from an earlier choice is dropped.
let chosen = null, previewFont = null, pick = 0;

function show(id) { ['pick', 'chosen', 'done'].forEach((s) => { byId(s).hidden = s !== id; }); }
function setError(text) { byId('err').textContent = text || ''; }
function extension(name) { const dot = name.lastIndexOf('.'); return dot < 0 ? '' : name.slice(dot + 1).toLowerCase(); }

// A result screen; its button goes back to [backTo]: choosing a file, or the chosen one (TV full: send again).
let backTo = 'pick';
function done(ok, title, body, button, back) {
  backTo = back || 'pick';
  byId('mark').className = 'res ' + (ok ? 'ok' : 'bad');
  byId('markPath').setAttribute('d', ok ? OK_PATH : BAD_PATH);
  byId('doneTitle').textContent = title;
  byId('doneBody').textContent = body;
  byId('doneButton').textContent = button;
  byId('doneButton').className = ok ? 'btn gh' : 'btn';
  setError('');
  show('done');
}

function refuse(name, size) {
  if (!['ttf', 'otf'].includes(extension(name))) {
    done(false, T.notFontTitle, fmt(T.notFontBody, name), T.chooseAnother);
    return true;
  }
  if (size > MAX_BYTES) {
    done(false, T.tooBigTitle, fmt(T.tooBigBody, name, sizeText(size), MAX_MB), T.chooseAnother);
    return true;
  }
  return false;
}

byId('file').addEventListener('change', async (event) => {
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  if (!file) return;
  setError('');
  // Counted before refuse(), so a refused pick also drops a pending preview of an earlier one.
  const mine = ++pick;
  if (refuse(file.name, file.size)) return;
  chosen = file;
  byId('ext').textContent = extension(file.name);
  byId('fileName').textContent = file.name;
  byId('fileSize').textContent = sizeText(file.size);
  if (previewFont) { document.fonts.delete(previewFont); previewFont = null; }
  const url = URL.createObjectURL(file);
  let face = null;
  try {
    face = new FontFace('UserFont', 'url(' + JSON.stringify(url) + ')');
    await face.load();
  } catch (e) {
    face = null; // No preview: the TV still decides whether the file is a font.
  } finally {
    URL.revokeObjectURL(url);
  }
  if (mine !== pick) return;
  if (face) { document.fonts.add(face); previewFont = face; }
  show('chosen');
});

sendButton.addEventListener('click', async () => {
  if (!chosen) return;
  // The file being sent, fixed for this send even if the screen changes.
  const file = chosen;
  setError('');
  sendButton.disabled = true;
  anotherButton.disabled = true;
  sendButton.textContent = T.sending;
  try {
    const response = await fetch(base + 'font?name=' + encodeURIComponent(file.name), {
      method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file, cache: 'no-store',
      // A TV that went away mid-send (asleep, off the Wi-Fi) must not leave the page on "Sending…".
      signal: window.AbortSignal && AbortSignal.timeout ? AbortSignal.timeout(SEND_TIMEOUT_MS) : undefined
    });
    let answer = {};
    try { answer = await response.json(); } catch (e) { answer = {}; }
    const family = typeof answer.family === 'string' ? answer.family : file.name;
    switch (answer.result) {
      case '${SubtitleFontServer.SAVED}':
        done(true, fmt(T.savedTitle, family), fmt(T.savedBody, Number(answer.count) || 0, CAP), T.sendAnother); break;
      case '${SubtitleFontServer.ALREADY}':
        done(true, T.alreadyTitle, fmt(T.alreadyBody, family), T.sendAnother); break;
      case '${SubtitleFontServer.FULL}':
        done(false, fmt(T.fullTitle, CAP), T.fullBody, T.sendAgain, 'chosen'); break;
      case '${SubtitleFontServer.TOO_BIG}':
        done(false, T.tooBigTitle, fmt(T.tooBigBody, file.name, sizeText(file.size), MAX_MB), T.chooseAnother); break;
      case '${SubtitleFontServer.NOT_A_FONT}':
        done(false, T.notFontTitle, fmt(T.notFontBody, file.name), T.chooseAnother); break;
      case '${SubtitleFontServer.BUSY}':
        setError(T.busy); break;
      default:
        setError(response.status === 404 ? T.expired : T.failed);
    }
  } catch (e) {
    setError(T.failed);
  } finally {
    sendButton.disabled = false;
    anotherButton.disabled = false;
    sendButton.textContent = T.send;
  }
});

anotherButton.addEventListener('click', () => { setError(''); show('pick'); });
byId('doneButton').addEventListener('click', () => { setError(''); show(backTo); });
</script>
</body>
</html>
""".trimIndent()
    }
}
