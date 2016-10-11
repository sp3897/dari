package com.psddev.dari.h2;

import com.psddev.dari.sql.AbstractSqlDatabase;
import com.psddev.dari.sql.SqlDatabaseException;
import org.jooq.Converter;
import org.jooq.DataType;
import org.jooq.SQLDialect;
import org.jooq.impl.SQLDataType;

import java.sql.Connection;
import java.sql.SQLException;

public class H2Database extends AbstractSqlDatabase {

    private static final DataType<String> STRING_INDEX_TYPE = SQLDataType.VARCHAR.asConvertedDataType(new Converter<String, String>() {

        private static final int MAX_STRING_INDEX_TYPE_LENGTH = 500;

        @Override
        public String from(String string) {
            return string;
        }

        @Override
        public String to(String string) {
            return string != null && string.length() > MAX_STRING_INDEX_TYPE_LENGTH
                    ? string.substring(0, MAX_STRING_INDEX_TYPE_LENGTH)
                    : string;
        }

        @Override
        public Class<String> fromType() {
            return String.class;
        }

        @Override
        public Class<String> toType() {
            return String.class;
        }
    });

    @Override
    protected SQLDialect getDialect() {
        return SQLDialect.H2;
    }

    @Override
    protected DataType<String> stringIndexType() {
        return STRING_INDEX_TYPE;
    }

    @Override
    protected void setUp() {
        if (isIndexSpatial()) {
            Connection connection = openConnection();

            try {
                org.h2gis.ext.H2GISExtension.load(connection);

            } catch (SQLException error) {
                throw new SqlDatabaseException(this, "Can't load H2 GIS extension!", error);

            } finally {
                closeConnection(connection);
            }
        }

        super.setUp();
    }

    @Override
    protected String getSetUpResourcePath() {
        return "schema-12.sql";
    }
}