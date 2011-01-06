/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.launcher.application;

import org.mule.MuleServer;
import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.config.ConfigurationBuilder;
import org.mule.api.config.MuleProperties;
import org.mule.api.context.notification.MuleContextNotificationListener;
import org.mule.config.builders.AutoConfigurationBuilder;
import org.mule.config.builders.SimpleConfigurationBuilder;
import org.mule.config.i18n.CoreMessages;
import org.mule.config.i18n.MessageFactory;
import org.mule.context.DefaultMuleContextFactory;
import org.mule.context.notification.MuleContextNotification;
import org.mule.context.notification.NotificationException;
import org.mule.module.launcher.AbstractFileWatcher;
import org.mule.module.launcher.AppBloodhound;
import org.mule.module.launcher.ApplicationMuleContextBuilder;
import org.mule.module.launcher.ConfigChangeMonitorThreadFactory;
import org.mule.module.launcher.DefaultAppBloodhound;
import org.mule.module.launcher.DefaultMuleSharedDomainClassLoader;
import org.mule.module.launcher.DeploymentInitException;
import org.mule.module.launcher.DeploymentStartException;
import org.mule.module.launcher.DeploymentStopException;
import org.mule.module.launcher.InstallException;
import org.mule.module.launcher.MuleApplicationClassLoader;
import org.mule.module.launcher.MuleSharedDomainClassLoader;
import org.mule.module.launcher.descriptor.ApplicationDescriptor;
import org.mule.module.reboot.MuleContainerBootstrapUtils;
import org.mule.util.ClassUtils;
import org.mule.util.FileUtils;
import org.mule.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class DefaultMuleApplication implements Application
{

    protected static final int DEFAULT_RELOAD_CHECK_INTERVAL_MS = 3000;
    protected static final String ANCHOR_FILE_BLURB = "Delete this file while Mule is running to undeploy this app in a clean way.";

    protected transient final Log logger = LogFactory.getLog(getClass());

    protected ScheduledExecutorService watchTimer;

    private String appName;
    private MuleContext muleContext;
    private ClassLoader deploymentClassLoader;
    private ApplicationDescriptor descriptor;

    protected String[] absoluteResourcePaths;

    protected DefaultMuleApplication(String appName)
    {
        this.appName = appName;
    }

    public void install()
    {
        if (logger.isInfoEnabled())
        {
            logger.info("Installing application: " + appName);
        }

        AppBloodhound bh = new DefaultAppBloodhound();
        try
        {
            descriptor = bh.fetch(getAppName());
        }
        catch (IOException e)
        {
            throw new InstallException(MessageFactory.createStaticMessage("Failed to parse the application deployment descriptor"), e);
        }

        // convert to absolute paths
        final String[] configResources = descriptor.getConfigResources();
        absoluteResourcePaths = new String[configResources.length];
        for (int i = 0; i < configResources.length; i++)
        {
            String resource = configResources[i];
            final File file = toAbsoluteFile(resource);
            if (!file.exists())
            {
                throw new InstallException(
                        MessageFactory.createStaticMessage(String.format("Config for app '%s' not found: %s", getAppName(), file))
                );
            }

            absoluteResourcePaths[i] = file.getAbsolutePath();
        }

        createDeploymentClassLoader();
    }

    public String getAppName()
    {
        return appName;
    }

    public ApplicationDescriptor getDescriptor()
    {
        return descriptor;
    }

    public void setAppName(String appName)
    {
        this.appName = appName;
    }

    public void start()
    {
        if (logger.isInfoEnabled())
        {
            logger.info("Starting application: " + appName);
        }

        try
        {
            this.muleContext.start();
            // save app's state in the marker file
            File marker = new File(MuleContainerBootstrapUtils.getMuleAppsDir(), String.format("%s-anchor.txt", getAppName()));
            FileUtils.writeStringToFile(marker, ANCHOR_FILE_BLURB);
        }
        catch (MuleException e)
        {
            // TODO add app name to the exception field
            throw new DeploymentStartException(MessageFactory.createStaticMessage(appName), e);
        }
        catch (IOException e)
        {
            // TODO add app name to the exception field
            throw new DeploymentStartException(MessageFactory.createStaticMessage(appName), e);
        }
    }

    public void init()
    {
        if (logger.isInfoEnabled())
        {
            logger.info("Initializing application: " + appName);
        }

        String configBuilderClassName = null;
        try
        {
            // Configuration builder
            // Provide a shortcut for Spring: "-builder spring"
            final String builderFromDesc = descriptor.getConfigurationBuilder();
            if ("spring".equalsIgnoreCase(builderFromDesc))
            {
                configBuilderClassName = ApplicationDescriptor.CLASSNAME_SPRING_CONFIG_BUILDER;
            }
            else if (builderFromDesc == null)
            {
                configBuilderClassName = AutoConfigurationBuilder.class.getName();
            }
            else
            {
                configBuilderClassName = builderFromDesc;
            }

            ConfigurationBuilder cfgBuilder = (ConfigurationBuilder) ClassUtils.instanciateClass(
                configBuilderClassName, new Object[] {absoluteResourcePaths}, getDeploymentClassLoader());

            if (!cfgBuilder.isConfigured())
            {
                //Load application properties first since they may be needed by other configuration builders
                List<ConfigurationBuilder> builders = new ArrayList<ConfigurationBuilder>(2);

                final Map<String,String> appProperties = descriptor.getAppProperties();

                //Add the app.home variable to the context
                appProperties.put(MuleProperties.APP_HOME_DIRECTORY_PROPERTY,
                        new File(MuleContainerBootstrapUtils.getMuleAppsDir(), getAppName()).getAbsolutePath());

                builders.add(new SimpleConfigurationBuilder(appProperties));

                // If the annotations module is on the classpath, add the annotations config builder to the list
                // This will enable annotations config for this instance
                //We need to add this builder before spring so that we can use Mule annotations in Spring or any other builder
                if (ClassUtils.isClassOnPath(MuleServer.CLASSNAME_ANNOTATIONS_CONFIG_BUILDER, getClass()))
                {
                    Object configBuilder = ClassUtils.instanciateClass(
                        MuleServer.CLASSNAME_ANNOTATIONS_CONFIG_BUILDER, ClassUtils.NO_ARGS, getClass());
                    builders.add((ConfigurationBuilder) configBuilder);
                }

                builders.add(cfgBuilder);

                DefaultMuleContextFactory muleContextFactory = new DefaultMuleContextFactory();
                this.muleContext = muleContextFactory.createMuleContext(builders, new ApplicationMuleContextBuilder(descriptor));

                if (descriptor.isRedeploymentEnabled())
                {
                    createRedeployMonitor();
                }
            }
        }
        catch (Exception e)
        {
            throw new DeploymentInitException(CoreMessages.failedToLoad(configBuilderClassName), e);
        }
    }

    public MuleContext getMuleContext()
    {
        return muleContext;
    }

    public ClassLoader getDeploymentClassLoader()
    {
        return this.deploymentClassLoader;
    }

    public void dispose()
    {
        if (muleContext == null)
        {
            if (logger.isInfoEnabled())
            {
                logger.info("MuleContext not created, nothing to dispose of");
            }
            return;
        }

        if (muleContext.isStarted() && !muleContext.isDisposed())
        {
            stop();
        }
        if (logger.isInfoEnabled())
        {
            logger.info("Disposing application: " + appName);
        }

        muleContext.dispose();
        muleContext = null;
        // kill any refs to the old classloader to avoid leaks
        Thread.currentThread().setContextClassLoader(null);
    }

    public void redeploy()
    {
        if (logger.isInfoEnabled())
        {
            logger.info("Redeploying application: " + appName);
        }
        dispose();
        install();

        // update thread with the fresh new classloader just created during the install phase
        final ClassLoader cl = getDeploymentClassLoader();
        Thread.currentThread().setContextClassLoader(cl);

        init();
        start();

        // release the ref
        Thread.currentThread().setContextClassLoader(null);
    }

    public void stop()
    {
        if (this.muleContext == null)
        {
            // app never started, maybe due to a previous error
            return;
        }
        if (logger.isInfoEnabled())
        {
            logger.info("Stopping application: " + appName);
        }
        try
        {
            this.muleContext.stop();
        }
        catch (MuleException e)
        {
            // TODO add app name to the exception field
            throw new DeploymentStopException(MessageFactory.createStaticMessage(appName), e);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]@%s", getClass().getName(),
                             appName,
                             Integer.toHexString(System.identityHashCode(this)));
    }

    protected void createDeploymentClassLoader()
    {
        final String domain = descriptor.getDomain();
        ClassLoader parent;

        if (StringUtils.isBlank(domain) || DefaultMuleSharedDomainClassLoader.DEFAULT_DOMAIN_NAME.equals(domain))
        {
            parent = new DefaultMuleSharedDomainClassLoader(getClass().getClassLoader());
        }
        else
        {
            // TODO handle non-existing domains with an exception
            parent = new MuleSharedDomainClassLoader(domain, getClass().getClassLoader());
        }

        this.deploymentClassLoader = new MuleApplicationClassLoader(appName, parent);
    }

    protected void createRedeployMonitor() throws NotificationException
    {
        if (logger.isInfoEnabled())
        {
            logger.info("Monitoring for hot-deployment: " + new File(absoluteResourcePaths [0]));
        }

        final AbstractFileWatcher watcher = new ConfigFileWatcher(new File(absoluteResourcePaths [0]));

        // register a config monitor only after context has started, as it may take some time
        muleContext.registerListener(new MuleContextNotificationListener<MuleContextNotification>()
        {

            public void onNotification(MuleContextNotification notification)
            {
                final int action = notification.getAction();
                switch (action)
                {
                    case MuleContextNotification.CONTEXT_STARTED:
                        scheduleConfigMonitor(watcher);
                        break;
                    case MuleContextNotification.CONTEXT_STOPPING:
                        watchTimer.shutdownNow();
                        muleContext.unregisterListener(this);
                        break;
                }
            }
        });
    }

    protected void scheduleConfigMonitor(AbstractFileWatcher watcher)
    {
        final int reloadIntervalMs = DEFAULT_RELOAD_CHECK_INTERVAL_MS;
        watchTimer = Executors.newSingleThreadScheduledExecutor(new ConfigChangeMonitorThreadFactory(appName));

        watchTimer.scheduleWithFixedDelay(watcher, reloadIntervalMs, reloadIntervalMs, TimeUnit.MILLISECONDS);

        if (logger.isInfoEnabled())
        {
            logger.info("Reload interval: " + reloadIntervalMs);
        }
    }

    /**
     * Resolve a resource relative to an application root.
     * @param path the relative path to resolve
     * @return absolute path, may not actually exist (check with File.exists())
     */
    protected File toAbsoluteFile(String path)
    {
        final String muleHome = System.getProperty(MuleProperties.MULE_HOME_DIRECTORY_PROPERTY);
        String configPath = String.format("%s/apps/%s/%s", muleHome, getAppName(), path);
        return new File(configPath);
    }

    protected class ConfigFileWatcher extends AbstractFileWatcher
    {
        public ConfigFileWatcher(File watchedResource)
        {
            super(watchedResource);
        }

        @Override
        protected synchronized void onChange(File file)
        {
            if (logger.isInfoEnabled())
            {
                logger.info("================== Reloading " + file);
            }

            // grab the proper classloader for our context
            final ClassLoader cl = getDeploymentClassLoader();
            Thread.currentThread().setContextClassLoader(cl);
            redeploy();
        }
    }
}