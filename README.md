# Connection Health Dashboard — SailPoint IIQ Plugin

A custom IIQ plugin that displays a health dashboard for application connections
(Active Directory, HR systems, and others).

## What It Does

- Shows **GREEN / YELLOW / RED** status for each monitored application
- Displays last aggregation timestamp, account counts, and errors
- Caches results via a scheduled task (and refreshes cache from the REST API)
- Adds a **Connection Health** link for users with the view right

## Project Structure

```
ConnectionHealthDashboard/
├── pom.xml
├── manifest.xml                     # IIQ Plugin object (required)
├── README.md
├── src/
│   ├── main/java/com/example/plugin/health/
│   │   ├── ConnectionHealthChecker.java    # Shared health logic
│   │   ├── ConnectionHealthResource.java   # REST endpoint
│   │   └── ConnectionHealthTask.java       # Cache task executor
│   └── assembly/
│       └── plugin-assembly.xml      # Maven ZIP packaging
├── ui/
│   ├── page.xhtml                   # Full-page UI (required name)
│   ├── css/dashboard.css
│   └── js/
│       ├── dashboard.js
│       └── snippets/nav.js
├── import/install/
│   ├── ConnectionHealthCache.xml
│   ├── ConnectionHealthCapability.xml
│   ├── ConnectionHealthSPRight.xml
│   └── ConnectionHealthTaskDef.xml
└── beanshell/
    └── ConnectionHealthCheck.bsh
```

## Prerequisites

- SailPoint IdentityIQ 8.0+
- Java 11+
- Maven 3.6+
- The IIQ `identityiq.jar` installed in your local Maven repository

## Install IIQ JAR to Local Maven Repo

```bash
mvn install:install-file \
  -Dfile=/path/to/identityiq.jar \
  -DgroupId=sailpoint \
  -DartifactId=identityiq \
  -Dversion=8.5 \
  -Dpackaging=jar
```

## Build

```bash
cd ConnectionHealthDashboard
mvn clean package
```

Plugin ZIP: `target/ConnectionHealthDashboard-1.0.zip`

ZIP contents (IIQ format):

- `manifest.xml`
- `lib/connection-health-dashboard-1.0.jar`
- `ui/`
- `import/install/`

## Deploy to IIQ

1. Log in as **spadmin**
2. Go to **Gear → Plugins**
3. Click **New** → upload `ConnectionHealthDashboard-1.0.zip`
4. Grant the **ConnectionHealthViewer** capability (or the **ConnectionHealthViewRight** SPRight) to users
5. Open **Gear → Connection Health**, or go to:
   `/identityiq/plugins/pluginPage.jsf?pn=ConnectionHealthDashboard`

## Configuration

### Plugin settings (Gear → Plugins → Configure)

| Setting | Description |
|---------|-------------|
| Monitored Applications | Comma-separated IIQ Application names (exact names). Required — there is no demo default list. |
| Cache TTL (minutes) | Serve cached results newer than this (default 5). Cache is skipped when the app list changes. |

### Cache task

Task definition: **Connection Health Cache Task**  
Schedule: **Connection Health Cache Schedule** (every 30 minutes)

Leave the task `applications` argument blank to use the plugin setting. A non-empty task argument overrides the plugin setting.

After changing Monitored Applications, click **Refresh** on the dashboard (or wait for TTL). If an old TaskDefinition still has hardcoded apps, clear that argument or re-import the task XML.

## Status Color Logic

| Color  | Condition |
|--------|-----------|
| GREEN  | Aggregation succeeded within 24 hours, no errors |
| YELLOW | Aggregation succeeded but &gt;24h ago, or completed with warnings |
| RED    | Aggregation failed/terminated, &gt;48h stale, application not found, or no aggregation TaskResult |

### Important

This plugin **does not run aggregations or create AD accounts**. It reports health from:

1. Account Aggregation **TaskResult**s when present
2. Otherwise the Application’s `acctAggregationEnd` / scorecard link count

If a card still says never aggregated after you ran aggregations:

1. Confirm Task Results under **Setup → Tasks → Task Results**
2. Run `beanshell/ConnectionHealthCheck.bsh` in **Debug → BeanShell Console**
3. Click **Refresh** on the dashboard (cache TTL may delay updates up to a few minutes)

## REST API

```
GET /identityiq/plugin/rest/connectionHealth/status
```

Requires `ConnectionHealthViewRight`. Returns a JSON array of health objects:

```json
[
  {
    "appName": "Active Directory - Corp",
    "appType": "Active Directory - Direct",
    "status": "GREEN",
    "lastAggregation": 1710000000000,
    "accountsTotal": 12000,
    "accountsCreated": 3,
    "accountsUpdated": 40,
    "accountsDeleted": 1
  }
]
```

## BeanShell Alternative

Use `beanshell/ConnectionHealthCheck.bsh` in **Debug → BeanShell Console**
to validate TaskResult queries before relying on the plugin.

## Troubleshooting

- Check `$IIQ_HOME/logs/iiq.log` for plugin classloader / REST errors
- Confirm the plugin is **Active** under Gear → Plugins
- Confirm `ConnectionHealthCache` exists under Debug → Objects → Custom
- Confirm TaskResults for aggregations use `targetName` = application name and `type` = `AccountAggregation`
- Run the cache task once manually after install if the schedule has not fired yet
- If the UI shows **Failed to load: Failed to fetch**, open DevTools → Network on the `status` call and confirm `X-XSRF-TOKEN` is set (IIQ exposes `SailPoint.XSRF_TOKEN` / `PluginHelper.getCsrfToken()`, not `SailPoint.csrfToken`)
