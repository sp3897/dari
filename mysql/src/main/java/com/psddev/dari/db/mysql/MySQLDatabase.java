package com.psddev.dari.db.mysql;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.psddev.dari.db.CompoundPredicate;
import com.psddev.dari.db.ObjectType;
import com.psddev.dari.db.Predicate;
import com.psddev.dari.db.PredicateParser;
import com.psddev.dari.db.Query;
import com.psddev.dari.db.Singleton;
import com.psddev.dari.db.SqlVendor;
import com.psddev.dari.db.State;
import com.psddev.dari.db.StateValueUtils;
import com.psddev.dari.db.sql.AbstractSqlDatabase;
import com.psddev.dari.db.sql.SqlSchema;
import com.psddev.dari.util.CompactMap;
import com.psddev.dari.util.Lazy;
import com.psddev.dari.util.ObjectUtils;
import com.psddev.dari.util.Profiler;
import com.psddev.dari.util.UuidUtils;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class MySQLDatabase extends AbstractSqlDatabase {

    public static final String ENABLE_REPLICATION_CACHE_SUB_SETTING = "enableReplicationCache";
    public static final String REPLICATION_CACHE_SIZE_SUB_SETTING = "replicationCacheSize";

    public static final String DISABLE_REPLICATION_CACHE_QUERY_OPTION = "sql.disableReplicationCache";
    public static final String MYSQL_INDEX_HINT_QUERY_OPTION = "sql.mysqlIndexHint";

    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLDatabase.class);

    private static final String SHORT_NAME = "MySQL";
    private static final String REPLICATION_CACHE_GET_PROFILER_EVENT = SHORT_NAME + " Replication Cache Get";
    private static final String REPLICATION_CACHE_PUT_PROFILER_EVENT = SHORT_NAME + " Replication Cache Put";

    private static final long DEFAULT_REPLICATION_CACHE_SIZE = 10000L;

    private volatile boolean enableReplicationCache;
    private volatile long replicationCacheMaximumSize;

    private transient volatile Cache<UUID, Object[]> replicationCache;
    private transient volatile MySQLBinaryLogReader mysqlBinaryLogReader;

    private final transient Lazy<MySQLSchema> schema = new Lazy<MySQLSchema>() {

        @Override
        protected MySQLSchema create() {
            return new MySQLSchema(MySQLDatabase.this);
        }
    };

    public boolean isEnableReplicationCache() {
        return enableReplicationCache;
    }

    public void setEnableReplicationCache(boolean enableReplicationCache) {
        this.enableReplicationCache = enableReplicationCache;
    }

    public void setReplicationCacheMaximumSize(long replicationCacheMaximumSize) {
        this.replicationCacheMaximumSize = replicationCacheMaximumSize;
    }

    public long getReplicationCacheMaximumSize() {
        return this.replicationCacheMaximumSize;
    }

    @Override
    protected SQLDialect dialect() {
        return SQLDialect.MYSQL;
    }

    @Override
    protected SqlSchema schema() {
        return schema.get();
    }

    @Override
    public SqlVendor getMetricVendor() {
        return new SqlVendor.MySQL();
    }

    @Override
    public void close() {
        try {
            super.close();

        } finally {
            if (mysqlBinaryLogReader != null) {
                LOGGER.info("Stopping MySQL binary log reader");
                mysqlBinaryLogReader.stop();
                mysqlBinaryLogReader = null;
            }
        }
    }

    /**
     * Invalidates all entries in the replication cache.
     */
    public void invalidateReplicationCache() {
        replicationCache.invalidateAll();
    }

    // Creates a previously saved object from the replication cache.
    @SuppressWarnings("unchecked")
    public <T> T createSavedObjectFromReplicationCache(byte[] typeId, UUID id, byte[] data, Map<String, Object> dataJson, Query<T> query) {
        T object = createSavedObject(typeId, id, query);
        State objectState = State.getInstance(object);

        objectState.setValues((Map<String, Object>) cloneJson(dataJson));

        Boolean returnOriginal = query != null ? ObjectUtils.to(Boolean.class, query.getOptions().get(RETURN_ORIGINAL_DATA_QUERY_OPTION)) : null;

        if (returnOriginal == null) {
            returnOriginal = Boolean.FALSE;
        }

        if (returnOriginal) {
            objectState.getExtras().put(ORIGINAL_DATA_EXTRA, data);
        }

        return swapObjectType(query, object);
    }

    @SuppressWarnings("unchecked")
    private static Object cloneJson(Object object) {
        if (object instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) object;
            Map<String, Object> clone = new CompactMap<>(map.size());

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                clone.put((String) entry.getKey(), cloneJson(entry.getValue()));
            }

            return clone;

        } else if (object instanceof List) {
            return ((List<Object>) object).stream()
                    .map(MySQLDatabase::cloneJson)
                    .collect(Collectors.toList());

        } else {
            return object;
        }
    }

    // Tries to find objects by the given ids from the replication cache.
    // If not found, execute the given query to populate it.
    private <T> List<T> findObjectsFromReplicationCache(List<Object> ids, Query<T> query) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }

        List<T> objects = null;
        List<UUID> missingIds = null;

        Profiler.Static.startThreadEvent(REPLICATION_CACHE_GET_PROFILER_EVENT);

        String queryGroup = query != null ? query.getGroup() : null;
        Class queryObjectClass = query != null ? query.getObjectClass() : null;

        try {
            for (Object idObject : ids) {
                UUID id = ObjectUtils.to(UUID.class, idObject);

                if (id == null) {
                    continue;
                }

                Object[] value = replicationCache.getIfPresent(id);

                if (value == null) {
                    if (missingIds == null) {
                        missingIds = new ArrayList<>();
                    }

                    missingIds.add(id);
                    continue;
                }

                UUID typeId = ObjectUtils.to(UUID.class, value[0]);

                ObjectType type = typeId != null ? ObjectType.getInstance(typeId) : null;

                // Restrict objects based on the class provided to the Query
                if (type != null && queryObjectClass != null && !query.getObjectClass().isAssignableFrom(type.getObjectClass())) {
                    continue;
                }

                // Restrict objects based on the group provided to the Query
                if (type != null && queryGroup != null && !type.getGroups().contains(queryGroup)) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                T object = createSavedObjectFromReplicationCache((byte[]) value[0], id, (byte[]) value[1], (Map<String, Object>) value[2], query);

                if (object != null) {
                    if (objects == null) {
                        objects = new ArrayList<>();
                    }

                    objects.add(object);
                }
            }

        } finally {
            Profiler.Static.stopThreadEvent((objects != null ? objects.size() : 0) + " Objects");
        }

        if (missingIds != null && !missingIds.isEmpty()) {
            Profiler.Static.startThreadEvent(REPLICATION_CACHE_PUT_PROFILER_EVENT);

            try {
                Connection connection = null;
                String sqlQuery = null;
                Statement statement = null;
                ResultSet result = null;

                try {
                    connection = openQueryConnection(query);

                    try (DSLContext context = openContext(connection)) {
                        SqlSchema schema = schema();
                        sqlQuery = context.select(schema.recordTypeIdField(), schema.recordDataField(), schema.recordIdField())
                                .from(schema.recordTable())
                                .where(schema.recordIdField().in(missingIds))
                                .getSQL(ParamType.INLINED);
                    }

                    statement = connection.createStatement();
                    result = executeQueryBeforeTimeout(statement, sqlQuery, 0);

                    while (result.next()) {
                        UUID id = ObjectUtils.to(UUID.class, result.getBytes(3));
                        byte[] data = result.getBytes(2);
                        Map<String, Object> dataJson = unserializeData(data);
                        byte[] typeIdBytes = UuidUtils.toBytes(ObjectUtils.to(UUID.class, dataJson.get(StateValueUtils.TYPE_KEY)));

                        if (!Arrays.equals(typeIdBytes, UuidUtils.ZERO_BYTES) && id != null) {
                            replicationCache.put(id, new Object[] { typeIdBytes, data, dataJson });
                        }

                        UUID typeId = ObjectUtils.to(UUID.class, typeIdBytes);

                        ObjectType type = typeId != null ? ObjectType.getInstance(typeId) : null;

                        // Restrict objects based on the class provided to the Query
                        if (type != null && queryObjectClass != null && !query.getObjectClass().isAssignableFrom(type.getObjectClass())) {
                            continue;
                        }

                        // Restrict objects based on the group provided to the Query
                        if (type != null && queryGroup != null && !type.getGroups().contains(queryGroup)) {
                            continue;
                        }

                        T object = createSavedObjectFromReplicationCache(typeIdBytes, id, data, dataJson, query);

                        if (object != null) {
                            if (objects == null) {
                                objects = new ArrayList<>();
                            }

                            objects.add(object);
                        }
                    }

                } catch (SQLException error) {
                    throw createQueryException(error, sqlQuery, query);

                } finally {
                    closeResources(query, connection, statement, result);
                }

            } finally {
                Profiler.Static.stopThreadEvent(missingIds.size() + " Objects");
            }
        }

        return objects;
    }

    @Override
    protected void doInitialize(String settingsKey, Map<String, Object> settings) {
        super.doInitialize(settingsKey, settings);

        setEnableReplicationCache(ObjectUtils.to(boolean.class, settings.get(ENABLE_REPLICATION_CACHE_SUB_SETTING)));
        Long replicationCacheMaxSize = ObjectUtils.to(Long.class, settings.get(REPLICATION_CACHE_SIZE_SUB_SETTING));
        setReplicationCacheMaximumSize(replicationCacheMaxSize != null ? replicationCacheMaxSize : DEFAULT_REPLICATION_CACHE_SIZE);

        if (isEnableReplicationCache()
                && (mysqlBinaryLogReader == null
                || !mysqlBinaryLogReader.isRunning())) {

            replicationCache = CacheBuilder.newBuilder().maximumSize(getReplicationCacheMaximumSize()).build();

            try {
                LOGGER.info("Starting MySQL binary log reader");
                mysqlBinaryLogReader = new MySQLBinaryLogReader(this, replicationCache, ObjectUtils.firstNonNull(getReadDataSource(), getDataSource()));
                mysqlBinaryLogReader.start();

            } catch (IllegalArgumentException error) {
                setEnableReplicationCache(false);
                LOGGER.warn("Can't start MySQL binary log reader!", error);
            }
        }
    }

    private boolean checkReplicationCache(Query<?> query) {
        return query.isCache()
                && isEnableReplicationCache()
                && !Boolean.TRUE.equals(query.getOptions().get(DISABLE_REPLICATION_CACHE_QUERY_OPTION))
                && mysqlBinaryLogReader != null
                && mysqlBinaryLogReader.isConnected();
    }

    @Override
    public <T> List<T> readAll(Query<T> query) {
        if (checkReplicationCache(query)) {
            List<Object> ids = query.findIdOnlyQueryValues();

            if (ids != null && !ids.isEmpty()) {
                List<T> objects = findObjectsFromReplicationCache(ids, query);

                return objects != null ? objects : new ArrayList<>();
            }
        }

        return super.readAll(query);
    }

    @Override
    public <T> T readFirst(Query<T> query) {
        if (query.getSorters().isEmpty()) {

            Predicate predicate = query.getPredicate();
            if (predicate instanceof CompoundPredicate) {

                CompoundPredicate compoundPredicate = (CompoundPredicate) predicate;
                if (PredicateParser.OR_OPERATOR.equals(compoundPredicate.getOperator())) {

                    for (Predicate child : compoundPredicate.getChildren()) {
                        Query<T> childQuery = query.clone();
                        childQuery.setPredicate(child);

                        T first = readFirst(childQuery);
                        if (first != null) {
                            return first;
                        }
                    }

                    return null;
                }
            }
        }

        if (checkReplicationCache(query)) {
            Class<?> objectClass = query.getObjectClass();
            List<Object> ids;

            if (objectClass != null
                    && Singleton.class.isAssignableFrom(objectClass)
                    && query.getPredicate() == null) {

                UUID id = singletonIds.get(objectClass);
                ids = id != null ? Collections.singletonList(id) : null;

            } else {
                ids = query.findIdOnlyQueryValues();
            }

            if (ids != null && !ids.isEmpty()) {
                List<T> objects = findObjectsFromReplicationCache(ids, query);

                return objects == null || objects.isEmpty() ? null : objects.get(0);
            }
        }

        return super.readFirst(query);
    }

    @Override
    protected boolean shouldUseSavepoint() {
        return false;
    }
}