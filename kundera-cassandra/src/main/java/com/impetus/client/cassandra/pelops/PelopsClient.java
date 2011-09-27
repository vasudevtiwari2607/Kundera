/*******************************************************************************
 * * Copyright 2011 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/

package com.impetus.client.cassandra.pelops;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.Query;

import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.util.Version;
import org.scale7.cassandra.pelops.Bytes;
import org.scale7.cassandra.pelops.Cluster;
import org.scale7.cassandra.pelops.IConnection;
import org.scale7.cassandra.pelops.Mutator;
import org.scale7.cassandra.pelops.Pelops;
import org.scale7.cassandra.pelops.RowDeletor;
import org.scale7.cassandra.pelops.Selector;

import com.impetus.client.cassandra.index.SolandraUtils;
import com.impetus.kundera.Client;
import com.impetus.kundera.Constants;
import com.impetus.kundera.db.DataRow;
import com.impetus.kundera.ejb.EntityManagerImpl;
import com.impetus.kundera.index.Indexer;
import com.impetus.kundera.index.KunderaIndexer;
import com.impetus.kundera.loader.DBType;
import com.impetus.kundera.metadata.MetadataUtils;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.Relation;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorFactory;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.impetus.kundera.proxy.EnhancedEntity;
import com.impetus.kundera.query.LuceneQuery;

/**
 * Client implementation using Pelops. http://code.google.com/p/pelops/
 * 
 * @author animesh.kumar
 * @since 0.1
 */
public class PelopsClient implements Client
{

    /** The Constant poolName. */
    private static final String POOL_NAME = "Main";

    /** array of cassandra hosts. */
    private String[] contactNodes;

    /** default port. */
    private int defaultPort;

    /** The closed. */
    private boolean closed = false;

    /** log for this class. */
    private static Log log = LogFactory.getLog(PelopsClient.class);

    /** The data handler. */
    PelopsDataHandler dataHandler = new PelopsDataHandler();

    /** Whether this client is connected */
    private boolean isConnected;

    /**
     * default constructor.
     */
    public PelopsClient()
    {
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#connect()
     */
    @Override
    public final void connect()
    {
       
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#shutdown()
     */
    @Override
    public final void shutdown()
    {
        Pelops.shutdown();
        closed = true;
    }

    /**
     * Checks if is open.
     * 
     * @return true, if is open
     */
    public final boolean isOpen()
    {
        return !closed;
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.impetus.kundera.Client#writeColumns(com.impetus.kundera.ejb.
     * EntityManagerImpl, com.impetus.kundera.proxy.EnhancedEntity,
     * com.impetus.kundera.metadata.EntityMetadata)
     */
    @Override
    public void writeData(EntityManagerImpl em, EnhancedEntity e, EntityMetadata m) throws Exception
    {

        String keyspace = m.getSchema();
        String columnFamily = m.getTableName();

        if (!isOpen())
        {
            throw new PersistenceException("PelopsClient is closed.");
        }

        PelopsClient.ThriftRow tf = dataHandler.toThriftRow(this, e, m, columnFamily);
        configurePool(keyspace);

        Mutator mutator = Pelops.createMutator(POOL_NAME);

        List<Column> thriftColumns = tf.getColumns();
        List<SuperColumn> thriftSuperColumns = tf.getSuperColumns();
        if (thriftColumns != null && !thriftColumns.isEmpty())
        {
            mutator.writeColumns(columnFamily, new Bytes(tf.getId().getBytes()),
                    Arrays.asList(tf.getColumns().toArray(new Column[0])));
        }

        if (thriftSuperColumns != null && !thriftSuperColumns.isEmpty())
        {
            for (SuperColumn sc : thriftSuperColumns)
            {
                Bytes.toUTF8(sc.getColumns().get(0).getValue());
                mutator.writeSubColumns(columnFamily, tf.getId(), Bytes.toUTF8(sc.getName()), sc.getColumns());

            }

        }
        mutator.execute(ConsistencyLevel.ONE);

    }

    @Override
    public final <E> E loadData(EntityManagerImpl em, String rowId, EntityMetadata m) throws Exception
    {

        if (!isOpen())
        {
            throw new PersistenceException("PelopsClient is closed.");
        }

        configurePool(m.getSchema());
        Selector selector = Pelops.createSelector(POOL_NAME);

        E e = (E) dataHandler.fromThriftRow(selector, em, m.getEntityClazz(), m, rowId);

        return e;
    }

    @Override
    public final <E> List<E> loadData(EntityManagerImpl em, EntityMetadata m, String... rowIds) throws Exception
    {
        if (!isOpen())
        {
            throw new PersistenceException("PelopsClient is closed.");
        }

        configurePool(m.getSchema());
        Selector selector = Pelops.createSelector(POOL_NAME);

        List<E> entities = (List<E>) dataHandler.fromThriftRow(selector, em, m.getEntityClazz(), m, rowIds);

        return entities;
    }

    /**
     * @param m
     * @param col
     * @param <E>
     * @return
     * @throws Exception
     */
    public <E> List<E> loadData(EntityManager em, EntityMetadata m, Map<String, String> col) throws Exception
    {
        List<E> entities = new ArrayList<E>();
        for (String superColName : col.keySet())
        {
            String entityId = col.get(superColName);
            List<SuperColumn> superColumnList = loadSuperColumns(m.getSchema(), m.getTableName(), entityId,
                    new String[] { superColName });
            E e = (E) fromThriftRow(em, m.getEntityClazz(), m, new DataRow<SuperColumn>(entityId, m.getTableName(),
                    superColumnList));
            entities.add(e);
        }
        return entities;
    }

    // TODO: This method is not being used currently anywhere and should be
    // deleted in code refactoring exercise
    private <E> List<E> loadColumns(EntityManagerImpl em, Class<E> clazz, String keyspace, String columnFamily,
            EntityMetadata m, String... rowIds) throws PropertyAccessException, Exception
    {
        if (!isOpen())
        {
            throw new PersistenceException("PelopsClient is closed.");
        }

        configurePool(keyspace);
        Selector selector = Pelops.createSelector(POOL_NAME);

        List<Bytes> bytesArr = new ArrayList<Bytes>();

        for (String rowkey : rowIds)
        {
            Bytes bytes = new Bytes(PropertyAccessorFactory.STRING.toBytes(rowkey));
            bytesArr.add(bytes);
        }

        Map<Bytes, List<Column>> map = selector.getColumnsFromRows(columnFamily, bytesArr,
                Selector.newColumnsPredicateAll(false, 1000), ConsistencyLevel.ONE);
        List<E> entities = new ArrayList<E>();
        // Iterate and populate entities
        for (Map.Entry<Bytes, List<Column>> entry : map.entrySet())
        {

            String id = PropertyAccessorFactory.STRING.fromBytes(entry.getKey().toByteArray());

            List<Column> columns = entry.getValue();

            if (entry.getValue().size() == 0)
            {
                log.debug("@Entity not found for id: " + id);
                continue;
            }

            E e = dataHandler.fromColumnThriftRow(em, clazz, m, this.new ThriftRow(id, columnFamily, columns, null));
            entities.add(e);
        }
        return entities;
    }

    // TODO: Move this code to PelopsDataHandler if at all required
    public final List<SuperColumn> loadSuperColumns(String keyspace, String columnFamily, String rowId,
            String... superColumnNames) throws Exception
    {
        if (!isOpen())
            throw new PersistenceException("PelopsClient is closed.");
        configurePool(keyspace);
        Selector selector = Pelops.createSelector(POOL_NAME);
        return selector.getSuperColumnsFromRow(columnFamily, rowId, Selector.newColumnsPredicate(superColumnNames),
                ConsistencyLevel.ONE);
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.impetus.kundera.Client#loadColumns(com.impetus.kundera.ejb.
     * EntityManagerImpl, com.impetus.kundera.metadata.EntityMetadata,
     * java.util.Queue)
     */
    public <E> List<E> loadData(EntityManagerImpl em, EntityMetadata m, Query query) throws Exception
    {
        throw new NotImplementedException("Not yet implemented");
    }

    /*
     * @see com.impetus.kundera.CassandraClient#delete(java.lang.String,
     * java.lang.String, java.lang.String)
     */
    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#delete(java.lang.String,
     * java.lang.String, java.lang.String)
     */
    @Override
    public final void delete(String keyspace, String columnFamily, String rowId) throws Exception
    {

        if (!isOpen())
        {
            throw new PersistenceException("PelopsClient is closed.");
        }

        configurePool(keyspace);
        RowDeletor rowDeletor = Pelops.createRowDeletor(POOL_NAME);
        rowDeletor.deleteRow(columnFamily, rowId, ConsistencyLevel.ONE);
    }

    /**
     * Sets the contact nodes.
     * 
     * @param contactNodes
     *            the contactNodes to set
     */
    @Override
    public final void setContactNodes(String... contactNodes)
    {
        this.contactNodes = contactNodes;
    }

    /**
     * Sets the default port.
     * 
     * @param defaultPort
     *            the defaultPort to set
     */
    @Override
    public final void setDefaultPort(int defaultPort)
    {
        this.defaultPort = defaultPort;
    }

    /* @see java.lang.Object#toString() */
    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public final String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("PelopsClient [contactNodes=");
        builder.append(Arrays.toString(contactNodes));
        builder.append(", defaultPort=");
        builder.append(defaultPort);
        builder.append(", closed=");
        builder.append(closed);
        builder.append("]");
        return builder.toString();
    }

    /**
     * Utility class that represents a row in Cassandra DB.
     * 
     * @author animesh.kumar
     */
    public class ThriftRow
    {

        /** Id of the row. */
        private String id;

        /** name of the family. */
        private String columnFamilyName;

        /** list of thrift columns from the row. */
        private List<Column> columns;

        /** list of thrift super columns columns from the row. */
        private List<SuperColumn> superColumns;

        /**
         * default constructor.
         */
        public ThriftRow()
        {
            columns = new ArrayList<Column>();
            superColumns = new ArrayList<SuperColumn>();
        }

        /**
         * The Constructor.
         * 
         * @param id
         *            the id
         * @param columnFamilyName
         *            the column family name
         * @param columns
         *            the columns
         * @param superColumns
         *            the super columns
         */
        public ThriftRow(String id, String columnFamilyName, List<Column> columns, List<SuperColumn> superColumns)
        {
            this.id = id;
            this.columnFamilyName = columnFamilyName;
            if (columns != null)
            {
                this.columns = columns;
            }

            if (superColumns != null)
            {
                this.superColumns = superColumns;
            }

        }

        /**
         * Gets the id.
         * 
         * @return the id
         */
        public String getId()
        {
            return id;
        }

        /**
         * Sets the id.
         * 
         * @param id
         *            the key to set
         */
        public void setId(String id)
        {
            this.id = id;
        }

        /**
         * Gets the column family name.
         * 
         * @return the columnFamilyName
         */
        public String getColumnFamilyName()
        {
            return columnFamilyName;
        }

        /**
         * Sets the column family name.
         * 
         * @param columnFamilyName
         *            the columnFamilyName to set
         */
        public void setColumnFamilyName(String columnFamilyName)
        {
            this.columnFamilyName = columnFamilyName;
        }

        /**
         * Gets the columns.
         * 
         * @return the columns
         */
        public List<Column> getColumns()
        {
            return columns;
        }

        /**
         * Sets the columns.
         * 
         * @param columns
         *            the columns to set
         */
        public void setColumns(List<Column> columns)
        {
            this.columns = columns;
        }

        /**
         * Adds the column.
         * 
         * @param column
         *            the column
         */
        public void addColumn(Column column)
        {
            columns.add(column);
        }

        /**
         * Gets the super columns.
         * 
         * @return the superColumns
         */
        public List<SuperColumn> getSuperColumns()
        {
            return superColumns;
        }

        /**
         * Sets the super columns.
         * 
         * @param superColumns
         *            the superColumns to set
         */
        public void setSuperColumns(List<SuperColumn> superColumns)
        {
            this.superColumns = superColumns;
        }

        /**
         * Adds the super column.
         * 
         * @param superColumn
         *            the super column
         */
        public void addSuperColumn(SuperColumn superColumn)
        {
            this.superColumns.add(superColumn);
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#setKeySpace(java.lang.String)
     */
    @Override
    public void setSchema(String keySpace)
    {

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#getType()
     */
    @Override
    public DBType getType()
    {
        return DBType.CASSANDRA;
    }

    /**
     * Configure pool.
     * 
     * @param keyspace
     *            the keyspace
     */
    private void configurePool(String keyspace)
    {
        Cluster cluster = new Cluster(contactNodes, new IConnection.Config(defaultPort, true, -1), false);
        Pelops.addPool(POOL_NAME, cluster, keyspace);

    }

    

    /**
     * From thrift row.
     * 
     * @param <E>
     *            the element type
     * @param clazz
     *            the clazz
     * @param m
     *            the m
     * @param tr
     *            the cr
     * @return the e
     * @throws Exception
     *             the exception
     */
    // TODO: this is a duplicate code snippet and we need to refactor this.(it
    // should be moved to PelopsDataHandler)
    private <E> E fromThriftRow(EntityManager em, Class<E> clazz, EntityMetadata m, DataRow<SuperColumn> tr)
            throws Exception
    {

        // Instantiate a new instance
        E e = clazz.newInstance();

        // Set row-key. Note: @Id is always String.
        PropertyAccessorHelper.set(e, m.getIdColumn().getField(), tr.getId());

        // Get a name->field map for super-columns
        Map<String, Field> columnNameToFieldMap = new HashMap<String, Field>();
        Map<String, Field> superColumnNameToFieldMap = new HashMap<String, Field>();
        MetadataUtils.populateColumnAndSuperColumnMaps(m, columnNameToFieldMap, superColumnNameToFieldMap);

        Collection embeddedCollection = null;
        Field embeddedCollectionField = null;
        for (SuperColumn sc : tr.getColumns())
        {

            String scName = PropertyAccessorFactory.STRING.fromBytes(sc.getName());
            String scNamePrefix = null;

            if (scName.indexOf(Constants.EMBEDDED_COLUMN_NAME_DELIMITER) != -1)
            {
                scNamePrefix = MetadataUtils.getEmbeddedCollectionPrefix(scName);
                embeddedCollectionField = superColumnNameToFieldMap.get(scNamePrefix);
                embeddedCollection = MetadataUtils.getEmbeddedCollectionInstance(embeddedCollectionField);

                String scFieldName = scName.substring(0, scName.indexOf(Constants.EMBEDDED_COLUMN_NAME_DELIMITER));
                Field superColumnField = e.getClass().getDeclaredField(scFieldName);
                if (!superColumnField.isAccessible())
                {
                    superColumnField.setAccessible(true);
                }
                // Collection embeddedCollection = null;
                if (superColumnField.getType().equals(List.class))
                {
                    embeddedCollection = new ArrayList();
                }
                else if (superColumnField.getType().equals(Set.class))
                {
                    embeddedCollection = new HashSet();
                }
                PelopsDataHandler handler = new PelopsDataHandler();
                Object embeddedObject = handler.populateEmbeddedObject(sc, m);
                embeddedCollection.add(embeddedObject);
                superColumnField.set(e, embeddedCollection);
            }
            else
            {
                boolean intoRelations = false;
                if (scName.equals(Constants.FOREIGN_KEY_EMBEDDED_COLUMN_NAME))
                {
                    intoRelations = true;
                }

                for (Column column : sc.getColumns())
                {
                    String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
                    byte[] value = column.getValue();

                    if (value == null)
                    {
                        continue;
                    }

                    if (intoRelations)
                    {
                        Relation relation = m.getRelation(name);

                        String foreignKeys = PropertyAccessorFactory.STRING.fromBytes(value);
                        Set<String> keys = MetadataUtils.deserializeKeys(foreignKeys);
                        ((EntityManagerImpl) em).getEntityResolver().populateForeignEntities(e, tr.getId(), relation,
                                keys.toArray(new String[0]));

                    }
                    else
                    {
                        // set value of the field in the bean
                        Field field = columnNameToFieldMap.get(name);
                        Object embeddedObject = PropertyAccessorHelper.getObject(e, scName);
                        PropertyAccessorHelper.set(embeddedObject, field, value);
                    }
                }
            }
        }
        return e;
    }

    @Override
    public Indexer getIndexer()
    {
        return new KunderaIndexer(this, new StandardAnalyzer(Version.LUCENE_CURRENT));
    }

    @Override
    public Query getQuery(EntityManagerImpl em, String ejbqlString)
    {
        return new LuceneQuery(em, ejbqlString);
    }

}
