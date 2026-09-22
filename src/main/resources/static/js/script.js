/*
  Console-wide behaviour: theme, sidebar state, page filter.

  The theme follows CoreUI's own convention (data-coreui-theme on <html>)
  rather than a home-grown one, so every CoreUI component switches with it.
  The initial value is applied by an inline script in the <head> — before the
  first paint — and this file only handles changes made after load.
*/
(function () {
  'use strict';

  var STORE_THEME = 'coreui-theme';
  var STORE_SIDEBAR = 'sidebar-unfoldable';

  function storedTheme() {
    try { return localStorage.getItem(STORE_THEME); } catch (e) { return null; }
  }

  function preferredTheme() {
    return storedTheme() || 'auto';
  }

  function effective(theme) {
    if (theme !== 'auto') return theme;
    return (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-coreui-theme', effective(theme));
    document.documentElement.dataset.themePreference = theme;
    var icon = document.getElementById('themeIcon');
    if (icon) {
      icon.className = theme === 'light' ? 'fa-solid fa-sun'
                     : theme === 'dark'  ? 'fa-solid fa-moon'
                     : 'fa-solid fa-circle-half-stroke';
    }
    document.querySelectorAll('[data-coreui-theme-value]').forEach(function (btn) {
      btn.classList.toggle('active', btn.getAttribute('data-coreui-theme-value') === theme);
    });
    // Charts read their colours from the CSS variables, so they have to be
    // told: a canvas keeps whatever it was painted with.
    document.dispatchEvent(new CustomEvent('clamav-dashboard:themechange'));
  }

  function initTheme() {
    applyTheme(preferredTheme());

    document.querySelectorAll('[data-coreui-theme-value]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var value = btn.getAttribute('data-coreui-theme-value');
        try { localStorage.setItem(STORE_THEME, value); } catch (e) {}
        applyTheme(value);
      });
    });

    if (window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
        if (preferredTheme() === 'auto') applyTheme('auto');
      });
    }
  }

  /*
    CoreUI collapses the sidebar for us but does not remember the choice,
    so it springs back open on every navigation. Persist it, and apply it
    before the sidebar is visible rather than after.
  */
  function initSidebar() {
    var sidebar = document.getElementById('sidebar');
    if (!sidebar) return;

    try {
      if (localStorage.getItem(STORE_SIDEBAR) === '1') {
        sidebar.classList.add('sidebar-narrow-unfoldable');
      }
    } catch (e) {}

    var observer = new MutationObserver(function () {
      var narrow = sidebar.classList.contains('sidebar-narrow-unfoldable');
      try { localStorage.setItem(STORE_SIDEBAR, narrow ? '1' : '0'); } catch (e) {}
    });
    observer.observe(sidebar, { attributes: true, attributeFilter: ['class'] });
  }

  /*
    The header search filters the rows of whatever table this page shows.
    It is deliberately client-side: it narrows what is already on screen and
    needs no endpoint, and it must never imply it searched the whole history.
  */
  function initSearch() {
    var input = document.getElementById('tableSearch');
    if (!input) return;

    var tables = document.querySelectorAll('table[data-filterable]');
    if (!tables.length) {
      var box = input.closest('.header-search');
      if (box) box.style.display = 'none';
      return;
    }

    function apply() {
      var q = input.value.trim().toLowerCase();
      tables.forEach(function (table) {
        var shown = 0, total = 0;
        table.querySelectorAll('tbody tr').forEach(function (tr) {
          if (tr.classList.contains('alert-row-detail')) return;
          total++;
          var hit = !q || tr.textContent.toLowerCase().indexOf(q) !== -1;
          tr.style.display = hit ? '' : 'none';
          if (hit) shown++;
          // keep an expanded detail row with its parent
          var next = tr.nextElementSibling;
          if (next && next.classList.contains('alert-row-detail')) {
            next.style.display = hit ? '' : 'none';
          }
        });
        var note = table.parentElement.querySelector('[data-filter-count]');
        if (note) {
          note.textContent = q ? (shown + ' of ' + total + ' rows') : '';
        }
      });
    }

    input.addEventListener('input', apply);
    // ⌘/ and Ctrl+/ focus the filter, the shortcut CoreUI's own search uses
    document.addEventListener('keydown', function (e) {
      if ((e.metaKey || e.ctrlKey) && e.key === '/') { e.preventDefault(); input.focus(); }
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    initTheme();
    initSidebar();
    initSearch();
  });
})();
