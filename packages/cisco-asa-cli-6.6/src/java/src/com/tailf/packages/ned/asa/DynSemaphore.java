
package com.tailf.packages.ned.asa;

import java.util.concurrent.Semaphore;

final public class DynSemaphore {

    private final ExtendedSemaphore semaphore = new ExtendedSemaphore();

    void release() {
        this.semaphore.release();
    }
    void acquire() throws InterruptedException {
        this.semaphore.acquire();
    }
    void increasePermits(int reduction) throws InterruptedException {
        for (int i = 0; i < reduction; i++)
            this.semaphore.release();
    }
    void reducePermits(int reduction) throws InterruptedException {
        this.semaphore.reducePermits(reduction);
    }
    int availablePermits() {
        return this.semaphore.availablePermits();
    }

    private static final class ExtendedSemaphore extends Semaphore {

        //private static final long serialVersionUID = 1L;

        ExtendedSemaphore() {
            super(1, true);
        }

        @Override
        protected void reducePermits(int reduction) {
            super.reducePermits(reduction);
        }
    }
}
