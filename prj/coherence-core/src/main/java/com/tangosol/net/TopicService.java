/*
 * Copyright (c) 2000, 2022, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.net;

import com.tangosol.net.topic.NamedTopic;

/**
 * A TopicService is a service providing Topics that can be used for
 * publish/subscribe functionality and message queue processing.
 */
public interface TopicService
        extends Service
    {

    /**
     * Obtain a Topic interface that provides
     * @param sName - the name, within this TopicService, that uniquely identifies a topic
     * @param loader  ClassLoader that should be used to deserialize messages
     *                sent to the topic by other members of the cluster;
     *                null is legal, and implies the default ClassLoader,
     *                which will typically be the context ClassLoader for
     *                this service
     * @return a NamedTopic interface which can be used to access the resources
     *         of the specified topic
     */
    NamedTopic ensureTopic(String sName, ClassLoader loader);

    /**
     * Release local resources associated with the specified instance of the
     * topic. This invalidates a reference obtained by using the
     * {@link #ensureTopic(String, ClassLoader)} method.
     * <p>
     * Releasing a topic reference to a topic makes the topic reference no longer
     * usable, but does not affect the topic itself. In other words, all other
     * references to the topic will still be valid, and the topic data is not
     * affected by releasing the reference.
     * <p>
     * The reference that is released using this method can no longer be used;
     * any attempt to use the reference will result in an exception.
     * <p>
     * The purpose for releasing a topic reference is to allow the topic
     * implementation to release the ClassLoader used to deserialize items
     * in the topic. The topic implementation ensures that all references to
     * that ClassLoader are released. This implies that objects in the topic
     * that were loaded by that ClassLoader will be re-serialized to release
     * their hold on that ClassLoader. The result is that the ClassLoader can
     * be garbage-collected by Java in situations where the topic is operating
     * in an application server and applications are dynamically loaded and
     * unloaded.
     *
     * @param topic  the topic object to be released
     *
     * @see NamedTopic#release()
     */
    void releaseTopic(NamedTopic topic);

    /**
     * Release and destroy the specified topic.
     * <p>
     * <b>Warning:</b> This method is used to completely destroy the specified
     * topic across the cluster. All references in the entire cluster to this
     * topic will be invalidated, the data will be cleared, and all
     * resources will be released.
     *
     * @param topic  the cache object to be released
     *
     * @see NamedTopic#destroy()
     */
    void destroyTopic(NamedTopic topic);

    // ----- constants ------------------------------------------------------

    /**
     * PagedTopic service type constant.
     * <p>
     * PagedTopic service provides globally ordered topics.
     *
     * @see Cluster#ensureService(String, String)
     */
    String TYPE_DEFAULT = "PagedTopic";
    }
