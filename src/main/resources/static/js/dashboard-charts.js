/*
  Dashboard charts.

  Every number here comes from /api/stats/timeseries — real jobs grouped by
  day, including the days with none, so a quiet week and a busy week do not
  draw the same line. Nothing is generated or padded client-side.

  Colours are read from CoreUI's live CSS variables rather than hardcoded, and
  redrawn on theme change: a canvas keeps whatever it was painted with, so a
  chart drawn in light mode stays light until it is told otherwise.
*/
(function () {
  'use strict';

  if (typeof Chart === 'undefined') return;

  var charts = {};
  var currentRange = 14;
  var lastData = null;

  function cssVar(name, fallback) {
    var v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return v || fallback;
  }

  function rgba(varName, alpha, fallback) {
    var triplet = cssVar(varName, fallback);
    return 'rgba(' + triplet + ', ' + alpha + ')';
  }

  /* ---- the small white line inside a coloured widget card ---- */
  function sparklineConfig(values, labels) {
    return {
      type: 'line',
      data: {
        labels: labels,
        datasets: [{
          data: values,
          borderColor: 'rgba(255,255,255,0.75)',
          backgroundColor: 'rgba(255,255,255,0.12)',
          borderWidth: 2,
          pointRadius: 0,
          pointHoverRadius: 4,
          pointHoverBackgroundColor: '#fff',
          fill: true,
          tension: 0.35
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            intersect: false,
            callbacks: {
              title: function (items) { return items[0].label; }
            }
          }
        },
        scales: {
          x: { display: false },
          // A sparkline that autoscales makes one scan look like a spike;
          // starting at zero keeps the shape honest.
          y: { display: false, beginAtZero: true }
        },
        elements: { line: { borderCapStyle: 'round' } }
      }
    };
  }

  function mainConfig(d) {
    var grid = cssVar('--cui-border-color', 'rgba(0,0,0,.1)');
    var tick = cssVar('--cui-secondary-color', '#6d7d9c');
    return {
      type: 'line',
      data: {
        labels: d.labels,
        datasets: [
          {
            label: 'Clean',
            data: d.ok,
            borderColor: rgba('--cui-success-rgb', 1, '27,158,62'),
            backgroundColor: rgba('--cui-success-rgb', 0.08, '27,158,62'),
            borderWidth: 2, pointRadius: 0, pointHoverRadius: 5, fill: true, tension: 0.3
          },
          {
            label: 'Threats',
            data: d.virus,
            borderColor: rgba('--cui-danger-rgb', 1, '229,83,83'),
            backgroundColor: rgba('--cui-danger-rgb', 0.12, '229,83,83'),
            borderWidth: 2, pointRadius: 0, pointHoverRadius: 5, fill: true, tension: 0.3
          },
          {
            label: 'Errors',
            data: d.error,
            borderColor: rgba('--cui-warning-rgb', 1, '249,177,21'),
            backgroundColor: 'transparent',
            borderWidth: 2, borderDash: [4, 3], pointRadius: 0, pointHoverRadius: 5, tension: 0.3
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { labels: { color: tick, usePointStyle: true, pointStyle: 'line', boxWidth: 24 } }
        },
        scales: {
          x: { grid: { color: grid }, ticks: { color: tick, maxRotation: 0, autoSkipPadding: 20 } },
          y: { grid: { color: grid }, ticks: { color: tick, precision: 0 }, beginAtZero: true }
        }
      }
    };
  }

  function destroyAll() {
    Object.keys(charts).forEach(function (k) { charts[k].destroy(); });
    charts = {};
  }

  function spark(id, values, labels) {
    var el = document.getElementById(id);
    if (!el) return;
    charts[id] = new Chart(el, sparklineConfig(values, labels));
  }

  /*
    Trend on the scans widget: the last 7 days against the 7 before them.
    Shown only when there is a previous week to compare with, because
    "+100%" against an empty week says nothing.
  */
  function renderDelta(d) {
    var el = document.getElementById('deltaScans');
    if (!el || !d.total || d.total.length < 14) return;
    var recent = d.total.slice(-7).reduce(function (a, b) { return a + b; }, 0);
    var prior = d.total.slice(-14, -7).reduce(function (a, b) { return a + b; }, 0);
    if (prior === 0) { el.textContent = ''; return; }
    var pct = Math.round(((recent - prior) / prior) * 100);
    var up = pct >= 0;
    el.innerHTML = '(' + (up ? '+' : '') + pct + '% <i class="fa-solid fa-arrow-' +
      (up ? 'up' : 'down') + '"></i>)';
    el.title = recent + ' scans in the last 7 days vs ' + prior + ' in the 7 before';
  }

  /*
    An empty range is a real answer, not a failure — but a chart of four flat
    lines at zero looks exactly like a chart that failed to load. Say which it
    is, and say when the last scan actually was, so the reader knows whether
    to widen the range or go looking for a broken agent.
  */
  function emptyState(d) {
    var host = document.getElementById('mainChart');
    if (!host || !host.parentElement) return;
    var when = '';
    if (d.lastScanAt) {
      var t = new Date(d.lastScanAt);
      var days = Math.floor((Date.now() - t.getTime()) / 86400000);
      when = 'Last tracked scan: ' + t.toLocaleDateString() +
             ' (' + (days === 0 ? 'today' : days + ' day' + (days === 1 ? '' : 's') + ' ago') + ').';
    } else {
      when = 'This console has not tracked any scan yet.';
    }
    host.parentElement.innerHTML =
      '<div class="d-flex flex-column align-items-center justify-content-center h-100 text-center py-5">' +
      '<i class="fa-solid fa-chart-line fa-2x text-body-secondary mb-3"></i>' +
      '<div class="fw-semibold">No scans in the last ' + currentRange + ' days</div>' +
      '<div class="small text-body-secondary mt-1">' + when + '</div>' +
      '<button type="button" class="btn btn-sm btn-outline-secondary mt-3" data-widen>Show 90 days</button>' +
      '</div>';
    var widen = host.parentElement.querySelector('[data-widen]');
    if (widen) {
      widen.addEventListener('click', function () {
        var btn = document.querySelector('[data-range="90"]');
        if (btn) btn.click();
      });
    }
  }

  function render(d) {
    lastData = d;
    destroyAll();
    spark('sparkEndpoints', d.endpoints, d.labels);
    spark('sparkScans', d.total, d.labels);
    spark('sparkErrors', d.error, d.labels);
    spark('sparkThreats', d.virus, d.labels);
    var anyScans = (d.total || []).some(function (n) { return n > 0; });
    if (!anyScans) {
      emptyState(d);
    } else {
      var main = document.getElementById('mainChart');
      if (main) charts.main = new Chart(main, mainConfig(d));
    }
    renderDelta(d);
  }

  function load(days) {
    // emptyState() replaces the canvas with a message; restore it so a wider
    // range can draw again.
    var wrap = document.querySelector('.chart-main');
    if (wrap && !document.getElementById('mainChart')) {
      wrap.innerHTML = '<canvas id="mainChart"></canvas>';
    }
    fetch('/api/stats/timeseries?days=' + days)
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(render)
      .catch(function (e) {
        var main = document.getElementById('mainChart');
        if (main && main.parentElement) {
          main.parentElement.innerHTML =
            '<div class="text-body-secondary small py-4 text-center">Chart data unavailable (' +
            String(e.message).replace(/</g, '&lt;') + ')</div>';
        }
      });
  }

  document.addEventListener('DOMContentLoaded', function () {
    load(currentRange);

    document.querySelectorAll('[data-range]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        document.querySelectorAll('[data-range]').forEach(function (b) { b.classList.remove('active'); });
        btn.classList.add('active');
        currentRange = parseInt(btn.getAttribute('data-range'), 10);
        load(currentRange);
      });
    });
  });

  // Repaint with the new palette when the theme changes.
  document.addEventListener('claimav:themechange', function () {
    if (lastData) render(lastData);
  });
})();
