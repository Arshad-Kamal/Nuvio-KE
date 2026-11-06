package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.data.local.MyPhotoKind

/**
 * The phone page of [PhotoUploadServer] (sandbox u44 stage 4): pick Avatar or Background, choose a
 * photo, crop it (drag, pinch, slider) straight into a D6-size canvas (no full-size copy: iOS
 * canvas memory), send it as a JPEG. Inline only (the server's CSP allows nothing else). Every
 * text is an app string in the app's language, set through textContent from one escaped JS
 * object, so no text (and never a file name or a server answer) is parsed as HTML.
 */
object PhotoUploadWebPage {
    fun html(appContext: Context): String {
        val context = appContext.withAppLocale()
        fun text(id: Int): String = context.getString(id).jsEscaped()
        val avatar = MyPhotoKind.Avatar
        val background = MyPhotoKind.Background
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
  .sub { font-size: 13px; line-height: 18px; color: rgba(255,255,255,.5); text-align: center; }
  .seg { display: flex; background: rgba(255,255,255,.08); border-radius: 14px; padding: 4px; }
  .seg button { flex: 1; height: 38px; border: 0; border-radius: 11px; background: transparent; color: rgba(255,255,255,.72); font: inherit; font-size: 15px; font-weight: 600; }
  .seg button.sel { background: #fff; color: #000; }
  .drop { flex: 1; min-height: 260px; border-radius: 20px; box-shadow: inset 0 0 0 1.5px rgba(255,255,255,.22); background: rgba(255,255,255,.04); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; text-align: center; padding: 0 20px; cursor: pointer; }
  .drop svg { width: 44px; height: 44px; fill: #fff; }
  .drop b { font-size: 18px; line-height: 24px; }
  .drop span { font-size: 14px; line-height: 19px; color: rgba(255,255,255,.5); }
  .drop input { position: absolute; width: 1px; height: 1px; opacity: 0; }
  .conn { display: flex; align-items: center; gap: 8px; font-size: 13px; color: rgba(255,255,255,.5); }
  .conn i { width: 8px; height: 8px; border-radius: 50%; background: #4CAF50; }
  canvas { display: block; width: 100%; border-radius: 16px; background: #111; touch-action: none; }
  .zoom { display: flex; align-items: center; gap: 10px; font-size: 18px; font-weight: 600; color: rgba(255,255,255,.5); }
  .zoom input { flex: 1; accent-color: #fff; }
  .actions { margin-top: auto; display: flex; flex-direction: column; gap: 10px; }
  .btn { height: 52px; border: 0; border-radius: 26px; background: #fff; color: #000; font: inherit; font-size: 16px; font-weight: 700; }
  .btn.gh { background: rgba(255,255,255,.1); color: #fff; }
  .btn:disabled { opacity: .6; }
  .ok { width: 88px; height: 88px; border-radius: 50%; background: rgba(76,175,80,.16); display: flex; align-items: center; justify-content: center; margin: 48px auto 6px; }
  .ok svg { width: 46px; height: 46px; fill: #7BD27F; }
  #sent h1 { text-align: center; }
  .err { min-height: 20px; font-size: 14px; line-height: 20px; color: #F28B82; text-align: center; }
</style>
</head>
<body>
<main class="pg">
  <div class="brand" data-t="app"></div>
  <section id="pick">
    <h1 data-t="title"></h1>
    <div class="seg">
      <button type="button" class="sel" data-kind="${avatar.prefix}" data-t="avatar"></button>
      <button type="button" data-kind="${background.prefix}" data-t="background"></button>
    </div>
    <label class="drop">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"/></svg>
      <b data-t="choose"></b>
      <span data-t="chooseSub"></span>
      <input id="file" type="file" accept="image/*">
    </label>
    <div class="conn"><i></i><span data-t="connected"></span></div>
  </section>
  <section id="crop" hidden>
    <h1 id="cropTitle"></h1>
    <canvas id="canvas"></canvas>
    <div class="zoom"><span>&minus;</span><input id="zoom" type="range" min="100" max="400" value="100"><span>+</span></div>
    <p class="sub" data-t="hint"></p>
    <div class="actions">
      <button id="send" type="button" class="btn" data-t="send"></button>
      <button id="another" type="button" class="btn gh" data-t="chooseAnother"></button>
    </div>
  </section>
  <section id="sent" hidden>
    <div class="ok"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg></div>
    <h1 data-t="sentTitle"></h1>
    <p class="sub" data-t="sentBody"></p>
    <div class="actions"><button id="again" type="button" class="btn" data-t="addAnother"></button></div>
  </section>
  <p id="err" class="err" role="alert"></p>
</main>
<script>
'use strict';
const T = {
  app: '${text(R.string.app_name)}',
  title: '${text(R.string.web_photo_title)}',
  avatar: '${text(R.string.profile_editor_tab_avatar)}',
  background: '${text(R.string.profile_editor_tab_background)}',
  choose: '${text(R.string.web_photo_choose)}',
  chooseSub: '${text(R.string.web_photo_choose_sub)}',
  connected: '${text(R.string.web_photo_connected)}',
  cropAvatar: '${text(R.string.web_photo_crop_avatar)}',
  cropBackground: '${text(R.string.web_photo_crop_background)}',
  hint: '${text(R.string.web_photo_crop_hint)}',
  send: '${text(R.string.web_photo_send)}',
  sending: '${text(R.string.web_photo_sending)}',
  chooseAnother: '${text(R.string.web_photo_choose_another)}',
  sentTitle: '${text(R.string.web_photo_sent_title)}',
  sentBody: '${text(R.string.web_photo_sent_body)}',
  addAnother: '${text(R.string.web_photo_add_another)}',
  cantOpen: '${text(R.string.web_photo_error_open)}',
  expired: '${text(R.string.web_photo_error_expired)}',
  full: '${text(R.string.profile_my_photos_full)}',
  busy: '${text(R.string.web_photo_error_busy)}',
  failed: '${text(R.string.web_photo_error_send)}'
};
const SIZES = {
  '${avatar.prefix}': { w: ${avatar.width}, h: ${avatar.height}, round: true },
  '${background.prefix}': { w: ${background.width}, h: ${background.height}, round: false }
};
const MAX_BYTES = ${PhotoUploadServer.MAX_PHOTO_BYTES};
const MIN_ZOOM = 1, MAX_ZOOM = 4, SEND_TIMEOUT_MS = 30000;
const base = location.pathname.endsWith('/') ? location.pathname : location.pathname + '/';
const byId = (id) => document.getElementById(id);
const canvas = byId('canvas'), ctx = canvas.getContext('2d'), zoomInput = byId('zoom'), sendButton = byId('send');

document.title = T.title;
document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = T[el.dataset.t]; });

let kind = '${avatar.prefix}';
// The chosen photo and its placement in output pixels: (cx, cy) is the photo's centre in the
// output frame, zoom 1 = the smallest scale that covers the frame.
let img = null, imgUrl = null, zoom = 1, cx = 0, cy = 0;
// The frame on screen (CSS px): offset, size, and screen px per output px.
let view = { fx: 0, fy: 0, fw: 0, fh: 0, d: 1 };

function show(id) { ['pick', 'crop', 'sent'].forEach((s) => { byId(s).hidden = s !== id; }); }
function setError(text) { byId('err').textContent = text || ''; }
function size() { return SIZES[kind]; }
function scale() { const s = size(); return zoom * Math.max(s.w / img.naturalWidth, s.h / img.naturalHeight); }

function clampPlacement() {
  const s = size(), k = scale(), hw = img.naturalWidth * k / 2, hh = img.naturalHeight * k / 2;
  cx = Math.min(hw, Math.max(s.w - hw, cx));
  cy = Math.min(hh, Math.max(s.h - hh, cy));
}

function setZoom(next) {
  const s = size(), before = scale();
  zoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next));
  const ratio = scale() / before;
  // Zoom about the frame's centre.
  cx = s.w / 2 - (s.w / 2 - cx) * ratio;
  cy = s.h / 2 - (s.h / 2 - cy) * ratio;
  clampPlacement();
  zoomInput.value = String(Math.round(zoom * 100));
}

function layout() {
  const s = size(), width = canvas.clientWidth || canvas.parentElement.clientWidth;
  const margin = Math.round(width * 0.06), fw = width - 2 * margin, fh = fw * s.h / s.w;
  const height = Math.round(fh + 2 * margin), dpr = window.devicePixelRatio || 1;
  canvas.style.height = height + 'px';
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  view = { fx: margin, fy: margin, fw: fw, fh: fh, d: fw / s.w, width: width, height: height };
}

// The crop frame: a circle for an avatar (how it shows on the TV), else the 16:9 rectangle.
function framePath(v) {
  if (size().round) ctx.arc(v.fx + v.fw / 2, v.fy + v.fh / 2, v.fw / 2, 0, Math.PI * 2);
  else ctx.rect(v.fx, v.fy, v.fw, v.fh);
}

function draw() {
  if (!img) return;
  const k = scale(), v = view;
  ctx.clearRect(0, 0, v.width, v.height);
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, v.fx + (cx - img.naturalWidth * k / 2) * v.d, v.fy + (cy - img.naturalHeight * k / 2) * v.d,
    img.naturalWidth * k * v.d, img.naturalHeight * k * v.d);
  ctx.beginPath();
  ctx.rect(0, 0, v.width, v.height);
  framePath(v);
  ctx.fillStyle = 'rgba(0,0,0,0.6)';
  ctx.fill('evenodd');
  ctx.beginPath();
  framePath(v);
  ctx.strokeStyle = '#fff';
  ctx.lineWidth = 2;
  ctx.stroke();
}

document.querySelectorAll('.seg button').forEach((button) => {
  button.addEventListener('click', () => {
    kind = button.dataset.kind;
    document.querySelectorAll('.seg button').forEach((b) => b.classList.toggle('sel', b === button));
  });
});

byId('file').addEventListener('change', async (event) => {
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  if (!file) return;
  setError('');
  const next = new Image(), url = URL.createObjectURL(file);
  try {
    next.src = url;
    await next.decode();
    if (!next.naturalWidth || !next.naturalHeight) throw new Error('empty');
  } catch (e) {
    URL.revokeObjectURL(url);
    setError(T.cantOpen);
    return;
  }
  if (imgUrl) URL.revokeObjectURL(imgUrl);
  img = next; imgUrl = url; zoom = 1;
  const s = size();
  cx = s.w / 2; cy = s.h / 2;
  zoomInput.value = '100';
  byId('cropTitle').textContent = s.round ? T.cropAvatar : T.cropBackground;
  show('crop');
  layout();
  draw();
});

zoomInput.addEventListener('input', () => { if (img) { setZoom(Number(zoomInput.value) / 100); draw(); } });
window.addEventListener('resize', () => { if (img && !byId('crop').hidden) { layout(); draw(); } });

const pointers = new Map();
function spread() {
  const p = Array.from(pointers.values());
  return p.length < 2 ? 0 : Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y);
}
function middle() {
  const p = Array.from(pointers.values()).slice(0, 2), n = p.length;
  return { x: p.reduce((a, q) => a + q.x, 0) / n, y: p.reduce((a, q) => a + q.y, 0) / n };
}
canvas.addEventListener('pointerdown', (e) => {
  canvas.setPointerCapture(e.pointerId);
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
});
canvas.addEventListener('pointermove', (e) => {
  if (!img || !pointers.has(e.pointerId)) return;
  const beforeSpread = spread(), beforeMiddle = middle();
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  const afterMiddle = middle();
  cx += (afterMiddle.x - beforeMiddle.x) / view.d;
  cy += (afterMiddle.y - beforeMiddle.y) / view.d;
  clampPlacement();
  if (beforeSpread > 0) setZoom(zoom * spread() / beforeSpread);
  draw();
});
['pointerup', 'pointercancel'].forEach((type) => canvas.addEventListener(type, (e) => { pointers.delete(e.pointerId); }));

function encode(output, quality) {
  return new Promise((resolve, reject) => output.toBlob((blob) => {
    if (!blob) reject(new Error('encode'));
    else if (blob.size > MAX_BYTES && quality > 0.55) resolve(encode(output, quality - 0.1));
    else resolve(blob);
  }, 'image/jpeg', quality));
}

function render() {
  const s = size(), k = scale(), output = document.createElement('canvas');
  output.width = s.w;
  output.height = s.h;
  const g = output.getContext('2d');
  g.imageSmoothingQuality = 'high';
  g.drawImage(img, cx - img.naturalWidth * k / 2, cy - img.naturalHeight * k / 2, img.naturalWidth * k, img.naturalHeight * k);
  return encode(output, 0.9);
}

sendButton.addEventListener('click', async () => {
  if (!img) return;
  setError('');
  sendButton.disabled = true;
  sendButton.textContent = T.sending;
  try {
    const blob = await render();
    const response = await fetch(base + 'photo?kind=' + encodeURIComponent(kind), {
      method: 'POST', headers: { 'Content-Type': 'image/jpeg' }, body: blob, cache: 'no-store',
      // A TV that went away mid-send (asleep, off the Wi-Fi) must not leave the page on "Sending…".
      signal: window.AbortSignal && AbortSignal.timeout ? AbortSignal.timeout(SEND_TIMEOUT_MS) : undefined
    });
    if (response.ok) {
      show('sent');
    } else {
      const word = response.status === 409 ? (await response.text()).trim() : '';
      setError(response.status === 404 ? T.expired : word === '${PhotoUploadServer.FULL}' ? T.full : word === '${PhotoUploadServer.BUSY}' ? T.busy : T.failed);
    }
  } catch (e) {
    setError(T.failed);
  } finally {
    sendButton.disabled = false;
    sendButton.textContent = T.send;
  }
});
function backToPick() { setError(''); show('pick'); }
byId('another').addEventListener('click', backToPick);
byId('again').addEventListener('click', backToPick);
</script>
</body>
</html>
""".trimIndent()
    }
}
