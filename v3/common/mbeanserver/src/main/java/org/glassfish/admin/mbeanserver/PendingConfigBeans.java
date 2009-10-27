/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.admin.mbeanserver;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.component.CageBuilder;
import org.jvnet.hk2.component.Inhabitant;
import org.jvnet.hk2.component.PostConstruct;
import org.jvnet.hk2.config.Dom;
import org.jvnet.hk2.config.ConfigBean;
import org.jvnet.hk2.config.ConfigBeanProxy;


import org.jvnet.hk2.config.TransactionListener;
import org.jvnet.hk2.config.Transactions;
import org.jvnet.hk2.config.UnprocessedChangeEvents;


import java.util.List;
import java.util.ArrayList;
import java.beans.PropertyChangeEvent;

/**
Called when ConfigBeans come into the habitat (see GlassfishConfigBean); a job queue
is maintained for processing by the AMXConfigLoader, which is lazily loaded.
 * @author llc
 */
@Service(name = "PendingConfigBeans")
public class PendingConfigBeans implements CageBuilder, PostConstruct, TransactionListener
{
    @Inject
    Transactions transactions;

    protected static void debug(final String s)
    {
        System.out.println(s);
    }

    private final LinkedBlockingQueue<PendingConfigBeanJob> mJobs = new LinkedBlockingQueue<PendingConfigBeanJob>();

    public int size()
    {
        return mJobs.size();
    }

    /**
    /**
    Singleton: there should be only one instance and hence a private constructor.
    But the framework using this wants to instantiate things with a public constructor.
     */
    public PendingConfigBeans()
    {
    }

    public void postConstruct()
    {
        transactions.addTransactionsListener(this);
    }


    public PendingConfigBeanJob take() throws InterruptedException
    {
        return mJobs.take();
    }

    public PendingConfigBeanJob peek() throws InterruptedException
    {
        return mJobs.peek();
    }
    
    /**
    @return a ConfigBean, or null if it's not a ConfigBean
     */
    final ConfigBean asConfigBean(final Object o)
    {
        return (o instanceof ConfigBean) ? ConfigBean.class.cast(o) : null;
    }

    public void onEntered(final Inhabitant<?> inhabitant)
    {
        //debug( "PendingConfigBeansNew.onEntered(): " + inhabitant);

        final ConfigBean cb = asConfigBean(inhabitant);
        if (cb != null)
        {
            add(cb);
        }
    }

    private PendingConfigBeanJob addJob(final PendingConfigBeanJob job)
    {
        if (job == null)
        {
            throw new IllegalArgumentException();
        }

        mJobs.add(job);
        return job;
    }

    private PendingConfigBeanJob add(final ConfigBean cb)
    {
        return add(cb, null);
    }

    public PendingConfigBeanJob add(final ConfigBean cb, final boolean useLatch)
    {
        final PendingConfigBeanJob job = add(cb, useLatch ? new CountDownLatch(1) : null);
        
        return job;
    }

    private PendingConfigBeanJob add(final ConfigBean cb, final CountDownLatch latch)
    {
        //debug( "PendingConfigBeans.add():  " + cb.getProxyType().getName() );
        
        // determine if the ConfigBean is a child of Domain by getting its most distant ancestor
        ConfigBean ancestor = cb;
        ConfigBean parent;
        while ( (parent = asConfigBean(ancestor.parent())) != null )
        {
            ancestor = parent;
        }
        //debug( "PendingConfigBeansNew.onEntered: " + cb.getProxyType().getName() + " with parent " + (parent == null ? "null" : parent.getProxyType().getName()) );
        
        PendingConfigBeanJob job = null;
        
        // ignore bogus ConfigBeans that are not part of the Domain
        if ( ancestor != null && ancestor.getProxyType().getName().endsWith( ".Domain" ))
        {
            //debug( "PendingConfigBeans.onEntered: " + cb.getProxyType().getName() );
            job = addJob(new PendingConfigBeanJob(cb, latch));
        }
        else
        {
            Util.getLogger().info("PendingConfigBeans.onEntered: ignoring ConfigBean that does not have Domain as ancestor: " + cb.getProxyType().getName());
            if ( latch != null )
            {
                latch.countDown();
                // job remains null
            }
        }
        
        return job;
    }

    /**
    Removing a ConfigBean must ensure that all its children are also removed.  This will normally
    happen if AMX is loaded as a side effect of unregistering MBeans, but if AMX has not loaded
    we must ensure it directly.
    This is all caused by an HK2 asymmetry that does not issue REMOVE events for children of removed
    elements.
    <p>
    TODO: remove all children of the ConfigBean.
     */
    public boolean remove(final ConfigBean cb)
    {
        //debug( "PendingConfigBeans.remove(): REMOVE: " + cb.getProxyType().getName() );
        boolean found = false;

        for (final PendingConfigBeanJob job : mJobs)
        {
            if (job.getConfigBean() == cb)
            {
                found = true;
                job.releaseLatch();
                mJobs.remove(job);
                break;
            }
        }

        if (found)
        {
            removeAllDescendants(cb);
        }
        return found;
    }

    /**
    Remove all jobs that have this ConfigBean as an ancestor.
     */
    private void removeAllDescendants(final ConfigBean cb)
    {
        final List<PendingConfigBeanJob> jobs = new ArrayList<PendingConfigBeanJob>(mJobs);

        for (final PendingConfigBeanJob job : jobs)
        {
            if (isDescendent(job.getConfigBean(), cb))
            {
                //debug( "removed descendent: " + job.getConfigBean().getProxyType().getName() );
                mJobs.remove(job);
            }
        }
    }

    /** return true if the candidate is a descendent of the parent */
    private boolean isDescendent(final ConfigBean candidate, final ConfigBean parent)
    {
        boolean isParent = false;
        Dom temp = candidate.parent();
        while (temp != null)
        {
            if (temp == parent)
            {
                isParent = true;
                break;
            }
            temp = temp.parent();
        }

        return isParent;
    }

    /**
    amx-impl has its own TransactionListener which takes over once AMX is loaded.
    Note that it is synchronized with transactionCommited() [sic] to avoid a race condition.
     */
    public synchronized void swapTransactionListener(final TransactionListener newListener)
    {
        //debug( "PendingConfigBeans.swapTransactionListener()" );
        transactions.addTransactionsListener(newListener);
        transactions.removeTransactionsListener(this);
    }

    /**
    This is a workaround for the fact that the onEntered() is not being called in all cases,
    namely during deployment before AMX has loaded.  See disableTransactionListener() above.
     */
    public synchronized void transactionCommited(final List<PropertyChangeEvent> events)
    {
        // could there be an add/remove/add/remove of the same thing?  Maintain the order just in case
        for (final PropertyChangeEvent event : events)
        {
            final Object oldValue = event.getOldValue();
            final Object newValue = event.getNewValue();
            final Object source = event.getSource();
            final String propertyName = event.getPropertyName();

            if (oldValue == null && newValue instanceof ConfigBeanProxy)
            {
                // ADD: a new ConfigBean was added
                final ConfigBean cb = asConfigBean(ConfigBean.unwrap((ConfigBeanProxy) newValue));
                add(cb);
            }
            else if (newValue == null && (oldValue instanceof ConfigBeanProxy))
            {
                // REMOVE
                final ConfigBean cb = asConfigBean(ConfigBean.unwrap((ConfigBeanProxy) oldValue));
                remove(cb);
            }
            else
            {
                // CHANGE can occur before ADD
                //final ConfigBean cb = asConfigBean( ConfigBean.unwrap( (ConfigBeanProxy)source) );
                //debug( "CHANGE: " +  cb.getProxyType().getName() + ": " + oldValue + " ===> " + newValue );
            }
        }
    }

    public void unprocessedTransactedEvents(List<UnprocessedChangeEvents> changes)
    {
        // ignore
    }

}






















