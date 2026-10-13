// QoE 지표 정의는 design.md §9
(function () {
  'use strict';

  var video = document.getElementById('video');
  var form = document.getElementById('form');
  var srcInput = document.getElementById('src');
  var table = document.getElementById('metrics');
  var hls = null;

  var qoe = null;

  function newSession(src) {
    return {
      src: src,
      startedAt: performance.now(),
      firstPlayingAt: null,
      startupMs: null,
      rebufferMs: 0,
      rebufferCount: 0,
      waitingSince: null,
      levelSwitches: 0,
      currentLevel: null,
      errors: [],
      ended: false
    };
  }

  function snapshot() {
    if (!qoe) return null;
    var now = performance.now();
    var watchedMs = qoe.firstPlayingAt ? now - qoe.firstPlayingAt : 0;
    var rebufferMs = qoe.rebufferMs + (qoe.waitingSince ? now - qoe.waitingSince : 0);
    return {
      src: qoe.src,
      startupMs: qoe.startupMs,
      watchedMs: Math.round(watchedMs),
      rebufferMs: Math.round(rebufferMs),
      rebufferCount: qoe.rebufferCount,
      rebufferRatio: watchedMs > 0 ? rebufferMs / watchedMs : null,
      levelSwitches: qoe.levelSwitches,
      currentLevel: qoe.currentLevel,
      errors: qoe.errors.slice(),
      ended: qoe.ended,
      hlsJsVersion: window.Hls ? Hls.version : null
    };
  }

  window.__qoe = snapshot;

  function render() {
    var s = snapshot();
    if (!s) return;
    var rows = [
      ['startup (ms)', s.startupMs === null ? '-' : Math.round(s.startupMs)],
      ['watched (ms)', s.watchedMs],
      ['rebuffer (ms / count)', s.rebufferMs + ' / ' + s.rebufferCount],
      ['rebuffer ratio', s.rebufferRatio === null ? '-' : s.rebufferRatio.toFixed(4)],
      ['level / switches', (s.currentLevel || '-') + ' / ' + s.levelSwitches],
      ['errors', s.errors.length ? s.errors.join(', ') : '-']
    ];
    table.innerHTML = rows.map(function (r) {
      return '<tr><td>' + r[0] + '</td><td>' + r[1] + '</td></tr>';
    }).join('');
  }

  video.addEventListener('playing', function () {
    if (!qoe) return;
    var now = performance.now();
    if (qoe.firstPlayingAt === null) {
      qoe.firstPlayingAt = now;
      qoe.startupMs = now - qoe.startedAt;
    }
    if (qoe.waitingSince !== null) {
      qoe.rebufferMs += now - qoe.waitingSince;
      qoe.waitingSince = null;
    }
  });

  video.addEventListener('waiting', function () {
    // 시작 전 대기는 startup에 들어간다
    if (!qoe || qoe.firstPlayingAt === null || qoe.waitingSince !== null) return;
    qoe.waitingSince = performance.now();
    qoe.rebufferCount += 1;
  });

  video.addEventListener('ended', function () {
    if (qoe) qoe.ended = true;
  });

  function load(src) {
    if (hls) {
      hls.destroy();
      hls = null;
    }
    qoe = newSession(src);

    if (window.Hls && Hls.isSupported()) {
      hls = new Hls();
      hls.on(Hls.Events.LEVEL_SWITCHED, function (_, data) {
        var level = hls.levels[data.level];
        var label = level ? level.height + 'p' : String(data.level);
        if (qoe.currentLevel !== null && qoe.currentLevel !== label) qoe.levelSwitches += 1;
        qoe.currentLevel = label;
      });
      hls.on(Hls.Events.ERROR, function (_, data) {
        qoe.errors.push(data.type + ':' + data.details + (data.fatal ? ':fatal' : ''));
      });
      hls.loadSource(src);
      hls.attachMedia(video);
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src; // Safari 네이티브 HLS, 화질 전환은 못 잰다
    } else {
      qoe.errors.push('hls-unsupported');
      return;
    }
    video.play().catch(function (e) { qoe.errors.push('play:' + e.name); });
  }

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    load(srcInput.value);
  });

  var params = new URLSearchParams(location.search);
  if (params.get('src')) srcInput.value = params.get('src');
  if (params.get('autoplay') === '1') load(srcInput.value);

  setInterval(render, 500);
})();
