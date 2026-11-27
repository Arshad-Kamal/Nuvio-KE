package com.nuvio.tv.core.backup

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.server.jsEscaped
import com.nuvio.tv.core.server.withAppLocale

/**
 * The phone page of [BackupPhoneServer] (mockup b6 v12): SAVE lists this TV's manual backups to download
 * (D46), RESTORE sends one file to the TV, which checks it and answers with the reason. Inline only (the
 * server's CSP allows nothing else). Every text is an app string set through textContent from one escaped
 * JS object, and list rows are built with createElement: no text, file name or server answer is parsed as
 * HTML. Every request is relative to the page's own path (the token rides in it).
 */
object BackupPhoneWebPage {
    /** On top of jsEscaped: nothing in a string can close the script or end the JS line (the device name is free text). */
    internal fun String.scriptSafe(): String =
        replace("<", "\\x3C").replace("\r", "\\r").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    fun html(appContext: Context, mode: BackupPhoneMode, deviceName: String): String {
        val context = appContext.withAppLocale()
        fun text(id: Int, vararg args: Any): String = context.getString(id, *args).jsEscaped().scriptSafe()
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
  .brand { font-size: 15px; line-height: 20px; font-weight: 700; }
  section { flex: 1; display: flex; flex-direction: column; gap: 14px; }
  h1 { font-size: 26px; line-height: 32px; font-weight: 700; letter-spacing: -0.3px; }
  .sub { font-size: 14px; line-height: 20px; color: rgba(255,255,255,.5); }
  .list { display: flex; flex-direction: column; gap: 10px; }
  .item { display: flex; align-items: center; gap: 12px; padding: 14px 14px 14px 16px; border-radius: 14px; background: rgba(255,255,255,.08); color: #fff; text-decoration: none; }
  .item div { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
  .item b { font-size: 16px; line-height: 21px; }
  .item small { font-size: 13px; line-height: 18px; color: rgba(255,255,255,.5); }
  .item i { width: 36px; height: 36px; border-radius: 50%; background: #fff; display: flex; align-items: center; justify-content: center; }
  .item i svg { width: 20px; height: 20px; fill: #000; }
  .foot { margin-top: auto; }
  .drop { min-height: 120px; border-radius: 16px; border: 1.5px dashed rgba(255,255,255,.3); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; text-align: center; padding: 16px; cursor: pointer; }
  .drop b { font-size: 17px; line-height: 23px; word-break: break-word; }
  .drop span { font-size: 13px; line-height: 18px; color: rgba(255,255,255,.5); }
  .drop input { position: absolute; width: 1px; height: 1px; opacity: 0; }
  .actions { margin-top: auto; display: flex; flex-direction: column; gap: 10px; }
  .conn { display: flex; align-items: center; gap: 8px; font-size: 13px; color: rgba(255,255,255,.5); }
  .conn i { width: 8px; height: 8px; border-radius: 50%; background: #4CAF50; }
  .btn { height: 52px; border: 0; border-radius: 26px; background: #fff; color: #000; font: inherit; font-size: 16px; font-weight: 700; }
  .btn:disabled { opacity: .5; }
  .ok { width: 88px; height: 88px; border-radius: 50%; background: rgba(76,175,80,.16); display: flex; align-items: center; justify-content: center; margin: 48px auto 6px; }
  .ok svg { width: 46px; height: 46px; fill: #7BD27F; }
  #sent h1, #sent .sub { text-align: center; }
  .err { min-height: 20px; font-size: 14px; line-height: 20px; color: #F28B82; text-align: center; }
</style>
</head>
<body>
<main class="pg">
  <div class="brand" data-t="app"></div>
  <section id="save" hidden>
    <h1 data-t="saveTitle"></h1>
    <p class="sub" data-t="saveSub"></p>
    <div id="list" class="list"></div>
    <p id="empty" class="sub" data-t="empty" hidden></p>
    <p class="sub foot" data-t="private"></p>
  </section>
  <section id="restore" hidden>
    <h1 data-t="restoreTitle"></h1>
    <p class="sub" data-t="restoreSub"></p>
    <label class="drop"><b id="fileName" data-t="choose"></b><span id="fileSub"></span><input id="file" type="file"></label>
    <div class="actions">
      <div class="conn"><i></i><span data-t="connected"></span></div>
      <button id="send" type="button" class="btn" data-t="send" disabled></button>
    </div>
  </section>
  <section id="sent" hidden>
    <div class="ok"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg></div>
    <h1 data-t="sentTitle"></h1>
    <p class="sub" data-t="sentBody"></p>
  </section>
  <p id="err" class="err" role="alert"></p>
</main>
<script>
'use strict';
const T = {
  app: '${text(R.string.app_name)}',
  saveTitle: '${text(R.string.web_backup_save_title)}',
  saveSub: '${text(R.string.web_backup_save_sub, deviceName)}',
  private: '${text(R.string.web_backup_save_private)}',
  empty: '${text(R.string.web_backup_save_empty)}',
  restoreTitle: '${text(R.string.web_backup_restore_title)}',
  restoreSub: '${text(R.string.web_backup_restore_sub)}',
  choose: '${text(R.string.web_backup_choose)}',
  chooseAnother: '${text(R.string.web_backup_choose_another, "%s")}',
  connected: '${text(R.string.web_backup_connected)}',
  send: '${text(R.string.web_backup_send)}',
  sending: '${text(R.string.web_backup_sending)}',
  sentTitle: '${text(R.string.web_backup_sent_title)}',
  sentBody: '${text(R.string.web_backup_sent_body)}',
  ${BackupPhoneServer.NOT_A_BACKUP}: '${text(R.string.web_backup_error_not_backup)}',
  ${BackupPhoneServer.NEEDS_NEWER_APP}: '${text(R.string.web_backup_error_newer)}',
  ${BackupPhoneServer.TOO_OLD}: '${text(R.string.web_backup_error_older)}',
  ${BackupPhoneServer.TOO_LARGE}: '${text(R.string.web_backup_error_too_large)}',
  ${BackupPhoneServer.DAMAGED}: '${text(R.string.web_backup_error_damaged)}',
  ${BackupPhoneServer.BUSY}: '${text(R.string.web_backup_error_busy)}',
  expired: '${text(R.string.web_photo_error_expired)}',
  ${BackupPhoneServer.FAILED}: '${text(R.string.web_backup_error_send)}'
};
const MODE = '${if (mode == BackupPhoneMode.SAVE) "save" else "restore"}';
const MAX_BYTES = ${BackupLimits.PHONE_COMPRESSED_MAX_BYTES};
const SEND_TIMEOUT_MS = 120000;
const base = location.pathname.endsWith('/') ? location.pathname : location.pathname + '/';
const byId = (id) => document.getElementById(id);
document.title = T.app;
document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = T[el.dataset.t]; });
function setError(text) { byId('err').textContent = text || ''; }
function show(id) { ['save', 'restore', 'sent'].forEach((s) => { byId(s).hidden = s !== id; }); }
function mb(bytes) { return (bytes / 1048576).toFixed(1) + ' MB'; }

async function loadList() {
  let items;
  try {
    const answer = await fetch(base + 'list', { cache: 'no-store' });
    if (answer.status === 404) { setError(T.expired); return; }
    if (!answer.ok) throw new Error('list');
    items = await answer.json();
  } catch (e) {
    setError(T.failed);
    return;
  }
  const list = byId('list');
  items.forEach((item) => {
    const a = document.createElement('a');
    a.className = 'item';
    a.href = base + 'f/' + encodeURIComponent(item.id);
    a.setAttribute('download', '');
    const text = document.createElement('div');
    const title = document.createElement('b');
    title.textContent = item.title;
    const detail = document.createElement('small');
    detail.textContent = item.detail;
    text.append(title, detail);
    const icon = document.createElement('i');
    icon.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>';
    a.append(text, icon);
    list.append(a);
  });
  byId('empty').hidden = items.length > 0;
}

let chosen = null;
byId('file').addEventListener('change', (event) => {
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  if (!file) return;
  setError('');
  if (file.size > MAX_BYTES) { chosen = null; byId('send').disabled = true; setError(T.${BackupPhoneServer.TOO_LARGE}); return; }
  chosen = file;
  byId('fileName').textContent = file.name;
  byId('fileSub').textContent = T.chooseAnother.replace('%s', mb(file.size));
  byId('send').disabled = false;
});
byId('send').addEventListener('click', async () => {
  if (!chosen) return;
  const button = byId('send');
  button.disabled = true;
  button.textContent = T.sending;
  setError('');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), SEND_TIMEOUT_MS);
  try {
    const answer = await fetch(base + 'backup', { method: 'POST', body: chosen, headers: { 'Content-Type': 'application/gzip' }, signal: controller.signal });
    const word = (await answer.text()).trim();
    if (answer.ok) { show('sent'); return; }
    setError(answer.status === 404 ? T.expired : (Object.prototype.hasOwnProperty.call(T, word) ? T[word] : T.failed));
  } catch (e) {
    setError(T.failed);
  } finally {
    clearTimeout(timer);
    button.textContent = T.send;
    button.disabled = !chosen;
  }
});

show(MODE);
if (MODE === 'save') loadList();
</script>
</body>
</html>
""".trimIndent()
    }
}
