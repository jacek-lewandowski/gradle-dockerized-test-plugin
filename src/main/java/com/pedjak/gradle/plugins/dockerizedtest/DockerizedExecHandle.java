/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedjak.gradle.plugins.dockerizedtest;

import com.google.common.base.Joiner;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.AttachContainerResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.github.dockerjava.core.util.CompressArchiveUtil;
import groovy.lang.Closure;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.operations.CurrentBuildOperationPreservingRunnable;
import org.gradle.process.ExecResult;
import org.gradle.process.internal.*;
import org.gradle.process.internal.shutdown.ShutdownHookActionRegister;

import javax.annotation.Nullable;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.String.format;

/**
 * Default implementation for the ExecHandle interface.
 *
 * <h3>State flows</h3>
 *
 * <ul>
 *   <li>INIT -> STARTED -> [SUCCEEDED|FAILED|ABORTED|DETACHED]</li>
 *   <li>INIT -> FAILED</li>
 *   <li>INIT -> STARTED -> DETACHED -> ABORTED</li>
 * </ul>
 *
 * State is controlled on all control methods:
 * <ul>
 * <li>{@link #start()} allowed when state is INIT</li>
 * <li>{@link #abort()} allowed when state is STARTED or DETACHED</li>
 * </ul>
 */
public class DockerizedExecHandle implements ExecHandle, ProcessSettings
{

    private static final Logger LOGGER = Logging.getLogger(DockerizedExecHandle.class);

    private final String displayName;

    /**
     * The working directory of the process.
     */
    private final File directory;

    /**
     * The executable to run.
     */
    private final String command;

    /**
     * Arguments to pass to the executable.
     */
    private final List<String> arguments;

    /**
     * The variables to set in the environment the executable is run in.
     */
    private final Map<String, String> environment;
    private final StreamsHandler outputHandler;
    private final StreamsHandler inputHandler;
    private final boolean redirectErrorStream;
    private int timeoutMillis;
    private boolean daemon;

    /**
     * Lock to guard all mutable state
     */
    private final Lock lock;
    private final Condition stateChanged;

    private final Executor executor;

    /**
     * State of this ExecHandle.
     */
    private ExecHandleState state;

    /**
     * When not null, the runnable that is waiting
     */
    private DockerizedExecHandleRunner execHandleRunner;

    private ExecResultImpl execResult;

    private final ListenerBroadcast<ExecHandleListener> broadcast;

    private final ExecHandleShutdownHookAction shutdownHookAction;

    private final BuildCancellationToken buildCancellationToken;

    private final DockerizedTestExtension testExtension;

    public DockerizedExecHandle(DockerizedTestExtension testExtension, String displayName, File directory, String command, List<String> arguments,
                      Map<String, String> environment, StreamsHandler outputHandler, StreamsHandler inputHandler,
                      List<ExecHandleListener> listeners, boolean redirectErrorStream, int timeoutMillis, boolean daemon,
                      Executor executor, BuildCancellationToken buildCancellationToken) {
        this.displayName = displayName;
        this.directory = directory;
        this.command = command;
        this.arguments = arguments;
        this.environment = environment;
        this.outputHandler = outputHandler;
        this.inputHandler = inputHandler;
        this.redirectErrorStream = redirectErrorStream;
        this.timeoutMillis = timeoutMillis;
        this.daemon = daemon;
        this.executor = executor;
        this.lock = new ReentrantLock();
        this.stateChanged = lock.newCondition();
        this.state = ExecHandleState.INIT;
        this.buildCancellationToken = buildCancellationToken;
        this.testExtension = testExtension;
        shutdownHookAction = new ExecHandleShutdownHookAction(this);
        broadcast = new ListenerBroadcast<ExecHandleListener>(ExecHandleListener.class);
        broadcast.addAll(listeners);
    }

    public File getDirectory() {
        return directory;
    }

    public String getCommand() {
        return command;
    }

    public boolean isDaemon() {
        return daemon;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public List<String> getArguments() {
        return Collections.unmodifiableList(arguments);
    }

    public Map<String, String> getEnvironment() {
        return Collections.unmodifiableMap(environment);
    }

    public ExecHandleState getState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    private void setState(ExecHandleState state) {
        lock.lock();
        try {
            LOGGER.debug("Changing state to: {}", state);
            this.state = state;
            this.stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean stateIn(ExecHandleState... states) {
        lock.lock();
        try {
            return Arrays.asList(states).contains(this.state);
        } finally {
            lock.unlock();
        }
    }

    private void setEndStateInfo(ExecHandleState newState, int exitValue, Throwable failureCause) {
        ShutdownHookActionRegister.removeAction(shutdownHookAction);
        buildCancellationToken.removeCallback(shutdownHookAction);
        ExecHandleState currentState;
        lock.lock();
        try {
            currentState = this.state;
        } finally {
            lock.unlock();
        }

        ExecResultImpl newResult = new ExecResultImpl(exitValue, execExceptionFor(failureCause, currentState), displayName);
        if (!currentState.isTerminal() && newState != ExecHandleState.DETACHED) {
            try {
                broadcast.getSource().executionFinished(this, newResult);
            } catch (Exception e) {
                newResult = new ExecResultImpl(exitValue, execExceptionFor(e, currentState), displayName);
            }
        }

        lock.lock();
        try {
            setState(newState);
            this.execResult = newResult;
        } finally {
            lock.unlock();
        }

        LOGGER.debug("Process '{}' finished with exit value {} (state: {})", displayName, exitValue, newState);
    }

    @Nullable
    private ExecException execExceptionFor(Throwable failureCause, ExecHandleState currentState) {
        return failureCause != null
            ? new ExecException(failureMessageFor(currentState), failureCause)
            : null;
    }

    private String failureMessageFor(ExecHandleState currentState) {
        return currentState == ExecHandleState.STARTING
            ? format("A problem occurred starting process '%s'", displayName)
            : format("A problem occurred waiting for process '%s' to complete.", displayName);
    }

    public ExecHandle start() {
        LOGGER.info("Starting process '{}'. Working directory: {} Command: {}",
                displayName, directory, command + ' ' + Joiner.on(' ').useForNull("null").join(arguments));
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Environment for process '{}': {}", displayName, environment);
        }
        lock.lock();
        try {
            if (!stateIn(ExecHandleState.INIT)) {
                throw new IllegalStateException(format("Cannot start process '%s' because it has already been started", displayName));
            }
            setState(ExecHandleState.STARTING);

            execHandleRunner = new DockerizedExecHandleRunner(this, new CompositeStreamsHandler(), executor);
            executor.execute(new CurrentBuildOperationPreservingRunnable(execHandleRunner));

            while (stateIn(ExecHandleState.STARTING)) {
                LOGGER.debug("Waiting until process started: {}.", displayName);
                try {
                    if (!stateChanged.await(30, TimeUnit.SECONDS)) {
                        execHandleRunner.abortProcess();
                        throw new RuntimeException("Giving up on "+execHandleRunner);
                    }
                } catch (InterruptedException e) {
                    //ok, wrapping up
                }
            }

            if (execResult != null) {
                execResult.rethrowFailure();
            }

            LOGGER.info("Successfully started process '{}'", displayName);
        } finally {
            lock.unlock();
        }
        return this;
    }

    public void abort() {
        lock.lock();
        try {
            if (stateIn(ExecHandleState.SUCCEEDED, ExecHandleState.FAILED, ExecHandleState.ABORTED)) {
                return;
            }
            if (!stateIn(ExecHandleState.STARTED, ExecHandleState.DETACHED)) {
                throw new IllegalStateException(
                    format("Cannot abort process '%s' because it is not in started or detached state", displayName));
            }
            this.execHandleRunner.abortProcess();
            this.waitForFinish();
        } finally {
            lock.unlock();
        }
    }

    public ExecResult waitForFinish() {
        lock.lock();
        try {
            while (!state.isTerminal()) {
                try {
                    stateChanged.await();
                } catch (InterruptedException e) {
                    //ok, wrapping up...
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        } finally {
            lock.unlock();
        }

        // At this point:
        // If in daemon mode, the process has started successfully and all streams to the process have been closed
        // If in fork mode, the process has completed and all cleanup has been done
        // In both cases, all asynchronous work for the process has completed and we're done

        return result();
    }

    private ExecResult result() {
        lock.lock();
        try {
            return execResult.rethrowFailure();
        } finally {
            lock.unlock();
        }
    }

    void detached() {
        setEndStateInfo(ExecHandleState.DETACHED, 0, null);
    }

    void started() {
        ShutdownHookActionRegister.addAction(shutdownHookAction);
        buildCancellationToken.addCallback(shutdownHookAction);
        setState(ExecHandleState.STARTED);
        broadcast.getSource().executionStarted(this);
    }

    void finished(int exitCode) {
        if (exitCode != 0) {
            setEndStateInfo(ExecHandleState.FAILED, exitCode, null);
        } else {
            setEndStateInfo(ExecHandleState.SUCCEEDED, 0, null);
        }
    }

    void aborted(int exitCode) {
        if (exitCode == 0) {
            // This can happen on Windows
            exitCode = -1;
        }
        setEndStateInfo(ExecHandleState.ABORTED, exitCode, null);
    }

    void failed(Throwable failureCause) {
        setEndStateInfo(ExecHandleState.FAILED, -1, failureCause);
    }

    public void addListener(ExecHandleListener listener) {
        broadcast.add(listener);
    }

    public void removeListener(ExecHandleListener listener) {
        broadcast.remove(listener);
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean getRedirectErrorStream() {
        return redirectErrorStream;
    }

    public int getTimeout() {
        return timeoutMillis;
    }

    private void maybeCopyJvmOptionFile(String containerId, DockerClient client) throws Exception {
        for (String arg: arguments) {
            if (arg.startsWith("@")) {
                File optionFile = new File(arg.substring(1));
                if (optionFile.isFile()) {
                    boolean copyingDone = false;
                    File tar = CompressArchiveUtil.archiveTARFiles(new File("/"), Arrays.asList(optionFile), optionFile.getName());
                    for (int i = 0; i < 10; i++)
                    {
                        try
                        {
                            client.copyArchiveToContainerCmd(containerId)
                                    .withRemotePath("/")
                                    .withTarInputStream(new FileInputStream(tar))
                                    .exec();
                            copyingDone = true;
                            tar.delete();
                            break;
                        } catch (Exception e) {
                            LOGGER.warn("Failed copying option file {} via tar {} to container {}", optionFile, tar, containerId, e);

                        }
                    }
                    if (!copyingDone) {
                        throw new IOException(String.format("Error copying option file %s to container %s", optionFile, containerId));
                    }
                }
            }
        }
    }

    public Process runContainer() {
        try
        {
            DockerClient client = testExtension.getClient();
            CreateContainerCmd createCmd = client.createContainerCmd(testExtension.getImage().toString())
                    .withTty(false)
                    .withStdinOpen(true)
                    .withStdInOnce(true)
                    .withWorkingDir(directory.getAbsolutePath());

            createCmd.withEnv(getEnv());

            String user = testExtension.getUser();
            if (user != null)
                createCmd.withUser(user);
            bindVolumes(createCmd);
            List<String> cmdLine = new ArrayList<String>();
            cmdLine.add(command);
            cmdLine.addAll(arguments);
            createCmd.withCmd(cmdLine);

            invokeIfNotNull(testExtension.getBeforeContainerCreate(), createCmd, client);
            String containerId = createCmd.exec().getId();

            invokeIfNotNull(testExtension.getAfterContainerCreate(), containerId, client);

            maybeCopyJvmOptionFile(containerId, client);

            invokeIfNotNull(testExtension.getBeforeContainerStart(), containerId, client);
            client.startContainerCmd(containerId).exec();
            invokeIfNotNull(testExtension.getAfterContainerStart(), containerId, client);

            if (!client.inspectContainerCmd(containerId).exec().getState().getRunning()) {
                throw new RuntimeException("Container "+containerId+" not running!");
            }

            Process proc = new DockerizedProcess(client, containerId, testExtension.getAfterContainerStop());

            return proc;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void invokeIfNotNull(Closure closure, Object... args) {
        if (closure != null) {
            int l = closure.getParameterTypes().length;
            Object[] nargs;
            if (l < args.length) {
                nargs = new Object[l];
                System.arraycopy(args, 0, nargs, 0, l);
            } else {
                nargs = args;
            }
            closure.call(nargs);
        }
    }
    private List<String> getEnv() {
        List<String> env = new ArrayList<String>();
        for (Map.Entry<String, String> e: environment.entrySet()) {
            env.add(e.getKey()+"="+e.getValue());
        }
        return env;
    }

    private void bindVolumes(CreateContainerCmd cmd) {
        List<Volume> volumes = new ArrayList<Volume>();
        List<Bind> binds = new ArrayList<Bind>();
        for (Iterator it = testExtension.getVolumes().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Object> e = (Map.Entry<Object, Object>) it.next();
            Volume volume = new Volume(e.getValue().toString());
            Bind bind = new Bind(e.getKey().toString(), volume);
            binds.add(bind);
            volumes.add(volume);
        }
        cmd.withVolumes(volumes).withBinds(binds);
    }

    private static class ExecResultImpl implements ExecResult {
        private final int exitValue;
        private final ExecException failure;
        private final String displayName;

        ExecResultImpl(int exitValue, ExecException failure, String displayName) {
            this.exitValue = exitValue;
            this.failure = failure;
            this.displayName = displayName;
        }

        public int getExitValue() {
            return exitValue;
        }

        public ExecResult assertNormalExitValue() throws ExecException {
            // all exit values are ok
//            if (exitValue != 0) {
//                throw new ExecException(format("Process '%s' finished with non-zero exit value %d", displayName, exitValue));
//            }
            return this;
        }

        public ExecResult rethrowFailure() throws ExecException {
            if (failure != null) {
                throw failure;
            }
            return this;
        }

        @Override
        public String toString() {
            return "{exitValue=" + exitValue + ", failure=" + failure + "}";
        }
    }

    private class CompositeStreamsHandler implements StreamsHandler {
        @Override
        public void connectStreams(Process process, String processName, Executor executor) {
            inputHandler.connectStreams(process, processName, executor);
            outputHandler.connectStreams(process, processName, executor);
        }

        @Override
        public void start() {
            inputHandler.start();
            outputHandler.start();
        }

        @Override
        public void stop() {
            inputHandler.stop();
            outputHandler.stop();
        }
    }

    private class DockerizedProcess extends Process {

        private final DockerClient dockerClient;
        private final String containerId;
        private final Closure afterContainerStop;

        private final PipedOutputStream stdInWriteStream = new PipedOutputStream();
        private final PipedInputStream stdOutReadStream = new PipedInputStream();
        private final PipedInputStream stdErrReadStream = new PipedInputStream();
        private final PipedInputStream stdInReadStream = new PipedInputStream(stdInWriteStream);
        private final PipedOutputStream stdOutWriteStream = new PipedOutputStream(stdOutReadStream);
        private final PipedOutputStream stdErrWriteStream = new PipedOutputStream(stdErrReadStream);

        private final CountDownLatch finished = new CountDownLatch(1);
        private AtomicInteger exitCode = new AtomicInteger();
        private final AttachContainerResultCallback attachContainerResultCallback = new AttachContainerResultCallback() {
            @Override public void onNext(Frame frame)
            {
                try
                {
                    if (frame.getStreamType().equals(StreamType.STDOUT))
                    {
                        stdOutWriteStream.write(frame.getPayload());
                    } else if (frame.getStreamType().equals(StreamType.STDERR)) {
                        stdErrWriteStream.write(frame.getPayload());
                    }
                } catch (Exception e) {
                    LOGGER.error("Error while writing to stream:", e);
                }
                super.onNext(frame);
            }
        };

        private final WaitContainerResultCallback waitContainerResultCallback = new WaitContainerResultCallback() {
            @Override public void onNext(WaitResponse waitResponse)
            {
                exitCode.set(waitResponse.getStatusCode());
                try
                {
                    attachContainerResultCallback.close();
                    attachContainerResultCallback.awaitCompletion();
                    stdOutWriteStream.close();
                    stdErrWriteStream.close();
                } catch (Exception e) {
                    LOGGER.debug("Error by detaching streams", e);
                } finally
                {
                    try
                    {
                        invokeIfNotNull(afterContainerStop, containerId, dockerClient);
                    } catch (Exception e) {
                        LOGGER.debug("Exception thrown at invoking afterContainerStop", e);
                    } finally
                    {
                        finished.countDown();
                    }

                }


            }
        };

        public DockerizedProcess(final DockerClient dockerClient, final String containerId, final Closure afterContainerStop) throws Exception
        {
            this.dockerClient = dockerClient;
            this.containerId = containerId;
            this.afterContainerStop = afterContainerStop;
            attachStreams();
            dockerClient.waitContainerCmd(containerId).exec(waitContainerResultCallback);
        }

        private void attachStreams() throws Exception {
            dockerClient.attachContainerCmd(containerId)
                    .withFollowStream(true)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withStdIn(stdInReadStream)
                    .exec(attachContainerResultCallback);
            if (!attachContainerResultCallback.awaitStarted(10, TimeUnit.SECONDS)) {
                LOGGER.warn("Not attached to container "+containerId+" within 10secs");
                throw new RuntimeException("Not attached to container "+containerId+" within 10secs");
            }
        }

        @Override public OutputStream getOutputStream()
        {
            return stdInWriteStream;
        }

        @Override public InputStream getInputStream()
        {
            return stdOutReadStream;
        }

        @Override public InputStream getErrorStream()
        {
            return stdErrReadStream;
        }

        @Override public int waitFor() throws InterruptedException
        {
            finished.await();
            return exitCode.get();
        }

        @Override public int exitValue()
        {
            if (finished.getCount() > 0) throw new IllegalThreadStateException("docker process still running");
            return exitCode.get();
        }

        @Override public void destroy()
        {
            dockerClient.killContainerCmd(containerId).exec();
        }

        @Override
        public String toString() {
            return "Container "+containerId+" on "+dockerClient.toString();
        }
    }

}
