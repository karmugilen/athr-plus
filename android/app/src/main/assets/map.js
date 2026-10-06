(function () {
  'use strict';

  let map, tiles, scooter, scooterAccuracy, phone, phoneAccuracy, guidance;
  let pendingScooter, pendingPhone;
  let heading = null, headingFrozen = false;
  let headingMode = 'heading', followTarget = 'phone';
  let interacting = false, browsing = false, firstCenter = true;
  let resumeTimer = null;
  let headingFrame = null, lastHeadingTime = null;
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const shortestAngle = (from, to) => ((to - from + 540) % 360) - 180;
  const pointers = new Set();
  const failedTiles = new Map();
  const retryTimers = new Set();
  const normalize = value => (value % 360 + 360) % 360;
  const finite = value => value !== null && value !== undefined && Number.isFinite(Number(value));
  const validPosition = (lat, lng) => finite(lat) && finite(lng) &&
    Math.abs(Number(lat)) <= 90 && Math.abs(Number(lng)) <= 180;

  function updateOrientation() {
    if (!map) return;
    const bearing = map.getBearing();
    document.getElementById('north-ring').style.transform = `rotate(${bearing}deg)`;
    const arrow = document.getElementById('phone-marker-icon');
    if (arrow) {
      arrow.style.transform = `rotate(${heading === null ? 0 : normalize(heading + bearing)}deg)`;
      arrow.querySelector('.phone-arrow').hidden = heading === null;
      arrow.classList.toggle('heading-unavailable', heading === null);
    }
    document.getElementById('camera-status').textContent = browsing ? 'Browsing · tap Follow' :
      (followTarget === 'scooter' ? 'Following scooter' :
        (headingMode === 'north' ? 'North up' : (heading === null ? 'North up · compass unavailable' : 'Heading up')));
    document.getElementById('empty-status').hidden = !!(phone || scooter);
  }

  function stopHeadingAnimation() {
    if (headingFrame !== null) cancelAnimationFrame(headingFrame);
    headingFrame = null;
    lastHeadingTime = null;
  }

  function animateHeading(time) {
    headingFrame = null;
    if (!map || interacting || headingFrozen || document.hidden) {
      lastHeadingTime = null;
      return;
    }
    const target = headingMode === 'north' || heading === null ? 0 : normalize(-heading);
    const current = normalize(map.getBearing());
    const delta = shortestAngle(current, target);
    // Time-based easing gives the same response at 60, 90, 120 and 144 Hz.
    const elapsed = lastHeadingTime === null ? 1000 / 60 : Math.min(time - lastHeadingTime, 50);
    lastHeadingTime = time;
    // A single short smoothing stage follows the fused sensor without an angular cutoff.
    const finished = reducedMotion.matches || Math.abs(delta) < 0.02;
    map.setBearing(finished ? target : normalize(current + delta * (1 - Math.exp(-elapsed / 20))));
    if (!finished) headingFrame = requestAnimationFrame(animateHeading);
    else lastHeadingTime = null;
  }

  function updateHeading() {
    if (!map || interacting || headingFrozen || document.hidden) return;
    if (typeof map.stopHeadingUp === 'function') map.stopHeadingUp();
    if (headingFrame === null) headingFrame = requestAnimationFrame(animateHeading);
  }

  function pauseCamera() {
    interacting = true;
    stopHeadingAnimation();
    clearTimeout(resumeTimer);
  }

  function browse() {
    browsing = true;
    followTarget = null;
    pauseCamera();
    updateOrientation();
  }

  function settleCamera() {
    clearTimeout(resumeTimer);
    resumeTimer = setTimeout(function () {
      if (pointers.size > 0) return;
      interacting = false;
      updateHeading();
    }, 250);
  }

  function showTileStatus() {
    const status = document.getElementById('tile-status');
    status.hidden = failedTiles.size === 0;
  }

  function retryTile(tile) {
    if (!tile.isConnected || !failedTiles.has(tile)) return;
    const failure = failedTiles.get(tile);
    tile.src = failure.url;
  }

  window.retryMapTiles = function () {
    failedTiles.forEach((failure, tile) => {
      failure.attempts = 0;
      retryTile(tile);
    });
    if (map) map.invalidateSize({ animate: false, pan: false });
  };

  window.setMapTheme = function (name) {
    const theme = ['day', 'night', 'neon'].includes(name) ? name : 'night';
    document.body.dataset.theme = theme;
  };

  window.setMapMode = function (mode) {
    headingMode = mode === 'north' ? 'north' : 'heading';
    browsing = false;
    if (pointers.size === 0) interacting = false;
    updateOrientation();
    updateHeading();
  };

  window.setMapHeading = function (value) {
    heading = finite(value) ? normalize(Number(value)) : null;
    updateOrientation();
    updateHeading();
  };

  window.setHeadingFrozen = function (frozen) {
    headingFrozen = !!frozen;
    if (headingFrozen) stopHeadingAnimation();
    if (!headingFrozen) updateHeading();
  };

  function accuracyCircle(existing, position, accuracy, color) {
    if (!finite(accuracy) || Number(accuracy) <= 0) {
      if (existing) map.removeLayer(existing);
      return null;
    }
    if (!existing) return L.circle(position, {
      radius: Number(accuracy), color, weight: 1, fillColor: color,
      fillOpacity: 0.1, interactive: false
    }).addTo(map);
    existing.setLatLng(position).setRadius(Number(accuracy));
    return existing;
  }

  function updateGuidance() {
    if (!phone || !scooter) return;
    const positions = [phone.getLatLng(), scooter.getLatLng()];
    if (!guidance) {
      guidance = L.polyline(positions, {
        color: '#00B0FF', weight: 2, dashArray: '6, 6', opacity: 0.8, interactive: false
      }).addTo(map);
    } else guidance.setLatLngs(positions);
  }

  function followPosition(marker) {
    if (!marker || browsing || interacting) return;
    map.setView(marker.getLatLng(), firstCenter ? 16 : map.getZoom(), { animate: false });
    firstCenter = false;
    if (pointers.size === 0) interacting = false;
  }

  window.updateAtherMarker = function (lat, lng, accuracy) {
    if (!validPosition(lat, lng)) return;
    if (!map) { pendingScooter = [lat, lng, accuracy]; return; }
    const position = [Number(lat), Number(lng)];
    if (!scooter) {
      const icon = L.divIcon({
        className: '', iconSize: [32, 32], iconAnchor: [16, 16],
        html: '<div class="ather-marker-container"><div class="ather-marker-pulse"></div><div class="ather-marker-core"></div></div>'
      });
      scooter = L.marker(position, { icon, zIndexOffset: 1000, interactive: false }).addTo(map);
    } else scooter.setLatLng(position);
    scooterAccuracy = accuracyCircle(scooterAccuracy, position, accuracy, '#00E676');
    updateGuidance();
    if (followTarget === 'scooter' || (!phone && followTarget)) followPosition(scooter);
    updateOrientation();
  };

  window.updatePhoneMarker = function (lat, lng, accuracy) {
    if (!validPosition(lat, lng)) return;
    if (!map) { pendingPhone = [lat, lng, accuracy]; return; }
    const position = [Number(lat), Number(lng)];
    if (!phone) {
      const icon = L.divIcon({
        className: '', iconSize: [36, 36], iconAnchor: [18, 18],
        html: '<div id="phone-marker-icon" class="phone-marker-container"><div class="phone-marker-pulse"></div><div class="phone-arrow"></div></div>'
      });
      phone = L.marker(position, { icon, zIndexOffset: 1100, interactive: false }).addTo(map);
    } else phone.setLatLng(position);
    phoneAccuracy = accuracyCircle(phoneAccuracy, position, accuracy, '#00B0FF');
    updateGuidance();
    updateOrientation();
    if (followTarget === 'phone') followPosition(phone);
  };

  window.removeAtherMarker = function () {
    pendingScooter = null;
    [scooter, scooterAccuracy, guidance].forEach(layer => { if (map && layer) map.removeLayer(layer); });
    scooter = scooterAccuracy = guidance = null;
    updateOrientation();
  };
  window.removePhoneMarker = function () {
    pendingPhone = null;
    [phone, phoneAccuracy, guidance].forEach(layer => { if (map && layer) map.removeLayer(layer); });
    phone = phoneAccuracy = guidance = null;
    updateOrientation();
  };

  window.followMe = function () {
    if (!map) return;
    browsing = false;
    interacting = false;
    followTarget = 'phone';
    map.stop();
    followPosition(phone || scooter);
    updateOrientation();
    updateHeading();
  };

  window.followScooter = function () {
    if (!map || !scooter) return;
    browsing = false;
    interacting = false;
    followTarget = 'scooter';
    map.stop();
    followPosition(scooter);
    updateOrientation();
    updateHeading();
  };

  window.fitBoth = function () {
    if (!map) return;
    browse();
    map.stop();
    if (phone && scooter) {
      map.fitBounds(L.latLngBounds([phone.getLatLng(), scooter.getLatLng()]), {
        padding: [60, 60], maxZoom: 17, animate: false
      });
    } else if (phone || scooter) map.setView((phone || scooter).getLatLng(), 16, { animate: false });
    settleCamera();
  };

  function zoomBy(amount) {
    if (!map) return;
    pauseCamera();
    map.stop();
    map.setZoom(Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), map.getZoom() + amount)), { animate: false });
    settleCamera();
  }
  window.zoomIn = function () { zoomBy(1); };
  window.zoomOut = function () { zoomBy(-1); };

  function initialize() {
    if (typeof L === 'undefined') return;
    map = L.map('map', {
      zoomControl: false, attributionControl: false,
      zoomAnimation: false, fadeAnimation: false, markerZoomAnimation: false,
      touchZoom: true, dragging: true, inertia: true,
      doubleClickZoom: false, scrollWheelZoom: false, boxZoom: false, keyboard: false,
      zoomSnap: 1, zoomDelta: 1, minZoom: 3, maxZoom: 19, bounceAtZoomLimits: false,
      rotate: true, bearing: 0, dragRotate: false, touchRotate: false,
      shiftKeyRotate: false, rotateControl: false
    }).setView([20, 0], 3);

    // The bundled rotation plugin's touch handler preserves the geographic point
    // between two fingers. Leaflet's unrotated pinch handler cannot do that here.
    if (map.touchGestures) {
      map.touchZoom.disable();
      map.touchGestures.enable();
    }
    tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      minZoom: 3, maxZoom: 19, maxNativeZoom: 19,
      keepBuffer: 3, updateWhenIdle: true, updateWhenZooming: false
    });
    tiles.on('tileerror', function (event) {
      const previous = failedTiles.get(event.tile);
      const failure = previous || { url: event.tile.src, attempts: 0 };
      failedTiles.set(event.tile, failure);
      showTileStatus();
      // Retry only failed, visible tiles. Bound retries and respect the HTTP cache.
      if (failure.attempts < 2) {
        failure.attempts += 1;
        const timer = setTimeout(function () {
          retryTimers.delete(timer);
          retryTile(event.tile);
        }, failure.attempts * 1500);
        retryTimers.add(timer);
      }
    });
    tiles.on('tileload tileunload', function (event) {
      failedTiles.delete(event.tile);
      showTileStatus();
    });
    tiles.addTo(map);

    const container = map.getContainer();
    // Capture before Leaflet starts transforming the map for a gesture.
    container.addEventListener('pointerdown', function (event) {
      pointers.add(event.pointerId);
      browse();
      map.stop();
    }, { capture: true, passive: true });
    ['pointerup', 'pointercancel'].forEach(name => window.addEventListener(name, function (event) {
      pointers.delete(event.pointerId);
      if (!pointers.size) settleCamera();
    }, { passive: true }));
    map.on('dragstart zoomstart', pauseCamera);
    map.on('dragend zoomend', settleCamera);
    map.on('rotate', updateOrientation);
    window.addEventListener('blur', function () { pointers.clear(); settleCamera(); });
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) stopHeadingAnimation();
      else updateHeading();
    });
    window.addEventListener('pagehide', stopHeadingAnimation);
    window.addEventListener('online', window.retryMapTiles);
    new ResizeObserver(() => map.invalidateSize({ animate: false, pan: false })).observe(container);
    document.getElementById('tile-status').addEventListener('click', window.retryMapTiles);
    document.getElementById('camera-status').addEventListener('click', window.followMe);
    if (pendingScooter) window.updateAtherMarker(...pendingScooter);
    if (pendingPhone) window.updatePhoneMarker(...pendingPhone);
    updateHeading();
    updateOrientation();
  }
  document.addEventListener('DOMContentLoaded', initialize);
})();
