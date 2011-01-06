/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.ajax;

import org.mule.api.MuleMessage;
import org.mule.module.client.MuleClient;
import org.mule.tck.AbstractMuleTestCase;
import org.mule.transport.NullPayload;
import org.mule.transport.servlet.JarResourceServlet;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.ServletHandler;

public class ResourceLoaderServletTestCase extends AbstractMuleTestCase
{
    private Server httpServer;

    public ResourceLoaderServletTestCase()
    {
        super();
        // use dynamic ports outside of a FunctionalTestCase 
        numPorts = 1;
    }

    @Override
    protected void doSetUp() throws Exception
    {
        super.doSetUp();
        httpServer = new Server();
        SelectChannelConnector conn = new SelectChannelConnector();
        conn.setPort(getPorts().get(0));
        httpServer.addConnector(conn);

        ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(JarResourceServlet.class, "/mule-resource/*");

        httpServer.addHandler(handler);

        httpServer.start();
    }

    @Override
    protected void doTearDown() throws Exception
    {
        super.doTearDown();
        // this generates an exception in GenericServlet which we can safely ignore
        if (httpServer != null)
        {
            httpServer.stop();
            httpServer.destroy();
        }
    }

    public void testRetriveJSFromClasspath() throws Exception
    {
        muleContext.start();
        MuleClient client = new MuleClient(muleContext);

        MuleMessage m = client.request("http://localhost:" + getPorts().get(0) + "/mule-resource/js/mule.js", 3000);
        assertFalse(m.getPayload() instanceof NullPayload);
    }
}