// 후원 위젯(후원 알림, 영상 후원) 페이지에서 지금 재생 중인 영상·소리를 끝으로 넘긴다 (WidgetHost.skipDonation).
// 위젯 페이지는 재생이 끝났다는 신호를 받아야 다음 후원으로 넘어가는데, 영상이 멈추거나 끝나지 않으면 그 뒤가 모두 밀린다.
// 페이지를 다시 부르지 않으므로 페이지가 쌓아 둔 다음 후원은 그대로 남는다.
// 돌려주는 값: 넘긴 영상·소리의 수. 0이면 넘길 것이 없었다.
(function () {
  var count = 0;

  function skipMedia(media) {
    try {
      if (media.ended) return;
      var hasEnd = isFinite(media.duration) && media.duration > 0;
      if (!media.paused) {
        if (hasEnd) {
          // 재생 중: 끝으로 옮기면 브라우저가 끝났다고(ended) 알린다.
          media.currentTime = media.duration;
        } else {
          // 불러오다 멈춘 것: 길이를 몰라 옮길 수 없으니 멈추고 끝났다고 알린다.
          try { media.pause(); } catch (e) {}
          media.dispatchEvent(new Event('ended'));
        }
        count++;
      } else if (media.currentTime > 0 || media.error) {
        // 재생하다 멈춰 섰거나 불러오지 못한 것. (아직 한 번도 재생하지 않은 멀쩡한 것은 다음 후원을 기다리는 것일 수 있어
        // 건드리지 않는다.)
        media.dispatchEvent(new Event('ended'));
        count++;
      }
    } catch (e) {}
  }

  function skipIn(doc) {
    var i;
    var media = doc.querySelectorAll('video, audio');
    for (i = 0; i < media.length; i++) skipMedia(media[i]);

    var frames = doc.querySelectorAll('iframe');
    for (i = 0; i < frames.length; i++) {
      var frame = frames[i];
      try {
        if (/youtube(-nocookie)?\.com\/embed\//.test(frame.src || '')) {
          // 유튜브 플레이어: 끝으로 옮기라고 시킨다. 플레이어가 끝났다고 알리면 위젯이 다음으로 넘어간다.
          frame.contentWindow.postMessage(JSON.stringify({ event: 'command', func: 'seekTo', args: [86400, true] }), '*');
          count++;
        }
      } catch (e) {}
      // 같은 사이트의 안쪽 페이지는 들어가서 본다 (다른 사이트면 막혀서 예외가 난다).
      try {
        if (frame.contentDocument) skipIn(frame.contentDocument);
      } catch (e) {}
    }
  }

  try { skipIn(document); } catch (e) {}
  return count;
})()
