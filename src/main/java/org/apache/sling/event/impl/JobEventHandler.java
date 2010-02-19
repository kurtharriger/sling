/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.event.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import javax.jcr.Item;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.observation.EventIterator;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.Services;
import org.apache.jackrabbit.util.ISO8601;
import org.apache.sling.commons.osgi.OsgiUtil;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.commons.threads.ThreadPool;
import org.apache.sling.event.EventPropertiesMap;
import org.apache.sling.event.EventUtil;
import org.apache.sling.event.JobStatusProvider;
import org.apache.sling.event.impl.job.JobBlockingQueue;
import org.apache.sling.event.impl.job.JobUtil;
import org.apache.sling.event.impl.job.ParallelInfo;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;


/**
 * An event handler for special job events.
 *
 * We schedule this event handler to run in the background and clean up
 * obsolete events.
 */
@Component(label="%job.events.name",
        description="%job.events.description",
        immediate=true,
        metatype=true)
@Services({
    @Service(value=JobStatusProvider.class),
    @Service(value=Runnable.class)
})
@Properties({
     @Property(name="event.topics",propertyPrivate=true,
               value={"org/osgi/framework/BundleEvent/UPDATED",
                      "org/osgi/framework/BundleEvent/STARTED",
                      EventUtil.TOPIC_JOB}),
     @Property(name="repository.path",value="/var/eventing/jobs",propertyPrivate=true),
     @Property(name="scheduler.period", longValue=300,label="%jobscheduler.period.name",description="%jobscheduler.period.description"),
     @Property(name="scheduler.concurrent", boolValue=false, propertyPrivate=true)
})
public class JobEventHandler
    extends AbstractRepositoryEventHandler
    implements EventUtil.JobStatusNotifier, JobStatusProvider, Runnable {

    /** A map for keeping track of currently processed job topics. */
    private final Map<String, Boolean> processingMap = new HashMap<String, Boolean>();

    /** A map for keeping track of currently processed parallel job topics. */
    private final Map<String, Integer> parallelProcessingMap = new HashMap<String, Integer>();

    /** A map for the different job queues. */
    private final Map<String, JobBlockingQueue> jobQueues = new HashMap<String, JobBlockingQueue>();

    /** Default sleep time. */
    private static final long DEFAULT_SLEEP_TIME = 30;

    /** Default number of job retries. */
    private static final int DEFAULT_MAX_JOB_RETRIES = 10;

    @Property(longValue=DEFAULT_SLEEP_TIME)
    private static final String CONFIG_PROPERTY_SLEEP_TIME = "sleep.time";

    @Property(intValue=DEFAULT_MAX_JOB_RETRIES)
    private static final String CONFIG_PROPERTY_MAX_JOB_RETRIES = "max.job.retries";

    /** Default number of seconds to wait for an ack. */
    private static final long DEFAULT_WAIT_FOR_ACK = 90; // by default we wait 90 secs

    /** Default nubmer of parallel jobs. */
    private static final long DEFAULT_MAXIMUM_PARALLEL_JOBS = 15;

    /** Default nubmer of job queues. */
    private static final int DEFAULT_MAXIMUM_JOB_QUEUES = 10;

    @Property(longValue=DEFAULT_MAXIMUM_PARALLEL_JOBS)
    private static final String CONFIG_PROPERTY_MAXIMUM_PARALLEL_JOBS = "max.parallel.jobs";

    @Property(longValue=DEFAULT_WAIT_FOR_ACK)
    private static final String CONFIG_PROPERTY_WAIT_FOR_ACK = "wait.for.ack";

    @Property(intValue=DEFAULT_MAXIMUM_JOB_QUEUES)
    private static final String CONFIG_PROPERTY_MAXIMUM_JOB_QUEUES = "max.job.queues";

    /** We check every 30 secs by default. */
    private long sleepTime;

    /** How often should a job be retried by default. */
    private int maxJobRetries;

    /** How long do we wait for an ack (in ms) */
    private long waitForAckMs;

    /** Maximum parallel running jobs for a single queue. */
    private long maximumParallelJobs;

    /** Background session. */
    protected Session backgroundSession;

    /** Unloaded jobs. */
    private Set<String>unloadedJobs = new HashSet<String>();

    /** List of deleted jobs. */
    private Set<String>deletedJobs = new HashSet<String>();

    /** Default clean up time is 5 minutes. */
    private static final int DEFAULT_CLEANUP_PERIOD = 5;

    @Property(intValue=DEFAULT_CLEANUP_PERIOD,label="%jobcleanup.period.name",description="%jobcleanup.period.description")
    private static final String CONFIG_PROPERTY_CLEANUP_PERIOD = "cleanup.period";

    /** We remove everything which is older than 5 min by default. */
    private int cleanupPeriod = DEFAULT_CLEANUP_PERIOD;

    /** The scheduler for rescheduling jobs.
     * @scr.reference */
    protected Scheduler scheduler;

    /** Our component context. */
    private ComponentContext componentContext;

    /** The map of events we're currently processing. */
    private final Map<String, StartedJobInfo> processingEventsList = new HashMap<String, StartedJobInfo>();

    public static volatile ThreadPool JOB_THREAD_POOL;

    /** Sync lock */
    private final Object writeLock = new Object();

    /** Sync lock */
    private final Object backgroundLock = new Object();

    /** Number of parallel jobs for the main queue. */
    private volatile long parallelJobCount;

    /** Number of jobs to load from the repository on startup in one go. */
    private long maxLoadJobs;

    /** Number of allowed job queues */
    private int maxJobQueues;

    /** Default maximum load jobs. */
    private static final long DEFAULT_MAXIMUM_LOAD_JOBS = 1000;

    @Property(longValue=DEFAULT_MAXIMUM_LOAD_JOBS)
    private static final String CONFIG_PROPERTY_MAX_LOAD_JOBS = "max.load.jobs";

    /** Threshold - if the queue is lower than this threshold the repository is checked for events. */
    private long loadThreshold;

    /** Default load threshold. */
    private static final long DEFAULT_LOAD_THRESHOLD = 400;

    @Property(longValue=DEFAULT_LOAD_THRESHOLD)
    private static final String CONFIG_PROPERTY_LOAD_THREASHOLD = "load.threshold";

    /** The background loader waits this time of seconds after startup before loading events from the repository. (in secs) */
    private long backgroundLoadDelay;

    /** Default background load delay. */
    private static final long DEFAULT_BACKGROUND_LOAD_DELAY = 30;

    @Property(longValue=DEFAULT_BACKGROUND_LOAD_DELAY)
    private static final String CONFIG_PROPERTY_BACKGROUND_LOAD_DELAY = "load.delay";

    /** The background loader waits this time of seconds between loads from the repository. (in secs) */
    private long backgroundCheckDelay;

    /** Default background check delay. */
    private static final long DEFAULT_BACKGROUND_CHECK_DELAY = 240;

    @Property(longValue=DEFAULT_BACKGROUND_CHECK_DELAY)
    private static final String CONFIG_PROPERTY_BACKGROUND_CHECK_DELAY = "load.checkdelay";

    /** Time when this service has been started. */
    private long startTime;

    /**
     * Activate this component.
     * @param context
     * @throws RepositoryException
     */
    protected void activate(final ComponentContext context)
    throws Exception {
        @SuppressWarnings("unchecked")
        final Dictionary<String, Object> props = context.getProperties();
        this.cleanupPeriod = OsgiUtil.toInteger(props.get(CONFIG_PROPERTY_CLEANUP_PERIOD), DEFAULT_CLEANUP_PERIOD);
        this.sleepTime = OsgiUtil.toLong(props.get(CONFIG_PROPERTY_SLEEP_TIME), DEFAULT_SLEEP_TIME);
        this.maxJobRetries = OsgiUtil.toInteger(props.get(CONFIG_PROPERTY_MAX_JOB_RETRIES), DEFAULT_MAX_JOB_RETRIES);
        this.waitForAckMs = OsgiUtil.toLong(props.get(CONFIG_PROPERTY_WAIT_FOR_ACK), DEFAULT_WAIT_FOR_ACK) * 1000;
        this.maximumParallelJobs = OsgiUtil.toLong(props.get(CONFIG_PROPERTY_MAXIMUM_PARALLEL_JOBS), DEFAULT_MAXIMUM_PARALLEL_JOBS);
        this.maxLoadJobs = OsgiUtil.toLong(props.get(CONFIG_PROPERTY_MAX_LOAD_JOBS), DEFAULT_MAXIMUM_LOAD_JOBS);
        this.loadThreshold = OsgiUtil.toLong(props.get(CONFIG_PROPERTY_LOAD_THREASHOLD), DEFAULT_LOAD_THRESHOLD);
        this.backgroundLoadDelay = OsgiUtil.toLong(props.get(CONFIG_PROPERTY_BACKGROUND_LOAD_DELAY), DEFAULT_BACKGROUND_LOAD_DELAY);
        this.backgroundCheckDelay = OsgiUtil.toLong(props.get(CONFIG_PROPERTY_BACKGROUND_CHECK_DELAY), DEFAULT_BACKGROUND_CHECK_DELAY);
        this.maxJobQueues = OsgiUtil.toInteger(props.get(CONFIG_PROPERTY_MAXIMUM_JOB_QUEUES), DEFAULT_MAXIMUM_JOB_QUEUES);
        this.componentContext = context;
        super.activate(context);
        JOB_THREAD_POOL = this.threadPool;
        this.startTime = System.currentTimeMillis();
        // start background thread which loads jobs from the repository
        this.threadPool.execute(new Runnable() {
            public void run() {
                loadJobsInTheBackground();
            }
        });
    }

    /**
     * @see org.apache.sling.event.impl.AbstractRepositoryEventHandler#deactivate(org.osgi.service.component.ComponentContext)
     */
    protected void deactivate(final ComponentContext context) {
        super.deactivate(context);
        synchronized ( this.jobQueues ) {
            final Iterator<JobBlockingQueue> i = this.jobQueues.values().iterator();
            while ( i.hasNext() ) {
                final JobBlockingQueue jbq = i.next();
                this.logger.debug("Shutting down job queue {}", jbq.getName());
                this.logger.debug("Waking up sleeping queue {}", jbq.getName());
                this.wakeUpJobQueue(jbq);
                // wake up qeue
                if ( jbq.isWaiting() ) {
                    this.logger.debug("Waking up waiting queue {}", jbq.getName());
                    synchronized ( jbq.getLock()) {
                        jbq.notifyFinish(null);
                    }
                }
                // continue queue processing to stop the queue
                try {
                    jbq.put(new EventInfo());
                } catch (InterruptedException e) {
                    this.ignoreException(e);
                }
                this.logger.info("Stopped job queue {}", jbq.getName());
            }
        }
        if ( this.backgroundSession != null ) {
            synchronized ( this.backgroundLock ) {
                this.logger.debug("Shutting down background session.");
                // notify possibly sleeping thread
                this.backgroundLock.notify();
                try {
                    this.backgroundSession.getWorkspace().getObservationManager().removeEventListener(this);
                } catch (RepositoryException e) {
                    // we just ignore it
                    this.logger.warn("Unable to remove event listener.", e);
                }
                this.backgroundSession.logout();
                this.backgroundSession = null;
            }
        }
        this.componentContext = null;
        if ( JOB_THREAD_POOL == this.threadPool ) {
            JOB_THREAD_POOL = null;
        }
    }

    /**
     * Return the query string for the clean up.
     */
    private String getCleanUpQueryString() {
        final Calendar deleteBefore = Calendar.getInstance();
        deleteBefore.add(Calendar.MINUTE, -this.cleanupPeriod);
        final String dateString = ISO8601.format(deleteBefore);

        final StringBuilder buffer = new StringBuilder("/jcr:root");
        buffer.append(this.repositoryPath);
        buffer.append("//element(*, ");
        buffer.append(getEventNodeType());
        buffer.append(")[@");
        buffer.append(EventHelper.NODE_PROPERTY_FINISHED);
        buffer.append(" < xs:dateTime('");
        buffer.append(dateString);
        buffer.append("')]");

        return buffer.toString();
    }

    private void loadJobsInTheBackground() {
        // give the system some time to start
        try {
            Thread.sleep(1000 * this.backgroundLoadDelay); // default is 30 seconds
        } catch (InterruptedException e) {
            this.ignoreException(e);
        }
        // are we still running?
        if ( this.running ) {
            logger.debug("Starting background loading.");
            long loadSince = -1;
            do {
                loadSince = this.loadJobs(loadSince);
                if ( this.running && loadSince > -1 ) {
                    do {
                        try {
                            Thread.sleep(1000 * this.backgroundCheckDelay); // default is 240 seconds
                        } catch (InterruptedException e) {
                            this.ignoreException(e);
                        }
                    } while ( this.running && this.queue.size() > this.loadThreshold );
                }
            } while (this.running && loadSince > -1);
            logger.debug("Finished background loading.");
        }
    }

    /**
     * This method is invoked periodically.
     * @see java.lang.Runnable#run()
     */
    public void run() {
        if ( this.running ) {
            // check for jobs that were started but never got an aknowledge
            final long tooOld = System.currentTimeMillis() - this.waitForAckMs;
            // to keep the synchronized block as fast as possible we just store the
            // jobs to be removed in a new list and process this list afterwards
            final List<StartedJobInfo> restartJobs = new ArrayList<StartedJobInfo>();
            synchronized ( this.processingEventsList ) {
                final Iterator<Map.Entry<String, StartedJobInfo>> i = this.processingEventsList.entrySet().iterator();
                while ( i.hasNext() ) {
                    final Map.Entry<String, StartedJobInfo> entry = i.next();
                    if ( entry.getValue().started <= tooOld ) {
                        restartJobs.add(entry.getValue());
                    }
                }
            }
            // remove obsolete jobs from the repository
            if ( this.cleanupPeriod > 0 ) {
                this.logger.debug("Cleaning up repository, removing all finished jobs older than {} minutes.", this.cleanupPeriod);

                final String queryString = this.getCleanUpQueryString();
                // we create an own session for concurrency issues
                Session s = null;
                try {
                    s = this.createSession();
                    final Node parentNode = (Node)s.getItem(this.repositoryPath);
                    logger.debug("Executing query {}", queryString);
                    final Query q = s.getWorkspace().getQueryManager().createQuery(queryString, Query.XPATH);
                    final NodeIterator iter = q.execute().getNodes();
                    int count = 0;
                    while ( iter.hasNext() ) {
                        final Node eventNode = iter.nextNode();
                        eventNode.remove();
                        count++;
                    }
                    parentNode.save();
                    logger.debug("Removed {} entries from the repository.", count);

                } catch (RepositoryException e) {
                    // in the case of an error, we just log this as a warning
                    this.logger.warn("Exception during repository cleanup.", e);
                } finally {
                    if ( s != null ) {
                        s.logout();
                    }
                }
            }
            // restart jobs is now a list of potential candidates, we now have to check
            // each candidate separately again!
            if ( restartJobs.size() > 0 ) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    // we just ignore this
                    this.ignoreException(e);
                }
            }
            final Iterator<StartedJobInfo> jobIter = restartJobs.iterator();
            while ( jobIter.hasNext() ) {
                final StartedJobInfo info = jobIter.next();
                boolean process = false;
                synchronized ( this.processingEventsList ) {
                    process = this.processingEventsList.remove(info.nodePath) != null;
                }
                if ( process ) {
                    this.logger.info("No acknowledge received for job {} stored at {}. Requeueing job.", EventUtil.toString(info.event), info.nodePath);
                    this.finishedJob(info.event, info.nodePath, true);
                }
            }

            // check for idle threads
            synchronized ( this.jobQueues ) {
                final Iterator<Map.Entry<String, JobBlockingQueue>> i = this.jobQueues.entrySet().iterator();
                while ( i.hasNext() ) {
                    final Map.Entry<String, JobBlockingQueue> current = i.next();
                    final JobBlockingQueue jbq = current.getValue();
                    if ( jbq.size() == 0 ) {
                        if ( jbq.isMarkedForCleanUp() ) {
                            // set to finished
                            jbq.setFinished(true);
                            // wake up
                            try {
                                jbq.put(new EventInfo());
                            } catch (InterruptedException e) {
                                this.ignoreException(e);
                            }
                            // remove
                            i.remove();
                        } else {
                            // mark to be removed during next cycle
                            jbq.markForCleanUp();
                        }
                    }
                }
            }
        }
    }

    /**
     * @see org.apache.sling.event.impl.AbstractRepositoryEventHandler#processWriteQueue()
     */
    protected void processWriteQueue() {
        while ( this.running ) {
            // so let's wait/get the next job from the queue
            Event event = null;
            try {
                event = this.writeQueue.take();
            } catch (InterruptedException e) {
                // we ignore this
                this.ignoreException(e);
            }
            if ( event != null && this.running ) {
                if ( logger.isDebugEnabled() ) {
                    logger.debug("Persisting job {}", EventUtil.toString(event));
                }
                final EventInfo info = new EventInfo();
                info.event = event;
                final String jobId = (String)event.getProperty(EventUtil.PROPERTY_JOB_ID);
                final String jobTopic = (String)event.getProperty(EventUtil.PROPERTY_JOB_TOPIC);
                final String nodePath = JobUtil.getUniquePath(jobTopic, jobId);

                // if the job has no job id, we can just write the job to the repo and don't
                // need locking
                if ( jobId == null ) {
                    try {
                        synchronized ( this.writeLock ) {
                            final Node eventNode = this.writeEvent(event, nodePath);
                            info.nodePath = eventNode.getPath();
                        }
                    } catch (RepositoryException re ) {
                        // something went wrong, so let's log it
                        this.logger.error("Exception during writing new job '" + EventUtil.toString(event) + "' to repository at " + nodePath, re);
                    }
                } else {
                    synchronized ( this.writeLock ) {
                        try {
                            this.writerSession.refresh(false);
                        } catch (RepositoryException re) {
                            // we just ignore this
                            this.ignoreException(re);
                        }
                        try {
                            // let's first search for an existing node with the same id
                            final Node parentNode = this.getWriterRootNode();
                            Node foundNode = null;
                            if ( parentNode.hasNode(nodePath) ) {
                                foundNode = parentNode.getNode(nodePath);
                            }
                            if ( foundNode != null ) {
                                // if the node is locked, someone else was quicker
                                // and we don't have to process this job
                                if ( !foundNode.isLocked() ) {
                                    // node is already in repository, so if not finished we just use it
                                    // otherwise it has already been processed
                                    try {
                                        if ( !foundNode.hasProperty(EventHelper.NODE_PROPERTY_FINISHED) ) {
                                            info.nodePath = foundNode.getPath();
                                        }
                                    } catch (RepositoryException re) {
                                        // if anything goes wrong, it means that (hopefully) someone
                                        // else is processing this node
                                    }
                                }
                            } else {
                                // We now write the event into the repository
                                try {
                                    final Node eventNode = this.writeEvent(event, nodePath);
                                    info.nodePath = eventNode.getPath();
                                } catch (ItemExistsException iee) {
                                    // someone else did already write this node in the meantime
                                    // nothing to do for us
                                }
                            }
                        } catch (RepositoryException re ) {
                            // something went wrong, so let's log it
                            this.logger.error("Exception during writing new job '" + EventUtil.toString(event) + "' to repository at " + nodePath, re);
                        }
                    }
                }
                // if we were able to write the event into the repository
                // we will queue it for processing
                if ( info.nodePath != null ) {
                    this.queueJob(info);
                }
            }
        }
    }

    /**
     * Put the job into the correct queue.
     */
    private void queueJob(final EventInfo info) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("Received new job {}", EventUtil.toString(info.event));
        }
        // check for local only jobs and remove them from the queue if they're meant
        // for another application node
        final String appId = (String)info.event.getProperty(EventUtil.PROPERTY_APPLICATION);
        if ( info.event.getProperty(EventUtil.PROPERTY_JOB_RUN_LOCAL) != null
            && appId != null && !this.applicationId.equals(appId) ) {
            if ( logger.isDebugEnabled() ) {
                 logger.debug("Discarding job {} : local job for a different application node.", EventUtil.toString(info.event));
            }
        } else {

            // check if we should put this into a separate queue
            boolean queued = false;
            if ( info.event.getProperty(EventUtil.PROPERTY_JOB_QUEUE_NAME) != null ) {
                final String queueName = (String)info.event.getProperty(EventUtil.PROPERTY_JOB_QUEUE_NAME);
                synchronized ( this.jobQueues ) {
                    BlockingQueue<EventInfo> jobQueue = this.jobQueues.get(queueName);
                    if ( jobQueue == null ) {
                        // check if we have exceeded the maximum number of job queues
                        if ( this.jobQueues.size() >= this.maxJobQueues ) {
                            this.logger.warn("Unable to create new job queue named {} as there are already {} job queues." +
                                    " Try to increase the maximum number of job queues!", queueName, this.jobQueues.size());
                        } else {
                            final boolean orderedQueue = info.event.getProperty(EventUtil.PROPERTY_JOB_QUEUE_ORDERED) != null;
                            final JobBlockingQueue jq = new JobBlockingQueue(queueName, orderedQueue, this.logger);
                            jobQueue = jq;
                            this.jobQueues.put(queueName, jq);
                            // Start background thread
                            this.threadPool.execute(new Runnable() {

                                /**
                                 * @see java.lang.Runnable#run()
                                 */
                                public void run() {
                                    while ( running && !jq.isFinished() ) {
                                        logger.info("Starting {}job queue {}", (orderedQueue ? "ordered " : ""), queueName);
                                        try {
                                            runJobQueue(queueName, jq);
                                        } catch (Throwable t) {
                                            logger.error("Job queue stopped with exception: " + t.getMessage() + ". Restarting.", t);
                                        }
                                    }
                                }

                            });
                        }
                    }
                    if ( jobQueue != null ) {
                        if ( logger.isDebugEnabled() ) {
                            logger.debug("Queuing job {} into queue {}.", EventUtil.toString(info.event), queueName);
                        }
                        try {
                            jobQueue.put(info);
                        } catch (InterruptedException e) {
                            // this should never happen
                            this.ignoreException(e);
                        }
                        // don't put into main queue
                        queued = true;
                    }
                }
            }
            if ( !queued ) {
                try {
                    this.queue.put(info);
                } catch (InterruptedException e) {
                    // this should never happen
                    this.ignoreException(e);
                }
            }
        }
    }

    /**
     * This method runs in the background and processes the local queue.
     */
    protected void runInBackground() throws RepositoryException {
        // create the background session and register a listener
        this.backgroundSession = this.createSession();
        this.backgroundSession.getWorkspace().getObservationManager()
                .addEventListener(this,
                                  javax.jcr.observation.Event.PROPERTY_REMOVED
                                    |javax.jcr.observation.Event.NODE_REMOVED,
                                  this.repositoryPath,
                                  true,
                                  null,
                                  new String[] {this.getEventNodeType()},
                                  true);
        if ( this.running ) {
            logger.info("Apache Sling Job Event Handler started.");
            logger.debug("Job Handler Configuration: (sleepTime={} secs, maxJobRetries={}," +
                    " waitForAck={} ms, maximumParallelJobs={}, cleanupPeriod={} min, maxJobQueues={})",
                    new Object[] {sleepTime, maxJobRetries,waitForAckMs,maximumParallelJobs,cleanupPeriod,maxJobQueues});
        } else {
            final ComponentContext ctx = this.componentContext;
            // deactivate
            if ( ctx != null ) {
                logger.info("Deactivating component {} due to errors during startup.", ctx.getProperties().get(Constants.SERVICE_ID));
                final String name = (String) componentContext.getProperties().get(
                    ComponentConstants.COMPONENT_NAME);
                ctx.disableComponent(name);
            }
        }
        // This is the main queue
        while ( this.running ) {
            // so let's wait/get the next job from the queue
            EventInfo info = null;
            try {
                info = this.queue.take();
            } catch (InterruptedException e) {
                // we ignore this
                this.ignoreException(e);
            }

            if ( info != null && this.running ) {
                if ( this.executeJob(info, null) == Status.RESCHEDULE ) {
                    this.putBackIntoMainQueue(info, true);
                }
            }
        }
    }

    /**
     * Execute a job queue
     * @param queueName The name of the job queue
     * @param jobQueue The job queue
     */
    private void runJobQueue(final String queueName, final JobBlockingQueue jobQueue) {
        EventInfo info = null;
        while ( this.running && !jobQueue.isFinished() ) {
            if ( info == null ) {
                // so let's wait/get the next job from the queue
                try {
                    info = jobQueue.take();
                } catch (InterruptedException e) {
                    // we ignore this
                    this.ignoreException(e);
                }
            }

            if ( info != null && this.running && !jobQueue.isFinished() ) {
                final EventInfo processInfo = info;
                info = null;
                if ( jobQueue.isOrdered() ) {
                    // if we are ordered we simply wait for the finish
                    synchronized ( jobQueue.getLock()) {
                        final Status status = this.executeJob(processInfo, jobQueue);
                        if ( status == Status.SUCCESS ) {
                            try {
                                info = jobQueue.waitForFinish();
                            } catch (InterruptedException e) {
                                this.ignoreException(e);
                            }
                        } else if ( status == Status.RESCHEDULE ) {
                            info = jobQueue.reschedule(processInfo, this.scheduler);
                        }
                    }
                } else {
                    final int maxJobs = ParallelInfo.getMaxNumberOfParallelJobs(processInfo.event);
                    synchronized ( jobQueue.getLock() ) {
                        try {
                            jobQueue.acquireSlot(maxJobs);
                        } catch (InterruptedException e) {
                            this.ignoreException(e);
                        }
                    }
                    if ( this.running && !jobQueue.isFinished() ) {
                        final Status status = this.executeJob(processInfo, jobQueue);
                        if ( status == Status.RESCHEDULE ) {
                            jobQueue.reschedule(processInfo, this.scheduler);
                        }
                    }
                }
            }
        }
    }

    /**
     * Check the precondition for the job.
     * This method handles the parallel settings and returns <code>true</code>
     * if the job can be run. If <code>false</code> is returned, we have to
     * wait for another job to finish first.
     */
    private boolean checkPrecondition(final ParallelInfo parInfo, final String jobTopic) {
        // check how we can process this job:
        // if the job should not be processed in parallel, we have to check
        //     if another job with the same topic is currently running
        // if parallel processing is allowed, we have to check for the number
        //     of max allowed parallel jobs for this topic
        boolean process = parInfo.processParallel;
        if ( !parInfo.processParallel ) {
            synchronized ( this.processingMap ) {
                final Boolean value = this.processingMap.get(jobTopic);
                if ( value == null || !value.booleanValue() ) {
                    this.processingMap.put(jobTopic, Boolean.TRUE);
                    process = true;
                }
            }
        } else {
            if ( parInfo.maxParallelJob > 1 ) {
                synchronized ( this.parallelProcessingMap ) {
                    final Integer value = this.parallelProcessingMap.get(jobTopic);
                    final int currentValue = (value == null ? 0 : value.intValue());
                    if ( currentValue < parInfo.maxParallelJob ) {
                        this.parallelProcessingMap.put(jobTopic, currentValue + 1);
                    } else {
                        process = false;
                    }
                }
            }
        }
        return process;
    }

    /**
     * Unlock the parallel job processing state.
     */
    private void unlockState(final ParallelInfo parInfo, final String jobTopic) {
        if ( !parInfo.processParallel ) {
            synchronized ( this.processingMap ) {
                this.processingMap.put(jobTopic, Boolean.FALSE);
            }
        } else {
            if ( parInfo.maxParallelJob > 1 ) {
                synchronized ( this.parallelProcessingMap ) {
                    final Integer value = this.parallelProcessingMap.get(jobTopic);
                    this.parallelProcessingMap.put(jobTopic, value.intValue() - 1);
                }
            }
        }
    }

    public enum Status {
        FAILED,
        RESCHEDULE,
        SUCCESS
    };

    /**
     * Process a job
     */
    private Status executeJob(final EventInfo info, final BlockingQueue<EventInfo> jobQueue) {
        boolean putback = false;
        synchronized (this.backgroundLock) {
            if ( logger.isDebugEnabled() ) {
                logger.debug("Executing job {}.", EventUtil.toString(info.event));
            }
            try {
                this.backgroundSession.refresh(false);
                // check if the node still exists
                if ( this.backgroundSession.itemExists(info.nodePath)
                     && !this.backgroundSession.itemExists(info.nodePath + '/' + EventHelper.NODE_PROPERTY_FINISHED)) {
                    final Event event = info.event;
                    final String jobTopic = (String)event.getProperty(EventUtil.PROPERTY_JOB_TOPIC);
                    final ParallelInfo parInfo = ParallelInfo.getParallelInfo(event);

                    final boolean process = checkPrecondition(parInfo, jobTopic);
                    // check number of parallel jobs for main queue
                    if ( process && jobQueue == null && this.parallelJobCount >= this.maximumParallelJobs ) {
                        if ( logger.isDebugEnabled() ) {
                            logger.debug("Waiting with executing job {} - maximum parallel job count of {} reached!",
                                    EventUtil.toString(info.event), this.maximumParallelJobs);
                        }
                        try {
                            this.backgroundLock.wait();
                        } catch (InterruptedException e) {
                            this.ignoreException(e);
                        }
                        // let's check if we're still running
                        if ( !this.running ) {
                            return Status.FAILED;
                        }
                        if ( logger.isDebugEnabled() ) {
                            logger.debug("Continuing with executing job {}.", EventUtil.toString(info.event));
                        }
                    }
                    if ( process ) {
                        boolean unlock = true;
                        try {
                            final Node eventNode = (Node) this.backgroundSession.getItem(info.nodePath);
                            if ( !eventNode.isLocked() ) {
                                // lock node
                                try {
                                    eventNode.lock(false, true);
                                } catch (RepositoryException re) {
                                    // lock failed which means that the node is locked by someone else, so we don't have to requeue
                                    return Status.FAILED;
                                }
                                unlock = false;
                                this.processJob(info.event, eventNode, jobQueue == null, parInfo);
                                return Status.SUCCESS;
                            }
                        } catch (RepositoryException e) {
                            // ignore
                            this.ignoreException(e);
                        } finally {
                            if ( unlock ) {
                                unlockState(parInfo, jobTopic);
                            }
                        }
                    } else {
                        try {
                            // check if the node is in processing or already finished
                            final Node eventNode = (Node) this.backgroundSession.getItem(info.nodePath);
                            if ( !eventNode.isLocked() && !eventNode.hasProperty(EventHelper.NODE_PROPERTY_FINISHED)) {
                                putback = true;
                            }
                        } catch (RepositoryException e) {
                            // ignore
                            this.ignoreException(e);
                        }
                    }
                }
            } catch (RepositoryException re) {
                this.ignoreException(re);
            }

        }
        // if we have to put back the job, we return this status
        if ( putback ) {
            return Status.RESCHEDULE;
        }
        return Status.FAILED;
    }

    /**
     * @see org.apache.sling.engine.event.impl.JobPersistenceHandler#getEventNodeType()
     */
    protected String getEventNodeType() {
        return EventHelper.JOB_NODE_TYPE;
    }

    /**
     * @see org.osgi.service.event.EventHandler#handleEvent(org.osgi.service.event.Event)
     */
    public void handleEvent(final Event event) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("Receiving event {}", EventUtil.toString(event));
        }
        // we ignore remote job events
        if ( EventUtil.isLocal(event) ) {
            // check for bundle event
            if ( event.getTopic().equals(EventUtil.TOPIC_JOB)) {
                if ( logger.isDebugEnabled() ) {
                    logger.debug("Handling local job {}", EventUtil.toString(event));
                }
                // job event
                final String jobTopic = (String)event.getProperty(EventUtil.PROPERTY_JOB_TOPIC);

                //  job topic must be set, otherwise we ignore this event!
                if ( jobTopic != null ) {
                    // queue the event in order to respond quickly
                    try {
                        this.writeQueue.put(event);
                    } catch (InterruptedException e) {
                        // this should never happen
                        this.ignoreException(e);
                    }
                } else {
                    this.logger.warn("Event does not contain job topic: {}", EventUtil.toString(event));
                }

            } else {
                // bundle event started or updated
                boolean doIt = false;
                synchronized ( this.unloadedJobs ) {
                    if ( this.unloadedJobs.size() > 0 ) {
                        doIt = true;
                    }
                }
                if ( doIt ) {
                    final Runnable t = new Runnable() {

                        public void run() {
                            synchronized (unloadedJobs) {
                                Session s = null;
                                final Set<String> newUnloadedJobs = new HashSet<String>();
                                newUnloadedJobs.addAll(unloadedJobs);
                                try {
                                    s = createSession();
                                    for(String path : unloadedJobs ) {
                                        newUnloadedJobs.remove(path);
                                        try {
                                            if ( s.itemExists(path) ) {
                                                final Node eventNode = (Node) s.getItem(path);
                                                tryToLoadJob(eventNode, newUnloadedJobs);
                                            }
                                        } catch (RepositoryException re) {
                                            // we ignore this and readd
                                            newUnloadedJobs.add(path);
                                            ignoreException(re);
                                        }
                                    }
                                } catch (RepositoryException re) {
                                    // unable to create session, so we try it again next time
                                    ignoreException(re);
                                } finally {
                                    if ( s != null ) {
                                        s.logout();
                                    }
                                    unloadedJobs.clear();
                                    unloadedJobs.addAll(newUnloadedJobs);
                                }
                            }
                        }

                    };
                    this.threadPool.execute(t);
                }
            }
        }
    }

    /**
     * Process a job and unlock the node in the repository.
     * @param event The original event.
     * @param eventNode The node in the repository where the job is stored.
     * @param isMainQueue Is this the main queue?
     */
    private void processJob(final Event event,
                            final Node eventNode,
                            final boolean isMainQueue,
                            final ParallelInfo parInfo)  {
        final String jobTopic = (String)event.getProperty(EventUtil.PROPERTY_JOB_TOPIC);
        if ( logger.isDebugEnabled() ) {
            logger.debug("Starting job {}", EventUtil.toString(event));
        }
        boolean unlock = true;
        try {
            if ( isMainQueue ) {
                this.parallelJobCount++;
            }
            final String nodePath = eventNode.getPath();
            final Event jobEvent = this.getJobEvent(event, nodePath);
            eventNode.setProperty(EventHelper.NODE_PROPERTY_PROCESSOR, this.applicationId);
            eventNode.save();
            final EventAdmin localEA = this.eventAdmin;
            if ( localEA != null ) {
                final StartedJobInfo jobInfo = new StartedJobInfo(jobEvent, nodePath, System.currentTimeMillis());
                // let's add the event to our processing list
                synchronized ( this.processingEventsList ) {
                    this.processingEventsList.put(nodePath, jobInfo);
                }

                // we need async delivery, otherwise we might create a deadlock
                // as this method runs inside a synchronized block and the finishedJob
                // method as well!
                localEA.postEvent(jobEvent);
                // do not unlock if sending was successful
                unlock = false;
            } else {
                this.logger.error("Job event can't be sent as no event admin is available.");
            }
        } catch (RepositoryException re) {
            // if an exception occurs, we just log
            this.logger.error("Exception during job processing.", re);
        } finally {
            if ( unlock ) {
                if ( isMainQueue ) {
                    this.parallelJobCount--;
                }
                this.unlockState(parInfo, jobTopic);

                // unlock node
                try {
                    eventNode.unlock();
                } catch (RepositoryException e) {
                    // if unlock fails, we silently ignore this
                    this.ignoreException(e);
                }
            }
        }
    }

    /**
     * Create the real job event.
     * This generates a new event object with the same properties, but with the
     * {@link EventUtil#PROPERTY_JOB_TOPIC} topic.
     * @param e The job event.
     * @return The real job event.
     */
    private Event getJobEvent(Event e, String nodePath) {
        final String eventTopic = (String)e.getProperty(EventUtil.PROPERTY_JOB_TOPIC);
        final Dictionary<String, Object> properties = new EventPropertiesMap(e);
        // put properties for finished job callback
        properties.put(EventUtil.JobStatusNotifier.CONTEXT_PROPERTY_NAME,
                new EventUtil.JobStatusNotifier.NotifierContext(this, nodePath));
        return new Event(eventTopic, properties);
    }

    /**
     * @see org.apache.sling.engine.event.impl.JobPersistenceHandler#addNodeProperties(javax.jcr.Node, org.osgi.service.event.Event)
     */
    protected void addNodeProperties(Node eventNode, Event event)
    throws RepositoryException {
        super.addNodeProperties(eventNode, event);
        eventNode.setProperty(EventHelper.NODE_PROPERTY_TOPIC, (String)event.getProperty(EventUtil.PROPERTY_JOB_TOPIC));
        final String jobId = (String)event.getProperty(EventUtil.PROPERTY_JOB_ID);
        if ( jobId != null ) {
            eventNode.setProperty(EventHelper.NODE_PROPERTY_JOBID, jobId);
        }
        final long retryCount = OsgiUtil.toLong(event.getProperty(EventUtil.PROPERTY_JOB_RETRY_COUNT), 0);
        final long retries = OsgiUtil.toLong(event.getProperty(EventUtil.PROPERTY_JOB_RETRIES), this.maxJobRetries);
        eventNode.setProperty(EventUtil.PROPERTY_JOB_RETRY_COUNT, retryCount);
        eventNode.setProperty(EventUtil.PROPERTY_JOB_RETRIES, retries);
    }

    /**
     * @see org.apache.sling.event.impl.AbstractRepositoryEventHandler#addEventProperties(javax.jcr.Node, java.util.Dictionary)
     */
    protected void addEventProperties(Node eventNode,
                                      Dictionary<String, Object> properties)
    throws RepositoryException {
        super.addEventProperties(eventNode, properties);
        // convert to integers (jcr only supports long)
        if ( properties.get(EventUtil.PROPERTY_JOB_RETRIES) != null ) {
            properties.put(EventUtil.PROPERTY_JOB_RETRIES, Integer.valueOf(properties.get(EventUtil.PROPERTY_JOB_RETRIES).toString()));
        }
        if ( properties.get(EventUtil.PROPERTY_JOB_RETRY_COUNT) != null ) {
            properties.put(EventUtil.PROPERTY_JOB_RETRY_COUNT, Integer.valueOf(properties.get(EventUtil.PROPERTY_JOB_RETRY_COUNT).toString()));
        }
        // add application id
        properties.put(EventUtil.PROPERTY_APPLICATION, eventNode.getProperty(EventHelper.NODE_PROPERTY_APPLICATION).getString());
    }

    /**
     * @see javax.jcr.observation.EventListener#onEvent(javax.jcr.observation.EventIterator)
     */
    public void onEvent(EventIterator iter) {
        // we create an own session here - this is done lazy
        Session s = null;
        try {
            while ( iter.hasNext() ) {
                final javax.jcr.observation.Event event = iter.nextEvent();
                if ( event.getType() == javax.jcr.observation.Event.PROPERTY_CHANGED
                   || event.getType() == javax.jcr.observation.Event.PROPERTY_REMOVED) {
                    try {
                        final String propPath = event.getPath();
                        int pos = propPath.lastIndexOf('/');
                        final String nodePath = propPath.substring(0, pos);
                        final String propertyName = propPath.substring(pos+1);

                        // we are only interested in unlocks
                        if ( "jcr:lockOwner".equals(propertyName) ) {
                            boolean doNotProcess = false;
                            synchronized ( this.deletedJobs ) {
                                doNotProcess = this.deletedJobs.remove(nodePath);
                            }
                            if ( !doNotProcess ) {
                                if ( s == null ) {
                                    s = this.createSession();
                                }
                                final Node eventNode = (Node) s.getItem(nodePath);
                                tryToLoadJob(eventNode, this.unloadedJobs);
                            }
                        }
                    } catch (RepositoryException re) {
                        this.logger.error("Exception during jcr event processing.", re);
                    }
                }
            }
        } finally {
            if ( s != null ) {
                s.logout();
            }
        }
    }

    /**
     * Load all active jobs from the repository.
     * @throws RepositoryException
     */
    private long loadJobs(final long since) {
        long eventCreated = since;
        final long maxLoad = (since == -1 ? this.maxLoadJobs : this.maxLoadJobs - this.queue.size());
        // sanity check
        if ( maxLoad > 0 ) {
            logger.debug("Loading from repository since {} and max {}", since, maxLoad);
            try {
                final QueryManager qManager = this.backgroundSession.getWorkspace().getQueryManager();
                final StringBuilder buffer = new StringBuilder("/jcr:root");
                buffer.append(this.repositoryPath);
                buffer.append("//element(*, ");
                buffer.append(this.getEventNodeType());
                buffer.append(") [not(@");
                buffer.append(EventHelper.NODE_PROPERTY_FINISHED);
                buffer.append(")");
                if ( since != -1 ) {
                    final Calendar beforeDate = Calendar.getInstance();
                    beforeDate.setTimeInMillis(since);
                    final String dateString = ISO8601.format(beforeDate);
                    buffer.append(" and @");
                    buffer.append(EventHelper.NODE_PROPERTY_CREATED);
                    buffer.append(" >= xs:dateTime('");
                    buffer.append(dateString);
                    buffer.append("')");
                }
                final Calendar startDate = Calendar.getInstance();
                startDate.setTimeInMillis(this.startTime);
                final String dateString = ISO8601.format(startDate);
                buffer.append(" and @");
                buffer.append(EventHelper.NODE_PROPERTY_CREATED);
                buffer.append(" < xs:dateTime('");
                buffer.append(dateString);
                buffer.append("')");
                buffer.append("] order by @");
                buffer.append(EventHelper.NODE_PROPERTY_CREATED);
                buffer.append(" ascending");
                final Query q = qManager.createQuery(buffer.toString(), Query.XPATH);
                final NodeIterator result = q.execute().getNodes();
                long count = 0;
                while ( result.hasNext() && count < maxLoad ) {
                    final Node eventNode = result.nextNode();
                    eventCreated = eventNode.getProperty(EventHelper.NODE_PROPERTY_CREATED).getLong();
                    if ( tryToLoadJob(eventNode, this.unloadedJobs) ) {
                        count++;
                    }
                }
                // now we have to add all jobs with the same created time!
                boolean done = false;
                while ( result.hasNext() && !done ) {
                    final Node eventNode = result.nextNode();
                    final long created = eventNode.getProperty(EventHelper.NODE_PROPERTY_CREATED).getLong();
                    if ( created == eventCreated ) {
                        if ( tryToLoadJob(eventNode, this.unloadedJobs) ) {
                            count++;
                        }
                    } else {
                        done = true;
                    }
                }
                // have we processed all jobs?
                if ( !done && !result.hasNext() ) {
                    eventCreated = -1;
                }
                logger.debug("Loaded {} jobs and new since {}", count, eventCreated);
            } catch (RepositoryException re) {
                this.logger.error("Exception during initial loading of stored jobs.", re);
            }
        }
        return eventCreated;
    }

    private boolean tryToLoadJob(final Node eventNode, final Set<String> unloadedJobSet) {
        try {
            if ( !eventNode.isLocked() && !eventNode.hasProperty(EventHelper.NODE_PROPERTY_FINISHED)) {
                final String nodePath = eventNode.getPath();
                try {
                    final Event event = this.readEvent(eventNode);
                    final EventInfo info = new EventInfo();
                    info.event = event;
                    info.nodePath = nodePath;
                    this.queueJob(info);
                } catch (ClassNotFoundException cnfe) {
                    // store path for lazy loading
                    synchronized ( unloadedJobSet ) {
                        unloadedJobSet.add(nodePath);
                    }
                    this.ignoreException(cnfe);
                } catch (RepositoryException re) {
                    this.logger.error("Unable to load stored job from " + nodePath, re);
                }
                return true;
            }
        } catch (RepositoryException re) {
            this.logger.error("Unable to load stored job from " + eventNode, re);
        }
        return false;
    }

    /**
     * @see org.apache.sling.event.EventUtil.JobStatusNotifier#sendAcknowledge(org.osgi.service.event.Event, java.lang.String)
     */
    public boolean sendAcknowledge(Event job, String eventNodePath) {
        synchronized ( this.processingEventsList ) {
            // if the event is still in the processing list, we confirm the ack
            final Object ack = this.processingEventsList.remove(eventNodePath);
            if ( ack != null ) {
                this.sendNotification(EventUtil.TOPIC_JOB_STARTED, job);
            }
            return ack != null;
        }

    }

    /**
     * This is a notification from the component which processed the job.
     *
     * @see org.apache.sling.event.EventUtil.JobStatusNotifier#finishedJob(org.osgi.service.event.Event, String, boolean)
     */
    public boolean finishedJob(Event job, String eventNodePath, boolean shouldReschedule) {
        if ( this.logger.isDebugEnabled() ) {
            this.logger.debug("Received finish for job {}, shouldReschedule={}", EventUtil.toString(job), shouldReschedule);
        }
        // let's remove the event from our processing list
        // this is just a sanity check, as usually the job should have been
        // removed during sendAcknowledge.
        synchronized ( this.processingEventsList ) {
            this.processingEventsList.remove(eventNodePath);
        }

        boolean reschedule = shouldReschedule;
        if ( shouldReschedule ) {
            // check if we exceeded the number of retries
            int retries = this.maxJobRetries;
            if ( job.getProperty(EventUtil.PROPERTY_JOB_RETRIES) != null ) {
                retries = (Integer) job.getProperty(EventUtil.PROPERTY_JOB_RETRIES);
            }
            int retryCount = 0;
            if ( job.getProperty(EventUtil.PROPERTY_JOB_RETRY_COUNT) != null ) {
                retryCount = (Integer)job.getProperty(EventUtil.PROPERTY_JOB_RETRY_COUNT);
            }
            retryCount++;
            if ( retries != -1 && retryCount > retries ) {
                reschedule = false;
            }
            if ( reschedule ) {
                // update event with retry count and retries
                final Dictionary<String, Object> newProperties = new EventPropertiesMap(job);
                newProperties.put(EventUtil.PROPERTY_JOB_RETRY_COUNT, retryCount);
                newProperties.put(EventUtil.PROPERTY_JOB_RETRIES, retries);
                job = new Event(job.getTopic(), newProperties);
                if ( this.logger.isDebugEnabled() ) {
                    this.logger.debug("Failed job {}", EventUtil.toString(job));
                }
                this.sendNotification(EventUtil.TOPIC_JOB_FAILED, job);
            } else {
                if ( this.logger.isDebugEnabled() ) {
                    this.logger.debug("Cancelled job {}", EventUtil.toString(job));
                }
                this.sendNotification(EventUtil.TOPIC_JOB_CANCELLED, job);
            }
        } else {
            if ( this.logger.isDebugEnabled() ) {
                this.logger.debug("Finished job {}", EventUtil.toString(job));
            }
            this.sendNotification(EventUtil.TOPIC_JOB_FINISHED, job);
        }
        final ParallelInfo parInfo = ParallelInfo.getParallelInfo(job);
        EventInfo putback = null;
        // we have to use the same session for unlocking that we used for locking!
        synchronized ( this.backgroundLock ) {
            // we might get here asnyc while this service has already been shutdown!
            if ( this.backgroundSession == null ) {
                checkForNotify(job, null);
                // we can only return false here
                return false;
            }
            try {
                this.backgroundSession.refresh(false);
                // check if the job has been cancelled
                if ( !this.backgroundSession.itemExists(eventNodePath) ) {
                    checkForNotify(job, null);
                    return true;
                }
                final Node eventNode = (Node) this.backgroundSession.getItem(eventNodePath);
                boolean unlock = true;
                try {
                    if ( !reschedule ) {
                        synchronized ( this.deletedJobs ) {
                            this.deletedJobs.add(eventNodePath);
                        }
                        // unlock node
                        try {
                            eventNode.unlock();
                        } catch (RepositoryException e) {
                            // if unlock fails, we silently ignore this
                            this.ignoreException(e);
                        }
                        unlock = false;
                        final String jobId = (String)job.getProperty(EventUtil.PROPERTY_JOB_ID);
                        if ( jobId == null ) {
                            // remove node from repository if no job is set
                            final Node parentNode = eventNode.getParent();
                            eventNode.remove();
                            parentNode.save();
                        } else {
                            eventNode.setProperty(EventHelper.NODE_PROPERTY_FINISHED, Calendar.getInstance());
                            eventNode.save();
                        }
                    }
                } catch (RepositoryException re) {
                    // if an exception occurs, we just log
                    this.logger.error("Exception during job finishing.", re);
                } finally {
                    final String jobTopic = (String)job.getProperty(EventUtil.PROPERTY_JOB_TOPIC);
                    this.unlockState(parInfo, jobTopic);

                    if ( job.getProperty(EventUtil.PROPERTY_JOB_QUEUE_NAME) == null ) {
                        this.parallelJobCount--;
                        this.backgroundLock.notify();
                    }

                    if ( unlock ) {
                        synchronized ( this.deletedJobs ) {
                            this.deletedJobs.add(eventNodePath);
                        }
                        // unlock node
                        try {
                            eventNode.unlock();
                        } catch (RepositoryException e) {
                            // if unlock fails, we silently ignore this
                            this.ignoreException(e);
                        }
                    }
                }
                if ( reschedule ) {
                    // update retry count and retries in the repository
                    try {
                        eventNode.setProperty(EventUtil.PROPERTY_JOB_RETRIES, (Integer)job.getProperty(EventUtil.PROPERTY_JOB_RETRIES));
                        eventNode.setProperty(EventUtil.PROPERTY_JOB_RETRY_COUNT, (Integer)job.getProperty(EventUtil.PROPERTY_JOB_RETRY_COUNT));
                        eventNode.save();
                    } catch (RepositoryException re) {
                        // if an exception occurs, we just log
                        this.logger.error("Exception during job updating job rescheduling information.", re);
                    }
                    final EventInfo info = new EventInfo();
                    try {
                        info.event = job;
                        info.nodePath = eventNode.getPath();
                    } catch (RepositoryException e) {
                        // this should never happen
                        this.ignoreException(e);
                    }
                    // if this is an own job queue, we simply signal the queue to continue
                    // it will pick up the event and either reschedule or wait
                    if ( job.getProperty(EventUtil.PROPERTY_JOB_QUEUE_NAME) != null ) {
                        checkForNotify(job, info);
                    } else {
                        putback = info;
                    }
                } else {
                    // if this is an own job queue, we simply signal the queue to continue
                    // it will pick up the event and continue with the next event
                    checkForNotify(job, null);
                }
            } catch (RepositoryException re) {
                this.logger.error("Unable to create new session.", re);
                return false;
            }
        }
        if ( putback != null ) {
            this.putBackIntoMainQueue(putback, false);
        }
        if ( !shouldReschedule ) {
            return true;
        }
        return reschedule;
    }

    private void putBackIntoMainQueue(final EventInfo info, final boolean useSleepTime) {
        final Runnable t = new Runnable() {
            public void run() {
                try {
                    queue.put(info);
                } catch (InterruptedException e) {
                    // this should never happen
                    ignoreException(e);
                }
            }
        };

        final long delay;
        if ( useSleepTime ) {
            delay = this.sleepTime * 1000;
        } else {
            final Long obj = (Long)info.event.getProperty(EventUtil.PROPERTY_JOB_RETRY_DELAY);
            delay = (obj == null) ? -1 : obj.longValue();
        }
        if ( delay == -1 ) {
            // put directly without waiting
            if ( logger.isDebugEnabled() ) {
                logger.debug("Putting job {} back into the queue.", EventUtil.toString(info.event));
            }
            t.run();
        } else {
            // schedule the put
            if ( logger.isDebugEnabled() ) {
                logger.debug("Putting job {} back into the queue after {}ms.", EventUtil.toString(info.event), delay);
            }
            final Date fireDate = new Date();
            fireDate.setTime(System.currentTimeMillis() + delay);

            try {
                this.scheduler.fireJobAt(null, t, null, fireDate);
            } catch (Exception e) {
                // we ignore the exception and just put back the job in the queue
                ignoreException(e);
                // then wait for the time and readd the job
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    // ignore
                    ignoreException(ie);
                }
                t.run();
            }
        }
    }

    private void checkForNotify(final Event job, final EventInfo info) {
        if ( job.getProperty(EventUtil.PROPERTY_JOB_QUEUE_NAME) != null ) {
            // we know the queue exists
            final JobBlockingQueue jobQueue;
            synchronized ( this.jobQueues ) {
                jobQueue = this.jobQueues.get(job.getProperty(EventUtil.PROPERTY_JOB_QUEUE_NAME));
            }
            synchronized ( jobQueue.getLock()) {
                EventInfo reprocessInfo = null;
                if ( info != null ) {
                    reprocessInfo = jobQueue.reschedule(info, this.scheduler);
                }
                if ( jobQueue.isOrdered() ) {
                    jobQueue.notifyFinish(reprocessInfo);
                } else {
                    jobQueue.freeSlot();
                }
            }
        }
    }

    /**
     * Search for job nodes
     * @param topic The job topic
     * @param filterProps optional filter props
     * @param locked only active jobs?
     * @return
     * @throws RepositoryException
     */
    private Collection<Event> queryJobs(final String topic,
                                        final Boolean locked,
                                        final Map<String, Object>... filterProps)  {
        // we create a new session
        Session s = null;
        final List<Event> jobs = new ArrayList<Event>();
        try {
            s = this.createSession();
            final QueryManager qManager = s.getWorkspace().getQueryManager();
            final StringBuilder buffer = new StringBuilder("/jcr:root");
            buffer.append(this.repositoryPath);
            if ( topic != null ) {
                buffer.append('/');
                buffer.append(topic.replace('/', '.'));
            }
            buffer.append("//element(*, ");
            buffer.append(this.getEventNodeType());
            buffer.append(") [not(@");
            buffer.append(EventHelper.NODE_PROPERTY_FINISHED);
            buffer.append(")");
            if ( locked != null ) {
                if ( locked ) {
                    buffer.append(" and @jcr:lockOwner");
                } else {
                    buffer.append(" and not(@jcr:lockOwner)");
                }
            }
            if ( filterProps != null && filterProps.length > 0 ) {
                buffer.append(" and (");
                int index = 0;
                for (Map<String,Object> template : filterProps) {
                    if ( index > 0 ) {
                        buffer.append(" or ");
                    }
                    buffer.append('(');
                    final Iterator<Map.Entry<String, Object>> i = template.entrySet().iterator();
                    boolean first = true;
                    while ( i.hasNext() ) {
                        final Map.Entry<String, Object> current = i.next();
                        // check prop name first
                        final String propName = EventHelper.getNodePropertyName(current.getKey());
                        if ( propName != null ) {
                            // check value
                            final Value value = EventHelper.getNodePropertyValue(s.getValueFactory(), current.getValue());
                            if ( value != null ) {
                                if ( first ) {
                                    first = false;
                                    buffer.append('@');
                                } else {
                                    buffer.append(" and @");
                                }
                                buffer.append(propName);
                                buffer.append(" = '");
                                buffer.append(current.getValue());
                                buffer.append("'");
                            }
                        }
                    }
                    buffer.append(')');
                    index++;
                }
                buffer.append(')');
            }
            buffer.append("]");
            buffer.append(" order by @");
            buffer.append(EventHelper.NODE_PROPERTY_CREATED);
            buffer.append(" ascending");
            final String queryString = buffer.toString();
            logger.debug("Executing job query {}.", queryString);

            final Query q = qManager.createQuery(queryString, Query.XPATH);
            final NodeIterator iter = q.execute().getNodes();
            while ( iter.hasNext() ) {
                final Node eventNode = iter.nextNode();
                try {
                    final Event event = this.readEvent(eventNode);
                    jobs.add(event);
                } catch (ClassNotFoundException cnfe) {
                    // in the case of a class not found exception we just ignore the exception
                    this.ignoreException(cnfe);
                }
            }
        } catch (RepositoryException e) {
            // in the case of an error, we return an empty list
            this.ignoreException(e);
        } finally {
            if ( s != null) {
                s.logout();
            }
        }
        return jobs;
    }

    /**
     * @see org.apache.sling.event.JobStatusProvider#getCurrentJobs(java.lang.String)
     */
    public Collection<Event> getCurrentJobs(String topic) {
        return this.getCurrentJobs(topic, (Map<String, Object>[])null);
    }

    /**
     * This is deprecated.
     */
    public Collection<Event> scheduledJobs(String topic) {
        return this.getScheduledJobs(topic);
    }

    /**
     * @see org.apache.sling.event.JobStatusProvider#getScheduledJobs(java.lang.String)
     */
    public Collection<Event> getScheduledJobs(String topic) {
        return this.getScheduledJobs(topic, (Map<String, Object>[])null);
    }

    /**
     * @see org.apache.sling.event.JobStatusProvider#getCurrentJobs(java.lang.String, java.util.Map...)
     */
    public Collection<Event> getCurrentJobs(String topic, Map<String, Object>... filterProps) {
        return this.queryJobs(topic, true, filterProps);
    }

    /**
     * @see org.apache.sling.event.JobStatusProvider#getScheduledJobs(java.lang.String, java.util.Map...)
     */
    public Collection<Event> getScheduledJobs(String topic, Map<String, Object>... filterProps) {
        return this.queryJobs(topic, false, filterProps);
    }


    /**
     * @see org.apache.sling.event.JobStatusProvider#getAllJobs(java.lang.String, java.util.Map...)
     */
    public Collection<Event> getAllJobs(String topic, Map<String, Object>... filterProps) {
        return this.queryJobs(topic, null, filterProps);
    }


    /**
     * @see org.apache.sling.event.JobStatusProvider#cancelJob(java.lang.String, java.lang.String)
     */
    public void cancelJob(String topic, String jobId) {
        if ( jobId != null && topic != null ) {
            this.cancelJob(JobUtil.getUniquePath(topic, jobId));
        }
    }

    /**
     * @see org.apache.sling.event.JobStatusProvider#cancelJob(java.lang.String)
     */
    public void cancelJob(String jobId) {
        if ( jobId != null ) {
            synchronized ( this.writeLock ) {
                try {
                    this.writerSession.refresh(false);
                } catch (RepositoryException e) {
                    this.ignoreException(e);
                }
                try {
                    if ( this.writerSession.itemExists(jobId) ) {
                        final Item item = this.writerSession.getItem(jobId);
                        final Node parentNode = item.getParent();
                        item.remove();
                        parentNode.save();
                    }
                } catch (RepositoryException e) {
                    this.logger.error("Error during cancelling job at " + jobId, e);
                }
            }
        }
    }


    /**
     * @see org.apache.sling.event.JobStatusProvider#wakeUpJobQueue(java.lang.String)
     */
    public void wakeUpJobQueue(String jobQueueName) {
        if ( jobQueueName != null ) {
            synchronized ( this.jobQueues ) {
                final JobBlockingQueue queue = this.jobQueues.get(jobQueueName);
                if ( queue != null ) {
                    this.wakeUpJobQueue(queue);
                }
            }
        }
    }

    private void wakeUpJobQueue(final JobBlockingQueue jobQueue) {
        if ( jobQueue.isSleeping() ) {
            final String schedulerJobName = jobQueue.getSchedulerJobName();
            final Thread thread = jobQueue.getSleepingThread();
            if ( schedulerJobName != null ) {
                final Scheduler localScheduler = this.scheduler;
                if ( localScheduler != null ) {
                    localScheduler.removeJob(schedulerJobName);
                }
            }
            if ( thread != null ) {
                thread.interrupt();
            }
        }
    }
    /**
     * Helper method for sending the notification events.
     */
    private void sendNotification(final String topic, final Event job) {
        final EventAdmin localEA = this.eventAdmin;
        if ( localEA != null ) {
            final Dictionary<String, Object> props = new Hashtable<String, Object>();
            props.put(EventUtil.PROPERTY_NOTIFICATION_JOB, job);
            props.put(EventConstants.TIMESTAMP, System.currentTimeMillis());
            localEA.postEvent(new Event(topic, props));
        }
    }

    private static final class StartedJobInfo {
        public final Event event;
        public final String nodePath;
        public final long  started;

        public StartedJobInfo(final Event e, final String path, final long started) {
            this.event = e;
            this.nodePath = path;
            this.started = started;
        }
    }
}