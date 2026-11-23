// 후원 넘기기 스크립트(app/src/main/assets/widget_skip.js)를 실제 Chromium에서 시험한다.
// 후원 위젯처럼 도는 페이지를 만들어 (재생이 끝나야 다음 후원으로 넘어가는 줄), 스크립트가 재생 중인 것만 한 번 넘기는지 본다.
//   node tools/widget-skip-test/test.js app/src/main/assets/widget_skip.js     (playwright가 깔려 있어야 한다)
const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2], 'utf8');

// 소리 없는 WAV를 페이지 안에서 만든다 (8 kHz, 모노, 8비트).
const pageHtml = `<!doctype html><body>
<script>
function wav(seconds) {
  var n = 8000 * seconds, b = new Uint8Array(44 + n), v = new DataView(b.buffer);
  function s(o, t) { for (var i = 0; i < t.length; i++) b[o + i] = t.charCodeAt(i); }
  s(0, 'RIFF'); v.setUint32(4, 36 + n, true); s(8, 'WAVEfmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true);
  v.setUint16(22, 1, true); v.setUint32(24, 8000, true); v.setUint32(28, 8000, true); v.setUint16(32, 1, true);
  v.setUint16(34, 8, true); s(36, 'data'); v.setUint32(40, n, true); b.fill(128, 44);
  return URL.createObjectURL(new Blob([b], { type: 'audio/wav' }));
}
// 후원 줄: 후원마다 10초짜리를 재생하고, 끝났다는 신호(ended)가 와야 다음으로 넘어간다.
window.played = []; window.endedEvents = 0; window.queue = ['a', 'b', 'c'];
window.idle = new Audio(wav(1)); // 미리 불러 둔 알림 소리 (재생한 적 없음): 건드리면 안 된다
window.idle.addEventListener('ended', function () { window.idleEnded = true; });
document.body.appendChild(window.idle);
function next() {
  var id = window.queue.shift(); if (!id) { window.current = null; return; }
  var a = new Audio(wav(10)); a.id = id; document.body.appendChild(a); window.current = a;
  a.addEventListener('ended', function () { window.endedEvents++; window.played.push(id); a.remove(); next(); });
  a.play();
}
next();
window.frameMessages = [];
window.addEventListener('message', function (e) { if (typeof e.data === 'string' && e.data.indexOf('got:') === 0) window.frameMessages.push(e.data.slice(4)); });
</script>
<iframe id="yt" src="/youtube.com/embed/abc?enablejsapi=1"></iframe>
<iframe id="other" src="/other/player"></iframe>
</body>`;
const frameHtml = `<!doctype html><script>
window.addEventListener('message', function (e) { parent.postMessage('got:' + e.data, '*'); });
</script>`;

(async () => {
  const server = http.createServer((req, res) => {
    res.setHeader('content-type', 'text/html');
    res.end(req.url === '/' ? pageHtml : frameHtml);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForFunction(() => window.current && !window.current.paused && window.current.currentTime > 0.2);
  const results = [];
  const check = (name, ok, detail) => { results.push(ok); console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? '  ' + detail : '')); };

  // 1. 재생 중인 'a': 넘기면 'b'로 딱 한 번 넘어가고, 기다리는 소리는 그대로다
  let n = await page.evaluate(script + '\nwindow.__camneuronSkipMedia()');
  await page.waitForFunction(() => window.current && window.current.id === 'b' && !window.current.paused, null, { timeout: 5000 });
  let st = await page.evaluate(() => ({ played: window.played, ended: window.endedEvents, idleEnded: !!window.idleEnded, left: window.queue }));
  check('playing clip is skipped once and the next one starts', st.played.join() === 'a' && st.ended === 1 && st.left.join() === 'c', JSON.stringify(st) + ' returned ' + n);
  check('idle (never played) sound is left alone', !st.idleEnded);
  check('script reports what it skipped (1 clip + 1 YouTube frame)', n === 2, 'returned ' + n);

  // 2. 유튜브 프레임은 끝으로 옮기라는 명령을 받고, 다른 프레임은 아무것도 받지 않는다
  await page.waitForFunction(() => window.frameMessages.length > 0, null, { timeout: 3000 });
  const msgs = await page.evaluate(() => window.frameMessages);
  const cmd = JSON.parse(msgs[0]);
  check('YouTube frame is told to seek to the end', msgs.length === 1 && cmd.event === 'command' && cmd.func === 'seekTo' && cmd.args[0] > 3600, msgs.join(' | '));

  // 3. 재생하다 멈춰 선 'b': 끝났다고 알려 'c'가 시작된다
  await page.waitForFunction(() => window.current.currentTime > 0.2);
  await page.evaluate(() => window.current.pause());
  n = await page.evaluate(script + '\nwindow.__camneuronSkipMedia()');
  await page.waitForFunction(() => window.current && window.current.id === 'c' && !window.current.paused, null, { timeout: 5000 });
  st = await page.evaluate(() => ({ played: window.played, ended: window.endedEvents }));
  check('clip stuck midway is skipped once', st.played.join() === 'a,b' && st.ended === 2, JSON.stringify(st));

  // 4. 불러오지 못한 것도 넘긴다
  await page.evaluate(() => {
    window.current.pause(); window.current.remove();
    var a = new Audio('/never-loads.wav?' + Date.now()); a.id = 'stalled'; document.body.appendChild(a); window.current = a;
    a.addEventListener('ended', function () { window.endedEvents++; window.played.push('stalled'); a.remove(); window.current = null; });
    a.play().catch(function () {});
  });
  await page.waitForTimeout(300);
  const before = await page.evaluate(() => ({ paused: window.current.paused, duration: String(window.current.duration) }));
  n = await page.evaluate(script + '\nwindow.__camneuronSkipMedia()');
  st = await page.evaluate(() => ({ played: window.played, ended: window.endedEvents }));
  check('clip that failed to load is skipped', st.played.join() === 'a,b,stalled' && st.ended === 3, JSON.stringify(before) + ' ' + JSON.stringify(st) + ' returned ' + n);

  // 5. 재생 중인 것이 없을 때: 아무것도 망가뜨리지 않는다
  n = await page.evaluate(script + '\nwindow.__camneuronSkipMedia()');
  const st2 = await page.evaluate(() => ({ ended: window.endedEvents }));
  check('with nothing playing nothing else is touched', st2.ended === st.ended, 'returned ' + n);

  await browser.close(); server.close();
  process.exit(results.every(Boolean) ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
