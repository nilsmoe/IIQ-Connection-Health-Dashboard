package com.example.plugin.health;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sailpoint.api.SailPointContext;
import sailpoint.object.Application;
import sailpoint.object.ApplicationScorecard;
import sailpoint.object.Attributes;
import sailpoint.object.Filter;
import sailpoint.object.QueryOptions;
import sailpoint.object.TaskItemDefinition;
import sailpoint.object.TaskResult;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

/**
 * Shared health evaluation logic used by the REST resource and cache task.
 *
 * Prefers Account Aggregation {@link TaskResult}s when available; otherwise
 * falls back to Application {@code acctAggregationEnd} + scorecard link count
 * (TaskResults are often missing when result retention deletes them).
 */
public final class ConnectionHealthChecker {

    private static final long TWENTY_FOUR_HOURS_MS = 24L * 60 * 60 * 1000;
    private static final long FORTY_EIGHT_HOURS_MS = 48L * 60 * 60 * 1000;

    private ConnectionHealthChecker() {
    }

    /**
     * Parses a comma-separated application list. Empty/null input yields an
     * empty list (no hardcoded defaults).
     */
    public static List<String> parseApplicationNames(String csv) {
        List<String> names = new ArrayList<>();
        if (Util.isNullOrEmpty(csv)) {
            return names;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!Util.isNullOrEmpty(trimmed)) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /** Canonical form used for cache keys (trimmed, comma-joined). */
    public static String canonicalApplications(List<String> appNames) {
        if (appNames == null || appNames.isEmpty()) {
            return "";
        }
        return String.join(",", appNames);
    }

    public static List<Map<String, Object>> checkApplications(
            SailPointContext context, List<String> appNames)
            throws GeneralException {

        List<Map<String, Object>> results = new ArrayList<>();
        for (String appName : appNames) {
            results.add(checkApplication(context, appName));
        }
        return results;
    }

    public static Map<String, Object> checkApplication(
            SailPointContext context, String appName)
            throws GeneralException {

        Map<String, Object> health = new HashMap<>();
        health.put("appName", appName);

        Application app = context.getObjectByName(Application.class, appName);
        if (app == null) {
            health.put("appType", "Unknown");
            health.put("status", "RED");
            health.put("error", "Application not found in IIQ.");
            return health;
        }

        health.put("appType", resolveAppType(app));

        TaskResult tr = findLatestAccountAggregation(context, appName);
        if (tr != null) {
            return applyTaskResult(health, tr);
        }

        return applyApplicationFallback(health, app, appName);
    }

    private static Map<String, Object> applyTaskResult(
            Map<String, Object> health, TaskResult tr) {

        Date completed = tr.getCompleted();
        health.put("lastAggregation",
                completed != null ? completed.getTime() : null);

        Attributes<String, Object> attrs = tr.getAttributes();
        health.put("accountsTotal", readIntAttr(attrs, "total"));
        health.put("accountsCreated", readIntAttr(attrs, "created"));
        health.put("accountsUpdated", readIntAttr(attrs, "updated"));
        health.put("accountsDeleted", readIntAttr(attrs, "deleted"));

        String completionStatus = completionStatusName(tr);
        applyAgeStatus(health, completed, completionStatus,
                firstMessage(tr, "Aggregation completed with errors."));
        return health;
    }

    /**
     * When TaskResults are gone or not linked by targetName, use the
     * Application's own last-aggregation timestamps and scorecard.
     */
    private static Map<String, Object> applyApplicationFallback(
            Map<String, Object> health, Application app, String appName) {

        Date completed = app.getDateAttributeValue(Application.ATTR_ACCT_AGGREGATION_END);
        if (completed == null) {
            completed = toDate(app.getAttributeValue(Application.ATTR_ACCT_AGGREGATION_END));
        }
        if (completed == null) {
            completed = app.getDateAttributeValue(Application.ATTR_ACCT_AGGREGATION_START);
        }
        if (completed == null) {
            completed = toDate(app.getAttributeValue(Application.ATTR_ACCT_AGGREGATION_START));
        }

        int linkCount = 0;
        ApplicationScorecard scorecard = app.getScorecard();
        if (scorecard != null) {
            linkCount = scorecard.getTotalLinks();
        }

        health.put("accountsTotal", linkCount);
        health.put("accountsCreated", 0);
        health.put("accountsUpdated", 0);
        health.put("accountsDeleted", 0);
        health.put("lastAggregation",
                completed != null ? completed.getTime() : null);

        if (completed == null && linkCount <= 0) {
            health.put("status", "RED");
            health.put("error",
                    "No aggregation history on application \"" + appName
                    + "\". Confirm Account Aggregation completed under "
                    + "Setup → Tasks → Task Results, then refresh.");
            return health;
        }

        if (completed == null && linkCount > 0) {
            health.put("status", "YELLOW");
            health.put("warning",
                    "Found " + linkCount
                    + " account link(s) but no aggregation timestamp "
                    + "on the Application object.");
            return health;
        }

        applyAgeStatus(health, completed, null, null);
        return health;
    }

    private static void applyAgeStatus(
            Map<String, Object> health,
            Date completed,
            String completionStatus,
            String errorMessage) {

        long ageMs = completed != null
                ? System.currentTimeMillis() - completed.getTime()
                : Long.MAX_VALUE;

        if ("Error".equalsIgnoreCase(completionStatus)
                || "Terminated".equalsIgnoreCase(completionStatus)) {
            health.put("status", "RED");
            health.put("error",
                    errorMessage != null
                            ? errorMessage
                            : "Aggregation completed with errors.");
        } else if (ageMs > FORTY_EIGHT_HOURS_MS) {
            health.put("status", "RED");
            health.put("error", "Last aggregation is more than 48 hours old.");
        } else if ("Warning".equalsIgnoreCase(completionStatus)
                || ageMs > TWENTY_FOUR_HOURS_MS) {
            health.put("status", "YELLOW");
            health.put("warning",
                    "Warning".equalsIgnoreCase(completionStatus)
                            ? "Aggregation completed with warnings."
                            : "Last aggregation is more than 24 hours old.");
        } else {
            health.put("status", "GREEN");
        }
    }

    /**
     * Finds the newest Account Aggregation result for an application.
     * Prefers {@code targetName}, then matches {@code applications} /
     * name among recent results (any type as last resort).
     */
    static TaskResult findLatestAccountAggregation(
            SailPointContext context, String appName)
            throws GeneralException {

        TaskResult match = firstMatching(context, appName,
                Filter.eq("type", TaskItemDefinition.Type.AccountAggregation),
                true);
        if (match != null) {
            return match;
        }

        // Some environments store type differently; scan recent results.
        return firstMatching(context, appName, null, false);
    }

    private static TaskResult firstMatching(
            SailPointContext context,
            String appName,
            Filter typeFilter,
            boolean tryTargetNameFirst)
            throws GeneralException {

        if (tryTargetNameFirst && typeFilter != null) {
            QueryOptions byTarget = new QueryOptions();
            byTarget.addFilter(typeFilter);
            byTarget.addFilter(Filter.eq("targetName", appName));
            byTarget.addOrdering("completed", false);
            byTarget.addOrdering("launched", false);
            byTarget.setResultLimit(1);
            List<TaskResult> direct = context.getObjects(TaskResult.class, byTarget);
            if (direct != null && !direct.isEmpty()) {
                return direct.get(0);
            }
        }

        QueryOptions recent = new QueryOptions();
        if (typeFilter != null) {
            recent.addFilter(typeFilter);
        }
        recent.addOrdering("completed", false);
        recent.addOrdering("launched", false);
        recent.setResultLimit(200);

        List<TaskResult> candidates = context.getObjects(TaskResult.class, recent);
        if (candidates == null) {
            return null;
        }
        for (TaskResult candidate : candidates) {
            if (matchesApplication(candidate, appName)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean matchesApplication(TaskResult tr, String appName) {
        if (appName.equals(tr.getTargetName())) {
            return true;
        }
        String name = tr.getName();
        if (!Util.isNullOrEmpty(name) && name.contains(appName)) {
            return true;
        }
        return attributeContainsApp(tr.getAttribute("applications"), appName)
                || attributeContainsApp(tr.getAttribute("application"), appName);
    }

    private static boolean attributeContainsApp(Object appsAttr, String appName) {
        if (appsAttr == null) {
            return false;
        }
        if (appsAttr instanceof List) {
            for (Object value : (List<?>) appsAttr) {
                if (valueNamesApp(value, appName)) {
                    return true;
                }
            }
            return false;
        }
        return valueNamesApp(appsAttr, appName)
                || csvContains(appsAttr.toString(), appName);
    }

    private static boolean valueNamesApp(Object value, String appName) {
        if (value == null) {
            return false;
        }
        if (value instanceof Application) {
            return appName.equals(((Application) value).getName());
        }
        return appName.equals(value.toString().trim());
    }

    private static boolean csvContains(String csv, String appName) {
        if (Util.isNullOrEmpty(csv)) {
            return false;
        }
        for (String part : csv.split(",")) {
            if (appName.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Date toDate(Object value) {
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof Long) {
            return new Date((Long) value);
        }
        if (value instanceof Number) {
            return new Date(((Number) value).longValue());
        }
        return null;
    }

    private static String resolveAppType(Application app) {
        String type = app.getType();
        if (!Util.isNullOrEmpty(type)) {
            return type;
        }
        return "Application";
    }

    private static int readIntAttr(Attributes<String, Object> attrs, String key) {
        if (attrs == null) {
            return 0;
        }
        return Util.otoi(attrs.get(key));
    }

    private static String completionStatusName(TaskResult tr) {
        Object status = tr.getCompletionStatus();
        return status != null ? status.toString() : null;
    }

    private static String firstMessage(TaskResult tr, String fallback) {
        try {
            if (tr.getMessages() != null && !tr.getMessages().isEmpty()) {
                return tr.getMessages().toString();
            }
        } catch (Exception ignored) {
            // Fall through to default message
        }
        return fallback;
    }
}
