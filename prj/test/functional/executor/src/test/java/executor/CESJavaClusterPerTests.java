/*
 * Copyright (c) 2021, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * http://oss.oracle.com/licenses/upl.
 */
package executor;

import com.oracle.bedrock.junit.CoherenceClusterResource;

import com.oracle.bedrock.runtime.LocalPlatform;

import com.oracle.bedrock.runtime.coherence.CoherenceCluster;

import com.oracle.bedrock.runtime.coherence.options.CacheConfig;
import com.oracle.bedrock.runtime.coherence.options.ClusterName;
import com.oracle.bedrock.runtime.coherence.options.ClusterPort;
import com.oracle.bedrock.runtime.coherence.options.LocalHost;
import com.oracle.bedrock.runtime.coherence.options.LocalStorage;
import com.oracle.bedrock.runtime.coherence.options.Logging;
import com.oracle.bedrock.runtime.coherence.options.Multicast;
import com.oracle.bedrock.runtime.coherence.options.Pof;
import com.oracle.bedrock.runtime.coherence.options.RoleName;

import com.oracle.bedrock.runtime.java.features.JmxFeature;

import com.oracle.bedrock.runtime.java.options.SystemProperty;

import com.oracle.bedrock.runtime.options.DisplayName;

import executor.common.LogOutput;
import executor.common.NewClusterPerTest;

import java.io.File;
import org.junit.Rule;

import org.junit.experimental.categories.Category;

/**
 * Tests will spin up a new cluster for each test using java as the serialization
 * format.
 *
 * @author rl 7.29.2009
 * @since 21.12
 */
@Category(NewClusterPerTest.class)
public class CESJavaClusterPerTests
        extends AbstractCESClusterPerTests
    {
    // ----- constructors ---------------------------------------------------

    public CESJavaClusterPerTests()
        {
        super(EXTEND_CONFIG);
        }

    // ----- AbstractClusteredExecutorServiceTests --------------------------

    public CoherenceClusterResource getCoherence()
        {
        return m_coherence;
        }

    public String getLabel()
        {
        return this.getClass().getSimpleName() + File.separatorChar + COUNTER;
        }

    // ----- constants ------------------------------------------------------

    protected static final String CACHE_CONFIG = "coherence-executor-cache-config.xml";

    protected static final String EXTEND_CONFIG = "proxy-cache-config-default-serializer.xml";

    // ----- data members ---------------------------------------------------

    /**
     * The {@link CoherenceClusterResource} to establish a {@link CoherenceCluster} for testing.
     */
    @Rule
    public CoherenceClusterResource m_coherence =
            new CoherenceClusterResource()
                    .with(SystemProperty.of("tangosol.coherence.serializer", "java"),
                          Multicast.ttl(0),
                          LocalHost.only(),
                          Logging.at(9),
                          Pof.disabled(),
                          CacheConfig.of(CACHE_CONFIG),
                          ClusterPort.of(7574),
                          ClusterName.of(CESJavaSingleClusterTests.class.getSimpleName()), // default name is too long
                          SystemProperty.of("coherence.executor.extend.address", LocalPlatform.get().getLoopbackAddress().getHostAddress()),
                          SystemProperty.of("coherence.executor.extend.port", "9099"),
                          JmxFeature.enabled())
                    .include(STORAGE_ENABLED_MEMBER_COUNT,
                             DisplayName.of("CacheServer"),
                             LogOutput.to(getLabel(), "CacheServer"),
                             RoleName.of(STORAGE_ENABLED_MEMBER_ROLE),
                             LocalStorage.enabled(),
                             SystemProperty.of("coherence.executor.extend.enabled", false),
                             SystemProperty.of("coherence.executor.trace.logging", true))
                    .include(STORAGE_DISABLED_MEMBER_COUNT,
                             DisplayName.of("ComputeServer"),
                             LogOutput.to(getLabel(), "ComputeServer"),
                             RoleName.of(STORAGE_DISABLED_MEMBER_ROLE),
                             LocalStorage.disabled(),
                             SystemProperty.of("coherence.executor.extend.enabled", false),
                             SystemProperty.of("coherence.executor.trace.logging", true))
                    .include(PROXY_MEMBER_COUNT,
                             DisplayName.of("ProxyServer"),
                             LogOutput.to(getLabel(), "ProxyServer"),
                             RoleName.of(PROXY_MEMBER_ROLE),
                             LocalStorage.disabled(),
                             SystemProperty.of("coherence.executor.extend.enabled", true));
    }
