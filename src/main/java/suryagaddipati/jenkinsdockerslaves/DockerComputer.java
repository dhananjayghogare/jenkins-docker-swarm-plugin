/*
 * The MIT License
 *
 *  Copyright (c) 2015, CloudBees, Inc.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 */

package suryagaddipati.jenkinsdockerslaves;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Statistics;
import com.google.common.collect.Iterables;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static suryagaddipati.jenkinsdockerslaves.ExceptionHandlingHelpers.executeSliently;
import static suryagaddipati.jenkinsdockerslaves.ExceptionHandlingHelpers.executeSlientlyWithLogging;

public class DockerComputer extends AbstractCloudComputer<DockerSlave> {

    private static final Logger LOGGER = Logger.getLogger(DockerComputer.class.getName());
    private String containerId;
    private String swarmNodeName;
    private PrintStream log;


    public DockerComputer(final DockerSlave dockerSlave) {
        super(dockerSlave);
    }


    public void setNodeName(final String nodeName) {
        this.swarmNodeName = nodeName;
    }

    public String getSwarmNodeName() {
        return this.swarmNodeName;
    }

    public Queue.Executable getCurrentBuild() {
        if (!Iterables.isEmpty(getExecutors())) {
            final Executor exec = getExecutors().get(0);
            return exec.getCurrentExecutable() == null ? null : exec.getCurrentExecutable();
        }
        return null;
    }

    public String getContainerId() {
        return this.containerId;
    }

    public void setContainerId(final String containerId) {
        this.containerId = containerId;
    }

    @Override
    public Map<String, Object> getMonitorData() {
        return new HashMap<>(); //no monitoring needed as this is a shortlived computer.
    }

    public void destroyContainer(final PrintStream logger) {
        final ExecutorService threadPool = Executors.newSingleThreadExecutor();
        threadPool.submit((Runnable) () -> {
            try {
                collectStatsAndCleanupDockerContainer(getContainerId(), logger);
            } catch (final IOException e) {
                LOGGER.log(Level.INFO, "couldn't cleanup container ", e);
            }
        });
        setAcceptingTasks(false);
    }

    @Override
    public void setChannel(final Channel channel, final OutputStream launchLog, final Channel.Listener listener) throws IOException, InterruptedException {
        final TaskListener taskListener = new StreamTaskListener(launchLog);
        this.log = taskListener.getLogger();
        channel.addListener(new Channel.Listener() {
            @Override
            public void onClosed(final Channel channel, final IOException cause) {
                try {
                    cleanupNode(DockerComputer.this.log);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace(DockerComputer.this.log);
                }
            }
        });
        super.setChannel(channel, launchLog, listener);
    }

    private void cleanupNode(final PrintStream logger) throws IOException, InterruptedException {
        if (getNode() != null) {
            logger.println("Removing node " + getNode().getDisplayName());
            getNode().terminateWithoutQueueLock();
        }
    }


    private void gatherStats(final DockerClient dockerClient) throws IOException {
        final String containerId = getContainerId();
        final Queue.Executable currentExecutable = getExecutors().get(0).getCurrentExecutable();
        if (currentExecutable instanceof Run && ((Run) currentExecutable).getAction(DockerSlaveInfo.class) != null) {
            final Run run = ((Run) currentExecutable);
            final DockerSlaveInfo slaveInfo = ((Run) currentExecutable).getAction(DockerSlaveInfo.class);
            final Statistics stats = dockerClient.statsCmd(containerId).exec();
            slaveInfo.setStats(stats);
            run.save();
        }
    }

    public void collectStatsAndCleanupDockerContainer(final String containerId, final PrintStream logger) throws IOException {

        final DockerSlaveConfiguration configuration = DockerSlaveConfiguration.get();
        try (DockerClient dockerClient = configuration.newDockerClient()) {
            if (containerId != null) {
                try {
                    final InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
                    executeSlientlyWithLogging(() -> gatherStats(dockerClient), logger); // No big deal if we can't get stats
                    if (container.getState().getPaused()) {
                        executeSlientlyWithLogging(() -> dockerClient.unpauseContainerCmd(containerId).exec(), logger);
                    }
                    executeSliently(() -> dockerClient.killContainerCmd(containerId).exec());
                    executeSlientlyWithLogging(() -> removeContainer(logger, containerId, dockerClient), logger);
                } catch (final NotFoundException e) {
                    //Ignore if container is already gone
                }

            }
        }
    }

    private void removeContainer(final PrintStream logger, final String containerId, final DockerClient dockerClient) {
        ExceptionHandlingHelpers.executeWithRetryOnError(() -> dockerClient.removeContainerCmd(containerId).withForce(true).exec());
        logger.println("Removed Container " + containerId);
    }

    protected void setNode(final Node node) {
        this.nodeName = node.getNodeName();
        addOneExecutor();
        JenkinsHacks.setPrivateField(SlaveComputer.class, "launcher", this, grabLauncher(node));

    }

    private void addOneExecutor() {
        final Executor e = new LockFreeExecutor(this, 0);
        final List executors = (List) JenkinsHacks.getPrivateField(Computer.class, "executors", this);
        executors.add(e);
    }


    @Override
    public void recordTermination() {
        //no need to record termination
    }

    public void delete() {
        executeSlientlyWithLogging(() -> collectStatsAndCleanupDockerContainer(getContainerId(), System.out), System.out); // Maybe be container was created, so attempt to delete it
        executeSlientlyWithLogging(() -> {
            if (getChannel() != null) getChannel().close();
        }, System.out);
        executeSlientlyWithLogging(() -> cleanupNode(System.out), System.out);
    }
}
