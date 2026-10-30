package com.example.plugin.health;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import sailpoint.api.SailPointContext;
import sailpoint.object.Application;
import sailpoint.object.Custom;
import sailpoint.object.Plugin;
import sailpoint.object.QueryOptions;
import sailpoint.plugin.PluginsCache;
import sailpoint.rest.plugin.BasePluginResource;
import sailpoint.rest.plugin.RequiredRight;
import sailpoint.server.Environment;
import sailpoint.service.plugin.PluginsService;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

/**
 * REST Resource for Connection Health Dashboard.
 * Endpoints under /plugin/rest/connectionHealth/...
 */
@Path("connectionHealth")
@Produces(MediaType.APPLICATION_JSON)
public class ConnectionHealthResource extends BasePluginResource {

    public static final String CACHE_NAME = "ConnectionHealthCache";
    public static final String PLUGIN_NAME = "ConnectionHealthDashboard";
    public static final String VIEW_RIGHT = "ConnectionHealthViewRight";
    public static final String SETTING_APPLICATIONS = "applications";
    public static final String SETTING_CACHE_TTL = "cacheTtlMinutes";
    public static final String CACHE_ATTR_HEALTH = "healthData";
    public static final String CACHE_ATTR_UPDATED = "lastUpdated";
    public static final String CACHE_ATTR_APPS = "applications";

    private static final long DEFAULT_CACHE_TTL_MS = 5L * 60 * 1000;

    @Override
    public String getPluginName() {
        return PLUGIN_NAME;
    }

    @GET
    @Path("status")
    @RequiredRight(VIEW_RIGHT)
    public Response getHealthStatus() {
        try {
            SailPointContext context = getContext();
            List<String> appNames = resolveConfiguredApplications(context);
            String appsKey = ConnectionHealthChecker.canonicalApplications(appNames);

            long cacheTtlMs = resolveCacheTtlMs();
            Custom cache = context.getObjectByName(Custom.class, CACHE_NAME);
            if (cache != null) {
                Object cachedData = cache.get(CACHE_ATTR_HEALTH);
                Object cacheTime = cache.get(CACHE_ATTR_UPDATED);
                Object cachedApps = cache.get(CACHE_ATTR_APPS);
                if (cachedData != null
                        && cacheTime instanceof Date
                        && appsKey.equals(cachedApps != null ? cachedApps.toString() : null)) {
                    long cacheAge = System.currentTimeMillis()
                            - ((Date) cacheTime).getTime();
                    if (cacheAge >= 0 && cacheAge < cacheTtlMs) {
                        return Response.ok(cachedData).build();
                    }
                }
            }

            List<Map<String, Object>> results =
                    ConnectionHealthChecker.checkApplications(context, appNames);

            if (cache == null) {
                cache = new Custom();
                cache.setName(CACHE_NAME);
            }
            cache.put(CACHE_ATTR_HEALTH, results);
            cache.put(CACHE_ATTR_UPDATED, new Date());
            cache.put(CACHE_ATTR_APPS, appsKey);
            context.saveObject(cache);
            context.commitTransaction();

            return Response.ok(results).build();

        } catch (GeneralException e) {
            return errorResponse(e.getMessage());
        }
    }

    /**
     * Returns all IIQ applications with a selected flag for the Configure page.
     */
    @GET
    @Path("settings")
    @RequiredRight(VIEW_RIGHT)
    public Response getSettings() {
        try {
            SailPointContext context = getContext();
            List<String> selected = resolveConfiguredApplications(context);
            Set<String> selectedSet = new HashSet<>(selected);

            QueryOptions qo = new QueryOptions();
            qo.addOrdering("name", true);
            List<Application> allApps = context.getObjects(Application.class, qo);

            List<Map<String, Object>> appRows = new ArrayList<>();
            if (allApps != null) {
                for (Application app : allApps) {
                    if (app == null || Util.isNullOrEmpty(app.getName())) {
                        continue;
                    }
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", app.getId());
                    row.put("name", app.getName());
                    row.put("type", app.getType() != null ? app.getType() : "Application");
                    row.put("selected", selectedSet.contains(app.getName()));
                    appRows.add(row);
                }
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("applications", appRows);
            payload.put("selectedNames", selected);
            payload.put("cacheTtlMinutes", resolveCacheTtlMinutesValue());
            return Response.ok(payload).build();

        } catch (GeneralException e) {
            return errorResponse(e.getMessage());
        }
    }

    /**
     * Saves monitored applications as a clean CSV of names (not object IDs).
     */
    @PUT
    @Path("settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @RequiredRight(VIEW_RIGHT)
    public Response saveSettings(Map<String, Object> body) {
        try {
            SailPointContext context = getContext();
            if (body == null) {
                return errorResponse("Missing request body.");
            }

            List<String> selectedNames = extractSelectedNames(context, body.get("applications"));
            int ttlMinutes = parseTtl(body.get("cacheTtlMinutes"));

            Plugin plugin = context.getObjectByName(Plugin.class, PLUGIN_NAME);
            if (plugin == null) {
                return errorResponse("Plugin object not found: " + PLUGIN_NAME);
            }

            Map<String, String> updates = new HashMap<>();
            updates.put(SETTING_APPLICATIONS,
                    ConnectionHealthChecker.canonicalApplications(selectedNames));
            updates.put(SETTING_CACHE_TTL, String.valueOf(ttlMinutes));

            PluginsCache pluginsCache = Environment.getEnvironment().getPluginsCache();
            new PluginsService(context).updateSettings(plugin, updates, pluginsCache);

            // Force next dashboard load to recompute for the new selection
            Custom cache = context.getObjectByName(Custom.class, CACHE_NAME);
            if (cache != null) {
                cache.put(CACHE_ATTR_APPS, "");
                cache.put(CACHE_ATTR_UPDATED, new Date(0L));
                context.saveObject(cache);
                context.commitTransaction();
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("applications", selectedNames);
            payload.put("cacheTtlMinutes", ttlMinutes);
            payload.put("saved", Boolean.TRUE);
            return Response.ok(payload).build();

        } catch (GeneralException e) {
            return errorResponse(e.getMessage());
        } catch (RuntimeException e) {
            return errorResponse(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private List<String> resolveConfiguredApplications(SailPointContext context)
            throws GeneralException {
        List<?> multiObjects = null;
        try {
            multiObjects = getSettingMultiObject(context, SETTING_APPLICATIONS);
        } catch (GeneralException ignored) {
            // Fall through
        }
        return ConnectionHealthChecker.resolveApplicationNames(
                context,
                multiObjects,
                getSettingMultiString(SETTING_APPLICATIONS),
                getSettingString(SETTING_APPLICATIONS));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractSelectedNames(SailPointContext context, Object raw)
            throws GeneralException {
        List<String> tokens = new ArrayList<>();
        if (raw instanceof List) {
            for (Object value : (List<?>) raw) {
                if (value instanceof Map) {
                    Object name = ((Map<?, ?>) value).get("name");
                    if (name != null && !Util.isNullOrEmpty(name.toString())) {
                        tokens.add(name.toString().trim());
                    }
                } else if (value != null && !Util.isNullOrEmpty(value.toString())) {
                    tokens.add(value.toString().trim());
                }
            }
        } else if (raw instanceof String) {
            tokens.addAll(ConnectionHealthChecker.parseApplicationNames((String) raw));
        }

        List<String> names = new ArrayList<>();
        for (String token : tokens) {
            Application app = ConnectionHealthChecker.findApplication(context, token);
            if (app != null && !Util.isNullOrEmpty(app.getName())) {
                if (!names.contains(app.getName())) {
                    names.add(app.getName());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    private int parseTtl(Object raw) {
        int ttl = Util.otoi(raw);
        if (ttl <= 0) {
            ttl = resolveCacheTtlMinutesValue();
        }
        if (ttl <= 0) {
            ttl = 5;
        }
        return ttl;
    }

    private int resolveCacheTtlMinutesValue() {
        try {
            int minutes = getSettingInt(SETTING_CACHE_TTL);
            return minutes > 0 ? minutes : 5;
        } catch (RuntimeException ignored) {
            return 5;
        }
    }

    private long resolveCacheTtlMs() {
        int minutes = resolveCacheTtlMinutesValue();
        return minutes > 0 ? minutes * 60L * 1000L : DEFAULT_CACHE_TTL_MS;
    }

    private static Response errorResponse(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message != null ? message : "Unknown error");
        return Response.serverError().entity(error).build();
    }
}
