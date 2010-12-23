/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.servlet.jetty;

import org.mule.config.spring.handlers.AbstractMuleNamespaceHandler;
import org.mule.config.spring.parsers.generic.ChildDefinitionParser;

/**
 * Registers a Bean Definition Parser for handling <code><jetty:connector></code> elements.
 */
public class JettyNamespaceHandler extends AbstractMuleNamespaceHandler
{

    public void init()
    {
        registerMetaTransportEndpoints(JettyHttpConnector.JETTY);
        registerConnectorDefinitionParser(JettyHttpConnector.class);
        registerBeanDefinitionParser("webapps", new ChildDefinitionParser("webappsConfiguration", WebappsConfiguration.class, true));
    }

}