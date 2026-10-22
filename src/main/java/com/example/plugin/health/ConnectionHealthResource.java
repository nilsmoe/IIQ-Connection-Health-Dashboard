package com.example.plugin.health;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import sailpoint.api.SailPointContext;
import sailpoint.object.Custom;
import sailpoint.rest.plugin.BasePluginResource;
import sailpoint.rest.plugin.RequiredRight;
import sailpoint.tools.GeneralException;

/**
 * REST Resource for Connection Health Dashboard.
 * Endpoint: GET /plugin/rest/connectionHealth/status
 */
@Path("connectionHealth")
@Produces(MediaType.APPLICATION_JSON)
public class ConnectionHealthResource extends BasePluginResource {

    public static final String CACHE_NAME = "ConnectionHealthCache";
    public static final String PLUGIN_NAME = "ConnectionHealthDashboard";
    public static final String VIEW_RIGHT = "ConnectionHealthViewRight";
    public static final String SETTING_APPLICATIONS = "applications";
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

            List<String> appNames = ConnectionHealthChecker.parseApplicationNames(
                    getSettingString(SETTING_APPLICATIONS));
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
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return Response.serverError().entity(error).build();
        }
    }

    private long resolveCacheTtlMs() {
        try {
            int minutes = getSettingInt("cacheTtlMinutes");
            if (minutes > 0) {
                return minutes * 60L * 1000L;
            }
        } catch (RuntimeException ignored) {
            // Fall back to default when setting is absent or unreadable
        }
        return DEFAULT_CACHE_TTL_MS;
    }
}
