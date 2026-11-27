// Terminal page for the Claude tab. Shows the bridge's session with xterm.js
// and talks to the app through the `UkHost` object it injects.
//
// Typing does not happen here: the app keeps the phone keyboard on its own
// input path and sends finished key sequences with uk.input(). That way the
// sticky Ctrl/Alt/Shift and the shortcut bar work the same for PC and Claude.
(function () {
  'use strict';

  var CLOSE_REPLACED = 4000, CLOSE_BAD_CODE = 4001, CLOSE_ENDED = 4002, CLOSE_START_FAILED = 4003;
  var MAX_RETRY_MS = 10000;

  var host = window.UkHost || { onStatus: function () {} };
  var term = new Terminal({
    disableStdin: true,
    fontFamily: 'monospace',
    fontSize: 13,
    scrollback: 5000,
    theme: { background: '#1B1D22', foreground: '#E6E8EE', cursor: '#7C9CFF' },
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('term'));
  // Never let the page pull up the keyboard itself.
  if (term.textarea) { term.textarea.readOnly = true; term.textarea.inputMode = 'none'; }
  fit.fit();

  var ws = null, port = 0, token = '', retryMs = 1000, retryTimer = null;

  function status(state, detail) { host.onStatus(state, detail || ''); }

  function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
  }

  function connect() {
    clearTimeout(retryTimer);
    if (ws) { ws.onclose = null; ws.close(); ws = null; }
    if (!port || !token) return;
    status('connecting');
    var socket = new WebSocket('ws://127.0.0.1:' + port + '/');
    socket.binaryType = 'arraybuffer';
    ws = socket;
    socket.onopen = function () {
      socket.send(JSON.stringify({ type: 'auth', token: token, cols: term.cols, rows: term.rows }));
    };
    socket.onmessage = function (e) {
      if (typeof e.data !== 'string') { term.write(new Uint8Array(e.data)); return; }
      var msg = JSON.parse(e.data);
      if (msg.type === 'ready') {
        // A replay redraws the whole session, so start from a clean screen.
        term.reset();
        retryMs = 1000;
        status('connected', msg.replay ? 'resumed' : 'new');
      } else if (msg.type === 'exit') {
        term.write('\r\n[session ended, exit code ' + msg.code + ']\r\n');
      } else if (msg.type === 'error') {
        term.write('\r\n[' + msg.message + ']\r\n');
      }
      // Unknown types are ignored. Phase B (typing on the host on Claude's
      // request) would add a message here and forward it through UkHost.
    };
    socket.onclose = function (e) {
      if (ws !== socket) return;
      ws = null;
      switch (e.code) {
        case CLOSE_BAD_CODE: status('bad_code'); break;
        case CLOSE_ENDED: status('ended'); break;
        case CLOSE_START_FAILED: status('error', 'The bridge could not start the session'); break;
        case CLOSE_REPLACED: status('replaced'); break;
        default:
          // Bridge not running yet, or restarted: keep trying, slowing down.
          status('disconnected', 'Retrying in ' + Math.round(retryMs / 1000) + ' s');
          retryTimer = setTimeout(connect, retryMs);
          retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
      }
    };
  }

  term.onResize(function (size) { send({ type: 'resize', cols: size.cols, rows: size.rows }); });

  // The view shrinks when the phone keyboard opens; refit so the program
  // gets the real size.
  var fitTimer = null;
  window.addEventListener('resize', function () {
    clearTimeout(fitTimer);
    fitTimer = setTimeout(function () { fit.fit(); }, 80);
  });

  // xterm.js has no touch scrolling of its own: map vertical drags to lines.
  var touchY = null, touchRest = 0;
  var el = document.getElementById('term');
  el.addEventListener('touchstart', function (e) {
    touchY = e.touches.length === 1 ? e.touches[0].clientY : null;
    touchRest = 0;
  }, { passive: true });
  el.addEventListener('touchmove', function (e) {
    if (touchY === null || e.touches.length !== 1) return;
    var y = e.touches[0].clientY;
    var lineHeight = el.clientHeight / term.rows;
    touchRest += (touchY - y) / lineHeight;
    touchY = y;
    var lines = touchRest > 0 ? Math.floor(touchRest) : Math.ceil(touchRest);
    if (lines !== 0) { term.scrollLines(lines); touchRest -= lines; }
    e.preventDefault();
  }, { passive: false });

  window.uk = {
    /** Connects (or reconnects) to the bridge on 127.0.0.1:[p]. */
    connect: function (p, code) { port = p; token = code; retryMs = 1000; connect(); },
    /** Sends key sequences (already encoded for a terminal) to the session. */
    input: function (text) {
      send({ type: 'input', data: text });
      term.scrollToBottom();
    },
    disconnect: function () {
      clearTimeout(retryTimer);
      port = 0;
      if (ws) { ws.onclose = null; ws.close(); ws = null; }
      status('disconnected', '');
    },
  };
  status('idle');
})();
