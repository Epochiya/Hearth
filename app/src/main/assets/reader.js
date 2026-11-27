(function () {
  if (window.hearth) return;
  document.documentElement.classList.add('hl-loading');
  var B = window.HearthBridge;
  var H = window.hearth = {};
  var cfg = { paged: true, margin: 32, top: 44, bottom: 52, twoPage: true, tapZones: true, anim: true };
  var W = 0, VH = 0, page = 0, pages = 1, chText = null, lastSel = null;
  var lastStart = -1, lastEnd = -1;
  var lastTap = 0, tapTimer = null, touch = null, dragging = false, ready = false, pinch = null;
  function dist(t) { var dx = t[0].clientX - t[1].clientX, dy = t[0].clientY - t[1].clientY; return Math.sqrt(dx * dx + dy * dy); }

  function root() { return document.scrollingElement || document.documentElement; }
  function imp(el, k, v) { el.style.setProperty(k, v, 'important'); }
  function text() { if (chText === null) chText = document.body ? (document.body.textContent || '') : ''; return chText; }

  function layout() {
    W = window.innerWidth; VH = window.innerHeight;
    var b = document.body, m = cfg.margin;
    if (!b) return;
    imp(b, 'padding', cfg.top + 'px ' + m + 'px ' + cfg.bottom + 'px ' + m + 'px');
    if (cfg.paged) {
      document.documentElement.classList.add('hp');
      var two = cfg.twoPage && W > VH && W >= 700;
      var colW = two ? (W / 2 - 2 * m) : (W - 2 * m);
      imp(b, 'width', W + 'px');
      imp(b, 'height', VH + 'px');
      imp(b, 'column-width', colW + 'px');
      imp(b, 'column-gap', (2 * m) + 'px');
      imp(b, 'column-fill', 'auto');
      root().scrollTop = 0;
    } else {
      document.documentElement.classList.remove('hp');
      ['width', 'height', 'column-width', 'column-gap', 'column-fill'].forEach(function (k) { b.style.removeProperty(k); });
      root().scrollLeft = 0;
    }
    b.style.transform = 'none';
  }

  function measure() {
    if (cfg.paged) pages = Math.max(1, Math.ceil((root().scrollWidth - 4) / W));
    else pages = Math.max(1, Math.ceil((root().scrollHeight - 4) / VH));
  }

  function offsetOf(node, off) {
    try {
      var r = document.createRange();
      r.setStart(document.body, 0); r.setEnd(node, off);
      return r.toString().length;
    } catch (e) { return -1; }
  }

  function posOf(off) {
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null), n, acc = 0, last = null;
    while ((n = w.nextNode())) {
      var l = n.data.length;
      if (acc + l > off) return { node: n, off: off - acc };
      acc += l; last = n;
    }
    return last ? { node: last, off: last.data.length } : null;
  }

  function caretAt(x, y) {
    if (!document.caretRangeFromPoint) return -1;
    var r = document.caretRangeFromPoint(x, y);
    if (!r || !document.body.contains(r.startContainer)) return -1;
    if (r.startContainer.nodeType !== 3) return -1;
    return offsetOf(r.startContainer, r.startOffset);
  }

  function pageStart() {
    var m = cfg.margin;
    for (var y = cfg.top + 6; y < VH - cfg.bottom; y += 16)
      for (var x = m + 2; x < W - m; x += 36) { var o = caretAt(x, y); if (o >= 0) return o; }
    return -1;
  }
  function pageEnd() {
    var m = cfg.margin;
    for (var y = VH - cfg.bottom - 6; y > cfg.top; y -= 16)
      for (var x = W - m - 2; x > m; x -= 36) { var o = caretAt(x, y); if (o >= 0) return o; }
    return -1;
  }

  function report() {
    var t = text();
    var s = pageStart(), e = pageEnd();
    if (s < 0) s = Math.floor(t.length * page / pages);
    if (page >= pages - 1) e = t.length;
    if (e < s) e = Math.floor(t.length * (page + 1) / pages);
    lastStart = s; lastEnd = Math.max(s, e);
    var ex = t.substr(s, 160).replace(/\s+/g, ' ').trim();
    B.onPage(page, pages, s, lastEnd, ex);
  }

  function slide(from) {
    var b = document.body;
    if (!cfg.anim) { b.style.transition = 'none'; b.style.transform = 'none'; return; }
    b.style.transition = 'none';
    b.style.transform = 'translateX(' + from + 'px)';
    void b.offsetWidth;
    b.style.transition = 'transform 300ms cubic-bezier(.2,.9,.25,1)';
    b.style.transform = 'translateX(0px)';
  }
  function resetDrag() { slide(0); }

  function go(p, dir, dragX) {
    p = Math.max(0, Math.min(pages - 1, p));
    page = p;
    if (cfg.paged) {
      root().scrollLeft = p * W;
      if (dir) slide(typeof dragX === 'number' ? dragX + dir * W : dir * W * 0.3);
      else { document.body.style.transition = 'none'; document.body.style.transform = 'none'; }
    } else {
      root().scrollTop = p * VH;
    }
    report();
  }

  H.next = function (dragX) {
    if (cfg.paged) {
      if (page < pages - 1) go(page + 1, 1, dragX);
      else { resetDrag(); B.nextChapter(); }
    } else {
      var r = root();
      if (r.scrollTop + VH >= r.scrollHeight - 4) B.nextChapter();
      else r.scrollBy({ top: VH - cfg.top - cfg.bottom, behavior: 'smooth' });
    }
  };
  H.prev = function (dragX) {
    if (cfg.paged) {
      if (page > 0) go(page - 1, -1, dragX);
      else { resetDrag(); B.prevChapter(); }
    } else {
      var r = root();
      if (r.scrollTop <= 2) B.prevChapter();
      else r.scrollBy({ top: -(VH - cfg.top - cfg.bottom), behavior: 'smooth' });
    }
  };

  function rectAt(off) {
    var p = posOf(off);
    if (!p) return null;
    var r = document.createRange();
    r.setStart(p.node, Math.min(p.off, p.node.data.length));
    r.setEnd(p.node, Math.min(p.node.data.length, p.off + 1));
    var rects = r.getClientRects();
    return rects.length ? rects[0] : r.getBoundingClientRect();
  }

  function goOffset(off, flashLen) {
    var rect = rectAt(off);
    if (!rect) { go(0); return; }
    if (cfg.paged) {
      go(Math.floor((rect.left + root().scrollLeft + 1) / W));
    } else {
      root().scrollTop = Math.max(0, rect.top + root().scrollTop - cfg.top);
      page = Math.floor(root().scrollTop / VH);
      report();
    }
    if (flashLen > 0) {
      wrap({ id: '__flash', start: off, end: off + flashLen, color: 'rgba(255,200,80,.55)' });
      setTimeout(function () { H.removeHl('__flash'); }, 1800);
    }
  }
  H.goOffset = goOffset;
  H.goFraction = function (f) { go(Math.round(f * (pages - 1))); };

  function goAnchor(id) {
    var el = document.getElementById(id);
    if (!el) { go(0); return; }
    var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    var n = w.nextNode();
    var off = n ? offsetOf(n, 0) : offsetOf(el, 0);
    goOffset(Math.max(0, off), 0);
  }
  H.goAnchor = goAnchor;

  H.find = function (q, k) {
    var t = text().toLowerCase(), ql = q.toLowerCase(), idx = -1;
    for (var i = 0; i <= k; i++) { var n = t.indexOf(ql, idx + 1); if (n < 0) break; idx = n; }
    if (idx < 0) idx = t.indexOf(ql);
    if (idx >= 0) goOffset(idx, q.length); else go(0);
  };

  /* ---------- highlights ---------- */
  function wrap(h) {
    var start = h.start, end = h.end;
    if (end <= start) return;
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null), n, pos = 0, targets = [];
    while ((n = w.nextNode())) {
      var len = n.data.length, s = pos, e = pos + len;
      if (e > start && s < end) targets.push([n, Math.max(0, start - s), Math.min(len, end - s)]);
      pos = e;
      if (pos >= end) break;
    }
    targets.forEach(function (t) {
      var node = t[0], a = t[1], b = t[2];
      if (!/\S/.test(node.data.substring(a, b))) return;
      var p = node.parentNode;
      if (!p || /^(TABLE|TBODY|THEAD|TR|UL|OL|SELECT|svg)$/i.test(p.nodeName)) return;
      if (b < node.data.length) node.splitText(b);
      var target = a > 0 ? node.splitText(a) : node;
      var m = document.createElement('mark');
      m.className = 'hl' + (h.note ? ' note' : '') + (h.id === '__flash' ? ' hl-flash' : '');
      m.setAttribute('data-id', h.id);
      imp(m, 'background-color', h.color);
      target.parentNode.insertBefore(m, target);
      m.appendChild(target);
    });
  }
  H.addHl = function (h) { wrap(h); };
  H.removeHl = function (id) {
    var ms = document.querySelectorAll('mark.hl[data-id="' + id + '"]');
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i], p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
      p.normalize();
    }
  };
  H.updateHl = function (h) { H.removeHl(h.id); wrap(h); };

  /* ---------- selection ---------- */
  document.addEventListener('selectionchange', function () {
    var s = window.getSelection();
    if (s && s.rangeCount && !s.isCollapsed) {
      var r = s.getRangeAt(0);
      var a = offsetOf(r.startContainer, r.startOffset), b = offsetOf(r.endContainer, r.endOffset);
      if (a >= 0 && b > a) lastSel = { start: a, end: b, text: s.toString() };
    }
  });
  H.useSelection = function (action) {
    if (lastSel) B.onSelection(action, lastSel.start, lastSel.end, lastSel.text);
    try { window.getSelection().removeAllRanges(); } catch (e) { }
    lastSel = null;
  };
  H.pageText = function () { return text().slice(Math.max(0, lastStart), Math.max(lastStart, lastEnd)); };

  /* ---------- touch ---------- */
  function hasSel() { var s = window.getSelection(); return s && !s.isCollapsed; }

  document.addEventListener('touchstart', function (e) {
    if (e.touches.length === 2) { pinch = { d: dist(e.touches), s: 1 }; }
    if (e.touches.length !== 1) { touch = null; if (dragging) { dragging = false; resetDrag(); } return; }
    var t = e.touches[0];
    touch = { x: t.clientX, y: t.clientY, t: Date.now() };
    dragging = false;
  }, { passive: true });

  document.addEventListener('touchmove', function (e) {
    if (pinch && e.touches.length === 2 && pinch.d > 0) { pinch.s = dist(e.touches) / pinch.d; return; }
    if (!touch || !cfg.paged) return;
    if (hasSel()) { touch = null; if (dragging) { dragging = false; resetDrag(); } return; }
    var t = e.touches[0], dx = t.clientX - touch.x, dy = t.clientY - touch.y;
    if (!dragging && Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.2) dragging = true;
    if (dragging && cfg.anim) {
      var b = document.body;
      var edge = (page === 0 && dx > 0) || (page === pages - 1 && dx < 0);
      b.style.transition = 'none';
      b.style.transform = 'translateX(' + (edge ? dx * 0.35 : dx) + 'px)';
    }
  }, { passive: true });

  document.addEventListener('touchend', function (e) {
    if (pinch) {
      if (e.touches.length === 0) {
        if (Math.abs(pinch.s - 1) > 0.08) B.pinch(Math.max(0.5, Math.min(2, pinch.s)));
        pinch = null;
      }
      touch = null;
      return;
    }
    if (!touch) return;
    var t = e.changedTouches[0], dx = t.clientX - touch.x, dy = t.clientY - touch.y, dt = Date.now() - touch.t;
    var tg = e.target;
    touch = null;
    if (hasSel()) { if (dragging) { dragging = false; resetDrag(); } return; }
    if (cfg.paged && dragging) {
      dragging = false;
      var edge = (page === 0 && dx > 0) || (page === pages - 1 && dx < 0);
      var cur = cfg.anim ? (edge ? dx * 0.35 : dx) : undefined;
      if (Math.abs(dx) > Math.min(90, W * 0.12) || (Math.abs(dx) > 30 && dt < 250)) {
        if (dx < 0) H.next(cur); else H.prev(cur);
      } else resetDrag();
      return;
    }
    if (Math.abs(dx) < 14 && Math.abs(dy) < 14 && dt < 450) tap(t.clientX, t.clientY, tg);
  }, { passive: true });

  function tap(x, y, tg) {
    var el = tg && tg.nodeType === 1 ? tg : (tg && tg.parentElement);
    if (el && el.closest && el.closest('a[href]')) return;
    var now = Date.now();
    if (now - lastTap < 300) {
      clearTimeout(tapTimer); tapTimer = null; lastTap = 0;
      B.toggleUi();
      return;
    }
    lastTap = now;
    tapTimer = setTimeout(function () {
      tapTimer = null;
      var mk = el && el.closest ? el.closest('mark.hl') : null;
      if (mk && mk.getAttribute('data-id') !== '__flash') { B.onHighlightTap(mk.getAttribute('data-id')); return; }
      if (el && el.tagName === 'IMG') { B.openImage(el.src); return; }
      if (el && el.classList && el.classList.contains('hearth-next')) { B.nextChapter(); return; }
      if (!cfg.tapZones) { B.singleTap(); return; }
      if (x < W * 0.25) H.prev(); else if (x > W * 0.75) H.next(); else B.singleTap();
    }, 270);
  }

  /* ---------- links & footnotes ---------- */
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    var hash = href.indexOf('#');
    var file = hash >= 0 ? href.substring(0, hash) : href;
    var id = hash >= 0 ? decodeURIComponent(href.substring(hash + 1)) : null;
    var here = location.pathname.split('/').pop();
    var same = !file || file.split('/').pop() === here;
    if (same && id) {
      e.preventDefault();
      var el = document.getElementById(id);
      if (!el) return;
      var box = el.closest('aside,li,p,div,section') || el;
      var tx = (box.textContent || '').replace(/\s+/g, ' ').trim();
      var kind = (a.getAttribute('epub:type') || '') + ' ' + (a.getAttribute('role') || '') + ' ' + a.className;
      var isNote = /noteref|footnote|endnote/i.test(kind) || box.tagName === 'ASIDE';
      if (tx && tx.length < 1200 && (isNote || tx.length < 400)) B.footnote(tx);
      else goAnchor(id);
    }
  }, true);

  function ensureNext() {
    if (document.querySelector('.hearth-next')) return;
    var d = document.createElement('div');
    d.className = 'hearth-next';
    document.body.appendChild(d);
  }
  function removeNext() { var d = document.querySelector('.hearth-next'); if (d) d.remove(); }

  /* ---------- scroll mode reporting ---------- */
  var sT = null;
  window.addEventListener('scroll', function () {
    if (cfg.paged || !ready) return;
    clearTimeout(sT);
    sT = setTimeout(function () { page = Math.floor(root().scrollTop / VH); report(); }, 140);
  }, { passive: true });

  /* ---------- auto-scroll (scroll mode) ---------- */
  var asId = null, asSpeed = 0, asLast = 0, asPos = 0;
  H.autoScroll = function (speed) {
    asSpeed = speed; asLast = 0;
    if (asId) cancelAnimationFrame(asId);
    asId = null;
    if (!(speed > 0) || cfg.paged) return;
    asPos = root().scrollTop;
    var step = function (t) {
      if (!asSpeed) return;
      var r = root();
      if (asLast) {
        if (Math.abs(r.scrollTop - asPos) > 3) asPos = r.scrollTop;
        asPos += asSpeed * (t - asLast) / 1000;
        r.scrollTop = asPos;
        if (r.scrollTop + VH >= r.scrollHeight - 2) { asSpeed = 0; B.autoScrollEnd(); return; }
      }
      asLast = t;
      asId = requestAnimationFrame(step);
    };
    asId = requestAnimationFrame(step);
  };

  /* ---------- lifecycle ---------- */
  function fontsReady(cb) {
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(cb, cb); else cb();
  }

  function target(t) {
    if (t.end) go(pages - 1);
    else if (t.find) H.find(t.find, t.k || 0);
    else if (typeof t.offset === 'number' && t.offset >= 0) goOffset(t.offset, t.flash || 0);
    else if (t.anchor) goAnchor(t.anchor);
    else if (typeof t.fraction === 'number') H.goFraction(t.fraction);
    else go(0);
  }

  H.init = function (o) {
    for (var k in (o.cfg || {})) cfg[k] = o.cfg[k];
    (o.hl || []).forEach(wrap);
    chText = null;
    if (!cfg.paged) ensureNext();
    fontsReady(function () {
      layout();
      requestAnimationFrame(function () {
        measure();
        target(o.target || {});
        ready = true;
        document.documentElement.classList.remove('hl-loading');
      });
    });
  };

  H.setStyle = function (css, c) {
    var keep = lastStart;
    var st = document.getElementById('hearth-style');
    if (st) st.textContent = css;
    for (var k in (c || {})) cfg[k] = c[k];
    if (cfg.paged) removeNext(); else ensureNext();
    chText = null;
    fontsReady(function () {
      layout();
      requestAnimationFrame(function () { measure(); goOffset(keep >= 0 ? keep : 0, 0); });
    });
  };

  var rT = null;
  window.addEventListener('resize', function () {
    if (!ready) return;
    var keep = lastStart;
    clearTimeout(rT);
    rT = setTimeout(function () { layout(); measure(); goOffset(keep >= 0 ? keep : 0, 0); }, 150);
  });
})();
