// 위젯 페이지의 시계를 앞으로 감을 수 있게 한다 (WidgetHost가 페이지를 다 불러온 뒤에 넣는다).
//
// 후원 위젯은 후원마다 정해진 시간 동안 보여 주고, 그 시간이 지나야 다음 후원으로 넘어간다 (영상 후원은 영상이 끝나도
// 산 시간이 남았으면 처음부터 다시 돌린다). 그래서 영상을 끝으로 넘기는 것으로는 넘어가지 않는다.
// 이 스크립트는 페이지의 타이머(setTimeout·setInterval)와 시계(Date, performance.now, requestAnimationFrame의 시각)를
// 감싸 두었다가, 넘기라고 하면 페이지의 시간만 빠르게 흘려보낸다. 페이지는 스스로 "시간이 다 됐다"고 보고 다음 후원으로
// 넘어가므로 쌓여 있던 후원은 그대로 남는다. 넘기지 않는 동안에는 원래와 똑같이 돈다.
// 넘어갈 때까지는 페이지를 가리고 소리를 끈다. 재생 시간을 서버가 재는 위젯(치지직 영상 후원)은 시계를 감아도 넘어가지
// 않지만, 그때도 방송 화면에서는 바로 사라지고 서버가 다음 후원을 보내면 다시 보인다.
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
  // 페이지가 내는 소리를 알아 둔다. 넘긴 후원의 목소리·효과음을 끄려면 어디서 나는지 알아야 한다.
  // 후원 위젯은 소리를 페이지에 붙이지 않고 트는 일이 흔하다: new Audio(주소).play() (페이지를 뒤져서는 찾을 수 없다),
  // 또는 Web Audio (소리 파일을 받아 직접 트는 방식). 그래서 "재생"을 부르는 자리에 끼어들어 적어 둔다.

  var epoch = 0; // 넘기는 동안 "아직 이 후원이다"를 확인할 때마다 는다. 그 뒤에 시작된 소리는 다음 후원의 것일 수 있다.
  var known = []; // 재생한 적 있는 영상·소리
  var sources = []; // 재생 중인 Web Audio 소리: { node, epoch }

  function mediaSrc(m) { return m.currentSrc || m.src || ''; }

  function noteMedia(m) {
    if (known.indexOf(m) < 0) {
      known.push(m);
      if (known.length > 60) known = known.filter(function (k) { return k.isConnected || (!k.paused && !k.ended); });
    }
    if (!hidden) return;
    // 가리는 중에 시작한 소리: 일단 끈다. 이 후원의 것인지 다음 후원의 것인지는 넘기기가 끝날 때 가른다.
    for (var i = 0; i < hidden.media.length; i++) {
      if (hidden.media[i].element === m) { hidden.media[i].replayed = epoch; return; }
    }
    if (!m.muted) {
      m.muted = true;
      hidden.media.push({ element: m, src: mediaSrc(m), time: m.currentTime, epoch: epoch, replayed: -1 });
    }
  }

  var mediaProto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
  if (mediaProto && mediaProto.play) {
    var nativePlay = mediaProto.play;
    mediaProto.play = function () {
      try { noteMedia(this); } catch (e) {}
      return nativePlay.apply(this, arguments);
    };
  }

  var sourceProto = window.AudioBufferSourceNode && window.AudioBufferSourceNode.prototype;
  if (sourceProto && sourceProto.start) {
    var nativeStart = sourceProto.start;
    sourceProto.start = function () {
      var result = nativeStart.apply(this, arguments);
      try {
        var entry = { node: this, epoch: epoch };
        sources.push(entry);
        this.addEventListener('ended', function () {
          var i = sources.indexOf(entry);
          if (i >= 0) sources.splice(i, 1);
        });
      } catch (e) {}
      return result;
    };
  }

  // [before]보다 먼저 시작한 Web Audio 소리를 멈춘다 (멈추면 페이지에는 소리가 끝났다고 알려진다).
  function stopSources(before) {
    var list = sources.slice();
    for (var i = 0; i < list.length; i++) {
      if (list[i].epoch < before) { try { list[i].node.stop(); } catch (e) {} }
    }
  }

  // 페이지에 있는 것과 페이지에 붙이지 않고 튼 것을 합친 영상·소리.
  function allMedia() {
    var out = [];
    try {
      var inPage = document.querySelectorAll('video, audio');
      for (var i = 0; i < inPage.length; i++) out.push(inPage[i]);
    } catch (e) {}
    for (var k = 0; k < known.length; k++) if (out.indexOf(known[k]) < 0) out.push(known[k]);
    return out;
  }

  // --------------------------------------------------------------------------------------------
  // 지금 나오는 후원이 끝났는지 알아보는 것.
  //
  // 후원이 줄지어 밀려 있으면 하나가 끝나자마자 다음 것이 나온다. 넘기기는 딱 하나만 넘겨야 하므로, 시간을 감는 동안
  // 타이머 하나가 돌 때마다 바로 살펴 "지금 것이 끝난 순간"에 멈춘다 (뭉텅이로 감고 나서 보면 다음 후원까지 감긴다).
  // 끝났다고 보는 때:
  //   - 넘기기 시작할 때 보이던 것들(글자, 그림, 영상)이 절반 넘게 없어지거나 안 보이게 됐다
  //     (내용이 똑같은 후원이 이어져도 페이지는 새로 그리므로 알 수 있다)
  //   - 글자가 다른 글자로 바뀌었다 (한 글자씩 이어서 나오는 것과 남은 시간을 세는 숫자는 같은 후원으로 본다)
  //   - 보이는 영상·플레이어가 다른 것으로 바뀌었다
  //   - 유튜브 플레이어가 다른 영상을 불러왔다고 알렸다
  // 후원 도중에 읽어 주는 소리가 시작되거나 효과음이 끝나는 것은 끝난 것으로 보지 않는다.

  function visible(element) {
    try { return element.getClientRects().length > 0; } catch (e) { return false; }
  }

  // 보이는 영상과 안쪽 페이지(플레이어)의 주소들.
  function visuals(doc, out) {
    var i;
    var videos = doc.querySelectorAll('video');
    for (i = 0; i < videos.length; i++) if (visible(videos[i])) out.push('v:' + (videos[i].currentSrc || videos[i].src || ''));
    var frames = doc.querySelectorAll('iframe');
    for (i = 0; i < frames.length; i++) {
      if (visible(frames[i])) out.push('f:' + (frames[i].src || ''));
      try { if (frames[i].contentDocument) visuals(frames[i].contentDocument, out); } catch (e) {}
    }
    return out;
  }

  // 보이는 글자. 남은 시간을 세는 숫자와 빈칸은 뺀다.
  function mainText() {
    try { return (document.body && document.body.innerText || '').replace(/[0-9\s]/g, '').slice(0, 600); } catch (e) { return ''; }
  }

  // 화면에 보이는 것이 있는지 (후원 위젯은 후원이 없을 때 비어 있다).
  function hasContent() {
    try {
      if (mainText().length > 0) return true;
      var things = document.querySelectorAll('img, video, iframe, canvas, svg');
      for (var i = 0; i < things.length; i++) if (visible(things[i])) return true;
    } catch (e) {}
    return false;
  }

  // 지금 보이는 것들: 글자를 직접 담은 요소와 그림·영상. 넘기기를 시작할 때 적어 두고, 이것들이 없어지는지 본다.
  function shownNodes() {
    var out = [];
    try {
      var all = document.body ? document.body.querySelectorAll('*') : [];
      for (var i = 0; i < all.length && out.length < 80; i++) {
        var el = all[i];
        var own = /^(IMG|VIDEO|IFRAME|CANVAS|SVG)$/i.test(el.tagName);
        for (var c = el.firstChild; c && !own; c = c.nextSibling) {
          if (c.nodeType === 3 && /\S/.test(c.nodeValue)) own = true;
        }
        if (own && visible(el)) out.push(el);
      }
    } catch (e) {}
    return out;
  }

  // 재생 중인 영상·소리나 보이는 안쪽 페이지(플레이어)가 있는지.
  function playing(doc) {
    var i;
    if (doc === document) {
      if (sources.length > 0) return true;
      var every = allMedia();
      for (i = 0; i < every.length; i++) if (!every[i].paused && !every[i].ended) return true;
    }
    try {
      var media = doc.querySelectorAll('video, audio');
      for (i = 0; i < media.length; i++) if (!media[i].paused && !media[i].ended) return true;
      var frames = doc.querySelectorAll('iframe');
      for (i = 0; i < frames.length; i++) {
        if (visible(frames[i])) return true;
        try { if (frames[i].contentDocument && playing(frames[i].contentDocument)) return true; } catch (e) {}
      }
    } catch (e) {}
    return false;
  }

  // 유튜브 플레이어는 상태가 바뀌면 바깥 페이지에 알린다. 늘 받아 적어 둔다.
  // 새 영상을 불러오면 "시작 전"(-1)이나 "준비됨"(5)을 거치고 영상 번호가 바뀐다. 같은 영상을 처음부터 다시 돌릴 때도
  // -1이 올 수 있어, 영상 번호를 아는 플레이어면 번호가 바뀌어야 새 후원으로 본다. "끝남"(0)은 세지 않는다.
  var player = { id: null, loads: 0, idChanges: 0 };
  window.addEventListener('message', function (event) {
    if (typeof event.data !== 'string' || event.data.charAt(0) !== '{') return;
    try {
      var data = JSON.parse(event.data);
      if (data.event === 'onStateChange' && (data.info === -1 || data.info === 5)) player.loads++;
      if (data.event === 'infoDelivery' && data.info && data.info.videoData && data.info.videoData.video_id) {
        if (player.id && player.id !== data.info.videoData.video_id) player.idChanges++;
        player.id = data.info.videoData.video_id;
      }
    } catch (e) {}
  });

  // 페이지가 바뀌었는지 (타이머가 돈 뒤에 바뀐 것이 없으면 다시 살피지 않는다).
  var observer = null;
  var mutated = false;
  function touched() {
    if (observer && observer.takeRecords().length > 0) mutated = true;
    var was = mutated;
    mutated = false;
    return was;
  }

  var base = null; // 넘기기를 시작할 때의 모습

  function remember() {
    base = {
      text: mainText(), visuals: visuals(document, []).join('\n'), content: hasContent(), nodes: shownNodes(),
      loads: player.loads, idChanges: player.idChanges, loadSeenAt: 0,
      media: [], // 그때 재생 중이던 영상·소리 (이 후원의 것)
    };
    var media = allMedia();
    for (var i = 0; i < media.length; i++) {
      if (!media[i].paused && !media[i].ended) base.media.push({ element: media[i], src: mediaSrc(media[i]) });
    }
  }

  // 이 후원의 영상·소리(넘기기를 시작할 때 재생 중이던 것)가 모두 끝났거나 없어졌는가.
  function ownMediaDone() {
    if (base.media.length === 0) return false;
    for (var i = 0; i < base.media.length; i++) {
      var m = base.media[i].element;
      if (!m.paused && !m.ended && mediaSrc(m) === base.media[i].src) return false;
    }
    return true;
  }

  // 지금 나오던 후원이 끝났는가 (페이지를 바로 살펴 안다).
  function ended() {
    // 보이는 것 없이 소리만 내는 위젯은 그 소리로 안다. 보이는 것이 있으면 소리는 보지 않는다
    // (후원 도중에 효과음이 끝나거나 읽어 주는 소리가 시작되는 것은 끝난 것이 아니다).
    if (base.nodes.length === 0 && !base.content) return ownMediaDone();
    if (base.nodes.length > 0) {
      var gone = 0;
      for (var i = 0; i < base.nodes.length; i++) if (!base.nodes[i].isConnected || !visible(base.nodes[i])) gone++;
      if (gone * 2 > base.nodes.length) return true;
    }
    if (base.content && !hasContent()) return true;
    if (visuals(document, []).join('\n') !== base.visuals) return true;
    var text = mainText();
    if (text !== base.text) {
      if (text.indexOf(base.text) === 0) base.text = text; // 글자가 이어서 나오는 중
      else if (text === '' || base.text.indexOf(text) !== 0) return true;
    }
    return false;
  }

  // 플레이어가 알린 것으로 본 결과: 'changed' 다른 영상이다, 'unsure' 새로 불러오긴 했는데 같은 영상인지 아직 모른다, 'same'.
  function playerState() {
    if (player.idChanges > base.idChanges) return 'changed';
    if (player.loads > base.loads) {
      if (player.id === null) return 'changed'; // 영상 번호를 알려 주지 않는 플레이어
      if (base.loadSeenAt === 0) base.loadSeenAt = NativeDate.now();
      if (NativeDate.now() - base.loadSeenAt < SAME_VIDEO_MS) return 'unsure';
      // 번호가 그대로다: 같은 영상을 처음부터 다시 돌린 것이다.
      base.loads = player.loads;
      base.loadSeenAt = 0;
    }
    return 'same';
  }

  // --------------------------------------------------------------------------------------------
  // 가리기: 넘기는 동안 페이지를 통째로 안 보이게 하고 소리를 끈다. 후원이 바뀌면 되돌린다.
  // 후원의 재생 시간을 서버가 재는 위젯(치지직 영상 후원)은 시계를 감아도 넘어가지 않는다. 그때도 방송 화면에서는 바로
  // 사라지고, 서버가 다음 후원을 보내면 다시 보인다.

  var hidden = null; // { opacity, priority, media: [소리를 끈 것과 그때의 주소], frames: [소리를 끄라고 한 유튜브 플레이어] }

  function tell(frame, func, args) {
    try { frame.contentWindow.postMessage(JSON.stringify({ event: 'command', func: func, args: args || [] }), '*'); } catch (e) {}
  }

  function muteIn(doc) {
    var i;
    var media = doc === document ? allMedia() : doc.querySelectorAll('video, audio');
    for (i = 0; i < media.length; i++) {
      if (!media[i].muted) {
        media[i].muted = true;
        hidden.media.push({ element: media[i], src: mediaSrc(media[i]), time: media[i].currentTime, epoch: epoch, replayed: -1 });
      }
    }
    var frames = doc.querySelectorAll('iframe');
    for (i = 0; i < frames.length; i++) {
      if (/youtube(-nocookie)?\.com\/embed\//.test(frames[i].src || '')) {
        tell(frames[i], 'mute');
        if (hidden.frames.indexOf(frames[i]) < 0) hidden.frames.push(frames[i]);
      }
      try { if (frames[i].contentDocument) muteIn(frames[i].contentDocument); } catch (e) {}
    }
  }

  function hide() {
    var style = document.documentElement.style;
    hidden = { opacity: style.getPropertyValue('opacity'), priority: style.getPropertyPriority('opacity'), media: [], frames: [] };
    style.setProperty('opacity', '0', 'important');
    try { muteIn(document); } catch (e) {}
    // Web Audio 소리는 따로 끌 수 없어 멈춘다. 브라우저가 읽어 주는 목소리도 멈춘다.
    stopSources(Infinity);
    try { if (window.speechSynthesis) window.speechSynthesis.cancel(); } catch (e) {}
    epoch++; // 이 뒤에 시작되는 소리는 따로 가른다
  }

  // 소리를 껐던 것을 되돌린다. 넘긴 후원의 소리가 아직 나오고 있으면 (화면은 끝났는데 읽어 주는 소리가 남은 경우)
  // 그 소리는 끝으로 넘기고, 끝나거나 다른 것을 불러올 때 소리를 켠다 (같은 것을 다음 후원에도 쓰는 페이지가 있다).
  function unmute(entry) {
    var m = entry.element;
    try {
      // 후원이 바뀐 바로 그때 시작한 소리는 다음 후원의 것이다 (같은 효과음을 처음부터 다시 튼 것도).
      if (entry.epoch === epoch || entry.replayed === epoch) { m.muted = false; return; }
      var same = mediaSrc(m) === entry.src;
      // 멈췄거나, 다른 것을 불러왔거나, 처음부터 다시 재생한 것은 바로 켠다.
      if (m.paused || m.ended || !same || m.currentTime < entry.time) { m.muted = false; return; }
      var done = false;
      var restore = function () {
        if (done) return;
        done = true;
        m.muted = false;
        m.removeEventListener('ended', restore); m.removeEventListener('pause', restore);
        m.removeEventListener('loadstart', restore); m.removeEventListener('emptied', restore);
      };
      m.addEventListener('ended', restore); m.addEventListener('pause', restore);
      m.addEventListener('loadstart', restore); m.addEventListener('emptied', restore);
      if (!m.loop && isFinite(m.duration) && m.duration > 0) m.currentTime = m.duration;
      nativeSetTimeout(restore, LEFTOVER_SOUND_MS);
    } catch (e) {
      try { m.muted = false; } catch (e2) {}
    }
  }

  function show() {
    if (!hidden) return;
    var was = hidden;
    hidden = null;
    var style = document.documentElement.style;
    if (was.opacity) style.setProperty('opacity', was.opacity, was.priority); else style.removeProperty('opacity');
    var i;
    for (i = 0; i < was.media.length; i++) unmute(was.media[i]);
    for (i = 0; i < was.frames.length; i++) tell(was.frames[i], 'unMute');
  }

  // 시계를 감아도 끝나지 않을 때: 이 후원의 영상·소리(넘기기를 시작할 때 재생 중이던 것)를 끝으로 넘긴다.
  // 재생이 끝나야 다음으로 넘어가는 위젯을 위한 것이다. 그 뒤에 시작된 것(다음 후원일 수 있다)은 건드리지 않는다.
  function endOwnMedia() {
    for (var i = 0; i < base.media.length; i++) {
      var m = base.media[i].element;
      try {
        if (m.ended || mediaSrc(m) !== base.media[i].src) continue;
        if (!m.paused && isFinite(m.duration) && m.duration > 0) {
          m.currentTime = m.duration;
        } else {
          try { m.pause(); } catch (e) {}
          m.dispatchEvent(new Event('ended'));
        }
      } catch (e) {}
    }
    if (player.idChanges === base.idChanges) {
      try {
        var frames = document.querySelectorAll('iframe');
        for (var j = 0; j < frames.length; j++) {
          if (/youtube(-nocookie)?\.com\/embed\//.test(frames[j].src || '')) tell(frames[j], 'seekTo', [86400, true]);
        }
      } catch (e) {}
    }
  }

  var STEP_MS = 1000; // 한 번에 흘려보내는 페이지 시간
  var STEP_FIRES = 300; // 한 번에 돌리는 타이머 수
  var STEP_REAL_MS = 40; // 그 사이에 페이지가 반응할 틈 (실제 시간)
  var SETTLE_REAL_MS = 90; // 후원이 끝나는 때일 수 있는 타이머를 돌린 뒤 플레이어의 알림을 기다리는 시간
  var MAX_WIND_MS = 3 * 60 * 1000; // 넘기기 한 번에 이만큼 감는다 (더 오래 남은 타이머가 걸려 있으면 그때까지)
  var LONG_MS = 1500; // 이보다 오래 남은 타이머가 후원이 끝나는 때일 수 있다
  var MEDIA_AFTER_MS = 5000; // 끝나는 때일 수 있는 타이머가 다 지나고 이만큼 더 감아도 그대로면 영상·소리를 끝으로 넘긴다
  var WAIT_STEP_MS = 250; // 가린 채로 후원이 바뀌기를 기다리며 살피는 간격
  var MAX_WAIT_MS = 15 * 60 * 1000; // 이만큼 기다려도 그대로면 다시 보인다
  var SAME_VIDEO_MS = 1500; // 새로 불러온 영상의 번호를 이만큼 기다려 본다
  var LEFTOVER_SOUND_MS = 5000; // 넘긴 후원의 남은 소리를 이만큼 지나면 다시 켠다

  // 지금 나오는 후원 하나를 넘긴다. [hideIt]이면 넘어갈 때까지 페이지를 가리고 소리를 끈다.
  //   1. 페이지의 시간을 빨리 감는다. 타이머가 돌 때마다 살펴 이 후원이 끝난 순간에 멈춘다
  //      (시간으로 넘어가는 위젯은 여기서 넘어간다. 다음 후원은 감지 않는다).
  //   2. 다 감아도 그대로면 이 후원의 영상·소리를 끝으로 넘겨 본다 (재생이 끝나야 넘어가는 위젯).
  //   3. 그래도 그대로면 가린 채로 기다린다 (서버가 시간을 재는 위젯). 후원이 바뀌면 다시 보인다.
  // [waitOnly]면 1·2를 건너뛴다: 서버가 시간을 재는 것으로 알려진 위젯은 시계를 감아 봐야 연결 유지 타이머만 앞당겨진다.
  // 돌려주는 값:
  //   'started'  넘기기 시작했다
  //   'idle'     나오는 후원이 없다 (화면이 비어 있고 재생 중인 것도, 기다리는 긴 타이머도 없다)
  //   'busy'     이미 넘기는 중이다
  function skip(hideIt, waitOnly) {
    if (skipping) return 'busy';
    // 후원이 끝나는 때일 수 있는 타이머: 지금 걸려 있는 것 가운데 오래 남은 것. 이것을 돌린 뒤에는 잠깐 멈춰 살핀다.
    var candidates = {};
    var left = 0; // 아직 돌지 않은 그런 타이머 수
    var longest = 0;
    for (var id in timers) {
      var t = timers[id];
      if (t.period === 0 && t.due - now() >= LONG_MS) { candidates[id] = true; left++; longest = Math.max(longest, t.due - now()); }
    }
    if (left === 0 && !playing(document) && !hasContent()) return 'idle';

    var state = {
      wound: 0, // 감은 페이지 시간
      limit: Math.min(Math.max(MAX_WIND_MS, longest + STEP_MS), MAX_WAIT_MS),
      since: 0, // 끝나는 때일 수 있는 타이머가 마지막으로 돈 뒤에 감은 시간
      mediaTried: false,
      waited: 0, waiting: !!(hideIt && waitOnly), result: null,
    };
    skipping = state;
    remember();
    if (hideIt) hide();
    mutated = false;
    try {
      observer = new MutationObserver(function () { mutated = true; });
      observer.observe(document, { subtree: true, childList: true, attributes: true, characterData: true });
    } catch (e) { observer = null; }

    function finish(result) {
      state.result = result;
      skipping = null;
      if (observer) { observer.disconnect(); observer = null; }
      show();
      base = null;
      arm();
    }

    // 페이지의 시간을 조금 감는다. 타이머가 돌 때마다 살핀다. 돌려주는 값: 'ended' 이 후원이 끝났다,
    // 'settle' 끝나는 때일 수 있는 타이머를 돌렸다 (플레이어의 알림을 기다려 본다), 'more' 더 감을 수 있다, 'done' 다 감았다.
    function wind() {
      var from = now();
      var until = Math.min(from + STEP_MS, from + (state.limit - state.wound));
      var fires = 0;
      var outcome = null;
      advancing = true;
      try {
        while (outcome === null) {
          var t = earliest(until);
          if (!t) {
            if (until > now()) offset += until - now();
            outcome = 'more';
            break;
          }
          if (t.due > now()) offset += t.due - now();
          var candidate = candidates[t.id] === true;
          if (candidate) { delete candidates[t.id]; left--; state.since = -(now() - from); }
          fire(t);
          fires++;
          if (touched() && ended()) {
            outcome = 'ended';
          } else {
            still();
            if (candidate) outcome = 'settle';
            else if (fires >= STEP_FIRES) outcome = 'more';
          }
        }
      } finally {
        advancing = false;
      }
      state.wound += now() - from;
      state.since += now() - from;
      if (outcome === 'more' && state.wound >= state.limit) outcome = 'done';
      arm();
      return outcome;
    }

    // 아직 이 후원이다. 지금까지 시작된 소리는 이 후원의 것이다 (가리는 중이면 Web Audio 소리는 멈춘다).
    function still() {
      epoch++;
      if (hidden) stopSources(epoch);
    }

    function step() {
      var players = playerState();
      if (players === 'changed' || ended()) return finish('changed');
      still();
      if (players === 'unsure') { nativeSetTimeout(step, STEP_REAL_MS); return; } // 다음 후원일 수 있다: 더 감지 않고 기다린다
      if (!state.waiting) {
        var outcome = wind();
        if (outcome === 'ended') return finish('changed');
        if (outcome === 'settle') { nativeSetTimeout(step, SETTLE_REAL_MS); return; }
        if (outcome === 'more') {
          // 끝나는 때일 수 있는 타이머가 다 지났는데도 그대로다: 시간이 아니라 재생이 끝나야 넘어가는 위젯일 수 있다.
          // 이 후원의 영상·소리를 끝으로 넘겨 보고 계속 감는다.
          if (!state.mediaTried && left === 0 && state.since >= MEDIA_AFTER_MS) {
            state.mediaTried = true;
            endOwnMedia();
            nativeSetTimeout(step, SETTLE_REAL_MS);
            return;
          }
          nativeSetTimeout(step, STEP_REAL_MS);
          return;
        }
        // 다 감았는데도 같은 후원이 나오고 있다.
        if (!state.mediaTried) { state.mediaTried = true; endOwnMedia(); }
        if (!hideIt) return finish('limit');
        state.waiting = true;
        nativeSetTimeout(step, SETTLE_REAL_MS);
        return;
      }
      state.waited += WAIT_STEP_MS;
      if (state.waited >= MAX_WAIT_MS) return finish('timeout');
      try { muteIn(document); } catch (e) {} // 되풀이하려고 새로 만든 것도 소리를 끈다
      nativeSetTimeout(step, WAIT_STEP_MS);
    }
    step();
    return 'started';
  }

  window.__camneuronTime = {
    skip: skip,
    advance: advance,
    offset: function () { return offset; },
    pending: function () { var n = 0; for (var id in timers) n++; return n; },
    skipping: function () { return skipping !== null; },
    hidden: function () { return hidden !== null; }
  };
})();
