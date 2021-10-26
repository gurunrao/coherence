/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * http://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.concurrent.executor;

import com.tangosol.io.pof.PofReader;
import com.tangosol.io.pof.PofWriter;
import com.tangosol.io.pof.PortableObject;

import java.io.IOException;

/**
 * A portable {@link Task.Collector}; a convenience interface for implementations
 * having no properties to serialize.
 *
 * @param <T>  {@inheritDoc}
 * @param <A>  {@inheritDoc}
 * @param <R>  {@inheritDoc}
 *
 * @author lh
 * @since 21.12
 */
public interface PortableCollector<T, A, R>
        extends Task.Collector<T, A, R>, PortableObject
    {
    // ----- PortableObject interface ---------------------------------------

    @Override
    default void readExternal(PofReader in) throws IOException
        {
        }

    @Override
    default void writeExternal(PofWriter out) throws IOException
        {
        }
    }