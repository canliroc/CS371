package nachos.threads;

import nachos.machine.Lib;

import java.util.ArrayList;
import java.util.List;

public class OneVehicle extends KThread {

    private static final int MAX_VEHICLE = 3;
    private static final Condition[] conditions;
    private static final Lock lock;
    private static int count;
    private static int[] waiting;
    private static int currentDirec;

    static {
        count = 0;
        waiting = new int[]{0, 0};
        lock = new Lock();
        conditions = new Condition[]{new Condition(lock), new Condition(lock)};
    }

    private String name;


    public OneVehicle(String name) {
        super();
        this.name = name;
    }

    public OneVehicle(Runnable target, String name) {
        super(target);
        this.name = name;
    }


    public static void selfTest() {
        List<Runnable> tasks = new ArrayList<>(10 + 12);
        for (int i = 0; i < 10 + 12; i++) {
            int direc = 0;
            if (i >= 10) {
                direc = 1;
            }
            String carName = String.valueOf((char) ('A' + i));
            OneVehicle car = new OneVehicle(carName);
            VehicleTest task = new VehicleTest(car, direc);
            car.setTarget(task);

            tasks.add(task);
        }

        for (int i = 0; i < 10 + 12; i++) {
            new KThread(tasks.get(i)).fork();
        }


    }

    static boolean isSafe(int direc) {
        //if no vehicle on bridge
        if (0 == count) {
            return true;
        }
        //safe to cross
        if (count < MAX_VEHICLE && currentDirec == direc) {
            return true;
        }
        return false;
    }

    public void ArriveBridge(int direc) {
        lock.acquire();
        if (!isSafe(direc)) {
            waiting[direc]++;
            while (!isSafe(direc)) {
                this.ready();
                conditions[direc].sleep();
            }
            waiting[direc]--;
        }
        ++count;
        currentDirec = direc;
        lock.release();
    }


    public void CrossBridge(int direc) {
        String format = "Car %s is crossing the bridge in direction %d.";
        Lib.debug('t', String.format(format, name, direc));
    }


    public void ExitBridge(int direc) {
        lock.acquire();
        --count;
        if (count > 0) {
            conditions[direc].wake();
        } else {
            if (waiting[direc] != 0) {
                conditions[direc].wake();
            } else {
                conditions[1 - direc].wake();
            }
        }
        lock.release();
    }


    private static class VehicleTest implements Runnable {

        private OneVehicle car;
        private int direc;

        public VehicleTest(OneVehicle car, int direc) {
            this.car = car;
            this.direc = direc;
        }

        @Override
        public void run() {
            car.ArriveBridge(direc);
            car.CrossBridge(direc);
            car.ExitBridge(direc);
        }
    }
}
