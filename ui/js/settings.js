(function () {
    'use strict';

    var apps = [];

    function csrfToken() {
        if (window.PluginHelper && typeof PluginHelper.getCsrfToken === 'function') {
            return PluginHelper.getCsrfToken() || '';
        }
        if (window.SailPoint && SailPoint.XSRF_TOKEN) {
            return SailPoint.XSRF_TOKEN;
        }
        return '';
    }

    function restUrl(path) {
        if (window.PluginHelper && typeof PluginHelper.getPluginRestUrl === 'function') {
            return PluginHelper.getPluginRestUrl(path);
        }
        var contextPath = (window.SailPoint && SailPoint.CONTEXT_PATH)
            ? SailPoint.CONTEXT_PATH
            : '/identityiq';
        return contextPath + '/plugin/rest/' + path;
    }

    function setStatus(message, kind) {
        var el = document.getElementById('settingsStatus');
        el.textContent = message || '';
        el.className = 'chd-settings-status' + (kind ? ' ' + kind : '');
    }

    function updateCount() {
        var selected = apps.filter(function (app) { return app.selected; }).length;
        document.getElementById('selectionCount').textContent =
            selected + ' of ' + apps.length + ' selected';
    }

    function renderList() {
        var list = document.getElementById('appList');
        if (!apps.length) {
            list.textContent = 'No applications found in IIQ.';
            updateCount();
            return;
        }

        list.innerHTML = '';
        apps.forEach(function (app, index) {
            var row = document.createElement('label');
            row.className = 'chd-app-row';
            row.setAttribute('data-index', String(index));

            var checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.checked = !!app.selected;
            checkbox.addEventListener('change', function () {
                app.selected = checkbox.checked;
                updateCount();
            });

            var meta = document.createElement('div');
            meta.className = 'chd-app-meta';
            meta.innerHTML = '<span class="chd-app-name"></span><span class="chd-app-type"></span>';
            meta.querySelector('.chd-app-name').textContent = app.name || '';
            meta.querySelector('.chd-app-type').textContent = app.type || 'Application';

            row.appendChild(checkbox);
            row.appendChild(meta);
            list.appendChild(row);
        });
        updateCount();
        applyFilter();
    }

    function applyFilter() {
        var query = (document.getElementById('appFilter').value || '').toLowerCase().trim();
        var rows = document.querySelectorAll('.chd-app-row');
        rows.forEach(function (row) {
            var index = parseInt(row.getAttribute('data-index'), 10);
            var app = apps[index];
            var haystack = ((app.name || '') + ' ' + (app.type || '')).toLowerCase();
            if (!query || haystack.indexOf(query) >= 0) {
                row.classList.remove('chd-hidden');
            } else {
                row.classList.add('chd-hidden');
            }
        });
    }

    function loadSettings() {
        setStatus('Loading...', '');
        fetch(restUrl('connectionHealth/settings'), {
            method: 'GET',
            credentials: 'same-origin',
            headers: {
                'X-XSRF-TOKEN': csrfToken(),
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
                apps = Array.isArray(data.applications) ? data.applications : [];
                document.getElementById('cacheTtlMinutes').value =
                    data.cacheTtlMinutes > 0 ? data.cacheTtlMinutes : 5;
                renderList();
                setStatus('', '');
            })
            .catch(function (err) {
                document.getElementById('appList').textContent =
                    'Failed to load applications: ' + (err.message || err);
                setStatus('Load failed', 'error');
            });
    }

    function saveSettings() {
        var selectedNames = apps
            .filter(function (app) { return app.selected; })
            .map(function (app) { return app.name; });
        var ttl = parseInt(document.getElementById('cacheTtlMinutes').value, 10);
        if (!ttl || ttl < 1) {
            setStatus('Cache TTL must be at least 1 minute.', 'error');
            return;
        }

        setStatus('Saving...', '');
        document.getElementById('saveSettingsBtn').disabled = true;

        fetch(restUrl('connectionHealth/settings'), {
            method: 'PUT',
            credentials: 'same-origin',
            headers: {
                'X-XSRF-TOKEN': csrfToken(),
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                applications: selectedNames,
                cacheTtlMinutes: ttl
            })
        })
            .then(function (response) {
                if (!response.ok) {
                    return response.json().then(function (body) {
                        throw new Error((body && body.error) || ('HTTP ' + response.status));
                    }).catch(function (err) {
                        if (err.message) {
                            throw err;
                        }
                        throw new Error('HTTP ' + response.status);
                    });
                }
                return response.json();
            })
            .then(function () {
                setStatus('Saved. Open the dashboard and click Refresh.', 'ok');
            })
            .catch(function (err) {
                setStatus('Save failed: ' + (err.message || err), 'error');
            })
            .finally(function () {
                document.getElementById('saveSettingsBtn').disabled = false;
            });
    }

    document.getElementById('appFilter').addEventListener('input', applyFilter);
    document.getElementById('selectAllBtn').addEventListener('click', function () {
        apps.forEach(function (app) { app.selected = true; });
        renderList();
    });
    document.getElementById('clearAllBtn').addEventListener('click', function () {
        apps.forEach(function (app) { app.selected = false; });
        renderList();
    });
    document.getElementById('saveSettingsBtn').addEventListener('click', saveSettings);

    loadSettings();
})();
