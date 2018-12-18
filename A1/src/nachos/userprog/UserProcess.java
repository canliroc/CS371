package nachos.userprog;

import nachos.machine.*;
import nachos.threads.Semaphore;
import nachos.threads.ThreadedKernel;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
    private static final int
            syscallHalt = 0,
            syscallExit = 1,
            syscallExec = 2,
            syscallJoin = 3,
            syscallCreate = 4,
            syscallOpen = 5,
            syscallRead = 6,
            syscallWrite = 7,
            syscallClose = 8,
            syscallUnlink = 9;
    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';


    /**
     * The number of pages in the program's stack.
     */
    protected final int stackPages = 8;
    public int exitStatus;
    /**
     * The program being run by this process.
     */
    protected Coff coff;
    /**
     * This process's page table.
     */
    protected TranslationEntry[] pageTable;
    /**
     * The number of contiguous pages occupied by the program.
     */
    protected int numPages;
    protected int id;
    protected OpenFile executable = null;
    private int initialPC, initialSP;
    private int argc, argv;
    private List<UserProcess> childList = new ArrayList<>();
    private List<OpenFile> fileTable;
    private Semaphore joinSem;
    private Stack<Integer> filePool = new Stack<>();

    /**
     * Allocate a new process.
     */
    public UserProcess() {
        id = UserKernel.getNewProcessID();

        fileTable = new ArrayList<>();
        fileTable.add(UserKernel.console.openForReading());
        fileTable.add(UserKernel.console.openForWriting());
        joinSem = new Semaphore(0);

        String filename = fileTable.get(0).getFileSystem() + "\0" + fileTable.get(0).getName();
        UserKernel.openCount.put(filename, UserKernel.openCount.containsKey(filename) ? UserKernel.openCount.get(filename) + 1 : 1);
        filename = fileTable.get(1).getFileSystem() + "\0" + fileTable.get(1).getName();
        UserKernel.openCount.put(filename, UserKernel.openCount.containsKey(filename) ? UserKernel.openCount.get(filename) + 1 : 1);
    }

    /**
     * Allocate and return a new process of the correct class. The class name
     * is specified by the <tt>nachos.sh.conf</tt> key
     * <tt>Kernel.processClassName</tt>.
     *
     * @return a new process of the correct class.
     */
    public static UserProcess newUserProcess() {
        return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
    }

    /**
     * Execute the specified program with the specified arguments. Attempts to
     * load the program, and then forks a thread to run it.
     *
     * @param name the name of the file containing the executable.
     * @param args the arguments to pass to the executable.
     * @return <tt>true</tt> if the program was successfully executed.
     */
    public boolean execute(String name, String[] args) {
        saveState();
        if (!load(name, args))
            return false;

        new UThread(this).setName(name).fork();

        return true;
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
        Machine.processor().setPageTable(pageTable);
    }

    /**
     * Read a null-terminated string from this process's virtual memory. Read
     * at most <tt>maxLength + 1</tt> bytes from the specified address, search
     * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
     * without including the null terminator. If no null terminator is found,
     * returns <tt>null</tt>.
     *
     * @param vaddr     the starting virtual address of the null-terminated
     *                  string.
     * @param maxLength the maximum number of characters in the string,
     *                  not including the null terminator.
     * @return the string read, or <tt>null</tt> if no null terminator was
     * found.
     */
    public String readVirtualMemoryString(int vaddr, int maxLength) {
        Lib.assertTrue(maxLength >= 0);

        byte[] bytes = new byte[maxLength + 1];

        int bytesRead = readVirtualMemory(vaddr, bytes);

        for (int length = 0; length < bytesRead; length++) {
            if (bytes[length] == 0)
                return new String(bytes, 0, length);
        }

        return null;
    }

    /**
     * Transfer data from this process's virtual memory to all of the specified
     * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param vaddr the first byte of virtual memory to read.
     * @param data  the array where the data will be stored.
     * @return the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data) {
        return readVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from this process's virtual memory to the specified array.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param vaddr  the first byte of virtual memory to read.
     * @param data   the array where the data will be stored.
     * @param offset the first byte to write in the array.
     * @param length the number of bytes to transfer from virtual memory to
     *               the array.
     * @return the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data, int offset,
                                 int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();
        if (vaddr < 0 || vaddr >= pageTable.length * pageSize) {
            return 0;
        }
        int amount = Math.min(length, pageTable.length * pageSize - vaddr);

        for (int i = vaddr; i < vaddr + amount; i = (i / pageSize + 1) * pageSize) {
            System.arraycopy(memory, i + (pageTable[i / pageSize].ppn - i / pageSize) * pageSize, data, i - vaddr + offset, Math.min(vaddr + amount - i, pageSize - i % pageSize));
        }
        return amount;
    }

    /**
     * Transfer all data from the specified array to this process's virtual
     * memory.
     * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param vaddr the first byte of virtual memory to write.
     * @param data  the array containing the data to transfer.
     * @return the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data) {
        return writeVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from the specified array to this process's virtual memory.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param vaddr  the first byte of virtual memory to write.
     * @param data   the array containing the data to transfer.
     * @param offset the first byte to transfer from the array.
     * @param length the number of bytes to transfer from the array to
     *               virtual memory.
     * @return the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data, int offset,
                                  int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();
        if (vaddr < 0 || vaddr >= pageTable.length * pageSize) {
            return 0;
        }

        int amount = Math.min(length, pageTable.length * pageSize - vaddr);
        for (int i = vaddr; i < vaddr + amount; i = (i / pageSize + 1) * pageSize) {
            System.arraycopy(data, i - vaddr + offset, memory, i + (pageTable[i / pageSize].ppn - i / pageSize) * pageSize, Math.min(vaddr + amount - i, pageSize - i % pageSize));
        }
        return amount;
    }

    /**
     * Load the executable with the specified name into this process, and
     * prepare to pass it the specified arguments. Opens the executable, reads
     * its header information, and copies sections and arguments into this
     * process's virtual memory.
     *
     * @param name the name of the file containing the executable.
     * @param args the arguments to pass to the executable.
     * @return <tt>true</tt> if the executable was successfully loaded.
     */
    private boolean load(String name, String[] args) {
        Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

        executable = ThreadedKernel.fileSystem.open(name, false);
        if (executable == null) {
            Lib.debug(dbgProcess, "\topen failed");
            return false;
        }
        String filename = executable.getFileSystem() + "\0" + name;
        UserKernel.openCount.put(filename, UserKernel.openCount.containsKey(filename) ? UserKernel.openCount.get(filename) + 1 : 1);

        try {
            coff = new Coff(executable);
        } catch (EOFException e) {
            executable.close();
            Lib.debug(dbgProcess, "\tcoff load failed");
            return false;
        }

        // make sure the sections are contiguous and start at page 0
        numPages = 0;
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);
            if (section.getFirstVPN() != numPages) {
                coff.close();
                Lib.debug(dbgProcess, "\tfragmented executable");
                return false;
            }
            numPages += section.getLength();
        }

        // make sure the argv array will fit in one page
        byte[][] argv = new byte[args.length][];
        int argsSize = 0;
        for (int i = 0; i < args.length; i++) {
            argv[i] = args[i].getBytes();
            // 4 bytes for argv[] pointer; then string plus one for null byte
            argsSize += 4 + argv[i].length + 1;
        }
        if (argsSize > pageSize) {
            coff.close();
            Lib.debug(dbgProcess, "\targuments too long");
            return false;
        }

        // program counter initially points at the program entry point
        initialPC = coff.getEntryPoint();

        // next comes the stack; stack pointer initially points to top of it
        numPages += stackPages;
        initialSP = numPages * pageSize;

        // and finally reserve 1 page for arguments
        numPages++;

        if (!loadSections())
            return false;

        // store arguments in last page
        int entryOffset = (numPages - 1) * pageSize;
        int stringOffset = entryOffset + args.length * 4;

        this.argc = args.length;
        this.argv = entryOffset;

        for (int i = 0; i < argv.length; i++) {
            byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
            Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
            entryOffset += 4;
            Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
                    argv[i].length);
            stringOffset += argv[i].length;
            Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[]{0}) == 1);
            stringOffset += 1;
        }

        return true;
    }

    /**
     * Allocates memory for this process, and loads the COFF sections into
     * memory. If this returns successfully, the process will definitely be
     * run (this is the last step in process initialization that can fail).
     *
     * @return <tt>true</tt> if the sections were successfully loaded.
     */
    protected boolean loadSections() {
        if (numPages > Machine.processor().getNumPhysPages()) {
            coff.close();
            Lib.debug(dbgProcess, "\tinsufficient physical memory");
            return false;
        }

        pageTable = new TranslationEntry[numPages];
        int[] phyPages = UserKernel.getFreePages(numPages);
        if (phyPages == null) {
            Lib.debug(dbgProcess, "\tOut of memory");
            return false;
        }
        for (int i = 0; i < numPages; i++) {
            pageTable[i] = new TranslationEntry(i, phyPages[i], true, false, false, false);
        }

        // load sections
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);

            Lib.debug(dbgProcess, "\tinitializing " + section.getName()
                    + " section (" + section.getLength() + " pages)");

            for (int i = 0; i < section.getLength(); i++) {
                int vpn = section.getFirstVPN() + i;
                pageTable[vpn].readOnly = section.isReadOnly();
                section.loadPage(i, pageTable[vpn].ppn);
            }
        }

        return true;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
        int[] phyPages = new int[pageTable.length];
        for (int i = 0; i < pageTable.length; ++i) {
            phyPages[i] = pageTable[i].ppn;
        }
        UserKernel.pageFree(phyPages);

        if (executable == null) {
            return;
        }

        String filename = executable.getFileSystem() + "\0" + executable.getName();
        if (UserKernel.openCount.get(filename) == 1) {
            UserKernel.openCount.remove(filename);
        } else {
            UserKernel.openCount.put(filename, UserKernel.openCount.get(filename) - 1);
        }
        executable.close();

        if (UserKernel.unlinking.contains(filename) && UserKernel.openCount.get(filename) == null) {
            ThreadedKernel.fileSystem.remove(executable.getName());
            UserKernel.unlinking.remove(filename);
        }
    }

    /**
     * Initialize the processor's registers in preparation for running the
     * program loaded into this process. Set the PC register to point at the
     * start function, set the stack pointer register to point at the top of
     * the stack, set the A0 and A1 registers to argc and argv, respectively,
     * and initialize all other registers to 0.
     */
    public void initRegisters() {
        Processor processor = Machine.processor();

        // by default, everything's 0
        for (int i = 0; i < processor.numUserRegisters; i++)
            processor.writeRegister(i, 0);

        // initialize PC and SP according
        processor.writeRegister(Processor.regPC, initialPC);
        processor.writeRegister(Processor.regSP, initialSP);

        // initialize the first two argument registers to argc and argv
        processor.writeRegister(Processor.regA0, argc);
        processor.writeRegister(Processor.regA1, argv);
    }

    /**
     * Handle the halt() system call.
     */
    private int handleHalt() {
        if (id != 0) {
            Lib.debug(dbgProcess, "only the root process can halt the machine");
            return 0;
        }

        Kernel.kernel.terminate();

        Lib.assertNotReached("Machine.halt() did not halt machine!");
        return 0;
    }

    /**
     * Handle the exec() system call.
     * int  exec(char *name, int argc, char **argv);
     * Creates a new UserProcess to run process "name"
     * and forks a new UserThread to run it in.
     */
    private int handleExec(int name, int argc, int argv) {
        String[] args = new String[argc];

        // argv is in the form [(4byte address of arg1), (4byte address of arg2), ...]
        for (int i = 0; i < argc; i++) {
            // get 4byte address to this argument
            byte[] argPoint = new byte[4];
            readVirtualMemory(argv + i * 4, argPoint);

            // read string argument at pointer from above
            args[i] = readVirtualMemoryString(Lib.bytesToInt(argPoint, 0), 256);
        }

        UserProcess child = new UserProcess();
        childList.add(child);
        String processName = readVirtualMemoryString(name, 256);
        boolean ret = child.execute(processName, args);
        if (!ret) {
            child.clean();
            return -1;
        }
        return child.id;
    }

    private void clean() {
        unloadSections();
        for (int i = 0; i < fileTable.size(); i++) {
            if (null != fileTable.get(i)) {
                handleClose(i);
            }
        }
        --UserKernel.processCount;
        if (UserKernel.processCount == 0) {
            Kernel.kernel.terminate();
        }
        joinSem.V();
    }

    private void kill() {
        clean();
        UThread.finish();
    }

    /**
     * Handle the exit() system call.
     * Clears the file table, sets exit type to "exit" argument,
     * wakes parent if it joined, and finishes thread.
     * If this is the last process to exit, halt the machine
     */
    private int handleExit(int status) {
        this.exitStatus = status;
        kill();
        return 0;
    }


    /**
     * Handle the join() system call.
     * Puts this process to sleep waiting on process "pid"
     * returns exit status of process slept on
     */
    private int handleJoin(int pid) {
        for (UserProcess child : childList) {
            if (child.id == pid) {
                child.joinSem.P();
                return child.exitStatus;
            }
        }
        return -1;
    }

    /**
     * Handle the creat() system call.
     * Opens file, and clears it if it already exists.
     */
    private int handleCreate(int vaddr) {
        Lib.debug(dbgProcess, "creating from " + vaddr);
        String filename = readVirtualMemoryString(vaddr, 256);
        if (filename == null) {
            Lib.debug(dbgProcess, "error while reading filename");
            return -1;
        }
        Lib.debug(dbgProcess, "\tname is " + filename);

        OpenFile file = ThreadedKernel.fileSystem.open(filename, true);
        if (file == null) {
            Lib.debug(dbgProcess, "error while creating file");
            return -1;
        }
        filename = file.getFileSystem() + "\0" + file.getName();

        if (UserKernel.unlinking.contains(filename)) {
            Lib.debug(dbgProcess, "file has been unlinked");
            return -1;
        }
        UserKernel.openCount.put(filename, UserKernel.openCount.containsKey(filename) ? UserKernel.openCount.get(filename) + 1 : 1);

        int fd;
        if (filePool.isEmpty()) {
            fd = this.fileTable.size();
            this.fileTable.add(file);
        } else {
            this.fileTable.set(fd = filePool.pop(), file);
        }

        Lib.debug(dbgProcess, "successfully creating file => " + fd);
        return fd;
    }

    /**
     * Handle the open() system call.
     * Tries to open file.
     * If it doesn't exist, returns -1.
     */
    private int handleOpen(int vaddr) {
        Lib.debug(dbgProcess, "opening from " + vaddr);
        String filename = readVirtualMemoryString(vaddr, 256);
        if (filename == null) {
            Lib.debug(dbgProcess, "error while reading filename");
            return -1;
        }
        Lib.debug(dbgProcess, "\tname is " + filename);

        OpenFile file = ThreadedKernel.fileSystem.open(filename, false);
        if (file == null) {
            Lib.debug(dbgProcess, "error while opening file");
            return -1;
        }
        filename = file.getFileSystem() + "\0" + file.getName();

        if (UserKernel.unlinking.contains(filename)) {
            Lib.debug(dbgProcess, "file has been unlinked");
            return -1;
        }
        UserKernel.openCount.put(filename, UserKernel.openCount.containsKey(filename) ? UserKernel.openCount.get(filename) + 1 : 1);

        int fd;
        if (filePool.isEmpty()) {
            fd = this.fileTable.size();
            this.fileTable.add(file);
        } else {
            this.fileTable.set(fd = filePool.pop(), file);
        }

        Lib.debug(dbgProcess, "successfully opening file => " + fd);
        return fd;
    }

    /**
     * Handle the close() system call.
     * Closes file it it exists.
     * Replaces file with null in the fileTable.
     */
    private int handleClose(int fileDescriptor) {
        OpenFile file;
        if (fileDescriptor < 0 || fileDescriptor >= fileTable.size() || null == (file = fileTable.get(fileDescriptor))) {
            Lib.debug(dbgProcess, "invalid file descriptor");
            return -1;
        }
        String filename = file.getFileSystem() + "\0" + file.getName();
        if (UserKernel.openCount.get(filename) == 1) {
            UserKernel.openCount.remove(filename);
        } else {
            UserKernel.openCount.put(filename, UserKernel.openCount.get(filename) - 1);
        }

        file.close();
        this.fileTable.set(fileDescriptor, null);
        filePool.add(fileDescriptor);

        if (UserKernel.unlinking.contains(filename) && UserKernel.openCount.get(filename) == null) {
            ThreadedKernel.fileSystem.remove(file.getName());
            UserKernel.unlinking.remove(filename);
        }
        return fileDescriptor;
    }

    /**
     * Handle the read() system call.
     * Reads file into buffer.
     * Returns size of what was read.
     */
    private int handleRead(int fileDescriptor, int buffer, int size) {
        OpenFile file = fileTable.get(fileDescriptor);
        if (file == null) {
            return -1;
        }

        byte[] buff = new byte[size];
        int sizeRead;
        sizeRead = file.read(buff, 0, size);

        writeVirtualMemory(buffer, buff);

        return sizeRead;
    }

    /**
     * Handle the write() system call.
     * Reads file from buffer.
     * Writes it through a new buffer to the new file.
     * Returns the size of what was written.
     */
    private int handleWrite(int fileDescriptor, int buffer, int size) {
        OpenFile file = fileTable.get(fileDescriptor);
        if (file == null) {
            return -1;
        }

        byte[] buff = new byte[size];
        readVirtualMemory(buffer, buff);
        int sizeWritten = file.write(buff, 0, size);
        return sizeWritten;
    }

    private int handleUnLink(int vaddr) {
        String filename = readVirtualMemoryString(vaddr, 256);
        if (filename == null) {
            Lib.debug(dbgProcess, "error while reading filename");
            return -1;
        }
        Lib.debug(dbgProcess, "unlinking " + filename);

        OpenFile file = Machine.stubFileSystem().open(filename, false);
        if (file == null) {
            Lib.debug(dbgProcess, "\terror while opening file");
            return -1;
        }
        String name = filename;
        filename = file.getFileSystem() + "\0" + file.getName();

        if (UserKernel.unlinking.contains(filename)) {
            return 0;
        }

        if (UserKernel.openCount.get(filename) == null) {
            return ThreadedKernel.fileSystem.remove(name) ? 0 : -1;
        }
        UserKernel.unlinking.add(filename);

        return 0;
    }

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr><td>syscall#</td><td>syscall prototype</td></tr>
     * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
     * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
     * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
     * </tt></td></tr>
     * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
     * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
     * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
     * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
     * </tt></td></tr>
     * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
     * </tt></td></tr>
     * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
     * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
     * </table>
     *
     * @param syscall the syscall number.
     * @param a0      the first syscall argument.
     * @param a1      the second syscall argument.
     * @param a2      the third syscall argument.
     * @param a3      the fourth syscall argument.
     * @return the value to be returned to the user.
     */
    public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
        UserKernel.console.openForReading();
        UserKernel.console.openForWriting();

        switch (syscall) {
            case syscallHalt:
                return handleHalt();
            case syscallExit:
                return handleExit(a0);
            case syscallExec:
                return handleExec(a0, a1, a2);
            case syscallJoin:
                return handleJoin(a0);
            case syscallCreate:
                return handleCreate(a0);
            case syscallOpen:
                return handleOpen(a0);
            case syscallRead:
                return handleRead(a0, a1, a2);
            case syscallWrite:
                return handleWrite(a0, a1, a2);
            case syscallClose:
                return handleClose(a0);
            case syscallUnlink:
                return handleUnLink(a0);

            default:
                Lib.debug(dbgProcess, "Unknown syscall " + syscall);
                Lib.assertNotReached("Unknown system call!");
        }
        return 0;
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param cause the user exception that occurred.
     */
    public void handleException(int cause) {
        Processor processor = Machine.processor();

        switch (cause) {
            case Processor.exceptionSyscall:
                int result = handleSyscall(processor.readRegister(Processor.regV0),
                        processor.readRegister(Processor.regA0),
                        processor.readRegister(Processor.regA1),
                        processor.readRegister(Processor.regA2),
                        processor.readRegister(Processor.regA3)
                );
                processor.writeRegister(Processor.regV0, result);
                processor.advancePC();
                break;
            case Processor.exceptionReadOnly:
                Lib.debug(dbgProcess, "memory " + processor.readRegister(Processor.regBadVAddr) + " is read-only");
                kill();
                break;

            case Processor.exceptionBusError:
                Lib.debug(dbgProcess, "physical address " + processor.readRegister(Processor.regBadVAddr) + " out of bound");
                kill();
                break;

            default:
                Lib.debug(dbgProcess, "Unexpected exception: " +
                        Processor.exceptionNames[cause]);
                Lib.assertNotReached("Unexpected exception");
        }
    }
}
