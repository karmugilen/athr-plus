(function () {
  'use strict';

  let map = null;
  let pathLine = null;
  let startMark = null;
  let endMark = null;
  let pending = null;
  let drawn = null;
  let followResize = true;
  let applying = false;

  function finite(value) {
    return Number.isFinite(Number(value));
  }

  function validPairs(points) {
    const valid = [];
    if (!Array.isArray(points)) return valid;
    for (let i = 0; i < points.length; i += 1) {
      const pair = points[i];
      if (!Array.isArray(pair) || pair.length < 2 || !finite(pair[0]) || !finite(pair[1])) continue;
      valid.push([Number(pair[0]), Number(pair[1])]);
    }
    return valid;
  }

  function setMissing(show) {
    const node = document.getElementById('route-missing');
    if (!node) return;
    if (show) node.removeAttribute('hidden');
    else node.setAttribute('hidden', '');
  }

  function clearPath() {
    if (!map) return;
    [pathLine, startMark, endMark].forEach(function (layer) {
      if (layer) map.removeLayer(layer);
    });
    pathLine = null;
    startMark = null;
    endMark = null;
  }

  function endpoint(latlng) {
    return L.circleMarker(latlng, {
      radius: 5,
      color: '#00E676',
      weight: 2,
      fillColor: '#00E676',
      fillOpacity: 1,
      interactive: false
    });
  }

  function fitDrawn() {
    if (!map || !drawn || drawn.length < 2) return;
    const size = map.getSize();
    if (!size || size.x < 2 || size.y < 2) return;
    applying = true;
    map.fitBounds(L.latLngBounds(drawn), { padding: [24, 24], maxZoom: 17, animate: false });
    applying = false;
  }

  function render(points) {
    const valid = validPairs(points);
    drawn = valid;
    followResize = true;
    if (!map) return;
    clearPath();
    if (valid.length < 2) {
      setMissing(true);
      return;
    }
    setMissing(false);
    pathLine = L.polyline(valid, {
      color: '#00E676',
      weight: 4,
      lineCap: 'round',
      lineJoin: 'round',
      interactive: false
    }).addTo(map);
    startMark = endpoint(valid[0]).addTo(map);
    endMark = endpoint(valid[valid.length - 1]).addTo(map);
    map.invalidateSize({ animate: false });
    fitDrawn();
  }

  window.showRidePath = function (points) {
    pending = points;
    if (!map) return;
    render(points);
  };

  function initialize() {
    if (typeof L === 'undefined' || map) return;
    map = L.map('map', {
      zoomControl: true,
      attributionControl: true,
      zoomAnimation: false,
      fadeAnimation: false,
      markerZoomAnimation: false,
      minZoom: 3,
      maxZoom: 19
    }).setView([20, 0], 3);

    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      minZoom: 3,
      maxZoom: 19,
      maxNativeZoom: 19,
      attribution: '&copy; OpenStreetMap'
    }).addTo(map);

    map.on('dragstart zoomstart', function () {
      if (!applying) followResize = false;
    });

    const container = map.getContainer();
    if (typeof ResizeObserver === 'function') {
      new ResizeObserver(function () {
        if (!map || !followResize) return;
        map.invalidateSize({ animate: false });
        fitDrawn();
      }).observe(container);
    }

    if (pending) render(pending);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
  else initialize();
})();
