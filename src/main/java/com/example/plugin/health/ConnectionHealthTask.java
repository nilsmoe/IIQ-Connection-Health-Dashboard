package com.example.plugin.health;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import sailpoint.api.SailPointContext;
import sailpoint.object.Application;
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
 * 2. Plugin settingsForm multi Application setting {@code applications}
 */
public class ConnectionHealthTask extends AbstractTaskExecutor {

    private boolean terminated = false;

    @Override
    public void execute(SailPointContext context,
                        TaskSchedule schedule,
                        TaskResult result,
                        Attributes<String, Object> args)
            throws Exception {

        List<String> appNames = resolveApplicationNames(context, args);
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

    private static List<String> resolveApplicationNames(
            SailPointContext context, Attributes<String, Object> args) {

        List<String> fromArgs = namesFromTaskArgs(args);
        if (!fromArgs.isEmpty()) {
            return fromArgs;
        }

        List<?> multiObjects = null;
        try {
            multiObjects = PluginBaseHelper.getSettingMultiObject(
                    context,
                    ConnectionHealthResource.PLUGIN_NAME,
                    ConnectionHealthResource.SETTING_APPLICATIONS);
        } catch (Exception ignored) {
            // Fall through to multi-string / CSV
        }

        try {
            return ConnectionHealthChecker.resolveApplicationNames(
                    context,
                    multiObjects,
                    PluginBaseHelper.getSettingMultiString(
                            ConnectionHealthResource.PLUGIN_NAME,
                            ConnectionHealthResource.SETTING_APPLICATIONS),
                    PluginBaseHelper.getSettingString(
                            ConnectionHealthResource.PLUGIN_NAME,
                            ConnectionHealthResource.SETTING_APPLICATIONS));
        } catch (Exception e) {
            return ConnectionHealthChecker.parseApplicationNames(
                    PluginBaseHelper.getSettingString(
                            ConnectionHealthResource.PLUGIN_NAME,
                            ConnectionHealthResource.SETTING_APPLICATIONS));
        }
    }

    private static List<String> namesFromTaskArgs(Attributes<String, Object> args) {
        List<String> names = new ArrayList<>();
        if (args == null) {
            return names;
        }

        Object multi = args.get("applications");
        if (multi instanceof List) {
            for (Object value : (List<?>) multi) {
                String name = nameFromValue(value);
                if (!Util.isNullOrEmpty(name)) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                return names;
            }
        }

        return ConnectionHealthChecker.parseApplicationNames(args.getString("applications"));
    }

    private static String nameFromValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Application) {
            return ((Application) value).getName();
        }
        String text = value.toString().trim();
        return Util.isNullOrEmpty(text) ? null : text;
    }

    @Override
    public boolean terminate() {
        terminated = true;
        return true;
    }
}
