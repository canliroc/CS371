package nachos.userprog;

import nachos.machine.Coff;
import nachos.machine.Lib;
import nachos.machine.Machine;
import nachos.machine.Processor;
import nachos.threads.KThread;
import nachos.threads.Semaphore;
import nachos.threads.ThreadedKernel;

import java.util.*;

/**
 * A kernel that can support multiple user processes.
 */
public class UserKernel extends ThreadedKernel {
    private static final char dbgProcess = 'a';
    /**
     * Globally accessible reference to the synchronized console.
     */
    public static SynchConsole console;
    public static int newProcessID = 0;
    public static Semaphore processIDSem;
    public static Semaphore freePagesSem;
    public static Set<Integer> freePages = new HashSet<>();
    protected static Map<String, Integer> openCount = new HashMap<>();
    protected static Set<String> unlinking = new HashSet<>();
    protected static int processCount = 0;
    // dummy variables to make javac smarter
    private static Coff dummy1 = null;

    /**
     * Allocate a new user kernel.
     */
    public UserKernel() {
        super();
    }

    /**
     * Returns the current process.
     *
     * @return the current process, or <tt>null</tt> if no process is current.
     */
    public static UserProcess currentProcess() {
        if (!(KThread.currentThread() instanceof UThread))
            return null;

        return ((UThread) KThread.currentThread()).process;
    }

    public static int getNewProcessID() {
        UserKernel.processIDSem.P();
        int id = UserKernel.newProcessID;
        UserKernel.newProcessID++;
        UserKernel.processIDSem.V();
        return id;
    }

    public static int getFreePage() {
        int page = -1;
        freePagesSem.P();
        Iterator<Integer> iterator = freePages.iterator();

        if (!freePages.isEmpty()) {
            page = iterator.next();
            iterator.remove();
        }

        freePagesSem.V();
        return page;
    }

    public static int[] getFreePages(int numOfPages) {
        int[] pages = new int[numOfPages];

        freePagesSem.P();
        Iterator<Integer> iterator = freePages.iterator();
        for (int i = 0; i < numOfPages; ++i) {
            if (!iterator.hasNext()) {
                pages = null;
                break;
            }
            pages[i] = iterator.next();
            iterator.remove();
            Lib.debug(dbgProcess, "\t\tallocate physical page " + pages[i]);
        }

        freePagesSem.V();
        return pages;
    }

    public static void pageFree(int[] pages) {
        for (int page : pages) {
            pageFree(page);
        }
    }

    public static void pageFree(int page) {
        freePagesSem.P();
        Lib.assertTrue(page >= 0 && page < Machine.processor().getNumPhysPages(), "cannot free invalid physical page");
        Lib.debug(dbgProcess, "\t\tfree physical page " + page);
        freePages.add(page);
        freePagesSem.V();
    }

    public static int pageRemain() {
        freePagesSem.P();
        int size = freePages.size();
        freePagesSem.V();
        return size;
    }

    /**
     * Initialize this kernel. Creates a synchronized console and sets the
     * processor's exception handler.
     */
    public void initialize(String[] args) {
        super.initialize(args);

        console = new SynchConsole(Machine.console());
        processIDSem = new Semaphore(1);
        freePagesSem = new Semaphore(1);

        for (int i = 0; i < Machine.processor().getNumPhysPages(); i++) {
            freePages.add(i);
        }

        Machine.processor().setExceptionHandler(new Runnable() {
            public void run() {
                exceptionHandler();
            }
        });
    }

    /**
     * Test the console device.
     */
    public void selfTest() {
        super.selfTest();

        System.out.println("Testing the console device. Typed characters");
        System.out.println("will be echoed until q is typed.");

        char c;

        do {
            c = (char) console.readByte(true);
            console.writeByte(c);
        }
        while (c != 'q');

        System.out.println("");
    }

    /**
     * The exception handler. This handler is called by the processor whenever
     * a user instruction causes a processor exception.
     *
     * <p>
     * When the exception handler is invoked, interrupts are enabled, and the
     * processor's cause register contains an integer identifying the cause of
     * the exception (see the <tt>exceptionZZZ</tt> constants in the
     * <tt>Processor</tt> class). If the exception involves a bad virtual
     * address (e.g. page fault, TLB miss, read-only, bus error, or address
     * error), the processor's BadVAddr register identifies the virtual address
     * that caused the exception.
     */
    public void exceptionHandler() {
        Lib.assertTrue(KThread.currentThread() instanceof UThread);

        UserProcess process = ((UThread) KThread.currentThread()).process;
        int cause = Machine.processor().readRegister(Processor.regCause);
        process.handleException(cause);
    }

    /**
     * Start running user programs, by creating a process and running a shell
     * program in it. The name of the shell program it must run is returned by
     * <tt>Machine.getShellProgramName()</tt>.
     *
     * @see nachos.machine.Machine#getShellProgramName
     */
    public void run() {
        super.run();

        UserProcess process = UserProcess.newUserProcess();

        String shellProgram = Machine.getShellProgramName();
        Lib.assertTrue(process.execute(shellProgram, new String[]{}));

        KThread.currentThread().finish();
    }

    /**
     * Terminate this kernel. Never returns.
     */
    public void terminate() {
        super.terminate();
    }
}
