/*
  The "Running scans" panel, shared by /scan and /jobs.

  Both pages carried their own copy of this poller, identical except for
  one behaviour: /jobs reloads when a job finishes so the filtered table
  below picks up the new verdict, while /scan just keeps polling. That
  difference is now the reloadOnFinish option, and there is one copy of
  the code instead of two that drift apart.

  Markup contract (see either page):
    #active-spinner  #active-count  #active-jobs-body  #active-last-updated
*/
(function (global) {
  'use strict';

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function fmtTime(ts) {
    if (!ts) return '';
    try { return new Date(ts).toLocaleTimeString(); } catch (e) { return ts; }
  }

  function activeJobs(options) {
    var opts = options || {};
    var reloadOnFinish = opts.reloadOnFinish === true;

    var spinner = document.getElementById('active-spinner');
    var countBadge = document.getElementById('active-count');
    var body = document.getElementById('active-jobs-body');
    var lastUpdated = document.getElementById('active-last-updated');
    if (!body) return;

    var seenIds = null;

    function renderRows(jobs) {
      return jobs.map(function (j) {
        var statusClass = 'badge-' + String(j.status || '').toLowerCase();
        return '<tr>' +
          '<td><a href="/jobs/' + encodeURIComponent(j.id) + '" class="mono">' + escapeHtml(j.idShort) + '</a></td>' +
          '<td class="mono" style="font-size:11px;">' + escapeHtml(j.type) + '</td>' +
          '<td><span class="badge-status ' + statusClass + '">' + escapeHtml(j.status) + '</span></td>' +
          '<td class="mono cell-truncate" style="max-width:320px;font-size:12px;">' + escapeHtml(j.target) + '</td>' +
          '<td>' + escapeHtml(j.endpointName || '—') + '</td>' +
          '<td class="mono muted" style="font-size:11px;">' + escapeHtml(fmtTime(j.submittedAt)) + '</td>' +
          '</tr>';
      }).join('');
    }

    function idle(message) {
      if (spinner) spinner.classList.remove('fa-spin');
      if (countBadge) countBadge.style.display = 'none';
      body.innerHTML = '<p class="muted mb-0">' + message + '</p>';
    }

    function poll() {
      fetch('/api/jobs/active')
        .then(function (r) { return r.json(); })
        .then(function (jobs) {
          if (lastUpdated) lastUpdated.textContent = 'updated ' + new Date().toLocaleTimeString();

          var ids = (jobs || []).map(function (j) { return j.id; });

          // A job that was active on the previous poll and no longer is has
          // just finished: reload so the table below shows its verdict.
          if (reloadOnFinish && seenIds &&
              seenIds.some(function (id) { return ids.indexOf(id) === -1; })) {
            location.reload();
            return;
          }
          seenIds = ids;

          if (!jobs || jobs.length === 0) {
            idle('No scans currently running or queued.');
            setTimeout(poll, 8000);
            return;
          }

          if (spinner) spinner.classList.add('fa-spin');
          if (countBadge) {
            countBadge.style.display = '';
            countBadge.textContent = jobs.length + ' active';
          }

          body.innerHTML =
            '<div class="table-responsive"><table class="table table-hover align-middle mb-0">' +
            '<thead><tr><th>ID</th><th>Type</th><th>Status</th><th>Target</th><th>Endpoint</th><th>Submitted</th></tr></thead>' +
            '<tbody>' + renderRows(jobs) + '</tbody></table></div>';

          setTimeout(poll, 3000);
        })
        .catch(function () {
          if (lastUpdated) lastUpdated.textContent = 'poll error';
          setTimeout(poll, 10000);
        });
    }

    poll();
  }

  global.ClaimAV = global.ClaimAV || {};
  global.ClaimAV.activeJobs = activeJobs;
})(window);
