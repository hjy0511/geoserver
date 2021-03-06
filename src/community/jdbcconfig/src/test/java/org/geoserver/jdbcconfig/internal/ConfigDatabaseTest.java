/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2011, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geoserver.jdbcconfig.internal;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;

import org.easymock.Capture;
import org.easymock.IAnswer;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.Info;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.impl.DataStoreInfoImpl;
import org.geoserver.catalog.impl.WorkspaceInfoImpl;
import org.geoserver.config.ConfigurationListener;
import org.geoserver.config.GeoServer;
import org.geoserver.config.ServiceInfo;
import org.geoserver.jdbcconfig.JDBCConfigTestSupport;
import org.geoserver.wms.WMSInfo;
import org.geoserver.wms.WMSInfoImpl;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.easymock.EasyMock.*;

/**
 * @author groldan
 * 
 */
@RunWith(Parameterized.class)
public class ConfigDatabaseTest {

    private JDBCConfigTestSupport testSupport;

    private ConfigDatabase database;
    
    private GeoServer geoServer;

    public ConfigDatabaseTest(JDBCConfigTestSupport.DBConfig dbConfig) {
        testSupport = new JDBCConfigTestSupport(dbConfig);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object[]> data() {
        return JDBCConfigTestSupport.parameterizedDBConfigs();
    }

    @Before
    public void setUp() throws Exception {
        testSupport.setUp();
        database = testSupport.getDatabase();
        
        // Mock the GeoServer instance to accept a listener, and to provide that listener back when asked
        geoServer = createMock(GeoServer.class);
        final Capture<ConfigurationListener> cap = new Capture<ConfigurationListener>();
        geoServer.addListener(capture(cap)); expectLastCall().asStub();
        expect(geoServer.getListeners()).andStubAnswer(new IAnswer<Collection<ConfigurationListener>>(){

            @Override
            public Collection<ConfigurationListener> answer() throws Throwable {
                return cap.getValues();
            }});
        replay(geoServer);
        
        database.setGeoServer(geoServer);
    }

    @After
    public void tearDown() throws Exception {
        verify(geoServer);
        database.dispose();
        testSupport.tearDown();
    }

    @Test
    public void testAdd() throws Exception {
        
        WorkspaceInfoImpl ws = new WorkspaceInfoImpl();
        try {
            database.add(ws);
            fail("Expected NPE on null id");
        } catch (NullPointerException e) {
            assertEquals("Object has no id", e.getMessage());
        }
        ws.setId("wsid");
        ws.setName("ws1");

        WorkspaceInfo addedWs = database.add(ws);
        assertNotNull(addedWs);
        assertTrue(addedWs instanceof Proxy);
        assertEquals(ws, addedWs);

        DataStoreInfo addedDs = addDataStore(ws);
        assertNotNull(addedDs);
    }

    private DataStoreInfo addDataStore(WorkspaceInfo ws) {
        Catalog catalog = database.getCatalog();
        DataStoreInfoImpl ds = new DataStoreInfoImpl(catalog);
        ds.setWorkspace(ws);
        ds.setId("ds1");
        ds.getConnectionParameters().put("param1", "value1");
        ds.getConnectionParameters().put("param2", "value2");
        ds.setName("data store one");
        ds.setDescription("data store description one");
        ds.setEnabled(true);
        ds.setType("Foo");

        DataStoreInfo addedDs = database.add(ds);
        return addedDs;
    }

    @Test
    public void testModifyWorkspace() throws Exception {
        WorkspaceInfo ws = addWorkspace();
        ws.setName("newName");
        testSaved(ws);
    }

    private WorkspaceInfo addWorkspace() {
        WorkspaceInfo ws = new WorkspaceInfoImpl();
        ((WorkspaceInfoImpl) ws).setId("wsid");
        ws.setName("ws1");

        ws = database.add(ws);
        return ws;
    }

    /**
     * @param info
     */
    private void testSaved(Info info) {
        Info saved = database.save(info);
        assertNotSame(info, saved);
        if (info instanceof DataStoreInfo) {
            assertEquals(((DataStoreInfo) info).getWorkspace(),
                    ((DataStoreInfo) saved).getWorkspace());
        }
        assertEquals(info, saved);
    }

    @Test
    public void testModifyService(){
        
        // Create a service to modify
        WMSInfo service = new WMSInfoImpl();
        ((WMSInfoImpl) service).setId("WMS-TEST");
        service.setName("WMS");
        service.setMaintainer("Foo");


        service = database.add(service);
        
        assertEquals(service.getMaintainer(), "Foo");
        
        service.setMaintainer("Bar");
        testSaved(service);

    }

    @Test
    public void testCacheCatalog() throws Exception {
        // Simulates the situation where multiple GeoServer instances are sharing a database.
        
        WorkspaceInfo ws = addWorkspace();
        ws.setName("name1");
        testSaved(ws);
        
        // Change the stored configuration
        // KS: sorry, this is an utter kludge
        Connection conn = testSupport.getDataSource().getConnection();
        try {
            Statement stmt = conn.createStatement();
            assertEquals(1, stmt.executeUpdate("UPDATE object_property SET value='name2' WHERE property_type=(SELECT oid FROM property_type WHERE type_id = (SELECT oid FROM type WHERE typename='org.geoserver.catalog.WorkspaceInfo') AND name='name') AND id = '"+ws.getId()+"'"));
            assertEquals(1, stmt.executeUpdate("UPDATE object SET blob=(SELECT replace(blob, '<name>name1</name>', '<name>name2</name>') FROM object WHERE id = '"+ws.getId()+"')"));
        } finally {
            conn.close();
        }
        
        // Should be cached
        WorkspaceInfo ws2 = database.getById(ws.getId(), WorkspaceInfo.class);
        assertEquals("name1", ws2.getName());
        
        // Notify of update
        testSupport.getCatalog().firePostModified(ws2);
        
        // Should show the new value
        WorkspaceInfo ws3 = database.getById(ws.getId(), WorkspaceInfo.class);
        assertEquals("name2", ws3.getName());
    }

    @Test
    public void testCacheConfig() throws Exception {
        // Simulates the situation where multiple GeoServer instances are sharing a database.
      
        ServiceInfo service = new WMSInfoImpl();
        ((WMSInfoImpl) service).setId("WMS-TEST");
        service.setName("WMS");
        service.setMaintainer("Foo");


        service = database.add(service);
        
        assertEquals(service.getMaintainer(), "Foo");
        
        // Change the stored configuration
        // KS: sorry, this is an utter kludge
        Connection conn = testSupport.getDataSource().getConnection();
        try {
            Statement stmt = conn.createStatement();
            //assertEquals(1, stmt.executeUpdate("UPDATE object_property SET value='Bar' WHERE property_type=(SELECT oid FROM property_type WHERE type_id = (SELECT oid FROM type WHERE typename='org.geoserver.wms.ServiceInfo') AND name='maintainer') AND id = '"+service.getId()+"';"));
            assertEquals(1, stmt.executeUpdate("UPDATE object SET blob=(SELECT replace(blob, '<maintainer>Foo</maintainer>', '<maintainer>Bar</maintainer>') FROM object WHERE id = '"+service.getId()+"')"));
        } finally {
            conn.close();
        }
        
        // Should be cached
        service = database.getById(service.getId(), ServiceInfo.class);
        assertEquals("Foo", service.getMaintainer());
        
        // Notify of update
        for(ConfigurationListener l : database.getGeoServer().getListeners()){
            l.handlePostServiceChange(service);
        }
        
        // Should show the new value
        service = database.getById(service.getId(), ServiceInfo.class);
        assertEquals("Bar", service.getMaintainer());
    }

    public void testGetServiceWithGeoServerRef() {
        WMSInfo service = new WMSInfoImpl();
        ((WMSInfoImpl) service).setId("WMS-TEST");
        service.setName("WMS");
        service.setMaintainer("Foo");

        service = database.add(service);
        database.clear(service);

        service = database.getAll(WMSInfo.class).iterator().next();
        assertNotNull(service.getGeoServer());
    }
}
