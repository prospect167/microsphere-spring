/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.microsphere.spring.config.zookeeper.annotation;

import io.microsphere.spring.config.context.annotation.ExtendablePropertySourceLoader;
import io.microsphere.spring.config.context.annotation.PropertySourceExtensionAttributes;
import io.microsphere.spring.config.zookeeper.env.ZookeeperPropertySource;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.GetChildrenBuilder;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.retry.RetryForever;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.microsphere.net.URLUtils.buildURI;
import static io.microsphere.util.ShutdownHookUtils.addShutdownHookCallback;

/**
 * {@link EnableZookeeperConfig} {@link PropertySource} Loader to load the Zookeeper Configuration:
 * <ul>
 *     <li>Create a CuratorFramework client based on the @EnableZookeeperConfig meta information, connection string, and root path</li>
 *     <li>Traverse all PropertySource child nodes according to the root path rootPath</li>
 * </ul>
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 * @since 1.0.0
 */
public class EnableZookeeperConfigPropertySourceLoader extends ExtendablePropertySourceLoader<EnableZookeeperConfig> {

    private static final Map<String, CuratorFramework> clientsCache;

    static {
        clientsCache = new HashMap<>();
        addShutdownHookCallback(new Runnable() {
            @Override
            public void run() {
                // Close clients
                close(clientsCache.values());
                // Clear clients cache when JVM is shutdown
                clientsCache.clear();
            }
        });
    }

    @Override
    protected PropertySource<?> loadPropertySource(PropertySourceExtensionAttributes<EnableZookeeperConfig> attributes,
                                                   String propertySourceName, AnnotationMetadata metadata) throws Throwable {

        String rootPath = attributes.getString("rootPath");

        CuratorFramework client = getClient(attributes, rootPath);

        CompositePropertySource compositePropertySource = new CompositePropertySource(propertySourceName);
        boolean rootPathNotExisted = client.checkExists().forPath(rootPath) == null;

        boolean autoRefreshed = attributes.isAutoRefreshed();

        if (rootPathNotExisted) { // Not Existed
            if (!autoRefreshed) {
                return null;
            }
            // Create Root Path
            client.create().forPath(rootPath);
        }

        GetChildrenBuilder childrenBuilder = client.getChildren();

        List<String> configBasePaths = childrenBuilder.forPath(rootPath);

        for (String configBasePath : configBasePaths) {
            String configPath = buildURI(rootPath, configBasePath);
            ZookeeperPropertySource zookeeperPropertySource = createPropertySource(configPath, client, autoRefreshed);
            compositePropertySource.addPropertySource(zookeeperPropertySource);
        }
        return compositePropertySource;
    }

    private ZookeeperPropertySource createPropertySource(String configPath, CuratorFramework client, boolean autoRefreshed) {
        return new ZookeeperPropertySource(configPath, client, autoRefreshed);
    }

    private CuratorFramework getClient(PropertySourceExtensionAttributes<EnableZookeeperConfig> propertySourceExtensionAttributes, String rootPath) {
        String connectString = propertySourceExtensionAttributes.getString("connectString");
        String key = connectString + rootPath;
        return clientsCache.computeIfAbsent(key, k -> {
            CuratorFramework client = CuratorFrameworkFactory.builder()
                    .connectString(connectString)
                    .retryPolicy(new RetryForever(300))
                    .build();
            // Start
            client.start();
            return client;
        });
    }

    private static void close(Collection<CuratorFramework> clients) {
        for (CuratorFramework client : clients) {
            close(client);
        }
    }

    private static void close(CuratorFramework client) {
        if (client != null) {
            CuratorFrameworkState state = client.getState();
            if (CuratorFrameworkState.STARTED.equals(state)) {
                client.close();
            }
        }
    }

}
