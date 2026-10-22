package com.example.plugin.health;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import sailpoint.api.SailPointContext;
import sailpoint.object.Attributes;
import sailpoint.object.Custom;
import sailpoint.object.TaskResult;
import sailpoint.object.TaskSchedule;
import sailpoint.plugin.PluginBaseHelper;
import sailpoint.task.AbstractTaskExecutor;
import sailpoint.tools.Util;

/**
 * Task executor that caches connection health data into a Custom object
 * so the REST endpoint can serve it quickly.
 *
 * Application list resolution order:
 * 1. Task argument {@code applications} (if non-empty)
 * 2. Plugin setting {@code applications}
 */
public class ConnectionHealthTask extends AbstractTaskExecutor {

    private boolean terminated = false;

    @Override
    public void execute(SailPointContext context,
                        TaskSchedule schedule,
                        TaskResult result,
                        Attributes<String, Object> args)
            throws Exception {

        String appList = resolveApplicationsCsv(args);
        List<String> appNames = ConnectionHealthChecker.parseApplicationNames(appList);
        String appsKey = ConnectionHealthChecker.canonicalApplications(appNames);
        List<Map<String, Object>> healthList = new ArrayList<>();

        for (String appName : appNames) {
            if (terminated) {
                break;
            }
            healthList.add(ConnectionHealthChecker.checkApplication(context, appName));
        }

        Custom cache = context.getObjectByName(
                Custom.class, ConnectionHealthResource.CACHE_NAME);
        if (cache == null) {
            cache = new Custom();
            cache.setName(ConnectionHealthResource.CACHE_NAME);
        }
        cache.put(ConnectionHealthResource.CACHE_ATTR_HEALTH, healthList);
        cache.put(ConnectionHealthResource.CACHE_ATTR_UPDATED, new Date());
        cache.put(ConnectionHealthResource.CACHE_ATTR_APPS, appsKey);
        context.saveObject(cache);
        context.commitTransaction();

        result.setAttribute("applicationsChecked", healthList.size());
        result.setAttribute("applications", appsKey);
        result.setCompletionStatus(TaskResult.CompletionStatus.Success);
    }

    private static String resolveApplicationsCsv(Attributes<String, Object> args) {
        String appList = null;
        if (args != null) {
            appList = args.getString("applications");
            if (Util.isNullOrEmpty(appList)) {
                Object multi = args.get("applications");
                if (multi instanceof List) {
                    List<?> values = (List<?>) multi;
                    List<String> joined = new ArrayList<>();
                    for (Object value : values) {
                        if (value != null && !Util.isNullOrEmpty(value.toString())) {
                            joined.add(value.toString().trim());
                        }
                    }
                    if (!joined.isEmpty()) {
                        appList = String.join(",", joined);
                    }
                }
            }
        }

        if (Util.isNullOrEmpty(appList)) {
            appList = PluginBaseHelper.getSettingString(
                    ConnectionHealthResource.PLUGIN_NAME,
                    ConnectionHealthResource.SETTING_APPLICATIONS);
        }
        return appList;
    }

    @Override
    public boolean terminate() {
        terminated = true;
        return true;
    }
}
