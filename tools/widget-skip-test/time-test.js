// 위젯 시계 감기 스크립트(app/src/main/assets/widget_time.js)를 실제 Chromium에서 시험한다.
// 후원 위젯이 다음 후원으로 넘어가는 여러 방식을 흉내 낸 페이지를 만들고, 넘기기가 지금 것만 넘기고 다음 것의 시간은
// 거의 깎지 않는지 본다. 넘기지 않을 때 타이머·시계가 원래대로 도는지도 본다.
//   node tools/widget-skip-test/time-test.js app/src/main/assets/widget_time.js     (playwright가 깔려 있어야 한다)
const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2], 'utf8');

const wav = `
function wav(seconds) {
  var n = 8000 * seconds, b = new Uint8Array(44 + n), v = new DataView(b.buffer);
  function s(o, t) { for (var i = 0; i < t.length; i++) b[o + i] = t.charCodeAt(i); }
  s(0, 'RIFF'); v.setUint32(4, 36 + n, true); s(8, 'WAVEfmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true);
  v.setUint16(22, 1, true); v.setUint32(24, 8000, true); v.setUint32(28, 8000, true); v.setUint16(32, 1, true);
  v.setUint16(34, 8, true); s(36, 'data'); v.setUint32(40, n, true); b.fill(128, 44);
  return URL.createObjectURL(new Blob([b], { type: 'audio/wav' }));
}`;

// 후원마다 30초를 산 줄. 방식만 다르다. window.log에 [이름, 시작한 페이지 시각]을 남긴다.
const common = `
window.log = []; window.queue = ['a', 'b', 'c']; window.current = null; window.DURATION = 30000;
function started(id) { window.current = id; window.log.push([id, Date.now()]); }
// 다른 사이트의 플레이어가 받은 명령 (플레이어가 되돌려 알려 준다)
window.commands = [];
window.addEventListener('message', function (e) { try { var d = JSON.parse(e.data); if (d.echo) window.commands.push(d.echo); } catch (x) {} });
var serverTimer = window.setTimeout.bind(window); // 스크립트를 넣기 전의 타이머: 페이지가 손댈 수 없는 것(서버)을 대신한다
var serverEvery = window.setInterval.bind(window);
`;
const pages = {
  // 영상을 산 시간만큼 되풀이해 돌리고 타이머로 넘어간다 (치지직 영상 후원처럼)
  loop: `<body><script>${wav}${common}
    function next() {
      var old = document.querySelector('audio'); if (old) { old.pause(); old.remove(); }
      var id = window.queue.shift(); if (!id) { window.current = null; return; }
      var a = new Audio(wav(2)); a.loop = true; a.dataset.id = id; document.body.appendChild(a); a.play(); started(id);
      setTimeout(next, window.DURATION);
    }
    window.begin = next;
  </script></body>`,
  // 1초마다 남은 시간을 세는 방식
  countdown: `<body><div id="box"></div><script>${wav}${common}
    function next() {
      var old = document.querySelector('audio'); if (old) { old.pause(); old.remove(); }
      var id = window.queue.shift(); if (!id) { window.current = null; document.getElementById('box').textContent = ''; return; }
      var a = new Audio(wav(2)); a.loop = true; a.dataset.id = id; document.body.appendChild(a); a.play(); started(id);
      var left = window.DURATION / 1000;
      var timer = setInterval(function () { left--; if (left <= 0) { clearInterval(timer); next(); } }, 1000);
    }
    window.begin = next;
  </script></body>`,
  // 시계를 보고 화면을 그릴 때마다 확인하는 방식
  clock: `<body><script>${wav}${common}
    function next() {
      var old = document.querySelector('audio'); if (old) { old.pause(); old.remove(); }
      var id = window.queue.shift(); if (!id) { window.current = null; return; }
      var a = new Audio(wav(2)); a.loop = true; a.dataset.id = id; document.body.appendChild(a); a.play(); started(id);
      var end = Date.now() + window.DURATION;
      (function check() { if (Date.now() >= end) next(); else requestAnimationFrame(check); })();
    }
    window.begin = next;
  </script></body>`,
  // 영상·소리 없는 후원 알림: 글자를 8초 보이고 0.5초 쉬었다 다음 것
  alert: `<body><div id="box" style="display:none"></div><script>${common}
    window.DURATION = 8000;
    function next() {
      var id = window.queue.shift(); if (!id) { window.current = null; return; }
      var box = document.getElementById('box'); box.textContent = id + ' 님이 1,000원 후원'; box.style.display = 'block'; started(id);
      setTimeout(function () { box.style.display = 'none'; box.textContent = ''; setTimeout(next, 500); }, window.DURATION);
    }
    window.begin = next;
  </script></body>`,
  // 시간이 아니라 재생이 끝나야 넘어가는 방식 (60초짜리를 끝까지 튼다). 시계를 감아도 그대로라 재생을 끝으로 넘겨야 한다.
  ended: `<body><script>${wav}${common}
    function next() {
      var old = document.querySelector('audio'); if (old) old.remove();
      var id = window.queue.shift(); if (!id) { window.current = null; return; }
      var a = new Audio(wav(60)); a.dataset.id = id; document.body.appendChild(a); a.addEventListener('ended', next); a.play(); started(id);
    }
    window.DURATION = 60000; window.begin = next;
  </script></body>`,
  // 유튜브처럼 다른 사이트의 플레이어 하나를 계속 쓰면서 영상만 바꾸는 방식. 한 시간 뒤의 다른 타이머도 걸려 있다.
  player: `<body><iframe id="p" src="PLAYER"></iframe><script>${common}
    function next() {
      var id = window.queue.shift(); if (!id) { window.current = null; return; }
      document.getElementById('p').contentWindow.postMessage(JSON.stringify({ load: id }), '*'); started(id);
      setTimeout(next, window.DURATION);
    }
    window.begin = function () { setTimeout(function () { window.refreshed = true; }, 3600000); next(); };
  </script></body>`,
  // 재생 시간을 서버가 재는 방식 (치지직 영상 후원으로 보이는 것): 페이지의 시계를 감아도 넘어가지 않는다.
  // 10초 뒤에 서버가 다음 영상을 보낸다. 그때까지는 가려져 있어야 하고, 다음 영상은 다시 보여야 한다.
  server: `<body><iframe id="p" src="PLAYER"></iframe><script>${common}
    window.DURATION = 10000;
    function next() {
      var id = window.queue.shift(); if (!id) { window.current = null; return; }
      document.getElementById('p').contentWindow.postMessage(JSON.stringify({ load: id }), '*'); started(id);
      serverTimer(next, window.DURATION);
    }
    window.begin = next;
  </script></body>`,
};
// --- 후원이 줄지어 밀려 있는 경우 (넘기기는 하나만 넘겨야 한다)
// 내용이 똑같은 후원 알림이 이어진다 (테스트 알림을 여러 번 누른 것처럼). 페이지는 후원마다 새로 그린다.
// 효과음은 같은 것을 다시 쓰고, 2초 뒤에 읽어 주는 소리가 시작되고, 남은 시간을 숫자로 세고, 화면은 계속 움직이고,
// 연결 확인 같은 긴 타이머도 걸려 있다. gap이 0이면 틈 없이 바로 다음 것으로 바뀐다. sound가 false면 소리 없이 글자·그림만.
const alertQueue = (gap, sound) => `<body><div id="root"></div><script>${wav}${common}
  window.queue = ['a', 'b', 'c', 'd']; window.DURATION = 8000;
  var sfx = new Audio(wav(3)); sfx.id = 'sfx'; document.body.appendChild(sfx);
  var spin = 0;
  function next() {
    var id = window.queue.shift(); var root = document.getElementById('root');
    if (!id) { root.innerHTML = ''; window.current = null; return; }
    root.innerHTML = '<div class="alert"><img alt="" width="40" height="40" src="data:image/gif;base64,R0lGODlhAQABAAAAACwAAAAAAQABAAACAkQBADs="><b>시청자</b>님이 1,000원 후원 <p>테스트 후원입니다</p><i id="left"></i></div>';
    started(id);
    if (${sound}) { sfx.currentTime = 0; sfx.play(); }
    var end = Date.now() + window.DURATION;
    var count = setInterval(function () { var l = document.getElementById('left'); if (l) l.textContent = Math.max(0, Math.ceil((end - Date.now()) / 1000)) + '초'; }, 100);
    var tts = null;
    if (${sound}) setTimeout(function () { tts = new Audio(wav(4)); tts.className = 'tts'; document.body.appendChild(tts); tts.play(); }, 2000);
    setTimeout(function () {
      clearInterval(count); if (tts) { tts.pause(); tts.remove(); }
      if (${gap} > 0) { root.innerHTML = ''; setTimeout(next, ${gap}); } else next();
    }, window.DURATION);
  }
  window.begin = function () {
    setTimeout(function () { window.watchdog = true; }, 45000);
    setInterval(function () { document.body.dataset.spin = String(spin++); }, 16);
    next();
  };
</script></body>`;
pages.queue = alertQueue(0, true);
pages.queuegap = alertQueue(300, true);
pages.plainqueue = alertQueue(0, false);
// 읽어 주는 소리가 끝나야 다음 후원으로 넘어가는 알림
pages.ttsqueue = `<body><div id="root"></div><script>${wav}${common}
  window.queue = ['a', 'b', 'c', 'd']; window.DURATION = 20000;
  function next() {
    var id = window.queue.shift(); var root = document.getElementById('root');
    if (!id) { root.innerHTML = ''; window.current = null; return; }
    root.innerHTML = '<div><b>시청자</b>님 후원: 안녕하세요</div>'; started(id);
    var tts = new Audio(wav(20)); tts.className = 'tts'; document.body.appendChild(tts); tts.play();
    tts.addEventListener('ended', function () { tts.remove(); root.innerHTML = ''; setTimeout(next, 200); });
  }
  window.begin = next;
</script></body>`;
// 영상 후원이 이어진다: 플레이어 하나에 영상만 바꾸고, 글자는 같은 것을 그 자리에 다시 쓴다 (페이지만 봐서는 알 수 없다).
pages.videoqueue = `<body><iframe id="p" src="PLAYER"></iframe><div id="label"></div><script>${common}
  window.queue = ['a', 'b', 'c', 'd'];
  function next() {
    var id = window.queue.shift();
    if (!id) { window.current = null; document.getElementById('label').textContent = ''; return; }
    document.getElementById('p').contentWindow.postMessage(JSON.stringify({ load: id }), '*');
    document.getElementById('label').textContent = '시청자님 영상 후원'; started(id);
    setTimeout(next, window.DURATION);
  }
  window.begin = function () { setTimeout(function () { window.watchdog = true; }, 45000); next(); };
</script></body>`;
// 서버가 시간을 재는 영상 후원이 이어진다 (6초씩). 그동안 위젯은 같은 영상을 2초마다 처음부터 다시 불러온다.
pages.serverqueue = `<body><iframe id="p" src="PLAYER"></iframe><script>${common}
  window.queue = ['a', 'b', 'c', 'd']; window.DURATION = 6000;
  function load(id) { document.getElementById('p').contentWindow.postMessage(JSON.stringify({ load: id }), '*'); }
  function next() {
    var id = window.queue.shift(); if (!id) { window.current = null; return; }
    load(id); started(id);
    serverTimer(next, window.DURATION);
  }
  window.begin = function () { serverEvery(function () { if (window.current) load(window.current); }, 2000); next(); };
</script></body>`;

// --- 목소리(읽어 주는 소리)가 있는 후원 알림. 소리를 페이지에 붙이지 않고 튼다 (new Audio(주소).play()): 페이지를 뒤져서는
// 찾을 수 없다. 효과음이 끝나면 목소리가 시작되고, 보여 주는 시간(6초)과 목소리가 둘 다 끝나야 다음 후원으로 넘어간다.
// how가 'webaudio'면 목소리를 Web Audio로 튼다. 'after'면 화면은 5초 뒤에 바로 다음 후원으로 바뀌고 목소리는 혼자 이어진다.
const voiceQueue = (how) => `<body><div id="root"></div><script>${wav}${common}
  window.queue = ['a', 'b', 'c', 'd']; window.DURATION = 6000; window.sounds = {};
  var ctx = ${how === 'webaudio'} ? new AudioContext() : null;
  function voice(state, done) {
    if (ctx) {
      var node = ctx.createBufferSource(); node.buffer = ctx.createBuffer(1, ctx.sampleRate * 12, ctx.sampleRate);
      node.connect(ctx.destination); state.voice = { node: node, ended: false };
      node.onended = function () { state.voice.ended = true; done(); };
      node.start();
    } else {
      var tts = new Audio(wav(12)); state.voice = tts; tts.addEventListener('ended', done); tts.play();
    }
  }
  function next() {
    var id = window.queue.shift(); var root = document.getElementById('root');
    if (!id) { root.innerHTML = ''; window.current = null; return; }
    root.innerHTML = '<div class="alert"><b>시청자</b>님이 1,000원 후원 <p>테스트 후원입니다</p></div>'; started(id);
    var state = window.sounds[id] = { sfx: new Audio(wav(1)), voice: null };
    if (${how === 'after'}) {
      voice(state, function () {});
      setTimeout(next, 5000);
      return;
    }
    var timeUp = false, voiceDone = false;
    function maybeNext() { if (timeUp && voiceDone) { root.innerHTML = ''; setTimeout(next, 300); } }
    state.sfx.addEventListener('ended', function () { voice(state, function () { voiceDone = true; maybeNext(); }); });
    state.sfx.play();
    setTimeout(function () { timeUp = true; maybeNext(); }, window.DURATION);
  }
  // 그 후원의 목소리가 지금 들리는가
  window.audible = function (id) {
    var v = window.sounds[id] && window.sounds[id].voice; if (!v) return false;
    if (v.node) return !v.ended;
    return !v.paused && !v.ended && !v.muted;
  };
  window.begin = next;
</script></body>`;
pages.voicequeue = voiceQueue('audio');
pages.webaudioqueue = voiceQueue('webaudio');
pages.voiceafter = voiceQueue('after');

const playerFrame = `<script>
window.addEventListener('message', function (e) {
  var d; try { d = JSON.parse(e.data); } catch (x) { return; }
  if (d.event === 'command') parent.postMessage(JSON.stringify({ echo: d.func }), '*');
  if (d.load) setTimeout(function () {
    parent.postMessage(JSON.stringify({ event: 'onStateChange', info: -1 }), '*');
    parent.postMessage(JSON.stringify({ event: 'infoDelivery', info: { videoData: { video_id: d.load } } }), '*');
  }, 30);
});
</script>`;

(async () => {
  const serve = (html) => new Promise(resolve => {
    const s = http.createServer((req, res) => { res.setHeader('content-type', 'text/html; charset=utf-8'); res.end(html(req.url)); });
    s.listen(0, '127.0.0.1', () => resolve(s));
  });
  const other = await serve(() => playerFrame); // 다른 포트 = 다른 사이트
  // 플레이어 주소는 유튜브 플레이어처럼 보이게 한다 (스크립트가 주소로 알아본다)
  const main = await serve(url => (pages[url.slice(1)] || '<body></body>').replace('PLAYER', `http://127.0.0.1:${other.address().port}/youtube.com/embed/x`));
  const base = `http://127.0.0.1:${main.address().port}/`;
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const results = [];
  const check = (name, ok, detail) => { results.push(ok); console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? '  ' + detail : '')); };

  // --- 넘기지 않을 때는 원래와 똑같이 돈다
  {
    const page = await browser.newPage();
    await page.goto(base + 'plain');
    await page.evaluate(script);
    const r = await page.evaluate(() => new Promise(resolve => {
      const out = { order: [], args: null, ticks: 0 };
      const t0 = performance.now();
      setTimeout(() => out.order.push('b'), 120);
      setTimeout(() => out.order.push('a'), 40);
      setTimeout((x, y) => { out.args = [x, y]; }, 10, 'x', 2);
      const cancelled = setTimeout(() => out.order.push('never'), 60); clearTimeout(cancelled);
      const iv = setInterval(() => { out.ticks++; if (out.ticks === 5) { clearInterval(iv); out.intervalMs = performance.now() - t0; } }, 50);
      // 0초 타이머를 계속 되거는 페이지: 브라우저처럼 한 차례에 한 번씩만 돈다 (헛돌지 않는다)
      out.spins = 0; (function spin() { out.spins++; if (performance.now() - t0 < 300) setTimeout(spin, 0); })();
      setTimeout(() => {
        out.elapsed = performance.now() - t0;
        const d = new Date(2020, 0, 2, 3, 4, 5);
        out.date = [d instanceof Date, d.getFullYear(), d.getHours(), typeof Date(), Date.UTC(2020, 0, 1), Date.parse('2020-01-01T00:00:00Z'),
          Math.abs(new Date().getTime() - Date.now()) < 50, JSON.stringify(new Date(0)), new Date('2021-05-05T00:00:00Z').getTime(),
          Object.prototype.toString.call(new Date()), new Date(new Date(5).getTime() + 1).getTime()];
        class Stamp extends Date { label() { return 'stamp' + this.getTime(); } }
        out.subclass = [new Stamp(7).label(), new Stamp() instanceof Date, new Stamp() instanceof Stamp];
        out.offset = window.__camneuronTime.offset();
        resolve(out);
      }, 400);
    }));
    check('timeouts fire in order, cleared ones do not, arguments are passed', r.order.join() === 'a,b' && r.args.join() === 'x,2', JSON.stringify(r.order) + ' ' + JSON.stringify(r.args));
    check('timing is unchanged (400 ms timeout, 5 x 50 ms interval)', r.elapsed > 380 && r.elapsed < 520 && r.intervalMs > 230 && r.intervalMs < 340 && r.offset === 0, `timeout ${r.elapsed.toFixed(0)} ms, interval ${r.intervalMs.toFixed(0)} ms`);
    check('a page re-arming a 0 ms timer does not spin (ran ' + r.spins + ' times in 300 ms)', r.spins > 20 && r.spins < 400);
    check('classes that extend Date keep working', r.subclass.join() === 'stamp7,true,true', JSON.stringify(r.subclass));
    check('Date still behaves like Date', r.date[0] === true && r.date[1] === 2020 && r.date[2] === 3 && r.date[3] === 'string' && r.date[4] === 1577836800000 && r.date[5] === 1577836800000 && r.date[6] === true && r.date[7] === '"1970-01-01T00:00:00.000Z"' && r.date[8] === 1620172800000 && r.date[9] === '[object Date]' && r.date[10] === 6, JSON.stringify(r.date));
    const idle = await page.evaluate(() => window.__camneuronTime.skip());
    check('nothing to skip on an empty widget', idle === 'idle', idle);
    await page.close();
  }

  // --- 후원을 넘기는 여러 방식
  for (const kind of ['loop', 'countdown', 'clock', 'alert', 'player', 'ended', 'server']) {
    const page = await browser.newPage();
    await page.goto(base + kind);
    await page.evaluate(script); // 앱처럼: 페이지를 다 불러온 뒤에 넣는다
    await page.evaluate(() => window.begin());
    await page.waitForFunction(() => window.current === 'a');
    await page.waitForTimeout(700); // 'a'가 조금 나온 뒤에
    const t0 = Date.now();
    const answer = await page.evaluate(() => window.__camneuronTime.skip(true));
    const opacity = () => page.evaluate(() => getComputedStyle(document.documentElement).opacity);
    check(`${kind}: the page is hidden as soon as skip is pressed`, await opacity() === '0' || await page.evaluate(() => window.current !== 'a'));
    if (kind === 'server') {
      // 서버가 재는 시간은 감을 수 없다: 3초 뒤에도 같은 후원이고, 가려진 채 소리가 꺼져 있다
      await page.waitForTimeout(3000);
      const mid = await page.evaluate(() => ({ current: window.current, hidden: window.__camneuronTime.hidden(), commands: window.commands.slice() }));
      check('server: the donation cannot be shortened, but it stays hidden and muted', mid.current === 'a' && mid.hidden && await opacity() === '0' && mid.commands.includes('mute') && !mid.commands.includes('unMute'), JSON.stringify(mid));
    }
    await page.waitForFunction(() => window.current === 'b' && !window.__camneuronTime.skipping(), null, { timeout: 15000 }).catch(() => {});
    const took = Date.now() - t0;
    const st = await page.evaluate(() => ({
      current: window.current, queue: window.queue.join(), log: window.log, now: Date.now(), duration: window.DURATION,
      skipping: window.__camneuronTime.skipping(), refreshed: !!window.refreshed,
    }));
    const bStart = (st.log.find(e => e[0] === 'b') || [])[1];
    // 다음 후원이 이미 쓴 시간. 'ended'는 재생한 길이로 본다 (그 방식은 페이지 시각과 상관없다).
    const bUsed = kind === 'ended'
      ? await page.evaluate(() => Math.round(document.querySelector('audio').currentTime * 1000))
      : (bStart ? st.now - bStart : -1);
    check(`${kind}: the current donation is skipped, the next one starts, the rest stay queued`,
      answer === 'started' && st.current === 'b' && st.queue === 'c' && !st.skipping, `answer ${answer}, current ${st.current}, queue [${st.queue}], took ${took} ms`);
    check(`${kind}: the next donation keeps its time (lost ${bUsed} ms of ${st.duration})`, bUsed >= 0 && bUsed <= 2600);
    check(`${kind}: the page is visible again for the next donation`, await opacity() === '1' && !(await page.evaluate(() => window.__camneuronTime.hidden())));
    if (kind === 'player') check('player: unrelated far-off timers are not fired', !st.refreshed);
    if (kind === 'player' || kind === 'server') {
      const commands = await page.evaluate(() => window.commands);
      check(`${kind}: the player is muted while hidden and unmuted for the next donation`, commands.indexOf('mute') >= 0 && commands.lastIndexOf('unMute') > commands.indexOf('mute'), commands.join(','));
    }
    if (kind === 'server') check('server: the next donation came when the server sent it (about 10 s)', took > 8500 && took < 12500, took + ' ms');
    // 다음 후원은 제 속도로 흐른다: 1초 뒤에도 여전히 'b'
    await page.waitForTimeout(1000);
    const later = await page.evaluate(() => window.current);
    check(`${kind}: after the skip time runs normally again`, later === 'b', later);
    await page.close();
  }

  // --- 서버가 시간을 재는 것으로 알려진 위젯: 시계를 감지 않고, 영상을 끝으로 넘기지도 않고, 가린 채로 기다리기만 한다
  {
    const page = await browser.newPage();
    await page.goto(base + 'server');
    await page.evaluate(script);
    await page.evaluate(() => window.begin());
    await page.waitForFunction(() => window.current === 'a');
    await page.waitForTimeout(700);
    const answer = await page.evaluate(() => window.__camneuronTime.skip(true, true));
    await page.waitForTimeout(2000);
    const mid = await page.evaluate(() => ({ current: window.current, hidden: window.__camneuronTime.hidden(), offset: window.__camneuronTime.offset(), commands: window.commands.slice(), opacity: getComputedStyle(document.documentElement).opacity }));
    check('wait only: hidden and muted at once, the clock is not wound, the video is not sought', answer === 'started' && mid.current === 'a' && mid.hidden && mid.opacity === '0' && mid.offset === 0 && mid.commands.includes('mute') && !mid.commands.includes('seekTo'), JSON.stringify(mid));
    const again = await page.evaluate(() => window.__camneuronTime.skip(true, true));
    check('wait only: pressing again while waiting changes nothing', again === 'busy', again);
    await page.waitForFunction(() => window.current === 'b' && !window.__camneuronTime.skipping(), null, { timeout: 15000 }).catch(() => {});
    const end = await page.evaluate(() => ({ current: window.current, queue: window.queue.join(), commands: window.commands.slice(), opacity: getComputedStyle(document.documentElement).opacity }));
    check('wait only: visible and unmuted again when the server sends the next donation', end.current === 'b' && end.queue === 'c' && end.opacity === '1' && end.commands[end.commands.length - 1] === 'unMute', JSON.stringify(end));
    await page.close();
  }

  // --- 후원이 줄지어 밀려 있을 때: 넘기기 한 번에 하나만 넘어가고, 다음 것은 제대로 나온다. 두 번 누르면 두 개.
  const visible = (page) => page.evaluate(() => getComputedStyle(document.documentElement).opacity === '1' && !window.__camneuronTime.hidden());
  const lost = (page, id) => page.evaluate((x) => { const e = window.log.find(l => l[0] === x); return e ? Date.now() - e[1] : -1; }, id);
  for (const kind of ['queue', 'queuegap', 'plainqueue', 'ttsqueue', 'videoqueue']) {
    const page = await browser.newPage();
    await page.goto(base + kind);
    await page.evaluate(script);
    await page.evaluate(() => window.begin());
    await page.waitForFunction(() => window.current === 'a');
    const plan = [['b', 700], ['c', 3200]]; // 두 번째는 후원 도중에 읽어 주는 소리가 시작된 뒤에 누른다
    for (const [expected, wait] of plan) {
      await page.waitForTimeout(wait);
      const pressed = Date.now();
      const answer = await page.evaluate(() => window.__camneuronTime.skip(true));
      await page.waitForFunction((x) => window.current === x && !window.__camneuronTime.skipping(), expected, { timeout: 15000 }).catch(() => {});
      const took = Date.now() - pressed;
      const used = await lost(page, expected);
      const st = await page.evaluate(() => ({ current: window.current, queue: window.queue.join(), watchdog: !!window.watchdog, commands: window.commands.join(',') }));
      check(`${kind}: one press skips exactly one (now '${expected}')`, answer === 'started' && st.current === expected && st.queue === (expected === 'b' ? 'c,d' : 'd'), JSON.stringify(st));
      check(`${kind}: it is skipped quickly (${took} ms)`, took <= 3000);
      check(`${kind}: '${expected}' keeps its time (lost ${used} ms)`, used >= 0 && used <= 700);
      check(`${kind}: '${expected}' is visible`, await visible(page));
      if (kind === 'plainqueue') check(`${kind}: far-off timers are not fired`, !st.watchdog);
      if (kind === 'queue' || kind === 'queuegap') {
        const sound = await page.evaluate(() => { const s = document.getElementById('sfx'); return { muted: s.muted, paused: s.paused, time: s.currentTime }; });
        check(`${kind}: the sound effect of '${expected}' plays (not muted, not cut)`, !sound.muted && !sound.paused && sound.time < 1.5, JSON.stringify(sound));
        check(`${kind}: far-off timers are not fired`, !st.watchdog);
      }
      if (kind === 'ttsqueue') {
        const tts = await page.evaluate(() => { const t = document.querySelector('audio.tts'); return t ? { muted: t.muted, paused: t.paused, time: t.currentTime } : null; });
        check(`${kind}: the voice of '${expected}' plays from its start`, tts && !tts.muted && !tts.paused && tts.time < 1.5, JSON.stringify(tts));
      }
      if (kind === 'videoqueue') check(`${kind}: the player is unmuted again`, /mute.*unMute$/.test(st.commands), st.commands);
    }
    // 그대로 두면 지금 것이 계속 나온다
    await page.waitForTimeout(1500);
    check(`${kind}: nothing else is skipped afterwards`, await page.evaluate(() => window.current) === 'c' && await visible(page));
    await page.close();
  }

  // --- 목소리가 있는 후원: 넘기면 그 후원의 목소리가 바로 꺼지고, 다음 후원의 목소리는 제대로 나온다
  for (const kind of ['voicequeue', 'webaudioqueue', 'voiceafter']) {
    const page = await browser.newPage();
    await page.goto(base + kind);
    await page.evaluate(script);
    await page.evaluate(() => window.begin());
    await page.waitForFunction(() => window.current === 'a');
    await page.waitForTimeout(2500); // 'a'의 목소리가 나오는 중
    check(`${kind}: the voice of 'a' is playing before the skip`, await page.evaluate(() => window.audible('a')));
    const pressed = Date.now();
    const answer = await page.evaluate(() => window.__camneuronTime.skip(true));
    await page.waitForTimeout(250);
    check(`${kind}: the voice stops as soon as skip is pressed`, !(await page.evaluate(() => window.audible('a'))));
    await page.waitForFunction(() => window.current === 'b' && !window.__camneuronTime.skipping(), null, { timeout: 15000 }).catch(() => {});
    const took = Date.now() - pressed;
    const st = await page.evaluate(() => ({ current: window.current, queue: window.queue.join() }));
    check(`${kind}: one press skips exactly one, quickly (${took} ms)`, answer === 'started' && st.current === 'b' && st.queue === 'c,d' && took <= 3000, JSON.stringify(st));
    await page.waitForTimeout(2500); // 'b'의 목소리가 시작될 때까지
    const after = await page.evaluate(() => ({ a: window.audible('a'), b: window.audible('b'), current: window.current }));
    check(`${kind}: the skipped voice stays off and the next donation's voice plays`, !after.a && after.b && after.current === 'b', JSON.stringify(after));
    check(`${kind}: the next donation is visible`, await visible(page));
    await page.close();
  }

  // --- 서버가 시간을 재는 영상 후원이 이어질 때 (치지직 영상 후원으로 보이는 것)
  {
    const page = await browser.newPage();
    await page.goto(base + 'serverqueue');
    await page.evaluate(script);
    await page.evaluate(() => window.begin());
    await page.waitForFunction(() => window.current === 'a');
    await page.waitForTimeout(700);
    await page.evaluate(() => window.__camneuronTime.skip(true, true));
    await page.waitForTimeout(4300); // 그 사이 같은 영상을 두 번 다시 불러왔다
    const mid = await page.evaluate(() => ({ current: window.current, hidden: window.__camneuronTime.hidden() }));
    check('serverqueue: replays of the skipped video stay hidden', mid.current === 'a' && mid.hidden, JSON.stringify(mid));
    await page.waitForFunction(() => window.current === 'b' && !window.__camneuronTime.skipping(), null, { timeout: 8000 }).catch(() => {});
    const used = await lost(page, 'b');
    const st = await page.evaluate(() => ({ current: window.current, queue: window.queue.join(), commands: window.commands.join(',') }));
    check(`serverqueue: the next video is shown and unmuted as soon as it starts (after ${used} ms)`, st.current === 'b' && await visible(page) && used <= 900 && /unMute$/.test(st.commands), JSON.stringify(st));
    await page.waitForTimeout(2600); // 'b'도 한 번 다시 불러왔다: 넘기지 않은 것은 가리지 않는다
    check('serverqueue: the next video stays visible, also when it replays', await page.evaluate(() => window.current) === 'b' && await visible(page));
    await page.evaluate(() => window.__camneuronTime.skip(true, true));
    await page.waitForTimeout(300);
    check('serverqueue: a second press hides the second video', !(await visible(page)));
    await page.waitForFunction(() => window.current === 'c' && !window.__camneuronTime.skipping(), null, { timeout: 8000 }).catch(() => {});
    check('serverqueue: and the third one is shown', await page.evaluate(() => window.current) === 'c' && await visible(page) && await page.evaluate(() => window.queue.join()) === 'd');
    await page.close();
  }

  await browser.close(); main.close(); other.close();
  process.exit(results.every(Boolean) ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
