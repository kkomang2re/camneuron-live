// 위젯 페이지의 시계를 앞으로 감을 수 있게 한다 (WidgetHost가 페이지를 다 불러온 뒤에 넣는다).
//
// 후원 위젯은 후원마다 정해진 시간 동안 보여 주고, 그 시간이 지나야 다음 후원으로 넘어간다 (영상 후원은 영상이 끝나도
// 산 시간이 남았으면 처음부터 다시 돌린다). 그래서 영상을 끝으로 넘기는 것으로는 넘어가지 않는다.
// 이 스크립트는 페이지의 타이머(setTimeout·setInterval)와 시계(Date, performance.now, requestAnimationFrame의 시각)를
// 감싸 두었다가, 넘기라고 하면 페이지의 시간만 빠르게 흘려보낸다. 페이지는 스스로 "시간이 다 됐다"고 보고 다음 후원으로
// 넘어가므로 쌓여 있던 후원은 그대로 남는다. 넘기지 않는 동안에는 원래와 똑같이 돈다.
//
// 이 스크립트를 넣기 전에 페이지가 건 타이머(연결 유지 등)는 건드리지 않는다. 후원이 올 때 거는 타이머만 감싸진다.
(function () {
  if (window.__camneuronTime) return;

  var nativeSetTimeout = window.setTimeout.bind(window);
  var nativeClearTimeout = window.clearTimeout.bind(window);
  var nativeClearInterval = window.clearInterval.bind(window);
  var NativeDate = window.Date;
  var nativeRaf = window.requestAnimationFrame ? window.requestAnimationFrame.bind(window) : null;
  var perf = window.performance;
  var nativePerfNow = perf && perf.now ? perf.now.bind(perf) : null;

  var offset = 0; // 페이지의 시계가 실제보다 앞서 있는 만큼 (ms)
  var timers = {}; // id -> { id, fn, args, due(페이지 시각), period(0이면 한 번) }
  var nextId = 1000000000; // 브라우저가 주는 번호와 겹치지 않게
  var pumpHandle = null;
  var skipping = null; // 넘기는 중이면 그 상태
  var advancing = false; // 시간을 한꺼번에 흘려보내는 중 ([advance])

  function now() { return NativeDate.now() + offset; }

  function earliest(limit) {
    var best = null;
    for (var id in timers) {
      var t = timers[id];
      if (t.due <= limit && (best === null || t.due < best.due || (t.due === best.due && t.id < best.id))) best = t;
    }
    return best;
  }

  function fire(t) {
    if (t.period === 0) {
      delete timers[t.id];
    } else {
      t.due += t.period;
      // 페이지가 바빠 늦게 돌았을 때 밀린 횟수를 몰아서 돌리지 않는다 (브라우저와 같게). 시간을 감을 때는 빠짐없이 돈다.
      if (!advancing && t.due < now()) t.due = now();
    }
    try {
      t.fn.apply(window, t.args);
    } catch (e) {
      // 페이지의 오류는 페이지의 것이다. 다른 타이머가 멈추지 않게 따로 던진다.
      nativeSetTimeout(function () { throw e; }, 0);
    }
  }

  // 지금(페이지 시각)까지 때가 된 타이머를 차례로 돌리고, 다음 것에 맞춰 깨어난다. 도는 중에 새로 걸린 타이머와
  // 한 번 돈 반복 타이머는 다음 차례에 돈다 (브라우저와 같게: 0초 타이머를 되거는 페이지가 헛돌지 않는다).
  function pump() {
    pumpHandle = null;
    var limit = now();
    var due = [];
    for (var id in timers) if (timers[id].due <= limit) due.push(timers[id]);
    due.sort(function (a, b) { return a.due - b.due || a.id - b.id; });
    for (var i = 0; i < due.length; i++) if (timers[due[i].id] === due[i]) fire(due[i]);
    arm();
  }

  function arm() {
    if (pumpHandle !== null) { nativeClearTimeout(pumpHandle); pumpHandle = null; }
    var next = earliest(Infinity);
    if (next) pumpHandle = nativeSetTimeout(pump, Math.max(0, next.due - now()));
  }

  function add(fn, delay, period, args) {
    var id = nextId++;
    timers[id] = { id: id, fn: fn, args: args, due: now() + delay, period: period };
    arm();
    return id;
  }

  function number(value) { value = Number(value); return value > 0 && isFinite(value) ? value : 0; }

  var originalSetTimeout = window.setTimeout;
  var originalSetInterval = window.setInterval;
  window.setTimeout = function (fn, delay) {
    if (typeof fn !== 'function') return originalSetTimeout.apply(window, arguments);
    return add(fn, number(delay), 0, Array.prototype.slice.call(arguments, 2));
  };
  window.setInterval = function (fn, delay) {
    if (typeof fn !== 'function') return originalSetInterval.apply(window, arguments);
    var period = Math.max(1, number(delay));
    return add(fn, period, period, Array.prototype.slice.call(arguments, 2));
  };
  function clear(id) {
    if (timers[id]) { delete timers[id]; arm(); return; }
    nativeClearTimeout(id); // 감싸기 전에 건 타이머
    nativeClearInterval(id);
  }
  window.clearTimeout = clear;
  window.clearInterval = clear;

  // 시계. Date()처럼 new 없이 부르는 것, 인자를 주는 것은 원래대로 돈다.
  function PageDate() {
    if (!new.target) return new NativeDate(now()).toString();
    var args = arguments;
    var date = args.length === 0 ? new NativeDate(now()) : new (Function.prototype.bind.apply(NativeDate, [null].concat(Array.prototype.slice.call(args))))();
    Object.setPrototypeOf(date, new.target.prototype); // Date를 물려받은 클래스도 제 모습을 지킨다
    return date;
  }
  PageDate.prototype = NativeDate.prototype;
  Object.setPrototypeOf(PageDate, NativeDate);
  PageDate.now = now;
  PageDate.parse = NativeDate.parse;
  PageDate.UTC = NativeDate.UTC;
  window.Date = PageDate;
  if (nativePerfNow) {
    try { perf.now = function () { return nativePerfNow() + offset; }; } catch (e) {}
  }
  if (nativeRaf) {
    window.requestAnimationFrame = function (callback) {
      return nativeRaf(function (time) { callback(time + offset); });
    };
  }

  // 페이지의 시간을 [ms]만큼 한꺼번에 흘려보낸다. 그 사이에 때가 되는 타이머는 제때의 시각으로 차례로 돈다.
  function advance(ms) {
    var target = now() + ms;
    var guard = 0;
    advancing = true;
    try {
      for (var t = earliest(target); t && guard < 5000; t = earliest(target)) {
        if (t.due > now()) offset += t.due - now();
        fire(t);
        guard++;
      }
    } finally {
      advancing = false;
    }
    if (target > now()) offset += target - now();
    arm();
  }

  // --------------------------------------------------------------------------------------------
  // 후원이 바뀌었는지 알아보는 것: 보이는 영상·소리·안쪽 페이지, 유튜브 플레이어가 알리는 상태, 화면에 내용이 있는지.

  function visible(element) {
    try { return element.getClientRects().length > 0; } catch (e) { return false; }
  }

  function describe(doc, out) {
    var i;
    var media = doc.querySelectorAll('video, audio');
    for (i = 0; i < media.length; i++) {
      var m = media[i];
      out.push((m.currentSrc || m.src || '') + '|' + (m.paused ? 'p' : 'r') + (m.ended ? 'e' : '') + (m.tagName === 'VIDEO' && !visible(m) ? 'h' : ''));
    }
    var frames = doc.querySelectorAll('iframe');
    for (i = 0; i < frames.length; i++) {
      out.push('f:' + (frames[i].src || '') + (visible(frames[i]) ? '' : '|h'));
      try { if (frames[i].contentDocument) describe(frames[i].contentDocument, out); } catch (e) {}
    }
  }

  // 화면에 보이는 것이 있는지 (후원 위젯은 후원이 없을 때 비어 있다).
  function hasContent() {
    try {
      var text = (document.body && document.body.innerText || '').replace(/\s+/g, '');
      if (text.length > 0) return true;
      var things = document.querySelectorAll('img, video, iframe, canvas, svg');
      for (var i = 0; i < things.length; i++) if (visible(things[i])) return true;
    } catch (e) {}
    return false;
  }

  function signature() {
    var out = [];
    try { describe(document, out); } catch (e) {}
    return out.join('\n') + '\n#' + (hasContent() ? 1 : 0);
  }

  // 유튜브 플레이어는 상태가 바뀌면 바깥 페이지에 알린다. 넘기는 동안 다른 영상으로 바뀌거나 멈추면 후원이 바뀐 것이다.
  var playerEvents = 0;
  window.addEventListener('message', function (event) {
    if (!skipping || typeof event.data !== 'string' || event.data.charAt(0) !== '{') return;
    try {
      var data = JSON.parse(event.data);
      if (data.event === 'onStateChange' && (data.info === 0 || data.info === -1 || data.info === 5)) playerEvents++;
      if (data.event === 'infoDelivery' && data.info && data.info.videoData && data.info.videoData.video_id) {
        if (skipping.videoId && skipping.videoId !== data.info.videoData.video_id) playerEvents++;
        skipping.videoId = data.info.videoData.video_id;
      }
    } catch (e) {}
  });

  var STEP_MS = 1000; // 한 번에 흘려보내는 페이지 시간
  var STEP_REAL_MS = 40; // 그 사이에 페이지가 반응할 틈 (실제 시간)
  var MAX_MS = 10 * 60 * 1000; // 넘기기 한 번에 많아야 이만큼
  var MAX_BLIND_MS = 3 * 60 * 1000; // 끝나는 때를 짐작할 타이머가 없을 때는 이만큼 (모자라면 한 번 더 누른다)
  var LONG_MS = 1500; // 이보다 오래 남은 타이머가 후원이 끝나는 때일 수 있다

  // 지금 나오는 후원을 넘긴다: 후원이 바뀔 때까지 페이지의 시간을 빨리 감는다. 돌려주는 값:
  //   'started'  감기 시작했다
  //   'idle'     나오는 후원이 없다 (화면이 비어 있고 재생 중인 것도, 기다리는 긴 타이머도 없다)
  //   'busy'     이미 넘기는 중이다
  function skip() {
    if (skipping) return 'busy';
    var start = signature();
    var limit = 0;
    for (var id in timers) {
      var t = timers[id];
      if (t.period === 0 && t.due - now() >= LONG_MS) limit = Math.max(limit, t.due - now());
    }
    var playing = /\|r/.test(start) || /f:/.test(start);
    if (limit === 0 && !playing && !hasContent()) return 'idle';
    // 이 후원이 끝나는 타이머는 지금 걸려 있는 것 중에 있다. 그것들이 다 지나면 더 감지 않는다
    // (다음 후원이 새로 건 타이머까지 감아 버리지 않게). 긴 타이머가 없으면 (1초마다 세는 식) 바뀔 때까지 감는다.
    var budget = Math.min(limit > 0 ? limit + STEP_MS : MAX_BLIND_MS, MAX_MS);
    skipping = { videoId: null, advanced: 0, result: null };
    playerEvents = 0;
    var state = skipping;
    function step() {
      if (signature() !== start || playerEvents > 0) { state.result = 'changed'; skipping = null; return; }
      if (state.advanced >= budget) {
        // 시간을 다 감았는데도 그대로다: 시간이 아니라 재생이 끝나야 넘어가는 위젯일 수 있다. 재생 중인 것을 끝으로 넘겨 본다.
        state.result = 'limit';
        skipping = null;
        try { if (window.__camneuronSkipMedia) window.__camneuronSkipMedia(); } catch (e) {}
        return;
      }
      var ms = Math.min(STEP_MS, budget - state.advanced);
      advance(ms);
      state.advanced += ms;
      nativeSetTimeout(step, STEP_REAL_MS);
    }
    step();
    return 'started';
  }

  window.__camneuronTime = {
    skip: skip,
    advance: advance,
    offset: function () { return offset; },
    pending: function () { var n = 0; for (var id in timers) n++; return n; },
    skipping: function () { return skipping !== null; }
  };
})();
