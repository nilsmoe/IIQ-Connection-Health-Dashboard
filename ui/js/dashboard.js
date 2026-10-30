(function () {
    'use strict';

    var refreshInterval = null;
    var statusLabel = {
        green: 'Healthy',
        yellow: 'Warning',
        red: 'Critical'
    };

    function escapeHtml(value) {
        if (value == null) {
            return '';
        }
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function timeAgo(timestamp) {
        if (!timestamp) {
            return 'Never';
        }
        var diff = Date.now() - timestamp;
        var mins = Math.floor(diff / 60000);
        if (mins < 1) {
            return 'Just now';
        }
        if (mins < 60) {
            return mins + ' minute' + (mins > 1 ? 's' : '') + ' ago';
        }
        var hrs = Math.floor(mins / 60);
        if (hrs < 24) {
            return hrs + ' hour' + (hrs > 1 ? 's' : '') + ' ago';
        }
        var days = Math.floor(hrs / 24);
        return days + ' day' + (days > 1 ? 's' : '') + ' ago';
    }

    function renderCard(app) {
        var status = (app.status || 'RED').toLowerCase();
        var html = '<div class="chd-health-card ' + escapeHtml(status) + '">';
        html += '<div class="chd-card-top">';
        html += '<div><div class="chd-app-name">' + escapeHtml(app.appName) + '</div>';
        html += '<div class="chd-app-type">' + escapeHtml(app.appType || '') + '</div></div>';
        html += '<span class="chd-status-badge ' + escapeHtml(status) + '">';
        html += '<span class="chd-status-dot"></span> ';
        html += escapeHtml(statusLabel[status] || status);
        html += '</span></div>';

        html += '<div class="chd-stat-row"><span class="chd-stat-label">Last Aggregation</span>';
        html += '<span class="chd-stat-value">' + escapeHtml(timeAgo(app.lastAggregation)) + '</span></div>';

        html += '<div class="chd-stat-row"><span class="chd-stat-label">Total Accounts</span>';
        html += '<span class="chd-stat-value">'
            + escapeHtml((app.accountsTotal || 0).toLocaleString())
            + '</span></div>';

        html += '<div class="chd-stat-row"><span class="chd-stat-label">Created / Updated / Deleted</span>';
        html += '<span class="chd-stat-value">'
            + escapeHtml(app.accountsCreated || 0) + ' / '
            + escapeHtml(app.accountsUpdated || 0) + ' / '
            + escapeHtml(app.accountsDeleted || 0)
            + '</span></div>';

        if (app.error) {
            html += '<div class="chd-error-box">' + escapeHtml(app.error) + '</div>';
        }
        if (app.warning) {
            html += '<div class="chd-warning-box">' + escapeHtml(app.warning) + '</div>';
        }

        html += '</div>';
        return html;
    }

    function showError(message) {
        var loading = document.getElementById('loading');
        loading.style.display = 'block';
        loading.innerHTML = '<p style="color:#e74c3c">' + escapeHtml(message) + '</p>';
        document.getElementById('dashboard').style.display = 'none';
    }

    function loadHealth() {
        var loading = document.getElementById('loading');
        var dashboard = document.getElementById('dashboard');
        loading.style.display = 'block';
        loading.textContent = 'Loading health data...';
        dashboard.style.display = 'none';

        var csrfToken = '';
        if (window.PluginHelper && typeof PluginHelper.getCsrfToken === 'function') {
            csrfToken = PluginHelper.getCsrfToken() || '';
        } else if (window.SailPoint && SailPoint.XSRF_TOKEN) {
            csrfToken = SailPoint.XSRF_TOKEN;
        }

        var url;
        if (window.PluginHelper && typeof PluginHelper.getPluginRestUrl === 'function') {
            url = PluginHelper.getPluginRestUrl('connectionHealth/status');
        } else {
            var contextPath = (window.SailPoint && SailPoint.CONTEXT_PATH)
                ? SailPoint.CONTEXT_PATH
                : '/identityiq';
            url = contextPath + '/plugin/rest/connectionHealth/status';
        }

        fetch(url, {
            method: 'GET',
            credentials: 'same-origin',
            headers: {
                'X-XSRF-TOKEN': csrfToken,
                'Accept': 'application/json'
            }
        })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                return response.json();
            })
            .then(function (data) {
                if (!Array.isArray(data)) {
                    throw new Error(data && data.error
                        ? data.error
                        : 'Unexpected response from health endpoint');
                }
                if (data.length === 0) {
                    showError(
                        'No applications selected. Choose apps under '
                        + 'Gear → Plugins → Connection Health Dashboard → Configure.'
                    );
                    return;
                }
                dashboard.innerHTML = '';
                data.forEach(function (app) {
                    dashboard.insertAdjacentHTML('beforeend', renderCard(app));
                });
                loading.style.display = 'none';
                dashboard.style.display = 'grid';
            })
            .catch(function (err) {
                showError('Failed to load: ' + (err.message || err));
            });
    }

    document.getElementById('refreshBtn').addEventListener('click', loadHealth);

    document.getElementById('autoRefresh')
        .addEventListener('change', function () {
            if (this.checked) {
                refreshInterval = setInterval(loadHealth, 300000);
            } else {
                clearInterval(refreshInterval);
                refreshInterval = null;
            }
        });

    loadHealth();
})();
