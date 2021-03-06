/*
 * Copyright 2009 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.stargate;

import java.io.IOException;
import java.util.Map;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriInfo;
import javax.xml.namespace.QName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.stargate.model.ColumnSchemaModel;
import org.apache.hadoop.hbase.stargate.model.TableSchemaModel;
import org.apache.hadoop.hbase.util.Bytes;

public class SchemaResource implements Constants {
  private static final Log LOG = LogFactory.getLog(SchemaResource.class);

  private String table;
  private CacheControl cacheControl;

  public SchemaResource(String table) {
    this.table = table;
    cacheControl = new CacheControl();
    cacheControl.setNoCache(true);
    cacheControl.setNoTransform(false);
  }

  private HTableDescriptor getTableSchema() throws IOException,
      TableNotFoundException {
    HTablePool pool = RESTServlet.getInstance().getTablePool(this.table);
    HTable table = pool.get();
    try {
      return table.getTableDescriptor();
    } finally {
      pool.put(table);
    }
  }

  @GET
  @Produces({MIMETYPE_TEXT, MIMETYPE_XML, MIMETYPE_JSON, MIMETYPE_JAVASCRIPT,
    MIMETYPE_PROTOBUF})
  public Response get(@Context UriInfo uriInfo) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("GET " + uriInfo.getAbsolutePath());
    }
    try {
      HTableDescriptor htd = getTableSchema();
      TableSchemaModel model = new TableSchemaModel();
      model.setName(htd.getNameAsString());
      for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> e:
          htd.getValues().entrySet()) {
        model.addAttribute(Bytes.toString(e.getKey().get()), 
            Bytes.toString(e.getValue().get()));
      }
      for (HColumnDescriptor hcd: htd.getFamilies()) {
        ColumnSchemaModel columnModel = new ColumnSchemaModel();
        columnModel.setName(hcd.getNameAsString());
        for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> e:
          hcd.getValues().entrySet()) {
        columnModel.addAttribute(Bytes.toString(e.getKey().get()), 
          Bytes.toString(e.getValue().get()));
      }
        model.addColumnFamily(columnModel);
      }
      ResponseBuilder response = Response.ok(model);
      response.cacheControl(cacheControl);
      return response.build();
    } catch (TableNotFoundException e) {
      throw new WebApplicationException(Response.Status.NOT_FOUND);
    } catch (IOException e) {
      throw new WebApplicationException(e,
                  Response.Status.SERVICE_UNAVAILABLE);
    }
  }

  private Response update(TableSchemaModel model, boolean replace,
      UriInfo uriInfo) {
    // NOTE: 'replace' is currently ignored... we always replace the schema
    try {
      HTableDescriptor htd = new HTableDescriptor(table);
      for (Map.Entry<QName,Object> e: model.getAny().entrySet()) {
        htd.setValue(e.getKey().getLocalPart(), e.getValue().toString());
      }
      for (ColumnSchemaModel family: model.getColumns()) {
        HColumnDescriptor hcd = new HColumnDescriptor(family.getName());
        for (Map.Entry<QName,Object> e: family.getAny().entrySet()) {
          hcd.setValue(e.getKey().getLocalPart(), e.getValue().toString());
        }
        htd.addFamily(hcd);
      }
      RESTServlet server = RESTServlet.getInstance();
      HBaseAdmin admin = new HBaseAdmin(server.getConfiguration());
      if (admin.tableExists(table)) {
        admin.disableTable(table);
        admin.modifyTable(Bytes.toBytes(table), htd);
        server.invalidateMaxAge(table);
        admin.enableTable(table);
        return Response.ok().build();
      } else {
        admin.createTable(htd);
        return Response.created(uriInfo.getAbsolutePath()).build();
      }
    } catch (TableExistsException e) {
      // race, someone else created a table with the same name
      throw new WebApplicationException(e, Response.Status.NOT_MODIFIED);
    } catch (IOException e) {
      throw new WebApplicationException(e, 
            Response.Status.SERVICE_UNAVAILABLE);
    }
  }

  @PUT
  @Consumes({MIMETYPE_XML, MIMETYPE_JSON, MIMETYPE_JAVASCRIPT,
    MIMETYPE_PROTOBUF})
  public Response put(TableSchemaModel model, @Context UriInfo uriInfo) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("PUT " + uriInfo.getAbsolutePath());
    }
    return update(model, true, uriInfo);
  }

  @POST
  @Consumes({MIMETYPE_XML, MIMETYPE_JSON, MIMETYPE_JAVASCRIPT,
    MIMETYPE_PROTOBUF})
  public Response post(TableSchemaModel model, @Context UriInfo uriInfo) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("PUT " + uriInfo.getAbsolutePath());
    }
    return update(model, false, uriInfo);
  }

  @DELETE
  public Response delete(@Context UriInfo uriInfo) {     
    if (LOG.isDebugEnabled()) {
      LOG.debug("DELETE " + uriInfo.getAbsolutePath());
    }
    try {
      HBaseAdmin admin =
        new HBaseAdmin(RESTServlet.getInstance().getConfiguration());
      admin.disableTable(table);
      admin.deleteTable(table);
      return Response.ok().build();
    } catch (TableNotFoundException e) {
      throw new WebApplicationException(Response.Status.NOT_FOUND);
    } catch (IOException e) {
      throw new WebApplicationException(e, 
            Response.Status.SERVICE_UNAVAILABLE);
    }
  }
}
