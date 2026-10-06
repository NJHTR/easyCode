package com.easycode.sandbox.windows;

import com.easycode.sandbox.api.SandboxBackend;
import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
import com.easycode.sandbox.model.SandboxLimits;
import com.easycode.sandbox.model.SandboxPolicy;
import com.easycode.sandbox.model.SandboxSpec;
import com.easycode.sandbox.model.SandboxStatus;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.ByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Windows backend that places every started process in a Job Object before
 * allowing it to run. It can optionally add an AppContainer security token.
 */
public final class WindowsJobObjectSandboxManager implements SandboxBackend {
    private static final Set<String> INHERITED_AGENT_CONFIGURATION = Set.of(
            "EASYCODE_LLM_API_KEY", "OPENAI_API_KEY", "EASYCODE_LLM_MODEL",
            "EASYCODE_LLM_BASE_URL", "EASYCODE_AGENT_MAX_STEPS");
    private static final int WAIT_TIMEOUT = 0x102;
    private static final int CREATE_SUSPENDED = 0x00000004;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final long PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009L;
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
    private static final int JOB_OBJECT_LIMIT_PROCESS_MEMORY = 0x00000100;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int GENERIC_EXECUTE = 0x20000000;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int OPEN_EXISTING = 3;
    private static final int CREATE_ALWAYS = 2;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int ERROR_SUCCESS = 0;
    private static final int SE_FILE_OBJECT = 1;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    private static final int SET_ACCESS = 2;
    private static final int TRUSTEE_IS_SID = 0;
    private static final int TRUSTEE_IS_WELL_KNOWN_GROUP = 5;
    private static final int SUB_CONTAINERS_AND_OBJECTS_INHERIT = 0x3;
    private static final int DELETE = 0x00010000;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final boolean appContainer;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "easycode-windows-sandbox");
        thread.setDaemon(true);
        return thread;
    });

    public WindowsJobObjectSandboxManager() throws SandboxException {
        this(false);
    }

    public WindowsJobObjectSandboxManager(boolean appContainer) throws SandboxException {
        if (!isWindows()) {
            throw new SandboxException("Windows Job Object backend requires Windows");
        }
        this.appContainer = appContainer;
    }

    @Override
    public SandboxHandle create(SandboxSpec spec) throws SandboxException {
        Objects.requireNonNull(spec, "spec");
        UUID id = UUID.randomUUID();
        SandboxHandle handle = new SandboxHandle(id, spec.alias());
        Session session = new Session(handle, spec);
        sessions.put(id, session);
        try {
            session.start();
            return handle;
        } catch (SandboxException exception) {
            sessions.remove(id);
            throw exception;
        }
    }

    @Override
    public SandboxInfo query(SandboxHandle handle) throws SandboxException {
        return session(handle).snapshot();
    }

    @Override
    public void destroy(SandboxHandle handle) throws SandboxException {
        session(handle).destroy();
    }

    @Override
    public void close() {
        sessions.values().forEach(Session::destroyQuietly);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private Session session(SandboxHandle handle) throws SandboxException {
        Objects.requireNonNull(handle, "handle");
        Session session = sessions.get(handle.id());
        if (session == null) {
            throw new SandboxException("sandbox not found: " + handle.id());
        }
        return session;
    }

    private final class Session {
        private final SandboxHandle handle;
        private final SandboxSpec spec;
        private final Instant createdAt = Instant.now();
        private final StringBuilder nativeError = new StringBuilder();

        private volatile SandboxStatus status = SandboxStatus.STARTING;
        private volatile boolean outputLimitExceeded;
        private volatile long processId = -1;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Integer exitCode;
        private volatile Path workDirectory;
        private volatile Path stdoutFile;
        private volatile Path stderrFile;
        private volatile NativeProcess nativeProcess;
        private volatile AppContainerIdentity appContainerIdentity;
        private volatile String completedOutput;
        private volatile String completedError;
        private final List<SecurityGrant> securityGrants = new ArrayList<>();

        private Session(SandboxHandle handle, SandboxSpec spec) {
            this.handle = handle;
            this.spec = spec;
        }

        private void start() throws SandboxException {
            try {
                if (appContainer && spec.policy().networkEnabled()) {
                    throw new SandboxException(
                            "AppContainer backend currently supports network denial only");
                }
                workDirectory = spec.workingDirectory() == null
                        ? Files.createTempDirectory("easycode-win-sandbox-")
                        : Files.createDirectories(spec.workingDirectory());
                appContainerIdentity = appContainer
                        ? AppContainerIdentity.create("easycode-sandbox-" + handle.id())
                        : null;
                if (appContainer) {
                    securityGrants.addAll(SecurityGrant.grantPath(
                            workDirectory, appContainerIdentity.sid, true));
                    for (Path path : spec.policy().readOnlyPaths()) {
                        securityGrants.addAll(SecurityGrant.grantPath(
                                path, appContainerIdentity.sid, false));
                    }
                    for (Path path : spec.policy().writablePaths()) {
                        securityGrants.addAll(SecurityGrant.grantPath(
                                path, appContainerIdentity.sid, true));
                    }
                }
                stdoutFile = Files.createTempFile(workDirectory, "stdout-", ".log");
                stderrFile = Files.createTempFile(workDirectory, "stderr-", ".log");
                nativeProcess = NativeProcess.start(
                        spec, workDirectory, stdoutFile, stderrFile, appContainerIdentity);
                processId = nativeProcess.processInfo.dwProcessId.intValue();
                startedAt = Instant.now();
                status = SandboxStatus.RUNNING;
                executor.submit(this::monitor);
            } catch (IOException | RuntimeException | SandboxException exception) {
                if (nativeProcess != null) {
                    nativeProcess.destroy();
                }
                closeAppContainerIdentity();
                closeSecurityGrants();
                status = SandboxStatus.FAILED;
                finishedAt = Instant.now();
                cleanupDirectory();
                throw new SandboxException("failed to start Windows sandbox " + handle.id(), exception);
            }
        }

        private void monitor() {
            SandboxStatus terminalStatus = SandboxStatus.FAILED;
            try {
                boolean completed = waitForCompletion();
                if (!completed) {
                    nativeProcess.terminateJobBestEffort();
                    terminalStatus = SandboxStatus.TIMED_OUT;
                } else {
                    exitCode = NativeProcess.exitCode(nativeProcess.processInfo.hProcess);
                    terminalStatus = outputLimitExceeded
                            ? SandboxStatus.OUTPUT_LIMIT
                            : (exitCode == 0 ? SandboxStatus.SUCCEEDED : SandboxStatus.FAILED);
                }
            } catch (RuntimeException exception) {
                synchronized (nativeError) {
                    nativeError.append("Windows sandbox monitor failed: ")
                            .append(exception.getMessage()).append('\n');
                }
            } finally {
                if (nativeProcess != null) {
                    nativeProcess.close();
                }
                closeAppContainerIdentity();
                closeSecurityGrants();
                completedOutput = readBounded(stdoutFile);
                completedError = readBounded(stderrFile) + nativeError();
                cleanupDirectory();
                synchronized (this) {
                    if (status != SandboxStatus.DESTROYED) {
                        status = terminalStatus;
                        finishedAt = Instant.now();
                    }
                }
            }
        }

        private void closeAppContainerIdentity() {
            if (appContainerIdentity != null) {
                appContainerIdentity.close();
                appContainerIdentity = null;
            }
        }

        private void closeSecurityGrants() {
            for (int index = securityGrants.size() - 1; index >= 0; index--) {
                securityGrants.get(index).close();
            }
            securityGrants.clear();
        }

        private boolean waitForCompletion() {
            long timeoutMillis = spec.limits().timeout().toMillis();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(
                        Math.max(0, deadline - System.nanoTime()));
                if (NativeProcess.waitFor(nativeProcess.processInfo.hProcess, Math.min(100, remaining))) {
                    if (outputExceeded()) {
                        markOutputLimitExceeded();
                        nativeProcess.terminateJobBestEffort();
                    }
                    return true;
                }
                if (outputExceeded()) {
                    markOutputLimitExceeded();
                    nativeProcess.terminateJobBestEffort();
                    return true;
                }
                if (System.nanoTime() >= deadline) {
                    return false;
                }
            }
        }

        private boolean outputExceeded() {
            long max = spec.limits().maxOutputChars();
            return fileSize(stdoutFile) > max || fileSize(stderrFile) > max;
        }

        private void markOutputLimitExceeded() {
            if (outputLimitExceeded) {
                return;
            }
            outputLimitExceeded = true;
            synchronized (nativeError) {
                nativeError.append("sandbox output exceeded ")
                        .append(spec.limits().maxOutputChars()).append(" characters\n");
            }
        }

        private long fileSize(Path file) {
            try {
                return file == null ? 0 : Files.size(file);
            } catch (IOException exception) {
                // A size read failure is treated as no output for limit checks;
                // the process monitor will still report its terminal status.
                return 0;
            }
        }

        private void destroy() {
            synchronized (this) {
                if (status == SandboxStatus.SUCCEEDED || status == SandboxStatus.FAILED
                        || status == SandboxStatus.TIMED_OUT
                        || status == SandboxStatus.OUTPUT_LIMIT
                        || status == SandboxStatus.DESTROYED) {
                    return;
                }
                status = SandboxStatus.DESTROYED;
                finishedAt = Instant.now();
            }
            if (nativeProcess != null) {
                nativeProcess.terminateJobBestEffort();
            }
        }

        private void destroyQuietly() {
            destroy();
        }

        private synchronized SandboxInfo snapshot() {
            return new SandboxInfo(
                    handle,
                    status,
                    processId,
                    createdAt,
                    startedAt,
                    finishedAt,
                    exitCode,
                    completedOutput == null ? readBounded(stdoutFile) : completedOutput,
                    completedError == null ? readBounded(stderrFile) + nativeError() : completedError);
        }

        private String nativeError() {
            synchronized (nativeError) {
                return nativeError.toString();
            }
        }

        private String readBounded(Path file) {
            if (file == null || Files.notExists(file)) {
                return "";
            }
            try {
                int max = (int) Math.min(spec.limits().maxOutputChars(), Integer.MAX_VALUE);
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] bytes = input.readNBytes(max);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (IOException exception) {
                return "failed to read sandbox output: " + exception.getMessage() + '\n';
            }
        }

        private void cleanupDirectory() {
            // Output files are implementation details even when the caller
            // supplied a persistent working directory.
            deleteIfExists(stdoutFile);
            deleteIfExists(stderrFile);
            if (workDirectory == null || spec.workingDirectory() != null) {
                return;
            }
            try {
                if (Files.notExists(workDirectory)) {
                    return;
                }
                try (var paths = Files.walk(workDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            // Cleanup must not mask the sandbox result.
                            path.toFile().deleteOnExit();
                        }
                    });
                }
            } catch (IOException exception) {
                // Cleanup must not mask the sandbox result.
                workDirectory.toFile().deleteOnExit();
            }
        }

        private void deleteIfExists(Path file) {
            if (file == null) {
                return;
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                // Cleanup must not mask the sandbox result.
                file.toFile().deleteOnExit();
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static final class AppContainerIdentity implements AutoCloseable {
        private final WString profileName;
        private final Pointer sid;

        private AppContainerIdentity(WString profileName, Pointer sid) {
            this.profileName = profileName;
            this.sid = sid;
        }

        private static AppContainerIdentity create(String name) throws IOException {
            PointerByReference sid = new PointerByReference();
            WString profileName = new WString(name);
            int result = UserEnv.INSTANCE.CreateAppContainerProfile(
                    profileName,
                    profileName,
                    new WString("easyCode sandbox"),
                    null,
                    0,
                    sid);
            if (result != 0 || sid.getValue() == null) {
                throw new IOException("CreateAppContainerProfile failed with HRESULT " + result);
            }
            return new AppContainerIdentity(profileName, sid.getValue());
        }

        private SecurityCapabilities securityCapabilities() {
            SecurityCapabilities capabilities = new SecurityCapabilities();
            capabilities.appContainerSid = sid;
            capabilities.capabilityCount = 0;
            capabilities.capabilities = null;
            capabilities.reserved = 0;
            capabilities.write();
            return capabilities;
        }

        @Override
        public void close() {
            int deleteResult = UserEnv.INSTANCE.DeleteAppContainerProfile(profileName);
            if (deleteResult != 0) {
                System.err.println("DeleteAppContainerProfile failed with HRESULT " + deleteResult);
            }
            Pointer freeResult = Advapi32Extra.INSTANCE.FreeSid(sid);
            if (freeResult != null) {
                System.err.println("FreeSid failed for AppContainer profile " + profileName);
            }
        }
    }

    private static final class SecurityGrant implements AutoCloseable {
        private final Path path;
        private final Pointer oldDacl;
        private final Pointer securityDescriptor;

        private SecurityGrant(Path path, Pointer oldDacl, Pointer securityDescriptor) {
            this.path = path;
            this.oldDacl = oldDacl;
            this.securityDescriptor = securityDescriptor;
        }

        private static List<SecurityGrant> grantPath(
                Path path, Pointer sid, boolean writable) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.notExists(normalized)) {
                throw new IOException("sandbox ACL path does not exist: " + normalized);
            }

            List<SecurityGrant> grants = new ArrayList<>();
            // Grant traversal only on the immediate parent. Do not walk up
            // through the user's profile or project tree: changing an
            // existing ancestor can trigger expensive ACL propagation and
            // would broaden the sandbox's access boundary.
            Path parent = normalized.getParent();
            try {
                if (parent != null && parent.getNameCount() > 2) {
                    grants.add(grant(parent, sid, GENERIC_READ | GENERIC_EXECUTE, false));
                }
                grants.add(grant(normalized, sid,
                        writable
                                ? GENERIC_READ | GENERIC_WRITE | GENERIC_EXECUTE | DELETE
                                : GENERIC_READ | GENERIC_EXECUTE,
                        true));
                return grants;
            } catch (IOException | RuntimeException exception) {
                for (int index = grants.size() - 1; index >= 0; index--) {
                    grants.get(index).close();
                }
                throw exception;
            }
        }

        private static SecurityGrant grant(
                Path path,
                Pointer sid,
                int permissions,
                boolean inheritToChildren) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();

            PointerByReference oldDacl = new PointerByReference();
            PointerByReference securityDescriptor = new PointerByReference();
            int result = Advapi32Extra.INSTANCE.GetNamedSecurityInfoW(
                    new WString(normalized.toString()),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION,
                    null,
                    null,
                    oldDacl,
                    null,
                    securityDescriptor);
            if (result != ERROR_SUCCESS) {
                throw new IOException("GetNamedSecurityInfoW failed with error " + result);
            }

            ExplicitAccessW direct = ExplicitAccessW.forSid(sid, permissions, 0);
            ExplicitAccessW inherited = ExplicitAccessW.forSid(
                    sid, permissions, SUB_CONTAINERS_AND_OBJECTS_INHERIT);
            ExplicitAccessW[] accessEntries = inheritToChildren
                    ? new ExplicitAccessW[]{direct, inherited}
                    : new ExplicitAccessW[]{direct};
            Memory entries = ExplicitAccessW.array(accessEntries);
            PointerByReference newDacl = new PointerByReference();
            result = Advapi32Extra.INSTANCE.SetEntriesInAclW(
                    accessEntries.length, entries, oldDacl.getValue(), newDacl);
            if (result != ERROR_SUCCESS) {
                NativeProcess.localFree(WindowsKernel32.INSTANCE, securityDescriptor.getValue());
                throw new IOException("SetEntriesInAclW failed with error " + result);
            }

            result = Advapi32Extra.INSTANCE.SetNamedSecurityInfoW(
                    new WString(normalized.toString()),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION,
                    null,
                    null,
                    newDacl.getValue(),
                    null);
            NativeProcess.localFree(WindowsKernel32.INSTANCE, newDacl.getValue());
            if (result != ERROR_SUCCESS) {
                NativeProcess.localFree(WindowsKernel32.INSTANCE, securityDescriptor.getValue());
                throw new IOException("SetNamedSecurityInfoW failed for " + normalized
                        + " with error " + result);
            }
            return new SecurityGrant(normalized, oldDacl.getValue(), securityDescriptor.getValue());
        }

        @Override
        public void close() {
            int result = Advapi32Extra.INSTANCE.SetNamedSecurityInfoW(
                    new WString(path.toString()),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION,
                    null,
                    null,
                    oldDacl,
                    null);
            if (result != ERROR_SUCCESS) {
                System.err.println("Failed to restore AppContainer ACL for " + path
                        + " with error " + result);
            }
            NativeProcess.localFree(WindowsKernel32.INSTANCE, securityDescriptor);
        }
    }

    /*
     * These mappings are public only because JNA reflects over public classes
     * and fields. They are not part of the SandboxBackend application API.
     */
    public static final class TrusteeW extends com.sun.jna.Structure {
        public Pointer multipleTrustee;
        public int multipleTrusteeOperation;
        public int trusteeForm;
        public int trusteeType;
        public Pointer name;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("multipleTrustee", "multipleTrusteeOperation", "trusteeForm",
                    "trusteeType", "name");
        }
    }

    public static final class ExplicitAccessW extends com.sun.jna.Structure {
        public int accessPermissions;
        public int accessMode;
        public int inheritance;
        public TrusteeW trustee = new TrusteeW();

        private static ExplicitAccessW forSid(Pointer sid, int permissions, int inheritance) {
            ExplicitAccessW access = new ExplicitAccessW();
            access.accessPermissions = permissions;
            access.accessMode = SET_ACCESS;
            access.inheritance = inheritance;
            access.trustee.multipleTrustee = null;
            access.trustee.multipleTrusteeOperation = 0;
            access.trustee.trusteeForm = TRUSTEE_IS_SID;
            access.trustee.trusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
            access.trustee.name = sid;
            access.write();
            return access;
        }

        private static Memory array(ExplicitAccessW... entries) {
            int size = entries[0].size();
            Memory memory = new Memory((long) size * entries.length);
            for (int index = 0; index < entries.length; index++) {
                memory.write((long) index * size,
                        entries[index].getPointer().getByteArray(0, size), 0, size);
            }
            return memory;
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("accessPermissions", "accessMode", "inheritance", "trustee");
        }
    }

    private static final class StartupConfiguration implements AutoCloseable {
        private final Pointer pointer;
        private final Memory attributeList;
        private final Memory startupMemory;
        private final SecurityCapabilities securityCapabilities;

        private StartupConfiguration(
                Pointer pointer,
                Memory attributeList,
                Memory startupMemory,
                SecurityCapabilities securityCapabilities) {
            this.pointer = pointer;
            this.attributeList = attributeList;
            this.startupMemory = startupMemory;
            this.securityCapabilities = securityCapabilities;
        }

        private static StartupConfiguration create(
                WinBase.STARTUPINFO startup,
                AppContainerIdentity appContainerIdentity) throws IOException {
            WindowsKernel32 api = WindowsKernel32.INSTANCE;
            if (appContainerIdentity == null) {
                startup.cb = new WinDef.DWORD(startup.size());
                startup.write();
                return new StartupConfiguration(startup.getPointer(), null, null, null);
            }

            SecurityCapabilities securityCapabilities = appContainerIdentity.securityCapabilities();
            SizeTByReference attributeListSize = new SizeTByReference();
            // The sizing call is documented to return FALSE while reporting
            // the required buffer size through the out parameter.
            boolean sizingCallSucceeded = api.InitializeProcThreadAttributeList(
                    null, 1, 0, attributeListSize);
            if (sizingCallSucceeded) {
                throw new IOException("InitializeProcThreadAttributeList(size) unexpectedly succeeded");
            }
            if (attributeListSize.getValue() <= 0) {
                throw new IOException(NativeProcess.lastError(api, "InitializeProcThreadAttributeList(size)"));
            }
            Memory attributeList = new Memory(attributeListSize.getValue());
            boolean attributeListInitialized = api.InitializeProcThreadAttributeList(
                    attributeList, 1, 0, attributeListSize);
            if (!attributeListInitialized) {
                throw new IOException(NativeProcess.lastError(api, "InitializeProcThreadAttributeList"));
            }
            Function updateAttribute = NativeLibrary.getInstance("kernel32")
                    .getFunction("UpdateProcThreadAttribute");
            int updateResult = (Integer) updateAttribute.invoke(
                    Integer.class,
                    new Object[]{
                            attributeList,
                            0,
                            PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
                            securityCapabilities.getPointer(),
                            (long) securityCapabilities.size(),
                            null,
                            null});
            if (updateResult == 0) {
                api.DeleteProcThreadAttributeList(attributeList);
                throw new IOException(NativeProcess.lastError(api, "UpdateProcThreadAttribute"));
            }

            int startupSize = startup.size();
            startup.cb = new WinDef.DWORD(startupSize + Native.POINTER_SIZE);
            startup.write();
            Memory startupEx = new Memory(startupSize + Native.POINTER_SIZE);
            startupEx.write(0, startup.getPointer().getByteArray(0, startupSize), 0, startupSize);
            startupEx.setPointer(startupSize, attributeList);
            return new StartupConfiguration(startupEx, attributeList, startupEx, securityCapabilities);
        }

        private Pointer pointer() {
            Reference.reachabilityFence(startupMemory);
            Reference.reachabilityFence(securityCapabilities);
            return pointer;
        }

        @Override
        public void close() {
            if (attributeList != null) {
                WindowsKernel32.INSTANCE.DeleteProcThreadAttributeList(attributeList);
            }
        }
    }

    public static final class SecurityCapabilities extends com.sun.jna.Structure {
        public Pointer appContainerSid;
        public Pointer capabilities;
        public int capabilityCount;
        public int reserved;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("appContainerSid", "capabilities", "capabilityCount", "reserved");
        }
    }

    public static final class SizeTByReference extends ByReference {
        public SizeTByReference() {
            super(Native.POINTER_SIZE);
        }

        public long getValue() {
            return Native.POINTER_SIZE == Long.BYTES
                    ? getPointer().getLong(0)
                    : Integer.toUnsignedLong(getPointer().getInt(0));
        }
    }

    private static final class NativeProcess {
        private final WinNT.HANDLE job;
        private final WinBase.PROCESS_INFORMATION processInfo;
        private boolean closed;

        private NativeProcess(WinNT.HANDLE job, WinBase.PROCESS_INFORMATION processInfo) {
            this.job = job;
            this.processInfo = processInfo;
        }

        private static NativeProcess start(
                SandboxSpec spec,
                Path workDirectory,
                Path stdoutFile,
                Path stderrFile,
                AppContainerIdentity appContainerIdentity) throws IOException {
            WindowsKernel32 api = WindowsKernel32.INSTANCE;
            WinNT.HANDLE job = api.CreateJobObjectW(null, null);
            if (invalid(job)) {
                throw new IOException(lastError(api, "CreateJobObjectW"));
            }

            try {
                applyJobLimits(api, job, spec.limits());
                WinBase.SECURITY_ATTRIBUTES inherit = inheritAttributes();
                WinNT.HANDLE stdin = api.CreateFileW(
                        new WString("NUL"), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                        inherit, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, null);
                WinNT.HANDLE stdout = api.CreateFileW(
                        new WString(stdoutFile.toString()), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                        inherit, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, null);
                WinNT.HANDLE stderr = api.CreateFileW(
                        new WString(stderrFile.toString()), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                        inherit, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, null);
                if (invalid(stdin) || invalid(stdout) || invalid(stderr)) {
                    close(api, stdin);
                    close(api, stdout);
                    close(api, stderr);
                    throw new IOException(lastError(api, "CreateFileW"));
                }

                WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
                startup.cb = new WinDef.DWORD(startup.size());
                startup.dwFlags = STARTF_USESTDHANDLES;
                startup.hStdInput = stdin;
                startup.hStdOutput = stdout;
                startup.hStdError = stderr;
                StartupConfiguration startupConfiguration = StartupConfiguration.create(
                        startup, appContainerIdentity);

                WinBase.PROCESS_INFORMATION processInfo = new WinBase.PROCESS_INFORMATION();
                Memory environment = environmentBlock(spec.environment());
                int flags = CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW
                        | (appContainerIdentity == null ? 0 : EXTENDED_STARTUPINFO_PRESENT);
                boolean created;
                try {
                    created = api.CreateProcessW(
                            null,
                            commandLine(spec.command()),
                            null,
                            null,
                            true,
                            flags,
                            environment,
                            new WString(workDirectory.toString()),
                            startupConfiguration.pointer(),
                            processInfo);
                } finally {
                    startupConfiguration.close();
                }
                close(api, stdin);
                close(api, stdout);
                close(api, stderr);
                if (!created) {
                    throw new IOException(lastError(api, "CreateProcessW"));
                }

                if (!api.AssignProcessToJobObject(job, processInfo.hProcess)) {
                    terminateProcess(api, processInfo.hProcess);
                    close(api, processInfo.hThread);
                    close(api, processInfo.hProcess);
                    throw new IOException(lastError(api, "AssignProcessToJobObject"));
                }
                if (api.ResumeThread(processInfo.hThread) == -1) {
                    terminateProcess(api, processInfo.hProcess);
                    close(api, processInfo.hThread);
                    close(api, processInfo.hProcess);
                    throw new IOException(lastError(api, "ResumeThread"));
                }
                close(api, processInfo.hThread);
                return new NativeProcess(job, processInfo);
            } catch (IOException | RuntimeException exception) {
                close(api, job);
                throw exception;
            }
        }

        private static void applyJobLimits(
                WindowsKernel32 api,
                WinNT.HANDLE job,
                SandboxLimits limits) throws IOException {
            JobObjectExtendedLimitInformation info = new JobObjectExtendedLimitInformation();
            int flags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            info.basicLimitInformation.activeProcessLimit = limits.maxProcessCount();
            flags |= JOB_OBJECT_LIMIT_ACTIVE_PROCESS;
            if (limits.maxMemoryBytes() > 0) {
                flags |= JOB_OBJECT_LIMIT_PROCESS_MEMORY;
                info.processMemoryLimit = new BaseTSD.SIZE_T(limits.maxMemoryBytes());
            }
            info.basicLimitInformation.limitFlags = flags;
            info.write();
            if (!api.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                    info.getPointer(), info.size())) {
                throw new IOException(lastError(api, "SetInformationJobObject"));
            }
        }

        private static WinBase.SECURITY_ATTRIBUTES inheritAttributes() {
            WinBase.SECURITY_ATTRIBUTES attributes = new WinBase.SECURITY_ATTRIBUTES();
            attributes.dwLength = new WinDef.DWORD(attributes.size());
            attributes.bInheritHandle = true;
            attributes.write();
            return attributes;
        }

        private static Memory environmentBlock(Map<String, String> overrides) {
            Map<String, String> environment = new LinkedHashMap<>(System.getenv());
            INHERITED_AGENT_CONFIGURATION.forEach(environment::remove);
            environment.putAll(overrides);
            StringBuilder block = new StringBuilder();
            environment.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .forEach(entry -> block.append(entry.getKey()).append('=').append(entry.getValue()).append('\0'));
            block.append('\0');
            Memory memory = new Memory((long) block.length() * Native.WCHAR_SIZE);
            for (int index = 0; index < block.length(); index++) {
                memory.setShort((long) index * Native.WCHAR_SIZE, (short) block.charAt(index));
            }
            return memory;
        }

        private static char[] commandLine(List<String> command) {
            StringBuilder line = new StringBuilder();
            for (String argument : command) {
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(quoteWindowsArgument(argument));
            }
            line.append('\0');
            return line.toString().toCharArray();
        }

        private static String quoteWindowsArgument(String value) {
            if (!value.isEmpty() && value.chars().noneMatch(character ->
                    character == ' ' || character == '\t' || character == '"')) {
                return value;
            }
            StringBuilder quoted = new StringBuilder("\"");
            int backslashes = 0;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '\\') {
                    backslashes++;
                } else if (character == '"') {
                    quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
                    backslashes = 0;
                } else {
                    quoted.append("\\".repeat(backslashes)).append(character);
                    backslashes = 0;
                }
            }
            quoted.append("\\".repeat(backslashes * 2)).append('"');
            return quoted.toString();
        }

        private static boolean waitFor(WinNT.HANDLE process, long timeoutMillis) {
            int millis = (int) Math.max(0, Math.min(timeoutMillis, Integer.MAX_VALUE - 1L));
            int result = WindowsKernel32.INSTANCE.WaitForSingleObject(process, millis);
            if (result == WinBase.WAIT_OBJECT_0) {
                return true;
            }
            if (result == WAIT_TIMEOUT) {
                return false;
            }
            throw new IllegalStateException("WaitForSingleObject failed with result " + result);
        }

        private static int exitCode(WinNT.HANDLE process) {
            IntByReference result = new IntByReference();
            if (!WindowsKernel32.INSTANCE.GetExitCodeProcess(process, result)) {
                return -1;
            }
            return result.getValue();
        }

        private void terminateJobBestEffort() {
            if (!WindowsKernel32.INSTANCE.TerminateJobObject(job, 1)) {
                System.err.println(lastError(WindowsKernel32.INSTANCE, "TerminateJobObject"));
            }
        }

        private void destroy() {
            terminateJobBestEffort();
            close();
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            close(WindowsKernel32.INSTANCE, processInfo.hProcess);
            close(WindowsKernel32.INSTANCE, job);
        }

        private static void terminateProcess(WindowsKernel32 api, WinNT.HANDLE process) {
            if (!api.TerminateProcess(process, 1)) {
                System.err.println(lastError(api, "TerminateProcess"));
            }
        }

        private static boolean invalid(WinNT.HANDLE handle) {
            return handle == null || handle.getPointer() == null
                    || Pointer.nativeValue(handle.getPointer()) == Pointer.nativeValue(WinBase.INVALID_HANDLE_VALUE.getPointer());
        }

        private static void close(WindowsKernel32 api, WinNT.HANDLE handle) {
            if (!invalid(handle)) {
                if (!api.CloseHandle(handle)) {
                    System.err.println(lastError(api, "CloseHandle"));
                }
            }
        }

        private static void localFree(WindowsKernel32 api, Pointer memory) {
            if (memory == null) {
                return;
            }
            Pointer result = api.LocalFree(memory);
            if (result != null) {
                System.err.println(lastError(api, "LocalFree"));
            }
        }

        private static String lastError(WindowsKernel32 api, String operation) {
            return operation + " failed with Win32 error " + api.GetLastError();
        }
    }

    // Native method names intentionally match the exported Win32 symbols.
    private interface WindowsKernel32 extends StdCallLibrary {
        WindowsKernel32 INSTANCE = Native.load("kernel32", WindowsKernel32.class);

        WinNT.HANDLE CreateJobObjectW(WinBase.SECURITY_ATTRIBUTES attributes, WString name);

        boolean SetInformationJobObject(WinNT.HANDLE job, int infoClass, Pointer info, int length);

        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

        boolean TerminateJobObject(WinNT.HANDLE job, int exitCode);

        boolean TerminateProcess(WinNT.HANDLE process, int exitCode);

        int ResumeThread(WinNT.HANDLE thread);

        boolean CreateProcessW(
                String applicationName,
                char[] commandLine,
                WinBase.SECURITY_ATTRIBUTES processAttributes,
                WinBase.SECURITY_ATTRIBUTES threadAttributes,
                boolean inheritHandles,
                int creationFlags,
                Pointer environment,
                WString currentDirectory,
                Pointer startupInfo,
                WinBase.PROCESS_INFORMATION processInformation);

        WinNT.HANDLE CreateFileW(
                WString fileName,
                int desiredAccess,
                int shareMode,
                WinBase.SECURITY_ATTRIBUTES securityAttributes,
                int creationDisposition,
                int flagsAndAttributes,
                WinNT.HANDLE templateFile);

        int WaitForSingleObject(WinNT.HANDLE handle, int milliseconds);

        boolean GetExitCodeProcess(WinNT.HANDLE process, IntByReference exitCode);

        boolean InitializeProcThreadAttributeList(
                Pointer attributeList,
                int attributeCount,
                int flags,
                SizeTByReference size);

        boolean UpdateProcThreadAttribute(
                Pointer attributeList,
                int flags,
                BaseTSD.SIZE_T attribute,
                Pointer value,
                BaseTSD.SIZE_T size,
                Pointer previousValue,
                SizeTByReference returnSize);

        void DeleteProcThreadAttributeList(Pointer attributeList);

        boolean CloseHandle(WinNT.HANDLE handle);

        Pointer LocalFree(Pointer memory);

        int GetLastError();
    }

    private interface UserEnv extends StdCallLibrary {
        UserEnv INSTANCE = Native.load("userenv", UserEnv.class);

        int CreateAppContainerProfile(
                WString appContainerName,
                WString displayName,
                WString description,
                Pointer capabilities,
                int capabilityCount,
                PointerByReference appContainerSid);

        int DeleteAppContainerProfile(WString appContainerName);
    }

    private interface Advapi32Extra extends StdCallLibrary {
        Advapi32Extra INSTANCE = Native.load("advapi32", Advapi32Extra.class);

        Pointer FreeSid(Pointer sid);

        int GetNamedSecurityInfoW(
                WString objectName,
                int objectType,
                int securityInfo,
                PointerByReference owner,
                PointerByReference group,
                PointerByReference dacl,
                PointerByReference sacl,
                PointerByReference securityDescriptor);

        int SetEntriesInAclW(
                int count,
                Pointer explicitEntries,
                Pointer oldAcl,
                PointerByReference newAcl);

        int SetNamedSecurityInfoW(
                WString objectName,
                int objectType,
                int securityInfo,
                Pointer owner,
                Pointer group,
                Pointer dacl,
                Pointer sacl);

    }

    public static final class IoCounters extends com.sun.jna.Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("readOperationCount", "writeOperationCount", "otherOperationCount",
                    "readTransferCount", "writeTransferCount", "otherTransferCount");
        }
    }

    public static final class JobObjectBasicLimitInformation extends com.sun.jna.Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public BaseTSD.SIZE_T minimumWorkingSetSize;
        public BaseTSD.SIZE_T maximumWorkingSetSize;
        public int activeProcessLimit;
        public BaseTSD.ULONG_PTR affinity;
        public int priorityClass;
        public int schedulingClass;

        public JobObjectBasicLimitInformation() {
            minimumWorkingSetSize = new BaseTSD.SIZE_T();
            maximumWorkingSetSize = new BaseTSD.SIZE_T();
            affinity = new BaseTSD.ULONG_PTR();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
                    "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit",
                    "affinity", "priorityClass", "schedulingClass");
        }
    }

    public static final class JobObjectExtendedLimitInformation extends com.sun.jna.Structure {
        public JobObjectBasicLimitInformation basicLimitInformation = new JobObjectBasicLimitInformation();
        public IoCounters ioInfo = new IoCounters();
        public BaseTSD.SIZE_T processMemoryLimit = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T jobMemoryLimit = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakProcessMemoryUsed = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakJobMemoryUsed = new BaseTSD.SIZE_T();

        @Override
        protected List<String> getFieldOrder() {
            return List.of("basicLimitInformation", "ioInfo", "processMemoryLimit",
                    "jobMemoryLimit", "peakProcessMemoryUsed", "peakJobMemoryUsed");
        }
    }
}
