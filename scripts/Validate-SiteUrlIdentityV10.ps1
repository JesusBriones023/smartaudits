<#
.SYNOPSIS
Validates V10 (site_url_identities) on an owned, disposable MariaDB 11.2.
.DESCRIPTION
Focused runner for F4.1-C Increment 1 (docs/F4_1C_SITE_URL_IDENTITY.md). It does not replace the F3
harness (Validate-SmartAudits20Harness.ps1) or Validate-FlywayPhase08.ps1.

Requires a running Docker daemon with the local mariadb:11.2 image (never pulled), JDK 17 and
Maven with every dependency cached for offline (-o) builds. -SelfTestOnly needs neither Docker nor
Maven: it runs the process-tree and GRANT self-tests only.

Owns exactly, per run: one container labelled smartaudits.v10-identity=<run> whose data lives in
tmpfs, three databases and two accounts inside it, and one temporary directory. It publishes
MariaDB only on a random 127.0.0.1 port and refuses 3306. It reads no .env file and connects to
no existing database; every password is random per run and redacted from output. The probe runs
on a hash-verified copy of the backend working tree; the repository is never written.

Accounts: the application user has USAGE globally and ALL PRIVILEGES on exactly its three owned
databases (underscores escaped, so no GRANT wildcard). The observer has PROCESS globally and
nothing else: it reads the InnoDB lock views that prove real lock waits and cannot read any table.
Both grant sets are parsed and checked before the probe runs.

Child processes: each one is created suspended, assigned to its own Windows Job Object
(kill-on-close, no breakaway), verified a member through its own handle and only then resumed, so it
and every descendant are members before any of their code runs. A failed assignment ends exactly
that never-run process through its handle and fails closed. Ownership is verified job membership,
never a parent process id. Termination is TerminateJobObject only, which reaches members and nothing
else. Waits, output draining and termination are bounded; a descendant that outlives its launcher is
a failure; every termination is logged and checked.
#>
[CmdletBinding()]
param([switch]$SelfTestOnly)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backendSource = Join-Path $repo 'smartaudits-backend'
$runId = [Guid]::NewGuid().ToString('N')
$label = 'smartaudits.v10-identity'
$resource = "smartaudits-v10-identity-$runId"
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $tempParent $resource
$backendCopy = Join-Path $tempRoot 'smartaudits-backend'
$user = 'v10_identity_user'
$observer = 'v10_identity_observer'
$databases = [ordered]@{
    V10_DB_FRESH = "v10_identity_${runId}_fresh"
    V10_DB_UPGRADE = "v10_identity_${runId}_upgrade"
    V10_DB_RACE = "v10_identity_${runId}_race"
}
$utf8 = New-Object Text.UTF8Encoding($false)
$secrets = New-Object 'System.Collections.Generic.List[string]'
$cleanupErrors = New-Object 'System.Collections.Generic.List[string]'
$ownedRecords = New-Object 'System.Collections.Generic.List[object]'
$killLog = New-Object 'System.Collections.Generic.List[string]'
$everOwned = @{}
$containerCreated = $false
$tempCreated = $false
$failure = $null
$baseEnv = @{}
foreach ($name in @('PATH','SystemRoot','WINDIR','ComSpec','PATHEXT','TEMP','TMP','HOME','USERPROFILE',
    'LOCALAPPDATA','APPDATA','ProgramData','ProgramFiles','ProgramFiles(x86)','JAVA_HOME','MAVEN_HOME','M2_HOME')) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ($null -ne $value) { $baseEnv[$name] = $value }
}
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

if (-not ('SmartAuditsV10.OwnedJob' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;
namespace SmartAuditsV10 {
    static class Native {
        [StructLayout(LayoutKind.Sequential)]
        public struct SecurityAttributes { public int Length; public IntPtr Descriptor; public bool Inherit; }
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        public struct StartupInfo {
            public int Size; public string Reserved; public string Desktop; public string Title;
            public int X; public int Y; public int XSize; public int YSize; public int XCountChars; public int YCountChars;
            public int FillAttribute; public int Flags; public short ShowWindow; public short Reserved2Size;
            public IntPtr Reserved2; public IntPtr StdInput; public IntPtr StdOutput; public IntPtr StdError;
        }
        [StructLayout(LayoutKind.Sequential)]
        public struct ProcessInformation { public IntPtr Process; public IntPtr Thread; public int ProcessId; public int ThreadId; }
        [StructLayout(LayoutKind.Sequential)]
        public struct BasicLimit {
            public long PerProcessUserTimeLimit; public long PerJobUserTimeLimit; public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize; public UIntPtr MaximumWorkingSetSize; public uint ActiveProcessLimit;
            public UIntPtr Affinity; public uint PriorityClass; public uint SchedulingClass;
        }
        [StructLayout(LayoutKind.Sequential)]
        public struct IoCounters { public ulong R; public ulong W; public ulong O; public ulong RB; public ulong WB; public ulong OB; }
        [StructLayout(LayoutKind.Sequential)]
        public struct ExtendedLimit {
            public BasicLimit Basic; public IoCounters Io; public UIntPtr ProcessMemoryLimit; public UIntPtr JobMemoryLimit;
            public UIntPtr PeakProcessMemoryUsed; public UIntPtr PeakJobMemoryUsed;
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] public static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref ExtendedLimit info, int length);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool TerminateJobObject(IntPtr job, uint exitCode);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool QueryInformationJobObject(IntPtr job, int infoClass, IntPtr info, int length, IntPtr returned);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool CreatePipe(out IntPtr read, out IntPtr write, ref SecurityAttributes attributes, int size);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetHandleInformation(IntPtr handle, int mask, int flags);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] public static extern bool CreateProcessW(string application, StringBuilder commandLine,
            IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string directory,
            ref StartupInfo startup, out ProcessInformation information);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool TerminateProcess(IntPtr process, uint exitCode);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool GetProcessTimes(IntPtr process, out long creation, out long exit, out long kernel, out long user);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern IntPtr OpenProcess(uint access, bool inherit, int processId);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] public static extern bool QueryFullProcessImageNameW(IntPtr process, int flags, StringBuilder name, ref int size);
        [DllImport("kernel32.dll", SetLastError = true)] public static extern bool CloseHandle(IntPtr handle);
        public static string Error() { return new Win32Exception(Marshal.GetLastWin32Error()).Message; }
    }

    /// <summary>Facts about one process, read through a handle opened on its id at that moment (never from a bare id).</summary>
    public sealed class ProcessFacts {
        public int Id; public DateTime StartUtc; public bool Alive; public bool InJob; public string Name;
    }

    /// <summary>A launch that failed after the process was created suspended: it had not run, and was ended through its own handle.</summary>
    public sealed class LaunchException : Exception {
        public readonly int ProcessId; public readonly DateTime StartUtc; public readonly bool Ended;
        public LaunchException(string message, int processId, DateTime startUtc, bool ended) : base(message) {
            ProcessId = processId; StartUtc = startUtc; Ended = ended;
        }
    }

    /// <summary>
    /// Count of this run's native handles that outlive a call: each job handle, each process handle and each
    /// output pipe read end. Start closes every other handle it creates (thread, child pipe ends) before it returns.
    /// </summary>
    public static class HandleLedger {
        static int open;
        public static int Open { get { return System.Threading.Volatile.Read(ref open); } }
        internal static void Opened() { System.Threading.Interlocked.Increment(ref open); }
        internal static void Closed() { System.Threading.Interlocked.Decrement(ref open); }
    }

    /// <summary>A process started by OwnedJob.Start. Its handle stays open, so its id cannot be reused while this object lives.</summary>
    public sealed class OwnedProcess : IDisposable {
        IntPtr process;
        public readonly int Id; public readonly DateTime StartUtc; public readonly Task<string> Out; public readonly Task<string> Err;
        internal OwnedProcess(IntPtr process, int id, DateTime startUtc, Task<string> output, Task<string> error) {
            this.process = process; Id = id; StartUtc = startUtc; Out = output; Err = error;
            HandleLedger.Opened();
        }
        public bool IsOpen { get { return process != IntPtr.Zero; } }
        public bool WaitForExit(int milliseconds) {
            if (process == IntPtr.Zero) throw new ObjectDisposedException("OwnedProcess");
            uint result = Native.WaitForSingleObject(process, (uint)milliseconds);
            if (result == 0xFFFFFFFF) throw new Win32Exception(Marshal.GetLastWin32Error());
            return result == 0;
        }
        public int ExitCode {
            get {
                if (!WaitForExit(0)) throw new InvalidOperationException("the process has not exited");
                uint code;
                if (!Native.GetExitCodeProcess(process, out code)) throw new Win32Exception(Marshal.GetLastWin32Error());
                return unchecked((int)code);
            }
        }
        /// <summary>Self-test only: assigns this already running process to another job, in the superseded start-then-assign order.</summary>
        public void AssignLateForSelfTest(OwnedJob job) { job.Assign(process); }
        public void Dispose() { if (process != IntPtr.Zero) { Native.CloseHandle(process); process = IntPtr.Zero; HandleLedger.Closed(); } }
    }

    /// <summary>
    /// A Windows Job Object owned by this run, with kill-on-close and no breakaway. Start creates each process suspended,
    /// assigns it, verifies its membership through its own handle and only then lets it run, so it and every descendant
    /// are members before a single instruction of theirs executes.
    /// </summary>
    public sealed class OwnedJob : IDisposable {
        const uint CreateSuspended = 0x4, CreateUnicodeEnvironment = 0x400, CreateNoWindow = 0x08000000;
        const uint QueryLimitedInformation = 0x1000, Synchronize = 0x100000, WaitTimeout = 0x102;
        IntPtr handle;
        public OwnedJob() {
            handle = Native.CreateJobObjectW(IntPtr.Zero, null);
            if (handle == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            Native.ExtendedLimit info = new Native.ExtendedLimit();
            info.Basic.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE; no BREAKAWAY_OK, so no descendant can leave
            if (!Native.SetInformationJobObject(handle, 9, ref info, Marshal.SizeOf(typeof(Native.ExtendedLimit)))) {
                int error = Marshal.GetLastWin32Error();
                Native.CloseHandle(handle);
                handle = IntPtr.Zero;
                throw new Win32Exception(error);
            }
            HandleLedger.Opened();
        }
        public bool IsOpen { get { return handle != IntPtr.Zero; } }
        internal void Assign(IntPtr process) {
            if (handle == IntPtr.Zero) throw new ObjectDisposedException("OwnedJob");
            if (!Native.AssignProcessToJobObject(handle, process)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }

        /// <summary>
        /// Starts application with commandLine, suspended; assigns it to this job; checks IsProcessInJob on its handle;
        /// runs whileSuspendedForSelfTest, if given; resumes it. Any failure ends exactly this never-run process through
        /// its own handle, closes every handle and throws LaunchException. Standard input is at end, output and error are
        /// read to the end asynchronously as UTF-8. failAssignmentForSelfTest makes the real assignment call fail.
        /// </summary>
        public OwnedProcess Start(string application, string commandLine, string directory, string[] environment,
                                  bool failAssignmentForSelfTest, Action<int, OwnedJob> whileSuspendedForSelfTest) {
            if (handle == IntPtr.Zero) throw new ObjectDisposedException("OwnedJob");
            IntPtr inRead = IntPtr.Zero, inWrite = IntPtr.Zero, outRead = IntPtr.Zero, outWrite = IntPtr.Zero, errRead = IntPtr.Zero, errWrite = IntPtr.Zero;
            Native.ProcessInformation created = new Native.ProcessInformation();
            bool started = false;
            GCHandle block = new GCHandle();
            try {
                Pipe(out inRead, out inWrite, false);
                Pipe(out outRead, out outWrite, true);
                Pipe(out errRead, out errWrite, true);
                Native.StartupInfo startup = new Native.StartupInfo();
                startup.Size = Marshal.SizeOf(typeof(Native.StartupInfo));
                startup.Flags = 0x100; // STARTF_USESTDHANDLES
                startup.StdInput = inRead; startup.StdOutput = outWrite; startup.StdError = errWrite;
                block = GCHandle.Alloc(EnvironmentBlock(environment), GCHandleType.Pinned);
                started = Native.CreateProcessW(application, new StringBuilder(commandLine), IntPtr.Zero, IntPtr.Zero, true,
                    CreateSuspended | CreateUnicodeEnvironment | CreateNoWindow, block.AddrOfPinnedObject(), directory, ref startup, out created);
                if (!started) throw new Win32Exception(Marshal.GetLastWin32Error());
            } finally {
                if (block.IsAllocated) block.Free();
                // The child has its own copies. The parent keeps only its two read ends; closing the input's write end gives end of input.
                Close(ref inRead); Close(ref inWrite); Close(ref outWrite); Close(ref errWrite);
                if (!started) { Close(ref outRead); Close(ref errRead); }
            }
            DateTime start = DateTime.MinValue;
            string failure = null;
            long creation, exited, kernel, user;
            if (Native.GetProcessTimes(created.Process, out creation, out exited, out kernel, out user)) start = DateTime.FromFileTimeUtc(creation);
            else failure = "has no readable creation time: " + Native.Error();
            if (failure == null && !Native.AssignProcessToJobObject(failAssignmentForSelfTest ? IntPtr.Zero : handle, created.Process)) {
                failure = "could not be placed in its owned job: " + Native.Error();
            }
            bool member = false;
            if (failure == null && !(Native.IsProcessInJob(created.Process, handle, out member) && member)) {
                failure = "is not a member of its owned job after assignment";
            }
            if (failure == null && whileSuspendedForSelfTest != null) {
                try { whileSuspendedForSelfTest(created.ProcessId, this); } catch (Exception e) { failure = "failed its check while suspended: " + e.Message; }
            }
            if (failure == null && Native.ResumeThread(created.Thread) == 0xFFFFFFFF) failure = "could not be resumed: " + Native.Error();
            Native.CloseHandle(created.Thread);
            if (failure != null) {
                bool ended = Native.TerminateProcess(created.Process, 1) && Native.WaitForSingleObject(created.Process, 10000) == 0;
                Native.CloseHandle(created.Process);
                Close(ref outRead); Close(ref errRead);
                throw new LaunchException(failure, created.ProcessId, start, ended);
            }
            return new OwnedProcess(created.Process, created.ProcessId, start, Read(outRead), Read(errRead));
        }

        static void Pipe(out IntPtr read, out IntPtr write, bool parentReads) {
            Native.SecurityAttributes attributes = new Native.SecurityAttributes();
            attributes.Length = Marshal.SizeOf(typeof(Native.SecurityAttributes));
            attributes.Inherit = true;
            if (!Native.CreatePipe(out read, out write, ref attributes, 0)) throw new Win32Exception(Marshal.GetLastWin32Error());
            if (!Native.SetHandleInformation(parentReads ? read : write, 1, 0)) { // the parent's end is never inherited
                int error = Marshal.GetLastWin32Error();
                Close(ref read); Close(ref write);
                throw new Win32Exception(error);
            }
        }
        static void Close(ref IntPtr h) { if (h != IntPtr.Zero) { Native.CloseHandle(h); h = IntPtr.Zero; } }
        static byte[] EnvironmentBlock(string[] variables) {
            List<string> sorted = new List<string>(variables);
            sorted.Sort(delegate(string a, string b) { return string.Compare(Key(a), Key(b), StringComparison.OrdinalIgnoreCase); });
            StringBuilder text = new StringBuilder();
            foreach (string variable in sorted) text.Append(variable).Append('\0');
            if (sorted.Count == 0) text.Append('\0');
            text.Append('\0');
            return Encoding.Unicode.GetBytes(text.ToString());
        }
        static string Key(string variable) { int i = variable.IndexOf('=', 1); return i < 0 ? variable : variable.Substring(0, i); }
        // The pipe read end is closed (by the SafeFileHandle, released once) before the task completes, so a completed
        // reader holds no handle.
        static async Task<string> Read(IntPtr pipe) {
            HandleLedger.Opened();
            try {
                using (SafeFileHandle owned = new SafeFileHandle(pipe, true))
                using (StreamReader reader = new StreamReader(new FileStream(owned, FileAccess.Read, 4096, false), new UTF8Encoding(false))) {
                    return await reader.ReadToEndAsync().ConfigureAwait(false);
                }
            } finally { HandleLedger.Closed(); }
        }

        /// <summary>Opens processId now and reports its creation time, liveness, image name and membership of job (null job: not checked).</summary>
        public static ProcessFacts Probe(int processId, OwnedJob job) {
            IntPtr process = Native.OpenProcess(QueryLimitedInformation | Synchronize, false, processId);
            if (process == IntPtr.Zero) return null;
            try {
                long creation, exited, kernel, user;
                if (!Native.GetProcessTimes(process, out creation, out exited, out kernel, out user)) return null;
                ProcessFacts facts = new ProcessFacts();
                facts.Id = processId;
                facts.StartUtc = DateTime.FromFileTimeUtc(creation);
                facts.Alive = Native.WaitForSingleObject(process, 0) == WaitTimeout;
                bool member = false;
                facts.InJob = job != null && job.handle != IntPtr.Zero && Native.IsProcessInJob(process, job.handle, out member) && member;
                StringBuilder name = new StringBuilder(1024);
                int size = name.Capacity;
                facts.Name = Native.QueryFullProcessImageNameW(process, 0, name, ref size) ? Path.GetFileNameWithoutExtension(name.ToString(0, size)) : "?";
                return facts;
            } finally { Native.CloseHandle(process); }
        }

        public int[] ProcessIds() {
            if (handle == IntPtr.Zero) return new int[0]; // closed: the kill-on-close limit already ended any member
            const int capacity = 4096;
            int size = 8 + IntPtr.Size * capacity;
            IntPtr buffer = Marshal.AllocHGlobal(size);
            try {
                if (!Native.QueryInformationJobObject(handle, 3, buffer, size, IntPtr.Zero)) throw new Win32Exception(Marshal.GetLastWin32Error());
                int assigned = Marshal.ReadInt32(buffer, 0);
                int listed = Marshal.ReadInt32(buffer, 4);
                if (assigned != listed) throw new InvalidOperationException("Job process list truncated");
                int[] ids = new int[listed];
                for (int i = 0; i < listed; i++) ids[i] = (int)Marshal.ReadIntPtr(buffer, 8 + i * IntPtr.Size).ToInt64();
                return ids;
            } finally { Marshal.FreeHGlobal(buffer); }
        }
        int Accounting(int offset) {
            if (handle == IntPtr.Zero) return 0;
            IntPtr buffer = Marshal.AllocHGlobal(48); // JOBOBJECT_BASIC_ACCOUNTING_INFORMATION
            try {
                if (!Native.QueryInformationJobObject(handle, 1, buffer, 48, IntPtr.Zero)) throw new Win32Exception(Marshal.GetLastWin32Error());
                return Marshal.ReadInt32(buffer, offset);
            } finally { Marshal.FreeHGlobal(buffer); }
        }
        /// <summary>The kernel's count of live members: authoritative, and independent of any process id.</summary>
        public int ActiveProcesses() { return Accounting(40); }
        /// <summary>Every process that was ever a member.</summary>
        public int TotalProcesses() { return Accounting(36); }
        /// <summary>Ends every member, and only members: the kernel applies it to the job, never to a process id.</summary>
        public void Terminate(uint exitCode) {
            if (handle == IntPtr.Zero) return;
            if (!Native.TerminateJobObject(handle, exitCode)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        public void Dispose() {
            if (handle != IntPtr.Zero) { Native.CloseHandle(handle); handle = IntPtr.Zero; HandleLedger.Closed(); }
        }
    }
}
'@
}
# Add-Type is skipped when the types already exist in this session; an older build without the ledger fails closed.
if ($null -eq ('SmartAuditsV10.HandleLedger' -as [type])) { throw 'An older SmartAuditsV10 type is loaded in this PowerShell session; run the runner in a new process' }
$ledgerBaseline = [SmartAuditsV10.HandleLedger]::Open

function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Pass([string]$Message) { Write-Host "PASS $Message" }
function Protect([string]$Text) {
    foreach ($secret in $secrets) { $Text = $Text.Replace($secret, '[REDACTED]') }
    return [regex]::Replace($Text, "PASSWORD '\*[0-9A-F]{40}'", "PASSWORD '[REDACTED]'")
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $value = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    $secrets.Add($value)
    return $value
}
# mysql_native_password hash, so the observer's plaintext password never appears on a command line.
function Get-NativePasswordHash([string]$Password) {
    $sha = [Security.Cryptography.SHA1]::Create()
    try { $hash = $sha.ComputeHash($sha.ComputeHash($utf8.GetBytes($Password))) } finally { $sha.Dispose() }
    $text = '*' + [BitConverter]::ToString($hash).Replace('-', '')
    $secrets.Add($text)
    return $text
}
function Quote-Argument([string]$Value) {
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

# ---------------------------------------------------------------- owned process trees

function Test-SameProcess($Identity) {
    $now = [SmartAuditsV10.OwnedJob]::Probe($Identity.Id, $null)
    return ($null -ne $now) -and $now.Alive -and ($now.StartUtc -eq $Identity.Start)
}
function Register-Owned($Record, $Identity) {
    $key = "$($Identity.Id)@$($Identity.Start.Ticks)"
    if (-not $Record.Members.ContainsKey($key)) { $Record.Members[$key] = $Identity }
    $everOwned[$key] = $Identity
}
# Ownership is Job Object membership verified through a handle: a listed id is owned only when the
# process opened under it is alive and, at that moment, a member of this run's job. No parent id is
# read, so an exited parent, a reused parent id or a reused child id can never adopt another process.
# $Probe maps an id to its facts (Id, StartUtc, Alive, InJob, Name) or $null; self-tests pass a mock.
function Select-OwnedMembers([int[]]$Listed, [scriptblock]$Probe) {
    $members = [ordered]@{}
    foreach ($id in $Listed) {
        $facts = & $Probe $id
        if ($null -eq $facts -or -not $facts.Alive -or -not $facts.InJob) { continue }
        $members["$($facts.Id)@$($facts.StartUtc.Ticks)"] = [pscustomobject]@{ Id = $facts.Id; Start = $facts.StartUtc; Name = $facts.Name }
    }
    return @($members.Values)
}
function Get-JobProbe($Job) {
    return { param($id) [SmartAuditsV10.OwnedJob]::Probe($id, $Job) }.GetNewClosure()
}
# The live, verified members of a record's job. A closed job has none: kill-on-close ended them.
function Get-OwnedTree($Record) {
    if (-not $Record.Job.IsOpen) { return @() }
    $members = @(Select-OwnedMembers @($Record.Job.ProcessIds()) (Get-JobProbe $Record.Job))
    foreach ($member in $members) { Register-Owned $Record $member }
    return $members
}
# Ends the job with TerminateJobObject, which the kernel applies to members only, so no other process
# can be reached; there is no termination by process id. Repeated while members remain (one may be
# starting at that moment), and bounded. It returns once the job has no active member and every member
# it ended is gone (the job's count drops slightly before a process finishes its rundown).
function Stop-OwnedTree($Record, $Errors) {
    if (-not $Record.Job.IsOpen -or $Record.Job.ActiveProcesses() -eq 0) { return }
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $ended = New-Object 'System.Collections.Generic.List[object]'
    while ($true) {
        foreach ($member in @(Get-OwnedTree $Record)) {
            $ended.Add($member)
            $killLog.Add("$($Record.Name): $($member.Name) pid=$($member.Id) start=$($member.Start.ToString('o'))")
        }
        try { $Record.Job.Terminate(1) } catch { $Errors.Add("$($Record.Name): job termination failed: $($_.Exception.Message)"); return }
        $settle = [DateTime]::UtcNow.AddSeconds(2)
        do {
            $alive = @($ended | Where-Object { Test-SameProcess $_ })
            if ($Record.Job.ActiveProcesses() -eq 0 -and $alive.Count -eq 0) { return }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $settle)
        if ([DateTime]::UtcNow -ge $deadline) { break }
    }
    $Errors.Add("$($Record.Name): after termination, $($Record.Job.ActiveProcesses()) job member(s) active and $($alive.Count) ended member(s) not yet gone")
}
function Get-LaunchException($Exception) {
    for ($e = $Exception; $null -ne $e; $e = $e.InnerException) { if ($e -is [SmartAuditsV10.LaunchException]) { return $e } }
    return $null
}
# A full path from PATH (never the current directory), passed to CreateProcess as the application.
function Resolve-Executable([string]$Exe) {
    if ([IO.Path]::IsPathRooted($Exe)) {
        Assert-That (Test-Path -LiteralPath $Exe -PathType Leaf) "Missing executable $Exe"
        return $Exe
    }
    return @(Get-Command -Name $Exe -CommandType Application -ErrorAction Stop)[0].Source
}
# The process is created suspended, placed in its own job, verified a member through its handle and
# only then resumed (see OwnedJob.Start). A failure ends exactly that never-run process and throws.
function Start-OwnedProcess([string]$Name, [string]$Exe, [string[]]$Arguments, [string]$Cwd, [hashtable]$Extra = @{},
        [switch]$FailAssignmentForSelfTest, [Action[int,SmartAuditsV10.OwnedJob]]$WhileSuspendedForSelfTest = $null) {
    $path = Resolve-Executable $Exe
    if ([IO.Path]::GetFileName($path) -ieq 'cmd.exe') {
        Assert-That ($Arguments.Count -eq 4 -and $Arguments[0] -eq '/d' -and $Arguments[1] -eq '/s' -and $Arguments[2] -eq '/c') 'Unexpected shell invocation'
        $commandLine = '"' + $path + '" /d /s /c "' + $Arguments[3] + '"'
    } else {
        $commandLine = (@('"' + $path + '"') + @($Arguments | ForEach-Object { Quote-Argument $_ })) -join ' '
    }
    $environment = @{}
    foreach ($key in $baseEnv.Keys) { $environment[$key] = $baseEnv[$key] }
    foreach ($key in $Extra.Keys) { $environment[$key] = [string]$Extra[$key] }
    $block = [string[]]@($environment.Keys | ForEach-Object { "$_=$($environment[$_])" })
    $job = New-Object SmartAuditsV10.OwnedJob
    try {
        $process = $job.Start($path, $commandLine, $Cwd, $block, [bool]$FailAssignmentForSelfTest, $WhileSuspendedForSelfTest)
    } catch {
        $launch = Get-LaunchException $_.Exception
        $job.Dispose()
        if ($null -eq $launch) { throw }
        # Created, never run, ended through its own handle: recorded as an owned process and a termination.
        $identity = [pscustomobject]@{ Id = $launch.ProcessId; Start = $launch.StartUtc; Name = [IO.Path]::GetFileNameWithoutExtension($path) }
        $everOwned["$($identity.Id)@$($identity.Start.Ticks)"] = $identity
        $killLog.Add("${Name}: $($identity.Name) pid=$($identity.Id) start=$($identity.Start.ToString('o'))")
        $suffix = if ($launch.Ended) { '' } else { ' (and it could not be confirmed ended)' }
        throw (New-Object SmartAuditsV10.LaunchException("$Name $($launch.Message)$suffix", $launch.ProcessId, $launch.StartUtc, $launch.Ended))
    }
    $record = [pscustomobject]@{ Name = $Name; Process = $process; Job = $job; Members = @{}; Out = $process.Out; Err = $process.Err
        Root = [pscustomobject]@{ Id = $process.Id; Start = $process.StartUtc; Name = [IO.Path]::GetFileNameWithoutExtension($path) } }
    Register-Owned $record $record.Root
    $ownedRecords.Add($record)
    return $record
}
function Wait-Drained($Record, [int]$Milliseconds) {
    return [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($Record.Out, $Record.Err), $Milliseconds)
}
# Runs to completion. Primary failure first; cleanup failures are appended, never replace it.
function Complete-Owned($Record, [int]$TimeoutSeconds, [switch]$AllowNonZero) {
    $errors = New-Object 'System.Collections.Generic.List[string]'
    $primary = $null
    $Record | Add-Member -Force -NotePropertyName Survivors -NotePropertyValue @()
    $Record | Add-Member -Force -NotePropertyName DrainedBeforeStop -NotePropertyValue $false
    $exited = $Record.Process.WaitForExit($TimeoutSeconds * 1000)
    if (-not $exited) {
        $primary = "$($Record.Name) timed out after $TimeoutSeconds s"
    } else {
        # The kernel's count of live members decides. A console host may take a moment to end after its
        # last client; a member still alive after this grace period has outlived its launcher.
        $grace = [DateTime]::UtcNow.AddSeconds(3)
        while ($Record.Job.ActiveProcesses() -gt 0 -and [DateTime]::UtcNow -lt $grace) { Start-Sleep -Milliseconds 50 }
        if ($Record.Job.ActiveProcesses() -gt 0) {
            $survivors = @(Get-OwnedTree $Record)
            $Record.Survivors = $survivors
            $primary = "$($Record.Name): descendant(s) survived launcher exit: " + (($survivors | ForEach-Object { "$($_.Name) pid=$($_.Id)" }) -join ', ')
        }
    }
    if ($null -ne $primary) {
        $Record.DrainedBeforeStop = Wait-Drained $Record 0
        Stop-OwnedTree $Record $errors
    }
    $drained = Wait-Drained $Record 15000
    if (-not $drained) {
        if ($null -eq $primary) { $primary = "$($Record.Name): output pipe still held open after the launcher exited" }
        Stop-OwnedTree $Record $errors
        $drained = Wait-Drained $Record 10000
        if (-not $drained) { $errors.Add("$($Record.Name): output capture incomplete after termination (not waited for further)") }
    }
    $remaining = $Record.Job.ActiveProcesses()
    if ($remaining -gt 0) { $errors.Add("$($Record.Name): $remaining owned process(es) still alive: " + ((@(Get-OwnedTree $Record) | ForEach-Object { "pid=$($_.Id)" }) -join ', ')) }
    $output = ''
    if ($drained) { $output = $Record.Out.GetAwaiter().GetResult() + $Record.Err.GetAwaiter().GetResult() }
    $Record | Add-Member -Force -NotePropertyName Output -NotePropertyValue $output
    if ($remaining -eq 0) { $Record.Job.Dispose() }
    if ($null -eq $primary -and $errors.Count -eq 0 -and -not $AllowNonZero -and $Record.Process.ExitCode -ne 0) {
        $tail = $output.Substring([Math]::Max(0, $output.Length - 6000))
        $primary = "$($Record.Name) failed (exit $($Record.Process.ExitCode)); final $($tail.Length) of $($output.Length) characters: $(Protect $tail)"
    }
    if ($null -ne $primary -or $errors.Count -gt 0) {
        $message = if ($null -ne $primary) { $primary } else { "$($Record.Name) cleanup failed" }
        if ($errors.Count -gt 0) { $message += ' | cleanup: ' + ($errors -join ' | ') }
        throw (Protect $message)
    }
    return $output.Trim()
}
function Native([string]$Name, [string]$Exe, [string[]]$Arguments, [string]$Cwd = $repo, [hashtable]$Extra = @{}, [int]$Timeout = 120) {
    return Complete-Owned (Start-OwnedProcess $Name $Exe $Arguments $Cwd $Extra) $Timeout
}
function Docker([string[]]$Arguments, [hashtable]$Extra = @{}) { return Native "docker $($Arguments[0])" 'docker' $Arguments $repo $Extra 120 }

# ---------------------------------------------------------------- native handle release

# Releases the native handles of every record in $Records: a still-active tree is first ended through its job
# (members only), then the job handle (kill-on-close) and the process handle are closed, in that order, whatever
# the stop did. Each step is guarded on its own: a failure is recorded and the other handle, and every later
# record, is still released. A record counts as released only when its wrappers report both handles closed.
# A record whose release failed is marked ReleaseFailed and never retried (its handle state is uncertain); it
# stays open, so Assert-OwnedHandlesReleased reports it. Idempotent: released and failed records are skipped,
# and Dispose never closes a released handle again. Returns how many records this call released.
function Close-OwnedRecords($Records, $Errors) {
    $released = 0
    foreach ($record in @($Records.ToArray())) {
        try {
            if ($null -ne $record.PSObject.Properties['ReleaseFailed']) { continue }
            if (-not $record.Job.IsOpen -and -not $record.Process.IsOpen) { continue }
            try {
                if ($record.Job.IsOpen -and $record.Job.ActiveProcesses() -gt 0) {
                    $Errors.Add("$($record.Name) still had owned processes at cleanup")
                    $stop = New-Object 'System.Collections.Generic.List[string]'
                    Stop-OwnedTree $record $stop
                    foreach ($e in $stop) { $Errors.Add($e) }
                }
            } catch { $Errors.Add("process cleanup: $($record.Name): $($_.Exception.Message)") }
            $failed = New-Object 'System.Collections.Generic.List[string]'
            try { $record.Job.Dispose() } catch { $failed.Add('job'); $Errors.Add("job handle: $($record.Name): $($_.Exception.Message)") }
            try { $record.Process.Dispose() } catch { $failed.Add('process'); $Errors.Add("process handle: $($record.Name): $($_.Exception.Message)") }
            if ($failed.Count -eq 0 -and ($record.Job.IsOpen -or $record.Process.IsOpen)) {
                $failed.Add('open after Dispose')
                $Errors.Add("handles: $($record.Name): Dispose returned but a handle is still open")
            }
            if ($failed.Count -gt 0) { $record | Add-Member -Force -NotePropertyName ReleaseFailed -NotePropertyValue ($failed -join ',') }
            else { $released++ }
        } catch {
            $record | Add-Member -Force -NotePropertyName ReleaseFailed -NotePropertyValue 'uncertain'
            $Errors.Add("release: $($record.Name): $($_.Exception.Message)")
        }
    }
    return $released
}
# After the final release: no record holds a job or process handle, both output readers of each record have
# completed (a reader closes its pipe read end before it completes; the write ends belonged to job members, all
# ended), and the ledger of open native handles is back at $Baseline. Bounded.
function Assert-OwnedHandlesReleased($Records, [int]$Baseline, $Errors) {
    $held = @($Records | Where-Object { $_.Job.IsOpen -or $_.Process.IsOpen })
    if ($held.Count -gt 0) { $Errors.Add("handles: $($held.Count) record(s) still hold a job or process handle: $(($held | ForEach-Object { $_.Name }) -join ', ')") }
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $reading = @($Records | Where-Object { -not $_.Out.IsCompleted -or -not $_.Err.IsCompleted })
        $open = [SmartAuditsV10.HandleLedger]::Open
        if ($reading.Count -eq 0 -and $open -eq $Baseline) { return }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($reading.Count -gt 0) { $Errors.Add("handles: $($reading.Count) output reader(s) still hold a pipe: $(($reading | ForEach-Object { $_.Name }) -join ', ')") }
    if ($open -ne $Baseline) { $Errors.Add("handles: $($open - $Baseline) owned native handle(s) still open (ledger $open, baseline $Baseline)") }
}
# The whole cleanup: a first release (ends any straggler before the steps touch Docker or the repository), each
# step in order (a failure is recorded and the next step still runs), then a final release after the last step,
# so every record created at any point, including by a cleanup or verification step, is released; then the
# handle accounting. Nothing after the final release launches a process.
function Invoke-OwnedCleanup($Records, $Steps, [int]$Baseline, $Errors) {
    $first = Close-OwnedRecords $Records $Errors
    foreach ($step in $Steps) {
        try { & $step.Run } catch { $Errors.Add("$($step.Name): $(Protect $_.Exception.Message)") }
    }
    $final = Close-OwnedRecords $Records $Errors
    Assert-OwnedHandlesReleased $Records $Baseline $Errors
    return [pscustomobject]@{ First = $first; Final = $final }
}
# The outcome message: the validation failure first, never replaced; cleanup failures appended, never hidden.
function Get-RunFailureMessage([string]$Primary, $Errors) {
    if ($Primary) {
        $message = "V10 VALIDATION FAILED: $Primary"
        if ($Errors.Count -gt 0) { $message += " | CLEANUP ALSO FAILED: $($Errors -join ' | ')" }
        return (Protect $message)
    }
    if ($Errors.Count -gt 0) { return (Protect "V10 CLEANUP FAILED: $($Errors -join ' | ')") }
    return $null
}

# ---------------------------------------------------------------- GRANT validation

# Parses SHOW GRANTS lines. Allowed: exactly one global line with exactly $GlobalPrivileges, and
# for each owned database exactly one "ALL PRIVILEGES ON `db`.*" line whose name has its
# underscores escaped (an unescaped _ is a GRANT wildcard). Anything else is a violation:
# other privileges or scopes (table, column, routine, proxy), roles, WITH GRANT OPTION.
function Get-GrantViolations([string[]]$Lines, [string]$Grantee, [string]$GlobalPrivileges, [string[]]$Databases) {
    $violations = New-Object 'System.Collections.Generic.List[string]'
    $grantee = '`' + $Grantee + '`@`%`'
    $expected = @{}
    foreach ($database in $Databases) { $expected[$database.Replace('_', '\_')] = 0 }
    $global = 0
    $pattern = '^GRANT (?<privs>[A-Z][A-Z ,]*?) ON (?<scope>\*\.\*|`(?<db>[^`]+)`\.\*|\S+) TO (?<to>`[^`]*`@`[^`]*`)(?<tail>.*)$'
    foreach ($line in $Lines) {
        $text = $line.Trim()
        if (-not $text) { continue }
        $match = [regex]::Match($text, $pattern)
        if (-not $match.Success) { $violations.Add("unparsed or non-privilege grant: $(Protect $text)"); continue }
        $tail = $match.Groups['tail'].Value
        if ($match.Groups['to'].Value -cne $grantee) { $violations.Add("grant for another account: $($match.Groups['to'].Value)") }
        if ($tail -and $tail -notmatch "^ IDENTIFIED BY PASSWORD '\*[0-9A-F]{40}'$") { $violations.Add("unexpected grant clause: $(Protect $tail)") }
        $privs = $match.Groups['privs'].Value
        $scope = $match.Groups['scope'].Value
        if ($scope -ceq '*.*') {
            $global++
            if ($privs -cne $GlobalPrivileges) { $violations.Add("global privileges '$privs' instead of '$GlobalPrivileges'") }
        } elseif ($match.Groups['db'].Success -and $expected.ContainsKey($match.Groups['db'].Value)) {
            $expected[$match.Groups['db'].Value]++
            if ($privs -cne 'ALL PRIVILEGES') { $violations.Add("database privileges '$privs' on $scope") }
        } else {
            $violations.Add("grant outside the owned databases: $privs ON $scope")
        }
    }
    if ($global -ne 1) { $violations.Add("expected exactly one global grant line, found $global") }
    foreach ($database in $expected.Keys) {
        if ($expected[$database] -ne 1) { $violations.Add("expected exactly one grant on ``$database``.*, found $($expected[$database])") }
    }
    return $violations.ToArray() # callers always wrap the result in @()
}

# ---------------------------------------------------------------- self-tests (no Docker, no Maven)

function Test-GrantParser {
    $dbs = @("v10_identity_${runId}_fresh", "v10_identity_${runId}_upgrade")
    $hash = "IDENTIFIED BY PASSWORD '*" + ('A' * 40) + "'"
    $app = @("GRANT USAGE ON *.* TO ``$user``@``%`` $hash") + @($dbs | ForEach-Object { "GRANT ALL PRIVILEGES ON ``$($_.Replace('_','\_'))``.* TO ``$user``@``%``" })
    Assert-That (@(Get-GrantViolations $app $user 'USAGE' $dbs).Count -eq 0) 'Exact application grants rejected'
    Assert-That (@(Get-GrantViolations @("GRANT PROCESS ON *.* TO ``$observer``@``%`` $hash") $observer 'PROCESS' @()).Count -eq 0) 'Exact observer grants rejected'
    # The review's bypass: privileges named before ON, which the old pattern (searching after TO) never saw.
    $bypass = "GRANT ALL PRIVILEGES ON *.* TO ``$user``@``%``"
    Assert-That (-not ($bypass -match 'ON \*\.\*\s+TO.*(ALL|SUPER|PROCESS|FILE)')) 'Old pattern is expected to miss the bypass'
    $first = $dbs[0].Replace('_','\_')
    $cases = [ordered]@{
        'ALL PRIVILEGES globally (original bypass)' = @{ Lines = @($bypass) + $app[1..2]; Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = "global privileges 'ALL PRIVILEGES' instead of 'USAGE'" }
        'SELECT globally' = @{ Lines = @("GRANT SELECT ON *.* TO ``$user``@``%`` $hash") + $app[1..2]; Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = "global privileges 'SELECT' instead of 'USAGE'" }
        'USAGE and PROCESS globally' = @{ Lines = @("GRANT PROCESS, SELECT ON *.* TO ``$observer``@``%`` $hash"); Global = 'PROCESS'; Who = $observer; Dbs = @(); Expect = "global privileges 'PROCESS, SELECT' instead of 'PROCESS'" }
        'WITH GRANT OPTION' = @{ Lines = @("GRANT USAGE ON *.* TO ``$user``@``%`` $hash WITH GRANT OPTION") + $app[1..2]; Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'unexpected grant clause' }
        'foreign database' = @{ Lines = $app + @("GRANT ALL PRIVILEGES ON ``smartaudits``.* TO ``$user``@``%``"); Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'grant outside the owned databases: ALL PRIVILEGES ON `smartaudits`.*' }
        'unescaped wildcard name' = @{ Lines = @($app[0], "GRANT ALL PRIVILEGES ON ``$($dbs[0])``.* TO ``$user``@``%``", $app[2]); Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'grant outside the owned databases' }
        'table-level grant' = @{ Lines = $app + @("GRANT SELECT ON ``$first``.``sites`` TO ``$user``@``%``"); Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'grant outside the owned databases' }
        'partial database privileges' = @{ Lines = @($app[0], "GRANT SELECT, INSERT ON ``$first``.* TO ``$user``@``%``", $app[2]); Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = "database privileges 'SELECT, INSERT'" }
        'proxy grant' = @{ Lines = $app + @("GRANT PROXY ON ''@'%' TO ``$user``@``%``"); Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'grant outside the owned databases: PROXY' }
        'role grant' = @{ Lines = $app + @("GRANT ``admin_role`` TO ``$user``@``%``"); Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'unparsed or non-privilege grant' }
        'missing database grant' = @{ Lines = $app[0..1]; Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'expected exactly one grant on' }
        'duplicate global line' = @{ Lines = $app + @($app[0]); Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'expected exactly one global grant line, found 2' }
        'other grantee' = @{ Lines = @("GRANT USAGE ON *.* TO ``root``@``%`` $hash") + $app[1..2]; Global = 'USAGE'; Who = $user; Dbs = $dbs; Expect = 'grant for another account' }
        'observer with a database' = @{ Lines = @("GRANT PROCESS ON *.* TO ``$observer``@``%`` $hash", "GRANT SELECT ON ``$first``.* TO ``$observer``@``%``"); Global = 'PROCESS'; Who = $observer; Dbs = @(); Expect = 'grant outside the owned databases' }
    }
    foreach ($case in $cases.Keys) {
        $c = $cases[$case]
        $found = @(Get-GrantViolations $c.Lines $c.Who $c.Global $c.Dbs)
        Assert-That (@($found | Where-Object { $_.Contains($c.Expect) }).Count -ge 1) "GRANT self-test '$case' not rejected as expected: $($found -join '; ')"
    }
    Pass "GRANT parser: exact application and observer grants accepted; $($cases.Count) negatives rejected, including ALL PRIVILEGES ON *.* (missed by the old pattern)"
}

# The ancestry rule of the second-review runner, kept only as the negative control of Test-OwnershipRules:
# it adopted children by parent id and, once a reused parent id's process had exited, could no longer tell
# that reuser's children from its own descendants.
function Get-SupersededAncestryAdoption($Tracked, $Live) {
    $adopted = New-Object 'System.Collections.Generic.List[int]'
    foreach ($parent in $Tracked) {
        $now = @($Live | Where-Object { $_.Id -eq $parent.Id }) | Select-Object -First 1
        $reusedAt = if ($null -ne $now -and $now.StartUtc -ne $parent.Start) { $now.StartUtc } else { [DateTime]::MaxValue }
        foreach ($child in @($Live | Where-Object { $_.Parent -eq $parent.Id })) {
            if ($child.StartUtc -ge $parent.Start -and $child.StartUtc -lt $reusedAt) { $adopted.Add($child.Id) }
        }
    }
    return $adopted.ToArray()
}

# Ownership rules on mocked process metadata (no process is created or touched), then one live check.
function Test-OwnershipRules {
    $t0 = New-Object DateTime 2026, 11, 25, 10, 0, 0, ([DateTimeKind]::Utc)
    $fact = { param($Id, $Seconds, $Alive, $InJob, $Parent)
        [pscustomobject]@{ Id = $Id; StartUtc = $t0.AddSeconds($Seconds); Alive = $Alive; InJob = $InJob; Parent = $Parent; Name = 'worker' } }
    $select = { param($Now, [int[]]$Listed)
        $probe = { param($id) if ($Now.ContainsKey($id)) { $Now[$id] } else { $null } }.GetNewClosure()
        return @(Select-OwnedMembers $Listed $probe | ForEach-Object { $_.Id } | Sort-Object) }

    # The review's case: owned 50; its descendant 100 exited; an unrelated process reused 100 and exited
    # too; that process's child 200 is alive, outside the job, with parent id 100.
    $owner = & $fact 50 0 $true $true 0
    $reusedChild = & $fact 200 30 $true $false 100
    $now = @{ 50 = $owner; 200 = $reusedChild } # what opening each id finds now: nothing at 100
    $tracked = @([pscustomobject]@{ Id = 50; Start = $t0 }, [pscustomobject]@{ Id = 100; Start = $t0.AddSeconds(10) })
    $old = @(Get-SupersededAncestryAdoption $tracked @($owner, $reusedChild))
    Assert-That ($old -contains 200) 'Negative control: the superseded ancestry rule was expected to adopt pid 200'
    Assert-That ((@(& $select $now @(50)) -join ',') -ceq '50') 'PID 50/100/200: the job list must give exactly 50'
    Assert-That ((@(& $select $now @(50, 100, 200)) -join ',') -ceq '50') 'PID 50/100/200: even a list naming 100 and 200 must give exactly 50'

    $cases = [ordered]@{
        # Parent 60 exited before its child 61; the child is still a member.
        'parent exits before child' = @{ Now = @{ 61 = (& $fact 61 5 $true $true 60) }; Listed = @(60, 61); Expect = '61' }
        # 70 was reused by an unrelated process; 71, the real descendant, stays.
        'parent pid reused' = @{ Now = @{ 70 = (& $fact 70 50 $true $false 1); 71 = (& $fact 71 5 $true $true 70) }; Listed = @(70, 71); Expect = '71' }
        # The job listed 81, which exited and was reused by an unrelated process before it was opened.
        'child pid reused' = @{ Now = @{ 81 = (& $fact 81 90 $true $false 1) }; Listed = @(81); Expect = '' }
        'legitimate descendants remain' = @{ Now = @{ 90 = (& $fact 90 0 $true $true 1); 91 = (& $fact 91 3 $true $true 90) }; Listed = @(90, 91); Expect = '90,91' }
        # Same name, parent id of an owned process, started after it: still not a member, so not owned.
        'unrelated with similar metadata' = @{ Now = @{ 90 = (& $fact 90 0 $true $true 1); 95 = (& $fact 95 4 $true $false 90) }; Listed = @(90, 95); Expect = '90' }
        'exited or unreadable' = @{ Now = @{ 96 = (& $fact 96 1 $false $true 1) }; Listed = @(96, 97); Expect = '' }
    }
    foreach ($case in $cases.Keys) {
        $actual = (@(& $select $cases[$case].Now $cases[$case].Listed)) -join ','
        Assert-That ($actual -ceq $cases[$case].Expect) "Ownership rule '$case': expected '$($cases[$case].Expect)', got '$actual'"
    }

    # Live: this runner's own process, listed against an owned empty job, is not owned; ending that job reaches nobody.
    $empty = New-Object SmartAuditsV10.OwnedJob
    try {
        Assert-That (-not ([SmartAuditsV10.OwnedJob]::Probe($PID, $empty)).InJob) 'The runner was taken for a member of its own job'
        Assert-That (@(Select-OwnedMembers @($PID) (Get-JobProbe $empty)).Count -eq 0) 'A foreign live process was selected as owned'
        $empty.Terminate(1)
        Assert-That ($empty.ActiveProcesses() -eq 0 -and $empty.TotalProcesses() -eq 0) 'The empty job gained a member'
    } finally { $empty.Dispose() }
    Pass "Ownership: PID 50/100/200 gives exactly 50 (the superseded ancestry rule adopts 200); $($cases.Count) more mocked cases: $($cases.Keys -join '; '); a foreign live process is never selected"
}

function Test-ProcessTrees {
    $ownedBefore = $everOwned.Count
    $killsBefore = $killLog.Count
    # D. Normal completion: output kept, nothing survives, no termination.
    $normal = Native 'self-test normal' $baseEnv.ComSpec @('/d','/s','/c','echo self-test-ok') $tempParent @{} 30
    Assert-That ($normal -ceq 'self-test-ok') "Normal output changed: $normal"
    Assert-That ($killLog.Count -eq $killsBefore) 'Normal completion terminated a process'
    Pass 'Process D: normal completion; output intact; no survivor; no termination'

    # G. Nothing runs before containment. While the root is still suspended it is already a verified member
    # (IsProcessInJob on its handle), the job's only process ever, without a child, and its first command
    # (writing a marker) has not run even after 1.5 s. It runs only once resumed.
    $marker = Join-Path $tempParent "smartaudits-v10-$runId-suspended.marker"
    $evidence = @{}
    $check = [Action[int,SmartAuditsV10.OwnedJob]]{
        param([int]$id, $job)
        $facts = [SmartAuditsV10.OwnedJob]::Probe($id, $job)
        $evidence.Member = ($null -ne $facts) -and $facts.InJob -and $facts.Alive
        $evidence.Active = $job.ActiveProcesses()
        $evidence.Total = $job.TotalProcesses()
        $evidence.Children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" -Property ProcessId -ErrorAction Stop).Count
        $evidence.MarkerAtStart = Test-Path -LiteralPath $marker
        Start-Sleep -Milliseconds 1500
        $evidence.MarkerAfterWait = Test-Path -LiteralPath $marker
    }
    $contained = Start-OwnedProcess 'self-test suspended until contained' $baseEnv.ComSpec @('/d','/s','/c',"echo contained>`"$marker`"") $tempParent @{} -WhileSuspendedForSelfTest $check
    $null = Complete-Owned $contained 30
    Assert-That ($evidence.Member -and $evidence.Active -eq 1 -and $evidence.Total -eq 1 -and $evidence.Children -eq 0) "Containment before execution not shown: $(($evidence.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ' ')"
    Assert-That (-not $evidence.MarkerAtStart -and -not $evidence.MarkerAfterWait) 'The process ran while it was suspended'
    Assert-That (Test-Path -LiteralPath $marker) 'The process did not run once resumed'
    Remove-Item -LiteralPath $marker -Force
    Assert-That (-not (Test-Path -LiteralPath $marker)) 'Self-test marker still present'
    Pass "Process G: suspended pid $($contained.Root.Id) was a verified job member, the job's only process ever (active 1, total 1), with no child, its first command not run after 1.5 s; it ran only after resumption"

    # H. Assignment failure: the real AssignProcessToJobObject call fails (self-test switch). The process,
    # created suspended, never runs; it is ended through its own handle and the launch fails closed.
    $marker = Join-Path $tempParent "smartaudits-v10-$runId-assignment.marker"
    $recordsBefore = $ownedRecords.Count
    $failed = $null
    try {
        $null = Start-OwnedProcess 'self-test assignment failure' $baseEnv.ComSpec @('/d','/s','/c',"echo ran>`"$marker`"") $tempParent @{} -FailAssignmentForSelfTest
    } catch { $failed = Get-LaunchException $_.Exception }
    Assert-That ($null -ne $failed) 'An assignment failure did not fail the launch'
    Assert-That ($failed.Message -like '*could not be placed in its owned job*') "Unexpected launch failure: $($failed.Message)"
    Assert-That $failed.Ended 'The never-run process was not confirmed ended'
    Start-Sleep -Milliseconds 1000
    Assert-That (-not (Test-Path -LiteralPath $marker)) 'The process ran although it was never contained'
    Assert-That (-not (Test-SameProcess ([pscustomobject]@{ Id = $failed.ProcessId; Start = $failed.StartUtc }))) 'The failed launch left its process alive'
    Assert-That ($ownedRecords.Count -eq $recordsBefore) 'A failed launch was registered as running'
    Pass "Process H: assignment failure (real API error) fails closed; pid $($failed.ProcessId) never ran (no marker after 1 s), ended through its own handle, nothing left running"

    # A. Child timeout: the root and its tree are terminated within a bound.
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $reason = ''
    try { $null = Native 'self-test timeout' $powershell @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 120') $tempParent @{} 3 } catch { $reason = $_.Exception.Message }
    $record = $ownedRecords[$ownedRecords.Count - 1]
    Assert-That ($reason -like '*self-test timeout timed out after 3 s*') "Timeout not reported: $reason"
    Assert-That (-not (Test-SameProcess $record.Root)) 'Timed-out root still alive'
    Assert-That ($timer.Elapsed.TotalSeconds -lt 40) "Timeout cleanup unbounded: $($timer.Elapsed.TotalSeconds) s"
    Pass "Process A: timeout reported; owned root terminated; $([int]$timer.Elapsed.TotalSeconds) s"

    # B. A descendant outlives its launcher without holding the output pipe (ShellExecute, no inheritance).
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $reason = ''
    $launch = "Start-Process -WindowStyle Hidden -FilePath '$powershell' -ArgumentList '-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 120'; [Console]::Out.WriteLine('launched')"
    try { $null = Native 'self-test orphan' $powershell @('-NoProfile','-NonInteractive','-Command',$launch) $tempParent @{} 60 } catch { $reason = $_.Exception.Message }
    $record = $ownedRecords[$ownedRecords.Count - 1]
    Assert-That ($reason -like '*descendant(s) survived launcher exit*') "Surviving descendant not reported: $reason"
    Assert-That ($record.Process.ExitCode -eq 0) 'Launcher was expected to exit 0 on its own'
    Assert-That (@($record.Survivors).Count -ge 1) 'Survivor not identified'
    foreach ($survivor in $record.Survivors) { Assert-That (-not (Test-SameProcess $survivor)) "Survivor pid $($survivor.Id) still alive" }
    Assert-That ($timer.Elapsed.TotalSeconds -lt 60) 'Orphan cleanup unbounded'
    Pass "Process B: launcher exit 0 with a surviving descendant (verified job member pid $(($record.Survivors | ForEach-Object { $_.Id }) -join ',')) is a failure; ended with the job; pipe drained before termination=$($record.DrainedBeforeStop)"

    # C. A descendant inherits and holds the output pipe after its launcher exits.
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $reason = ''
    try { $null = Native 'self-test pipe holder' $baseEnv.ComSpec @('/d','/s','/c',"start /b $powershell -NoProfile -NonInteractive -Command Start-Sleep -Seconds 120") $tempParent @{} 60 } catch { $reason = $_.Exception.Message }
    $record = $ownedRecords[$ownedRecords.Count - 1]
    Assert-That ($reason -like '*survived launcher exit*') "Pipe-holding descendant not reported: $reason"
    Assert-That (-not $record.DrainedBeforeStop) 'The pipe was expected to be held open by the descendant'
    Assert-That (Wait-Drained $record 0) 'Output did not drain after termination'
    foreach ($survivor in $record.Survivors) { Assert-That (-not (Test-SameProcess $survivor)) "Pipe holder pid $($survivor.Id) still alive" }
    Assert-That ($timer.Elapsed.TotalSeconds -lt 60) "Pipe-holder cleanup unbounded: $($timer.Elapsed.TotalSeconds) s"
    Pass "Process C: immediate spawn then launcher exit; the descendant (verified job member pid $(($record.Survivors | ForEach-Object { $_.Id }) -join ',')) held the pipe; no hang ($([int]$timer.Elapsed.TotalSeconds) s); ended with the job; output drained"

    # E. Termination of an already finished tree is a no-op, repeated or not.
    $done = Start-OwnedProcess 'self-test finished' $baseEnv.ComSpec @('/d','/s','/c','exit 0') $tempParent
    Assert-That ($done.Process.WaitForExit(30000) -and (Wait-Drained $done 15000)) 'Finished process did not complete'
    # Finished means the whole tree: its console host, a member too, ends just after it, on its own.
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ($done.Job.ActiveProcesses() -gt 0 -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50 }
    Assert-That ($done.Job.ActiveProcesses() -eq 0) 'The finished tree did not end on its own'
    $kills = $killLog.Count
    $errors = New-Object 'System.Collections.Generic.List[string]'
    Stop-OwnedTree $done $errors
    Stop-OwnedTree $done $errors
    Assert-That ($errors.Count -eq 0 -and $killLog.Count -eq $kills -and @($done.Job.ProcessIds()).Count -eq 0) 'Termination of a finished tree was not a no-op'
    $null = Complete-Owned $done 5
    Pass 'Process E: terminating a finished tree twice is a no-op; no termination attempted'

    # K. Negative control of the superseded order (start, then assign): a fast launcher's first children
    # escape a job assigned afterwards. Here the launcher runs contained in its owned job, so nothing can
    # escape; it starts two children at once and is then assigned late to a second, nested job. The children
    # are members of the owned job only, and end with it.
    $control = Start-OwnedProcess 'self-test late assignment control' $baseEnv.ComSpec @('/d','/s','/c','start /b ping -n 120 127.0.0.1 >nul & ping -n 120 127.0.0.1 >nul') $tempParent
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (@(Get-OwnedTree $control | Where-Object { $_.Name -eq 'PING' }).Count -lt 2 -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
    $late = New-Object SmartAuditsV10.OwnedJob
    $errors = New-Object 'System.Collections.Generic.List[string]'
    try {
        $control.Process.AssignLateForSelfTest($late)
        $inLate = @(Select-OwnedMembers @($late.ProcessIds()) (Get-JobProbe $late))
        $inOwned = @(Get-OwnedTree $control)
        $missed = @($inOwned | Where-Object { $_.Name -eq 'PING' -and @($inLate | Where-Object Id -eq $_.Id).Count -eq 0 })
        Assert-That (@($inLate | Where-Object Id -eq $control.Root.Id).Count -eq 1) 'The launcher was not placed in the late job'
        Assert-That ($missed.Count -ge 2) "The late job was expected to miss the earlier children: late=$(($inLate | ForEach-Object { "$($_.Name):$($_.Id)" }) -join ','), owned=$(($inOwned | ForEach-Object { "$($_.Name):$($_.Id)" }) -join ',')"
    } finally {
        Stop-OwnedTree $control $errors
        $late.Dispose()
    }
    Assert-That ($errors.Count -eq 0) "Control cleanup failed: $($errors -join '; ')"
    $null = Complete-Owned $control 10 -AllowNonZero
    foreach ($member in $inOwned) { Assert-That (-not (Test-SameProcess $member)) "Control member pid $($member.Id) still alive" }
    Pass "Process K (negative control): a job assigned after start missed $($missed.Count) children the launcher started first (pids $(($missed | ForEach-Object { $_.Id }) -join ',')); all were members of the job assigned before execution and ended with it"

    # A process whose start time differs is never treated as owned (pid reuse), then the ownership rules.
    $self = [SmartAuditsV10.OwnedJob]::Probe($PID, $null)
    $impostor = [pscustomobject]@{ Id = $self.Id; Start = $self.StartUtc.AddSeconds(-5); Name = $self.Name }
    Assert-That (-not (Test-SameProcess $impostor)) 'A reused pid was taken for an owned process'
    Pass 'Identity: a pid with another start time is not owned and is never terminated'
    Test-OwnershipRules

    # Every termination targeted a process this run created; nothing it created is still alive.
    foreach ($entry in @($killLog | Select-Object -Skip $killsBefore)) {
        Assert-That ($entry -match 'pid=(\d+) start=(\S+)$') "Unparseable kill log: $entry"
        $key = "$($Matches[1])@$(([DateTime]::Parse($Matches[2], $null, [Globalization.DateTimeStyles]::RoundtripKind)).Ticks)"
        Assert-That ($everOwned.ContainsKey($key)) "Terminated a process that this run did not own: $entry"
    }
    $alive = @($everOwned.Values | Where-Object { Test-SameProcess $_ })
    Assert-That ($alive.Count -eq 0) "Owned processes still alive: $(($alive | ForEach-Object { $_.Id }) -join ',')"
    Pass "Process trees: $($everOwned.Count - $ownedBefore) owned processes tracked, $($killLog.Count - $killsBefore) terminations, all owned; none alive"
}

# The superseded cleanup, kept only as the negative control of Test-HandleLifecycle: it released the records that
# existed before its steps ran, so each record a step created kept its process handle for the life of the runner.
function Invoke-SupersededSnapshotCleanup($Records, $Steps, $Errors) {
    foreach ($record in @($Records.ToArray())) {
        try {
            if ($record.Job.IsOpen -and $record.Job.ActiveProcesses() -gt 0) { Stop-OwnedTree $record $Errors }
            $record.Job.Dispose()
            $record.Process.Dispose()
        } catch { $Errors.Add("process cleanup: $($record.Name): $($_.Exception.Message)") }
    }
    foreach ($step in $Steps) { try { & $step.Run } catch { $Errors.Add("$($step.Name): $($_.Exception.Message)") } }
}

# Cleanup lifecycle on isolated record lists with the real Invoke-OwnedCleanup. External operations are mocked by
# owned synthetic processes in the same shape as the real final steps: container (3 launches: ps, rm, ps), temp
# (none), docker state (2: ps, volume ls) and repository (2: git rev-parse, git status), i.e. the seven final
# launches of a successful run.
function Test-HandleLifecycle {
    $exit = { param([string]$Name, [int]$Code = 0)
        Native "self-test final $Name" $baseEnv.ComSpec @('/d','/s','/c',"exit $Code") $tempParent @{} 30 }
    $finalSteps = { param([scriptblock]$Container = $null, [scriptblock]$Repository = $null)
        $c = if ($Container) { $Container } else { { $null = & $exit 'docker ps'; $null = & $exit 'docker rm'; $null = & $exit 'docker ps' } }
        $r = if ($Repository) { $Repository } else { { $null = & $exit 'git rev-parse'; $null = & $exit 'git status' } }
        return @([pscustomobject]@{ Name = 'container'; Run = $c }, [pscustomobject]@{ Name = 'temp'; Run = { } },
            [pscustomobject]@{ Name = 'docker state'; Run = { $null = & $exit 'docker ps -a'; $null = & $exit 'docker volume ls' } },
            [pscustomobject]@{ Name = 'repository'; Run = $r }) }
    $handles = { $self = [Diagnostics.Process]::GetCurrentProcess(); try { $self.Refresh(); $self.HandleCount } finally { $self.Dispose() } }
    $settle = { [GC]::Collect(); [GC]::WaitForPendingFinalizers(); [GC]::Collect() }
    # One isolated lifecycle: the run's record list is swapped for a fresh one while $Validation and the cleanup
    # run, so only this scenario's records are counted; they are handed back to the run's list afterwards, whose
    # own final release must then find nothing left (idempotency in place).
    $scenario = { param([scriptblock]$Validation, $Steps, [switch]$Superseded)
        $saved = $script:ownedRecords
        $records = New-Object 'System.Collections.Generic.List[object]'
        $errors = New-Object 'System.Collections.Generic.List[string]'
        $baseline = [SmartAuditsV10.HandleLedger]::Open
        $primary = $null
        $result = $null
        $script:ownedRecords = $records
        try {
            try { & $Validation } catch { $primary = $_.Exception.Message }
            if ($Superseded) { Invoke-SupersededSnapshotCleanup $records $Steps $errors }
            else { $result = Invoke-OwnedCleanup $records $Steps $baseline $errors }
        } finally {
            $script:ownedRecords = $saved
            # Native records only: fault-injection mocks hold no native handle and stay out of the run's accounting.
            foreach ($r in $records) { if ($r.Process -is [SmartAuditsV10.OwnedProcess]) { $saved.Add($r) } }
        }
        # Readers have finished (Complete-Owned drained them), so the ledger has settled.
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        while (@($records | Where-Object { -not $_.Out.IsCompleted -or -not $_.Err.IsCompleted }).Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50 }
        # ToArray: in Windows PowerShell 5.1, @() of a List[object] throws "argument types do not match".
        return [pscustomobject]@{ Primary = $primary; Errors = $errors.ToArray(); Records = $records.ToArray(); Result = $result
            Leaked = [SmartAuditsV10.HandleLedger]::Open - $baseline
            Held = @($records | Where-Object { $_.Job.IsOpen -or $_.Process.IsOpen }) }
    }
    $closed = { param($s, [string]$What)
        Assert-That ($s.Held.Count -eq 0) "${What}: $($s.Held.Count) record(s) still hold a handle: $(($s.Held | ForEach-Object { $_.Name }) -join ', ')"
        Assert-That ($s.Leaked -eq 0) "${What}: handle ledger delta $($s.Leaked), expected 0"
        foreach ($r in $s.Records) { Assert-That ($r.Out.IsCompleted -and $r.Err.IsCompleted) "${What}: reader of $($r.Name) still holds its pipe" }
        foreach ($r in $s.Records) { if ($r.PSObject.Properties['Root']) { Assert-That (-not (Test-SameProcess $r.Root)) "${What}: $($r.Name) still alive" } } }
    $warm = & $scenario { $null = & $exit 'warm-up' } (& $finalSteps)
    & $closed $warm 'Warm-up'

    # Negative control: the superseded snapshot cleanup leaves exactly the seven final-step process handles open.
    $old = & $scenario { $null = & $exit 'validation' } (& $finalSteps) -Superseded
    $oldHeld = @($old.Records | Where-Object { $_.Process.IsOpen })
    Assert-That ($old.Errors.Count -eq 0) "Negative control errors: $($old.Errors -join '; ')"
    Assert-That ($oldHeld.Count -eq 7 -and $old.Leaked -eq 7) "Negative control: expected 7 leaked process handles, got $($oldHeld.Count) records / ledger $($old.Leaked)"
    Assert-That (@($oldHeld | Where-Object { $_.Name -like 'self-test final docker *' }).Count -eq 5 -and @($oldHeld | Where-Object { $_.Name -like 'self-test final git *' }).Count -eq 2) 'Negative control: the leaked records are not the 5 Docker + 2 Git final launches'
    $errors = New-Object 'System.Collections.Generic.List[string]'
    $fixedLeft = Close-OwnedRecords ([System.Collections.Generic.List[object]]$old.Records) $errors
    Assert-That ($fixedLeft -eq 7 -and $errors.Count -eq 0 -and @($old.Records | Where-Object { $_.Process.IsOpen -or $_.Job.IsOpen }).Count -eq 0) 'The leaked records of the negative control were not released'
    Pass "Handles (negative control): the superseded snapshot cleanup left 7 process handles open (5 final Docker + 2 final Git launches, ledger +$($old.Leaked)); released afterwards"

    # 1-3. Successful path: 2 validation launches, then the 7 final launches; all released after the last step.
    $ok = & $scenario { $null = & $exit 'validation'; $null = & $exit 'validation 2' } (& $finalSteps)
    Assert-That ($null -eq $ok.Primary -and $ok.Errors.Count -eq 0) "Success path errors: $($ok.Primary) $($ok.Errors -join '; ')"
    Assert-That ($ok.Records.Count -eq 9 -and $ok.Result.First -eq 2 -and $ok.Result.Final -eq 7) "Success path: expected 9 records, 2 released first and 7 finally; got $($ok.Records.Count), $($ok.Result.First), $($ok.Result.Final)"
    & $closed $ok 'Success path'
    Assert-That (@($ok.Records | Where-Object { $_.Name -like 'self-test final docker *' }).Count -eq 5) 'Success path: expected 5 final Docker records'
    Assert-That (@($ok.Records | Where-Object { $_.Name -like 'self-test final git *' }).Count -eq 2) 'Success path: expected 2 final Git records'
    Pass 'Handles 1-3: success path; 9 records (2 validation, 5 final Docker, 2 final Git); 2 released before the steps, 7 after; every job and process handle closed, every reader complete, ledger delta 0'

    # 4. The first final verification command fails (nonzero exit, as a failing docker ps): the step stops, its
    # record is still released, later steps still run and are released too.
    $first = & $scenario { $null = & $exit 'validation' } (& $finalSteps -Container { $null = & $exit 'docker ps (fails)' 3; $null = & $exit 'docker rm' })
    Assert-That ($first.Errors.Count -eq 1 -and $first.Errors[0] -like 'container: self-test final docker ps (fails) failed (exit 3)*') "First-command failure not reported alone: $($first.Errors -join '; ')"
    Assert-That (@($first.Records | Where-Object { $_.Name -eq 'self-test final docker rm' }).Count -eq 0 -and $first.Records.Count -eq 6) "First-command failure: expected 6 records without the skipped docker rm, got $($first.Records.Count)"
    & $closed $first 'First-command failure'
    Pass 'Handles 4: the first final command failed (exit 3); reported as a container error; its record and the 4 later final records released; ledger delta 0'

    # 5. A later verification command throws while its process is still running (as an interrupted git status):
    # the final release ends that tree through its job and closes its handles.
    $later = & $scenario { $null = & $exit 'validation' } (& $finalSteps -Repository {
        $null = & $exit 'git rev-parse'
        $null = Start-OwnedProcess 'self-test final git status (interrupted)' $baseEnv.ComSpec @('/d','/s','/c','ping -n 60 127.0.0.1 >nul') $tempParent
        throw 'mock git failure' })
    $interrupted = @($later.Records | Where-Object { $_.Name -eq 'self-test final git status (interrupted)' })
    Assert-That ($interrupted.Count -eq 1) 'Later-command failure: the interrupted record is missing'
    Assert-That (@($later.Errors | Where-Object { $_ -eq 'repository: mock git failure' }).Count -eq 1) "Later-command failure not reported: $($later.Errors -join '; ')"
    Assert-That (@($later.Errors | Where-Object { $_ -eq 'self-test final git status (interrupted) still had owned processes at cleanup' }).Count -eq 1) "The interrupted tree was not reported: $($later.Errors -join '; ')"
    Assert-That ($later.Records.Count -eq 8 -and $later.Result.Final -eq 7) "Later-command failure: expected 8 records, 7 released finally; got $($later.Records.Count), $($later.Result.Final)"
    & $closed $later 'Later-command failure'
    Pass "Handles 5: a later final command threw with its process running; that tree (pid $($interrupted[0].Root.Id)) was ended through its job and released with the other 6 final records; ledger delta 0"

    # 6. Repeated cleanup: a second and third call release nothing, report nothing and change no count.
    $again = New-Object 'System.Collections.Generic.List[string]'
    $before = [SmartAuditsV10.HandleLedger]::Open
    $okList = [System.Collections.Generic.List[object]]$ok.Records
    $second = Invoke-OwnedCleanup $okList @() $before $again
    $third = Close-OwnedRecords $okList $again
    Assert-That ($second.First -eq 0 -and $second.Final -eq 0 -and $third -eq 0 -and $again.Count -eq 0 -and [SmartAuditsV10.HandleLedger]::Open -eq $before) "Repeated cleanup was not a no-op: $($again -join '; ')"
    Pass 'Handles 6: cleanup repeated twice on released records; nothing released, no error, ledger unchanged'

    # 7. A record disposed (twice) before cleanup is skipped: no handle is closed twice (the ledger never drops
    # below). Non-vacuous: setup must succeed and register exactly one record that held its job and process
    # handles; any failure throws. $Exe lets the negative control prevent setup before registration.
    $disposedEarly = { param([string]$Exe)
        $setup = @{ Registered = $false }
        $s = & $scenario {
            $before = [SmartAuditsV10.HandleLedger]::Open
            $r = Start-OwnedProcess 'self-test disposed early' $Exe @('/d','/s','/c','exit 0') $tempParent
            $setup.Registered = $true
            $setup.Root = $r.Root
            $setup.JobOpen = $r.Job.IsOpen; $setup.ProcessOpen = $r.Process.IsOpen
            $setup.Opened = [SmartAuditsV10.HandleLedger]::Open - $before
            $null = Complete-Owned $r 30
            $r.Job.Dispose(); $r.Process.Dispose()
            $setup.ClosedOnce = -not $r.Job.IsOpen -and -not $r.Process.IsOpen
            $afterFirst = [SmartAuditsV10.HandleLedger]::Open
            $r.Job.Dispose(); $r.Process.Dispose()
            $setup.ClosedTwice = -not $r.Job.IsOpen -and -not $r.Process.IsOpen
            $setup.RepeatDelta = [SmartAuditsV10.HandleLedger]::Open - $afterFirst
        } @()
        Assert-That ($null -eq $s.Primary) "Scenario 7 setup failed: $($s.Primary)"
        Assert-That ($setup.Registered -and $s.Records.Count -eq 1 -and $s.Records[0].Name -ceq 'self-test disposed early') "Scenario 7: expected exactly one registered record, got $($s.Records.Count)"
        Assert-That ($setup.JobOpen -and $setup.ProcessOpen -and $setup.Opened -ge 2) "Scenario 7: the record did not start with its job and process handles (job=$($setup.JobOpen) process=$($setup.ProcessOpen) ledger +$($setup.Opened))"
        Assert-That $setup.ClosedOnce 'Scenario 7: the first disposal left a handle open'
        Assert-That ($setup.ClosedTwice -and $setup.RepeatDelta -eq 0) "Scenario 7: the repeated disposal changed the ledger by $($setup.RepeatDelta)"
        Assert-That ($s.Errors.Count -eq 0 -and $s.Result.First -eq 0 -and $s.Result.Final -eq 0) "Scenario 7: the disposed record was released again or reported: $($s.Errors -join '; ')"
        & $closed $s 'Already disposed'
        Assert-That (-not (Test-SameProcess $setup.Root)) 'Scenario 7: the process survived'
        return $setup.Opened
    }
    # Negative control: setup prevented before registration (missing executable). The superseded assertions pass
    # on the empty result; scenario 7 must fail.
    $missing = Join-Path $tempParent "smartaudits-v10-$runId-missing.exe"
    Assert-That (-not (Test-Path -LiteralPath $missing)) 'The missing-executable control exists'
    $empty = & $scenario { $null = Start-OwnedProcess 'self-test disposed early' $missing @('/d','/s','/c','exit 0') $tempParent } @()
    Assert-That ($empty.Records.Count -eq 0 -and $null -ne $empty.Primary -and $empty.Errors.Count -eq 0 -and $empty.Result.First -eq 0 -and $empty.Result.Final -eq 0 -and $empty.Held.Count -eq 0 -and $empty.Leaked -eq 0) 'Negative control: the superseded scenario 7 assertions were expected to pass vacuously'
    $vacuous = $null
    try { $null = & $disposedEarly $missing; $vacuous = 'passed' } catch { $vacuous = $_.Exception.Message }
    Assert-That ($vacuous -like 'Scenario 7 setup failed: *Missing executable*') "Scenario 7 did not fail when its setup was prevented: $vacuous"
    $opened = & $disposedEarly $baseEnv.ComSpec
    Pass "Handles 7: exactly one registered record (job and process handles open, ledger +$opened) disposed twice before cleanup; skipped by cleanup, no error, ledger delta 0, process gone; with setup prevented the superseded assertions pass vacuously and scenario 7 fails"

    # 8. No records at all.
    $none = & $scenario { } @()
    Assert-That ($none.Records.Count -eq 0 -and $none.Errors.Count -eq 0 -and $none.Result.First -eq 0 -and $none.Result.Final -eq 0 -and $none.Leaked -eq 0) 'Cleanup without records reported something'
    Pass 'Handles 8: cleanup with no records; nothing released, no error, ledger delta 0'

    # 9. Validation failure, then a successful cleanup: the failure is kept and every record is released.
    $failed = & $scenario { $null = & $exit 'validation'; $null = Start-OwnedProcess 'self-test validation (interrupted)' $baseEnv.ComSpec @('/d','/s','/c','ping -n 60 127.0.0.1 >nul') $tempParent; throw 'mock validation failure' } (& $finalSteps)
    Assert-That ($failed.Primary -ceq 'mock validation failure') "Validation failure lost: $($failed.Primary)"
    Assert-That ($failed.Errors.Count -eq 1 -and $failed.Errors[0] -ceq 'self-test validation (interrupted) still had owned processes at cleanup') "Unexpected cleanup errors: $($failed.Errors -join '; ')"
    Assert-That ($failed.Result.First -eq 2 -and $failed.Result.Final -eq 7) "Validation failure: expected 2 released first and 7 finally, got $($failed.Result.First), $($failed.Result.Final)"
    & $closed $failed 'Validation failure'
    $message = Get-RunFailureMessage $failed.Primary ([System.Collections.Generic.List[string]]@())
    Assert-That ($message -ceq 'V10 VALIDATION FAILED: mock validation failure') "Unexpected outcome: $message"
    Pass 'Handles 9: validation failed with a process running; it was ended and released first, the 7 final records after the steps; the original failure is the outcome'

    # 10. Validation failure plus a disposal failure on one record (its job query throws): that record's handles
    # are still closed, the next record is still released, and both failures are reported.
    $mockJob = [pscustomobject]@{ IsOpen = $true }
    $mockJob | Add-Member ScriptMethod ActiveProcesses { throw 'mock disposal failure' }
    $mockJob | Add-Member ScriptMethod Dispose { $this.IsOpen = $false }
    $mockProcess = [pscustomobject]@{ IsOpen = $true }
    $mockProcess | Add-Member ScriptMethod Dispose { $this.IsOpen = $false }
    $done = [Threading.Tasks.Task]::FromResult('')
    $mock = [pscustomobject]@{ Name = 'self-test mock record'; Job = $mockJob; Process = $mockProcess; Out = $done; Err = $done }
    $both = & $scenario { $script:ownedRecords.Add($mock); $null = & $exit 'validation'; throw 'mock validation failure' } (& $finalSteps)
    Assert-That ($both.Primary -ceq 'mock validation failure') "Validation failure lost: $($both.Primary)"
    Assert-That ($both.Errors.Count -eq 1 -and $both.Errors[0] -like 'process cleanup: self-test mock record: *mock disposal failure*') "Disposal failure not reported: $($both.Errors -join '; ')"
    Assert-That (-not $mockJob.IsOpen -and -not $mockProcess.IsOpen) 'The failing record was not disposed'
    & $closed $both 'Validation and disposal failure'
    $message = Get-RunFailureMessage $both.Primary ([System.Collections.Generic.List[string]]$both.Errors)
    Assert-That ($message -like 'V10 VALIDATION FAILED: mock validation failure | CLEANUP ALSO FAILED: process cleanup: self-test mock record: *mock disposal failure*') "Outcome hid a failure: $message"
    Pass 'Handles 10: validation failure plus a disposal failure; the failing record and all others released; outcome reports the validation failure first and the cleanup failure after it'

    # Fault injection on Dispose itself. A mock handle counts attempts and closures separately; one made to throw
    # refuses to close and stays open. Mocks are not native handles: they never touch the ledger, and the native
    # records around them must still be released with ledger delta 0.
    $mockHandle = { param([switch]$Throws)
        $h = [pscustomobject]@{ IsOpen = $true; Throws = [bool]$Throws; Attempts = 0; Closed = 0 }
        $h | Add-Member ScriptMethod ActiveProcesses { 0 }
        $h | Add-Member ScriptMethod Dispose { $this.Attempts += 1; if ($this.Throws) { throw 'injected dispose failure' }; if ($this.IsOpen) { $this.IsOpen = $false; $this.Closed += 1 } }
        return $h }
    $mockRecord = { param([string]$Name, $Job, $Process) [pscustomobject]@{ Name = $Name; Job = $Job; Process = $Process; Out = $done; Err = $done } }
    $jobFails = & $mockRecord 'self-test fault job' (& $mockHandle -Throws) (& $mockHandle)
    $processFails = & $mockRecord 'self-test fault process' (& $mockHandle) (& $mockHandle -Throws)
    $clean = & $mockRecord 'self-test fault clean' (& $mockHandle) (& $mockHandle)
    $fault = & $scenario { $script:ownedRecords.Add($jobFails); $script:ownedRecords.Add($processFails); $null = & $exit 'after faults'
        $script:ownedRecords.Add($clean); throw 'mock validation failure' } (& $finalSteps)
    # A. Job.Dispose throws: the process handle of the same record is still disposed; the job is reported, not called closed.
    Assert-That ($jobFails.Job.Attempts -eq 1 -and $jobFails.Job.Closed -eq 0 -and $jobFails.Job.IsOpen) 'A: the throwing job handle was not attempted exactly once, or was claimed closed'
    Assert-That ($jobFails.Process.Attempts -eq 1 -and $jobFails.Process.Closed -eq 1 -and -not $jobFails.Process.IsOpen) 'A: a throwing job Dispose prevented the process Dispose'
    Assert-That ($jobFails.ReleaseFailed -ceq 'job') "A: the record was not marked as a failed job release: $($jobFails.PSObject.Properties['ReleaseFailed'])"
    # B, C. Process.Dispose throws (job already closed); every later record is still visited and released.
    Assert-That ($processFails.Job.Closed -eq 1 -and $processFails.Process.Attempts -eq 1 -and $processFails.Process.IsOpen -and $processFails.ReleaseFailed -ceq 'process') 'B: the throwing process handle was not attempted once and reported open'
    Assert-That ($clean.Job.Attempts -eq 1 -and $clean.Job.Closed -eq 1 -and $clean.Process.Attempts -eq 1 -and $clean.Process.Closed -eq 1) 'C: a record after the failures was not released'
    $native = @($fault.Records | Where-Object { $_.Process -is [SmartAuditsV10.OwnedProcess] })
    Assert-That ($native.Count -eq 8 -and @($native | Where-Object { $_.Job.IsOpen -or $_.Process.IsOpen }).Count -eq 0) "C: native records after the failures were not all released ($($native.Count))"
    Assert-That ($fault.Result.First -eq 2 -and $fault.Result.Final -eq 7) "C: expected 2 released first (native, clean mock) and 7 finally; got $($fault.Result.First), $($fault.Result.Final)"
    Assert-That ($fault.Leaked -eq 0) "C: native handle ledger delta $($fault.Leaked), expected 0"
    foreach ($r in $native) { Assert-That (-not (Test-SameProcess $r.Root)) "C: $($r.Name) still alive" }
    # D. Every independent failure is reported, including the two mock handles honestly left open.
    $expected = @('job handle: self-test fault job: *injected dispose failure*', 'process handle: self-test fault process: *injected dispose failure*',
        'handles: 2 record(s) still hold a job or process handle: self-test fault job, self-test fault process')
    Assert-That ($fault.Errors.Count -eq 3) "D: expected 3 cleanup errors, got $($fault.Errors.Count): $($fault.Errors -join '; ')"
    foreach ($pattern in $expected) { Assert-That (@($fault.Errors | Where-Object { $_ -like $pattern }).Count -eq 1) "D: missing cleanup error '$pattern': $($fault.Errors -join '; ')" }
    Assert-That ($fault.Held.Count -eq 2) "D: expected exactly the 2 refusing mocks held, got $($fault.Held.Count)"
    # E. The validation failure stays primary; the disposal failures follow it.
    Assert-That ($fault.Primary -ceq 'mock validation failure') "E: validation failure lost: $($fault.Primary)"
    $message = Get-RunFailureMessage $fault.Primary ([System.Collections.Generic.List[string]]$fault.Errors)
    Assert-That ($message.StartsWith('V10 VALIDATION FAILED: mock validation failure | CLEANUP ALSO FAILED: ') -and $message -like '*job handle: self-test fault job*' -and $message -like '*process handle: self-test fault process*') "E: outcome hid a failure: $message"
    # G. Repeated release: nothing new is attempted (an uncertain handle is never retried), nothing new reported.
    $again = New-Object 'System.Collections.Generic.List[string]'
    $repeat = Close-OwnedRecords ([System.Collections.Generic.List[object]]$fault.Records) $again
    $repeat2 = Close-OwnedRecords ([System.Collections.Generic.List[object]]$fault.Records) $again
    Assert-That ($repeat -eq 0 -and $repeat2 -eq 0 -and $again.Count -eq 0) "G: repeated release was not a no-op: $($again -join '; ')"
    Assert-That ($jobFails.Job.Attempts -eq 1 -and $processFails.Process.Attempts -eq 1 -and $jobFails.Process.Attempts -eq 1 -and $clean.Job.Attempts -eq 1) 'G: a repeated release retried a handle'
    # F. No records (with and without steps): nothing attempted, nothing reported.
    $none = & $scenario { } @()
    Assert-That ($none.Records.Count -eq 0 -and $none.Errors.Count -eq 0 -and $none.Result.First -eq 0 -and $none.Result.Final -eq 0 -and $none.Leaked -eq 0) 'F: cleanup without records reported something'
    Pass 'Handles fault A-G: job Dispose throwing still disposed that record''s process handle; process Dispose throwing still released every later record (8 native, 1 mock); 3 independent errors reported (2 injected failures, 2 mock handles honestly left open); validation failure primary; repeats retry nothing; empty cleanup a no-op; native ledger delta 0'

    # Process handle count of this PowerShell across repeated controlled lifecycles (9 launches each): the fixed
    # cleanup returns to its level. The superseded one leaks; its OS handles are measured by releasing exactly the
    # leaked records in a short window without launches, because over the longer leaking rounds an idle thread-pool
    # thread may retire and free unrelated handles (observed: one thread, about 12 handles).
    $rounds = 3
    $threads = { $self = [Diagnostics.Process]::GetCurrentProcess(); try { $self.Refresh(); $self.Threads.Count } finally { $self.Dispose() } }
    & $settle; $start = & $handles
    for ($i = 0; $i -lt $rounds; $i++) { & $closed (& $scenario { $null = & $exit 'validation'; $null = & $exit 'validation 2' } (& $finalSteps)) "Round $i" }
    & $settle; $fixedDelta = (& $handles) - $start
    $leaked = New-Object 'System.Collections.Generic.List[object]'
    & $settle; $start = & $handles; $threadsStart = & $threads
    for ($i = 0; $i -lt $rounds; $i++) { foreach ($r in (& $scenario { $null = & $exit 'validation'; $null = & $exit 'validation 2' } (& $finalSteps) -Superseded).Records) { $leaked.Add($r) } }
    & $settle; $oldDelta = (& $handles) - $start; $threadsDelta = (& $threads) - $threadsStart
    $ledgerLeak = [SmartAuditsV10.HandleLedger]::Open
    $errors = New-Object 'System.Collections.Generic.List[string]'
    $releasedLeak = Close-OwnedRecords $leaked $errors
    $ledgerLeak -= [SmartAuditsV10.HandleLedger]::Open
    & $settle; $afterRelease = (& $handles) - $start
    $drop = $oldDelta - $afterRelease
    Assert-That ($errors.Count -eq 0 -and $releasedLeak -eq 7 * $rounds -and $ledgerLeak -eq 7 * $rounds) "Releasing the control leak: $releasedLeak records, ledger -$ledgerLeak, errors: $($errors -join '; ')"
    Assert-That ($drop -ge 7 * $rounds - 2) "Handle count: releasing the superseded cleanup's $(7 * $rounds) leaked records freed only $drop OS handles"
    Assert-That ($fixedDelta -le 3) "Handle count: the fixed cleanup grew by $fixedDelta over $rounds rounds"
    Pass "Handles (process handle count, $rounds rounds of 9 launches): fixed cleanup $fixedDelta; superseded snapshot cleanup left $(7 * $rounds) records (ledger +$ledgerLeak) whose release freed $drop OS handles (net over the leaking rounds +$oldDelta, threads $threadsDelta)"
}

# ---------------------------------------------------------------- validation

function Assert-Owned {
    $owner = Docker @('container','inspect','--format',"{{index .Config.Labels `"$label`"}}",$resource)
    Assert-That ($owner -ceq $runId) 'Container is not owned by this run'
}
function Root-Sql([string]$Query) {
    Assert-Owned
    # --raw: batch mode would otherwise print a backslash as two, and SHOW GRANTS must be read exactly.
    return Docker @('exec','-e','MYSQL_PWD',$resource,'mariadb','--host=127.0.0.1','-uroot','--batch','--raw','--skip-column-names','-e',$Query) @{ MYSQL_PWD = $rootPassword }
}
function Get-ForeignDockerState {
    $containers = @((Docker @('ps','-a','--no-trunc','--format','{{.ID}}|{{.Names}}|{{.Image}}|{{.State}}')) -split '\r?\n' |
        Where-Object { $_ -and $_ -notlike "*|$resource|*" } | Sort-Object)
    $volumes = @((Docker @('volume','ls','--format','{{.Name}}')) -split '\r?\n' | Where-Object { $_ } | Sort-Object)
    return @{ Containers = ($containers -join "`n"); Volumes = ($volumes -join "`n") }
}
function Get-InputHashes([string]$Root) {
    $result = [ordered]@{}
    $files = @(Get-Item -LiteralPath (Join-Path $Root 'pom.xml')) + @(Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Recurse -File -Force | Sort-Object FullName)
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($Root.Length).TrimStart('\','/').Replace('\','/')
        $result[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    return $result
}
function Compare-Hashes($Expected, $Actual, [string]$What) {
    Assert-That ($Expected.Count -eq $Actual.Count) "${What}: file count differs ($($Expected.Count) vs $($Actual.Count))"
    foreach ($key in $Expected.Keys) {
        Assert-That ($Actual.Contains($key) -and $Actual[$key] -ceq $Expected[$key]) "${What}: differs at $key"
    }
}

$rootPassword = New-Secret
$appPassword = New-Secret
$observerPassword = New-Secret
try {
    if ($SelfTestOnly) {
        Test-GrantParser
        Test-ProcessTrees
        Test-HandleLifecycle
    } else {
    # 1. Self-tests first, then read-only preconditions.
    Test-GrantParser
    Test-ProcessTrees
    Test-HandleLifecycle
    $null = Docker @('version','--format','{{.Server.Version}}')
    $image = Docker @('image','inspect','--format','{{.Id}}','mariadb:11.2')
    Pass "Docker reachable; local mariadb:11.2 image $image (never pulled)"
    Assert-That (-not (Docker @('ps','-a','--filter',"label=$label",'--format','{{.Names}}'))) 'A V10 identity container from another run exists; refusing to proceed'
    $foreignBefore = Get-ForeignDockerState
    $gitHead = Native 'git rev-parse' 'git' @('rev-parse','HEAD')
    $gitStatus = Native 'git status' 'git' @('-c','core.quotePath=false','status','--porcelain=v1','--untracked-files=all')
    $inputsBefore = Get-InputHashes $backendSource
    Pass "Repository HEAD $gitHead; $($inputsBefore.Count) backend input files hashed"

    # 2. Owned temporary copy of the backend working tree, verified byte for byte.
    Assert-That ($tempRoot.StartsWith($tempParent) -and -not (Test-Path -LiteralPath $tempRoot)) 'Unsafe temporary directory'
    $null = New-Item -ItemType Directory -Path $tempRoot
    $tempCreated = $true
    $null = New-Item -ItemType Directory -Path $backendCopy
    Copy-Item -LiteralPath (Join-Path $backendSource 'pom.xml') -Destination $backendCopy
    Copy-Item -LiteralPath (Join-Path $backendSource 'src') -Destination $backendCopy -Recurse
    Compare-Hashes $inputsBefore (Get-InputHashes $backendCopy) 'Backend copy'
    Pass "Backend copy at $backendCopy equals the working tree ($($inputsBefore.Count) files); no target/ reused"

    # 3. Owned disposable MariaDB 11.2: labelled, tmpfs data, loopback random port, random credentials.
    $containerCreated = $true
    $null = Docker @('run','--detach','--pull=never','--name',$resource,'--label',"$label=$runId",
        '--publish','127.0.0.1::3306','--tmpfs','/var/lib/mysql:rw',
        '-e','MARIADB_ROOT_PASSWORD','-e','MARIADB_USER','-e','MARIADB_PASSWORD','mariadb:11.2') @{
            MARIADB_ROOT_PASSWORD = $rootPassword; MARIADB_USER = $user; MARIADB_PASSWORD = $appPassword }
    Assert-Owned
    $mounts = Docker @('container','inspect','--format','{{range .Mounts}}{{.Type}}:{{.Name}};{{end}}',$resource)
    Assert-That (-not ($mounts -match 'volume:')) "Unexpected Docker volume mount: $mounts"
    $tmpfs = Docker @('container','inspect','--format','{{json .HostConfig.Tmpfs}}',$resource)
    Assert-That ($tmpfs -like '*"/var/lib/mysql"*') "MariaDB data is not on tmpfs: $tmpfs"
    $port = [int](Docker @('inspect','--format','{{(index (index .NetworkSettings.Ports "3306/tcp") 0).HostPort}}',$resource))
    $hostIp = Docker @('inspect','--format','{{(index (index .NetworkSettings.Ports "3306/tcp") 0).HostIp}}',$resource)
    Assert-That ($port -gt 1024 -and $port -ne 3306 -and $hostIp -ceq '127.0.0.1') "Unsafe published port $hostIp`:$port"
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    $ready = $false
    while (-not $ready -and [DateTime]::UtcNow -lt $deadline) {
        try {
            $ready = (Docker @('exec','-e','MYSQL_PWD',$resource,'mariadb','--host=127.0.0.1',"-u$user",'--batch','--skip-column-names','-e','SELECT 1') @{ MYSQL_PWD = $appPassword }) -eq '1'
        } catch { Start-Sleep -Seconds 2 }
    }
    Assert-That $ready 'MariaDB did not become ready'
    $version = Root-Sql 'SELECT VERSION()'
    Assert-That ($version -match '^11\.2\.') "Not MariaDB 11.2: $version"
    Assert-That ((Root-Sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 'v10%'") -eq '0') 'Fresh server already has V10 databases'
    $observerHash = Get-NativePasswordHash $observerPassword
    $create = (@($databases.Values | ForEach-Object {
        "CREATE DATABASE $_ CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci; GRANT ALL PRIVILEGES ON ``$($_.Replace('_','\_'))``.* TO '$user'@'%';" }) +
        @("CREATE USER '$observer'@'%' IDENTIFIED BY PASSWORD '$observerHash'; GRANT PROCESS ON *.* TO '$observer'@'%';")) -join ' '
    $null = Root-Sql $create
    $appGrants = @((Root-Sql "SHOW GRANTS FOR '$user'@'%'") -split '\r?\n')
    $appViolations = @(Get-GrantViolations $appGrants $user 'USAGE' @($databases.Values))
    Assert-That ($appViolations.Count -eq 0) "Application grants rejected: $($appViolations -join '; ')"
    $observerGrants = @((Root-Sql "SHOW GRANTS FOR '$observer'@'%'") -split '\r?\n')
    $observerViolations = @(Get-GrantViolations $observerGrants $observer 'PROCESS' @())
    Assert-That ($observerViolations.Count -eq 0) "Observer grants rejected: $($observerViolations -join '; ')"
    Pass "Owned MariaDB $version ready: $resource on 127.0.0.1:$port (tmpfs, label $label=$runId); databases $($databases.Values -join ', ')"
    Pass "Grants parsed: $user = USAGE on *.* + ALL PRIVILEGES on exactly 3 escaped owned databases; $observer = PROCESS on *.* only"

    # 4. The probe: fresh V1-V10 and constraints, populated V9 -> V10, database-observed concurrency.
    $environment = @{ V10_RUN_ID = $runId; V10_DB_PORT = "$port"; V10_DB_PASSWORD = $appPassword
        V10_OBSERVER_USER = $observer; V10_OBSERVER_PASSWORD = $observerPassword }
    foreach ($key in $databases.Keys) { $environment[$key] = $databases[$key] }
    $output = Native 'Maven V10 probe' $baseEnv.ComSpec @('/d','/s','/c',
        'mvn -B -o -Dtest=com.smartaudits.v2.model.SiteUrlIdentityMariaDbProbe -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Duser.timezone=Europe/Madrid test') $backendCopy $environment 1200
    Assert-That ($output -match 'Tests run: 3, Failures: 0, Errors: 0, Skipped: 0') 'V10 MariaDB probe did not pass 3/3'
    Assert-That ($output -match 'BUILD SUCCESS') 'Maven did not report BUILD SUCCESS'
    foreach ($marker in @('V10 FRESH PASS:','V10 CONSTRAINTS PASS:','V10 UPGRADE PASS:','V10 CONCURRENCY PASS:')) {
        $lines = @(($output -split '\r?\n') | Where-Object { $_.StartsWith($marker) })
        Assert-That ($lines.Count -eq 1) "Missing proof marker $marker"
        Pass (Protect $lines[0])
    }
    $observed = @(($output -split '\r?\n') | Where-Object { $_.StartsWith('V10 CONCURRENCY OBSERVED:') })
    Assert-That ($observed.Count -eq 5) "Expected 5 database-observed concurrency lines, found $($observed.Count)"
    foreach ($line in $observed) { Pass (Protect $line) }
    $schemas = Root-Sql "SELECT GROUP_CONCAT(schema_name ORDER BY schema_name) FROM information_schema.schemata WHERE schema_name NOT IN ('information_schema','mysql','performance_schema','sys')"
    Assert-That ($schemas -ceq (($databases.Values | Sort-Object) -join ',')) "Unexpected databases in the owned server: $schemas"
    Pass 'Probe touched only its three owned databases'
    }
} catch {
    $failure = $_
} finally {
    # 5. Cleanup of owned resources only, then verification. The steps launch owned processes of their own;
    # Invoke-OwnedCleanup releases those too, after the last step.
    $steps = New-Object 'System.Collections.Generic.List[object]'
    if ($containerCreated) {
        $steps.Add([pscustomobject]@{ Name = 'container'; Run = {
            $owner = Docker @('ps','-a','--filter',"label=$label=$runId",'--format','{{.Names}}')
            if ($owner) {
                Assert-That ($owner -ceq $resource) "Refusing to remove unexpected container: $owner"
                $null = Docker @('rm','--force','--volumes',$resource)
            }
            Assert-That (-not (Docker @('ps','-a','--filter',"label=$label=$runId",'--format','{{.Names}}'))) 'Owned container still present'
        } })
    }
    if ($tempCreated) {
        $steps.Add([pscustomobject]@{ Name = 'temp'; Run = {
            Assert-That ($tempRoot.StartsWith($tempParent) -and (Split-Path $tempRoot -Leaf) -ceq $resource) 'Unsafe temporary path'
            Remove-Item -LiteralPath $tempRoot -Recurse -Force
            Assert-That (-not (Test-Path -LiteralPath $tempRoot)) 'Temporary directory still present'
        } })
    }
    if (Get-Variable -Name foreignBefore -ErrorAction SilentlyContinue) {
        $steps.Add([pscustomobject]@{ Name = 'docker state'; Run = {
            $foreignAfter = Get-ForeignDockerState
            Assert-That ($foreignAfter.Containers -ceq $foreignBefore.Containers) 'Other containers changed during the run'
            Assert-That ($foreignAfter.Volumes -ceq $foreignBefore.Volumes) 'Docker volumes changed during the run'
        } })
    }
    if (Get-Variable -Name inputsBefore -ErrorAction SilentlyContinue) {
        $steps.Add([pscustomobject]@{ Name = 'repository'; Run = {
            Assert-That ((Native 'git rev-parse' 'git' @('rev-parse','HEAD')) -ceq $gitHead) 'HEAD changed'
            Assert-That ((Native 'git status' 'git' @('-c','core.quotePath=false','status','--porcelain=v1','--untracked-files=all')) -ceq $gitStatus) 'Git status changed'
            Compare-Hashes $inputsBefore (Get-InputHashes $backendSource) 'Repository backend inputs'
        } })
    }
    try {
        $released = Invoke-OwnedCleanup $ownedRecords $steps $ledgerBaseline $cleanupErrors
    } catch { $cleanupErrors.Add("handle release: $($_.Exception.Message)") }
    try {
        $alive = @($everOwned.Values | Where-Object { Test-SameProcess $_ })
        Assert-That ($alive.Count -eq 0) "Owned processes alive at exit: $(($alive | ForEach-Object { $_.Id }) -join ',')"
        foreach ($entry in $killLog) {
            Assert-That ($entry -match 'pid=(\d+) start=(\S+)$') "Unparseable kill log: $entry"
            $key = "$($Matches[1])@$(([DateTime]::Parse($Matches[2], $null, [Globalization.DateTimeStyles]::RoundtripKind)).Ticks)"
            Assert-That ($everOwned.ContainsKey($key)) "A terminated process was not owned: $entry"
        }
        # Only the directed self-tests may need a termination; a real-run process never may.
        $unexpected = @($killLog | Where-Object { $_ -notlike 'self-test *' })
        Assert-That ($unexpected.Count -eq 0) "A validation process had to be terminated: $($unexpected -join '; ')"
    } catch { $cleanupErrors.Add("processes: $($_.Exception.Message)") }
}
if ($failure -and $cleanupErrors.Count -gt 0) { Write-Host "CLEANUP ERRORS: $(Protect ($cleanupErrors -join ' | '))" }
$outcome = Get-RunFailureMessage $(if ($failure) { $failure.Exception.Message } else { $null }) $cleanupErrors
if ($outcome) { throw $outcome }
Pass "Owned processes: $($everOwned.Count) tracked, none alive; $($killLog.Count) terminations, all of owned processes (self-tests only)"
Pass "Native handles: $($ownedRecords.Count) owned records, each with its job and process handle closed and both output readers complete ($($released.First) released before the cleanup steps, $($released.Final) after them); handle ledger back at baseline $ledgerBaseline"
if ($SelfTestOnly) {
    Write-Host 'V10 RUNNER SELF-TESTS PASS'
} else {
    Pass "Cleanup verified: container $resource and its tmpfs removed; temporary directory removed; other containers and volumes unchanged; HEAD, Git status and $($inputsBefore.Count) backend inputs unchanged"
    Write-Host 'V10 SITE URL IDENTITY VALIDATION PASS'
}
